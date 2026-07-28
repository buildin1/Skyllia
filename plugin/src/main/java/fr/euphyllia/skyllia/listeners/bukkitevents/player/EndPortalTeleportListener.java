package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 末地门 1:1 坐标传送修正。
 * <p>
 * 背景：Shiroha 26.2 的 {@code Entity.findOrCreatePortalAsync} END 分支读取
 * {@code ServerLevel.END_SPAWN_POINT}（{@code public static final BlockPos(100, 50, 0)}）
 * 作为末地落点，relatives 为 {@code Set.of()} 空集（绝对坐标）。
 * 落点 Y 由 {@code atBottomCenterOf(END_SPAWN_POINT.below())} 算出，即 END_SPAWN_POINT.y - 1。
 * <p>
 * 修正策略（多层兜底）：
 * <ol>
 *   <li>best-effort：用 NMS 修改 {@code END_SPAWN_POINT} 实例字段。
 *       END_SPAWN_POINT.y = 主世界 Y + 2，玩家落点 = (targetY + 2) - 1 = targetY + 1。</li>
 *   <li>兜底纠正：延迟检查玩家是否在期望落点 (targetX, targetY+1, targetZ)。
 *       不在则用 {@code teleportAsync} 纠正（应对 HotSpot 常量传播导致字段修改不生效）。</li>
 *   <li>阻止黑曜石平台：监听 {@link BlockPlaceEvent} 取消末地黑曜石放置。</li>
 *   <li>兜底清除：延迟任务里主动扫描落点周围 5x5，清除已生成的黑曜石平台。</li>
 * </ol>
 * <p>
 * 坐标规则：X/Z 为 1:1，Y = 主世界 Y + 1（防止卡墙）。
 */
public class EndPortalTeleportListener implements Listener {

    private static final Logger log = LogManager.getLogger(EndPortalTeleportListener.class);

    /** 记录过期时间（毫秒）：防止玩家未进入末地导致记录泄漏 */
    private static final long TARGET_EXPIRY_MS = 15_000L;
    /** 纠正延迟（tick）：等待末地传送流程完成（PortalProcessor 计时 + 区块加载 + teleportAsync） */
    private static final long CORRECTION_DELAY_TICKS = 20L; // 1 秒
    /** 坐标偏差阈值（方块）：在此范围内视为"在目标位置" */
    private static final int POS_THRESHOLD = 2;
    /** 黑曜石平台半径：createEndPlatform 5x5，半径 2 */
    private static final int PLATFORM_RADIUS = 2;

    private final ConcurrentHashMap<UUID, PendingTarget> pendingTargets = new ConcurrentHashMap<>();

    private static final class PendingTarget {
        final int targetX;
        final int targetY;
        final int targetZ;
        final long timestamp;

        PendingTarget(int x, int y, int z) {
            this.targetX = x;
            this.targetY = y;
            this.targetZ = z;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TARGET_EXPIRY_MS;
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPortalEnter(EntityPortalEnterEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        World world = player.getWorld();
        // 只在非末地世界进入末地门时处理（末地门是从主世界/地狱前往末地）
        if (world.getEnvironment() == World.Environment.THE_END) return;

        // EntityPortalEnterEvent 在玩家处于末地门方块期间每 tick 触发，
        // 用 putIfAbsent 只记录第一次进门的 target，避免高频事件覆盖记录导致延迟任务失效
        PendingTarget existing = pendingTargets.get(player.getUniqueId());
        if (existing != null && !existing.isExpired()) {
            // 已有未处理的进门前置记录，跳过本次（避免重复设 END_SPAWN_POINT + 重复调度）
            return;
        }

        Location loc = player.getLocation();
        int targetX = loc.getBlockX();
        int targetY = loc.getBlockY();
        int targetZ = loc.getBlockZ();

        // 记录 1:1 目标
        PendingTarget target = new PendingTarget(targetX, targetY, targetZ);
        PendingTarget prev = pendingTargets.putIfAbsent(player.getUniqueId(), target);
        if (prev != null) {
            // 并发竞争失败，放弃本次
            return;
        }

        // best-effort：尝试修改 END_SPAWN_POINT（若解释执行则可生效，免兜底纠正）
        try {
            SkylliaAPI.getWorldNMS().adjustEndPortalSpawnPoint(player);
        } catch (Exception e) {
            log.error("[Skyllia-末地门] adjustEndPortalSpawnPoint 失败", e);
        }

        // 兜底纠正 + 清除黑曜石平台：延迟到末地传送流程完成
        // 用 EntityScheduler 确保在玩家所在 region 的 tick 线程执行
        player.getScheduler().runDelayed(Skyllia.getInstance(), scheduledTask -> {
            // 校验记录未被覆盖（理论上 putIfAbsent 保证只有本任务持有 target）
            PendingTarget current = pendingTargets.get(player.getUniqueId());
            if (current != target) {
                return;
            }

            World currentWorld = player.getWorld();
            // 玩家未进入末地（可能取消或走错门），清理记录
            if (currentWorld.getEnvironment() != World.Environment.THE_END) {
                pendingTargets.remove(player.getUniqueId());
                return;
            }

            // 期望落点：X/Z 1:1，Y = 主世界 Y + 1（防卡墙）
            int expectedY = target.targetY + 1;

            // 1. 清除黑曜石平台 + 脚/头方块
            // 平台实际生成在 END_SPAWN_POINT 位置（targetY+2），但 Shiroha 用 heightmap 算玩家落点（可能更低）
            // 所以扫描范围需同时覆盖：玩家实际落点、END_SPAWN_POINT 位置、期望落点
            Location currentLoc = player.getLocation();
            int curX = currentLoc.getBlockX();
            int curY = currentLoc.getBlockY();
            int curZ = currentLoc.getBlockZ();
            clearEndPlatform(currentWorld, target.targetX, target.targetZ,
                    Math.min(curY, expectedY), Math.max(curY, expectedY) + 3);

            // 2. 检查玩家是否在期望落点
            boolean atTarget = Math.abs(curX - target.targetX) <= POS_THRESHOLD
                    && Math.abs(curZ - target.targetZ) <= POS_THRESHOLD
                    && Math.abs(curY - expectedY) <= POS_THRESHOLD;

            // 3. 不在期望落点 → 纠正（Shiroha 用 heightmap 算落点，Y 受末地地形影响，必须纠正）
            if (!atTarget) {
                // 纠正前再次清除期望落点的脚/头方块（防止卡墙）
                clearBlockSafe(currentWorld, target.targetX, expectedY, target.targetZ);
                clearBlockSafe(currentWorld, target.targetX, expectedY + 1, target.targetZ);
                Location correct = new Location(currentWorld,
                        target.targetX + 0.5, expectedY, target.targetZ + 0.5);
                player.teleportAsync(correct).thenAccept(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        log.info("[Skyllia-末地门] 已纠正 {} 从 ({}, {}, {}) 到 1:1 ({}, {}, {})",
                                player.getName(), curX, curY, curZ,
                                target.targetX, expectedY, target.targetZ);
                    } else {
                        log.warn("[Skyllia-末地门] 纠正 {} 失败，玩家仍在 ({}, {}, {})",
                                player.getName(), curX, curY, curZ);
                    }
                });
            }
            // 释放记录，允许下次进门再处理
            pendingTargets.remove(player.getUniqueId(), target);
        }, null, CORRECTION_DELAY_TICKS);
    }

    /**
     * 阻止末地黑曜石平台生成（事件层）。
     * <p>
     * Shiroha 26.2 的 {@code EndPlatformFeature.createEndPlatform} 通过
     * {@code BlockStateListPopulator.placeBlocks()} → {@code CraftBlockState.update()}
     * 触发 {@link BlockPlaceEvent}。在末地环境且放置方块为黑曜石时取消事件。
     * <p>
     * 若 Shiroha 跳过事件直接 setBlock，本监听无效，由 {@link #clearEndPlatform} 兜底清除。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEndPlatformPlace(BlockPlaceEvent event) {
        if (event.getBlock().getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (event.getBlockPlaced().getType() == Material.OBSIDIAN) {
            event.setCancelled(true);
        }
    }

    /**
     * 清除末地黑曜石平台 + 玩家脚/头方块。
     * <p>
     * 平台范围参考 {@code EndPlatformFeature.createEndPlatform}：5x5（半径 2），
     * Y 范围 [yStart, yEnd] 覆盖玩家实际落点 + END_SPAWN_POINT 位置 + 期望落点上方。
     * 玩家所在列（中心列）清除所有非基岩方块防卡墙，其他位置仅清除黑曜石（平台本体）。
     */
    private void clearEndPlatform(World world, int cx, int cz, int yStart, int yEnd) {
        for (int y = yStart; y <= yEnd; y++) {
            for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
                for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                    Block block = world.getBlockAt(cx + dx, y, cz + dz);
                    Material type = block.getType();
                    boolean isPlayerColumn = (dx == 0 && dz == 0);
                    if (isPlayerColumn) {
                        if (type != Material.AIR && type != Material.BEDROCK) {
                            block.setType(Material.AIR, false);
                        }
                    } else if (type == Material.OBSIDIAN) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /**
     * 清除单个方块（仅限非空气、非基岩），避免破坏末地结构。
     * 用 setType 不触发物理更新，防止连锁反应。
     */
    private void clearBlockSafe(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        Material type = block.getType();
        if (type != Material.AIR && type != Material.BEDROCK) {
            block.setType(Material.AIR, false);
        }
    }
}
