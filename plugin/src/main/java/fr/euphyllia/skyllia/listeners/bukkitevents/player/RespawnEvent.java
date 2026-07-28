package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import fr.euphyllia.skyllia.api.InterneAPI;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.event.players.PlayerRespawnSkylliaEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.model.WarpIsland;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.WorldUtils;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 空岛重生处理器。
 * <p>
 * <b>背景</b>：这个 Shiroha 构建的正常死亡重生走的是
 * {@code ServerGamePacketListenerImpl#handleClientCommand} 里被硬编码 {@code if (true)}
 * 恒真分支选中的 {@code ServerPlayer#respawn(Consumer, RespawnReason)}（Folia 改的异步版本），
 * 这个方法从头到尾不会触发任何 {@link PlayerRespawnEvent} 或 {@link PlayerPostRespawnEvent}
 * ——两者的构造点分别只在末地传送门重生（{@code EndPortalBlock}）和"打完龙点重生"
 * （{@code wonGame} 分支，走的是老的同步 {@code PlayerList#respawn}）里才会命中。
 * 也就是说标准 Bukkit 重生事件对"正常死亡"这个最常见场景完全失效，插件侧无法通过监听
 * 这两个事件来纠正重生位置。
 * </p>
 * <p>
 * 因此这里额外用 {@link #onPlayerDeath} + 轮询的方式兜底（见 {@link #schedulePostDeathCorrection}），
 * 不依赖任何重生事件：
 * <ol>
 *   <li>死亡时记录玩家是否有有效床/锚（{@link Player#getRespawnLocation()} 非空即代表有效，
 *       这个方法本身就会校验对应方块是否仍是床/锚，语义与原版 forced 校验一致）。有的话完全
 *       不介入，原版自己处理。</li>
 *   <li>没有的话，用玩家的 {@code EntityScheduler} 按固定周期检查血量。原版重生流程里
 *       {@code reset()}（恢复满血）发生在玩家真正被放置到目标世界<b>之前</b>，所以不能一
 *       看到血量恢复就立刻纠正——要求血量 > 0 连续命中几次（约 100ms 量级）才认为落地已经
 *       稳定，避免读到过渡态的坐标。</li>
 *   <li>确认落地后，检查玩家是否在自己岛屿范围内、脚下是否安全（{@link WorldUtils#isSafeLocation}）。
 *       不满足则用普通 {@code teleportAsync} 纠正——跟 {@code /is home} 同一套机制，region 安全。
 *       如果连解析出来的岛屿 spawn 点本身都不安全（比如玩家自己挖空了地基），
 *       额外在附近搜索一个安全落脚点（{@link #findNearbySafeLocation}）。</li>
 * </ol>
 * </p>
 * <p>
 * 同时保留标准事件监听（{@link #onPlayerRespawn}、{@link #onPlayerRespawnSkylla}、
 * {@link #onPlayerPostRespawn}），覆盖它们各自仍然有效的场景（末地重生、Canvas 服务端、
 * "打龙重生"），互不冲突：{@link #onPlayerDeath} 只在玩家没有有效床/锚时才会介入，
 * 且真正执行纠正前会重新检查一次位置，不会跟其它路径已经做好的正确传送打架。
 * </p>
 * <p>
 * 重要：不再通过 NMS 强制写入 respawn 维度/坐标（{@code forced=true}）。原版对
 * forced 位置的解析要求目标必须是"两格空气"（跟床、重生锚的安全站位要求一致，
 * 见 {@code ServerPlayer#findRespawnAndUseSpawnBlock}），而空岛中心/自定义 spawn
 * 通常是建筑平台，脚下是实心方块，这项校验必然失败，导致原版静默 fallback 到
 * 世界出生点（虚空）——这正是"死亡后掉入 0,0 虚空"这个 bug 最初的根因。
 * </p>
 * <p>
 * 重生位置优先级（从高到低）：
 * <ol>
 *   <li>玩家有效床 / 重生锚（尊重玩家设置，原版处理，哪怕床不在岛屿范围内）</li>
 *   <li>空岛自定义 spawn（{@link Island#hasCustomSpawn()}）</li>
 *   <li>空岛 home warp（{@code /is sethome} 设置）</li>
 *   <li>空岛中心点（{@link Island#getSpawnLocation(World)} 默认，最终兜底）</li>
 *   <li>玩家没有岛屿：若开启 {@code player.join.teleport.spawn-not-island}，传送到全局 spawn</li>
 * </ol>
 * </p>
 */
public class RespawnEvent implements Listener {

    private static final Logger logger = LogManager.getLogger(RespawnEvent.class);

    /** 兜底纠正时，围绕原始落点搜索安全地面的最大半径（方块）。 */
    private static final int SAFE_SPOT_SEARCH_RADIUS = 16;

    /** 死后轮询检查血量的周期（tick）。 */
    private static final long DEATH_POLL_PERIOD_TICKS = 2L;
    /** 血量恢复后，要求连续命中几次才认为落地已经稳定（避免读到过渡态坐标）。 */
    private static final int SETTLE_STREAK_REQUIRED = 3;
    /** 轮询的最长尝试次数（约 10 分钟），纯粹是防止极端情况下任务泄漏的安全阀。 */
    private static final int MAX_POLL_ATTEMPTS = 6000;

    private final InterneAPI api;

    public RespawnEvent(InterneAPI interneAPI) {
        this.api = interneAPI;
    }

    /**
     * 死亡事件：没有有效床/锚时，安排一次死后轮询，兜底纠正正常死亡重生
     * （这个 Shiroha 构建的正常死亡重生流程不会触发任何 Bukkit 重生事件，见类注释）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();

        // 玩家有有效床或重生锚 → 原版会自己处理，不用管
        if (player.getRespawnLocation() != null) {
            return;
        }

        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null && !ConfigLoader.playerManager.isTeleportSpawnIfNoIsland()) {
            return;
        }

        schedulePostDeathCorrection(player);
    }

    /**
     * 用玩家自己的 {@code EntityScheduler} 按固定周期检查血量，直到确认玩家已经
     * 重生落地（血量恢复且连续稳定几次），再执行一次性的位置纠正。
     */
    private void schedulePostDeathCorrection(Player player) {
        int[] state = new int[]{0, 0}; // [0]=连续存活次数, [1]=已尝试次数
        player.getScheduler().runAtFixedRate(api.getPlugin(), scheduledTask ->
                pollForRespawnSettled(player, scheduledTask, state), null, DEATH_POLL_PERIOD_TICKS, DEATH_POLL_PERIOD_TICKS);
    }

    private void pollForRespawnSettled(Player player, ScheduledTask scheduledTask, int[] state) {
        state[1]++;

        if (player.getHealth() <= 0) {
            state[0] = 0;
            if (state[1] >= MAX_POLL_ATTEMPTS) {
                scheduledTask.cancel();
            }
            return;
        }

        state[0]++;
        if (state[0] < SETTLE_STREAK_REQUIRED) {
            return;
        }

        scheduledTask.cancel();
        correctIfNeeded(player);
    }

    /**
     * 重生事件：末地传送门重生等仍会触发标准事件的场景下，玩家没有有效床/锚时，
     * 把重生位置改到空岛（或无岛屿时的全局 spawn）。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        // 玩家有有效床或重生锚 → 尊重原版决定，不干预
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }

        Player player = event.getPlayer();
        Location respawnLoc = resolveRespawnLocation(player);
        if (respawnLoc == null) return;

        event.setRespawnLocation(respawnLoc);
    }

    /**
     * Canvas 专用重生事件：Canvas 的重生流程是异步的，不会像标准 Folia/Paper 那样
     * 触发普通 {@link PlayerRespawnEvent} 让插件介入，而是由
     * {@code fr.euphyllia.skyllia.hook.canvas.player.PlayerRespawnHooks} 监听 Canvas
     * 自己的 {@code PlayerRespawnAsyncEvent}（已在那里过滤掉床/锚重生）转发成这个事件。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawnSkylla(PlayerRespawnSkylliaEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        Location respawnLoc = resolveRespawnLocation(player);
        if (respawnLoc == null) return;

        event.setRespawnLocation(respawnLoc);
    }

    /**
     * 兜底："打完龙点重生"等仍会触发标准事件的场景下，respawn 完全落地后再检查一次。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPostRespawn(PlayerPostRespawnEvent event) {
        // 玩家有有效床或重生锚 → 尊重原版决定，不干预（哪怕床不在岛屿范围内）
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }

        correctIfNeeded(event.getPlayer());
    }

    /**
     * 检查玩家当前位置是否合理，不合理则用普通 {@code teleportAsync} 纠正——跟
     * {@code /is home} 同一套机制，region 安全，且不受原版重生安全校验影响。
     * 有岛屿的玩家必须落在自己岛屿范围内且脚下安全；没有岛屿的玩家只要求脚下安全。
     */
    private void correctIfNeeded(Player player) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        Location current = player.getLocation();

        // current 是玩家自己当前所在的位置：EntityScheduler 保证这段代码运行在
        // 玩家当前所属 region 的线程上，读这里的方块是安全的。
        if (island != null) {
            if (island.isInside(current) && WorldUtils.isSafeLocation(current)) {
                return;
            }
        } else {
            if (!ConfigLoader.playerManager.isTeleportSpawnIfNoIsland()) return;
            if (WorldUtils.isSafeLocation(current)) return;
        }

        Location target = resolveRespawnLocation(player);
        if (target == null) {
            logger.warn("[Skyllia-重生] 兜底纠正失败：玩家 {} 无法解析重生坐标（世界未加载？）",
                    player.getName());
            return;
        }

        // 注意：这里不能提前读 target 所在方块是否安全——target 通常在另一个岛屿的
        // region 上，跟玩家当前所在 region 不是同一个，同步读取会触发 Folia 的跨
        // region 线程校验失败（TickThread.ensureTickThread）。
        // 所以先无条件传送过去，落地后再用 EntityScheduler（保证运行在传送后新
        // region 的线程上）检查安全性，不安全的话就地搜索附近的安全落脚点纠正一次。
        logger.warn("[Skyllia-重生] 玩家 {} 重生后位置异常（当前：{}），执行兜底纠正到 {}",
                player.getName(), current, target);

        Location finalTarget = target;
        player.teleportAsync(finalTarget, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
            if (!Boolean.TRUE.equals(success) || !player.isOnline()) return;

            player.getScheduler().run(api.getPlugin(), scheduledTask -> {
                if (WorldUtils.isSafeLocation(finalTarget)) return;

                // 岛屿 spawn 点脚下的方块可能已被挖空（例如玩家自己挖了地基），
                // 原样落地会立刻开始下坠、摔进虚空、再次死亡——无限循环。
                // 现在已经落地到目标 region，读取附近方块是安全的。
                Location safeSpot = findNearbySafeLocation(finalTarget);
                if (safeSpot != null) {
                    player.teleportAsync(safeSpot, PlayerTeleportEvent.TeleportCause.PLUGIN);
                } else {
                    logger.warn("[Skyllia-重生] 玩家 {} 的 spawn 点脚下是虚空，且附近 {} 格内找不到安全位置，" +
                                    "暂时停留在原始坐标 {}（需要管理员介入修复地形）",
                            player.getName(), SAFE_SPOT_SEARCH_RADIUS, finalTarget);
                }
            }, null);
        });
    }

    /**
     * 解析玩家的重生位置：有岛屿则解析空岛坐标；没有岛屿则按配置决定是否传送到全局 spawn。
     */
    private @Nullable Location resolveRespawnLocation(Player player) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            if (!ConfigLoader.playerManager.isTeleportSpawnIfNoIsland()) return null;
            Location spawnLocation = ConfigLoader.general.getSpawnLocation();
            if (spawnLocation == null || spawnLocation.getWorld() == null) return null;
            return spawnLocation;
        }

        Location respawnLoc = resolveIslandRespawn(island);
        if (respawnLoc == null) {
            logger.warn("[Skyllia-重生] 玩家 {} 无法解析空岛重生坐标（空岛世界未加载？），保留原版重生位置",
                    player.getName());
        }
        return respawnLoc;
    }

    /**
     * 解析空岛的重生位置。
     * <p>
     * 优先级：自定义 spawn > home warp > 空岛中心。
     * 世界按 {@link World.Environment#NORMAL} 显式匹配，不依赖世界配置列表的顺序
     * （{@code getFirst()} 在配置为 Map 时是不可靠的）。
     * 返回的位置已加 +0.5 X/Z 和 +0.1 Y 偏移，与 /is home 行为一致。
     * </p>
     */
    private @Nullable Location resolveIslandRespawn(Island island) {
        World islandWorld = WorldUtils.getWorldConfigs().stream()
                .filter(wc -> wc.getEnvironment() == World.Environment.NORMAL)
                .map(WorldConfig::getWorld)
                .filter(w -> w != null)
                .findFirst()
                .orElse(null);
        if (islandWorld == null) return null;

        WarpIsland homeWarp = island.getWarpByName("home");
        Location loc;
        if (island.hasCustomSpawn()) {
            loc = island.getSpawnLocation(islandWorld);
        } else if (homeWarp != null && homeWarp.location() != null) {
            loc = homeWarp.location().clone();
        } else {
            loc = island.getSpawnLocation(islandWorld);
        }
        return loc.add(0.5, 0.1, 0.5);
    }

    /**
     * 在给定位置附近按环形扩散搜索一个安全的落脚点（脚下有方块、头顶和脚下都不是危险方块）。
     * 用每列的最高非空气方块作为候选 Y，避免逐格扫描 Y 轴的开销。
     * 调用方（{@link #correctIfNeeded}）保证只在玩家已经落地到目标世界之后才会执行，
     * 当前线程必然拥有该区域的 Folia 分区所有权，读取附近方块是安全的。
     *
     * @return 找到的安全位置，或 {@code null}（半径内确实找不到任何安全地面）
     */
    private @Nullable Location findNearbySafeLocation(Location origin) {
        World world = origin.getWorld();
        if (world == null) return null;

        int centerX = origin.getBlockX();
        int centerZ = origin.getBlockZ();

        for (int radius = 1; radius <= SAFE_SPOT_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue; // 只扫环形边界，避免重复检查内圈

                    int x = centerX + dx;
                    int z = centerZ + dz;
                    int y = world.getHighestBlockYAt(x, z) + 1;
                    Location candidate = new Location(world, x + 0.5, y, z + 0.5, origin.getYaw(), origin.getPitch());
                    if (WorldUtils.isSafeLocation(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }
}
