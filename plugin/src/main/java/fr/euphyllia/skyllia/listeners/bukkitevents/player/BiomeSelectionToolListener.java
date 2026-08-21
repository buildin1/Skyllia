package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.coordinate.ChunkCoordinate;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.utils.RegionUtils;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.gui.BiomeCatalogGui;
import fr.euphyllia.skyllia.gui.BiomeSelectionToolItem;
import fr.euphyllia.skyllia.utils.WorldUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「群系修改」选区工具（{@link BiomeSelectionToolItem}）的选点交互监听。
 * <p>
 * 只在玩家主手持有带专属 PDC 标记的木锄头、且点中了方块时才介入；其余一切情况（包括
 * 玩家用普通木锄头正常耕地）完全不受影响——判定精确到 {@link BiomeSelectionToolItem#isSelectionTool}，
 * 不是仅看 {@code Material.WOODEN_HOE}。
 * </p>
 * <p>
 * 左键记录起点 A、右键记录终点 B 并完成选区。两次点击的事件本身都会 {@code setCancelled(true)}
 * （连同 {@code useInteractedBlock}/{@code useItemInHand} 一并 DENY），防止工具被当成正常
 * 锄头触发耕地，也防止创造模式左键单击瞬间破坏方块——这是 WorldEdit 类选区工具的标准做法。
 * 额外挂了一个 {@link BlockBreakEvent} 兜底取消，双重保险（见类底部说明）。
 * </p>
 */
public class BiomeSelectionToolListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** 玩家已记录但尚未完成的起点 A。 */
    private record PendingPoint(@NotNull String worldName, int chunkX, int chunkZ) {}

    private final Map<UUID, PendingPoint> pendingPointA = new ConcurrentHashMap<>();

    /**
     * 复用 {@code SetBiomeSubCommand} 已经注册过的 {@code skyllia:command.island.set_biome}
     * 权限节点。{@link fr.euphyllia.skyllia.api.permissions.PermissionRegistry#register} 按
     * {@link NamespacedKey} 做幂等查找（见其实现：命中已存在的 key 直接返回同一个
     * {@link PermissionId}，不会产生第二个 id 或抛异常），所以这里用完全相同的
     * {@link NamespacedKey} 重新调用 register 是安全的，不会与 SetBiomeSubCommand 冲突，
     * 也不需要它对外暴露一个 getter。
     */
    private final PermissionId islandSetBiomePermission;

    public BiomeSelectionToolListener() {
        this.islandSetBiomePermission = SkylliaAPI.getPermissionRegistry().register(new PermissionNode(
                new NamespacedKey(Skyllia.getInstance(), "command.island.set_biome"),
                "island.permission.command.set_biome.name",
                "island.permission.command.set_biome.description"
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return; // 避免双手各触发一次

        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!BiomeSelectionToolItem.isSelectionTool(mainHand)) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;

        // 硬规则：必须取消，防止触发耕地 / 创造模式瞬间破坏方块。
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        if (action == Action.LEFT_CLICK_BLOCK) {
            handleLeftClick(player, clicked);
        } else {
            handleRightClick(player, clicked);
        }
    }

    /**
     * 兜底：{@link PlayerInteractEvent} 的取消已经在 Shiroha 本地源码里逐行核实足以拦住
     * 创造模式左键单击瞬间破坏方块（见类注释引用的 {@code ServerPlayerGameMode.handleBlockBreakAction}
     * 第 204-209 行），这里再挂一层独立兜底纯属多一道保险：只要玩家主手是这把工具，
     * 一律取消 {@link BlockBreakEvent}。对持有普通木锄头的玩家完全不生效。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (BiomeSelectionToolItem.isSelectionTool(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        pendingPointA.remove(event.getPlayer().getUniqueId());
    }

    private void handleLeftClick(@NotNull Player player, @NotNull Block clicked) {
        int chunkX = clicked.getX() >> 4;
        int chunkZ = clicked.getZ() >> 4;
        pendingPointA.put(player.getUniqueId(), new PendingPoint(clicked.getWorld().getName(), chunkX, chunkZ));

        spawnChunkOutline(clicked.getWorld(), chunkX, chunkZ, player.getLocation().getY() + 1, Particle.HAPPY_VILLAGER);
        player.sendMessage(MM.deserialize("<light_purple>[群系选区]</light_purple> <gray>已记录起点 A（区块 "
                + chunkX + ", " + chunkZ + "）。请右键另一个方块完成选区。</gray>"));
    }

    private void handleRightClick(@NotNull Player player, @NotNull Block clicked) {
        PendingPoint pointA = pendingPointA.get(player.getUniqueId());
        if (pointA == null) {
            player.sendMessage(MM.deserialize("<red>请先左键选择起点。</red>"));
            return;
        }

        World world = clicked.getWorld();
        if (!pointA.worldName().equals(world.getName())) {
            pendingPointA.remove(player.getUniqueId());
            player.sendMessage(MM.deserialize("<red>你已切换世界，起点已失效，请重新左键选择起点。</red>"));
            return;
        }

        if (!WorldUtils.isWorldSkyblock(world.getName())) {
            pendingPointA.remove(player.getUniqueId());
            player.sendMessage(MM.deserialize("<red>此工具只能在空岛世界使用。</red>"));
            return;
        }

        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            ConfigLoader.language.sendMessage(player, "island.player.no-island");
            pendingPointA.remove(player.getUniqueId());
            return;
        }

        // 必须站在自己岛屿的领地内 —— 与 SetBiomeSubCommand 完全相同的 RegionCoordinate 比对。
        RegionCoordinate islandRegion = island.getRegionCoordinate();
        RegionCoordinate playerRegion = RegionHelper.getRegionCoordinateFromLocation(player.getLocation());
        if (islandRegion.x() != playerRegion.x() || islandRegion.z() != playerRegion.z()) {
            ConfigLoader.language.sendMessage(player, "island.player.not-on-own-island");
            pendingPointA.remove(player.getUniqueId());
            return;
        }

        boolean allowed = SkylliaAPI.getPermissionsManager().hasPermission(
                player, island, islandSetBiomePermission, null, ConfigLoader.general.getDebugSettings().permission());
        if (!allowed) {
            ConfigLoader.language.sendMessage(player, "island.player.permission-denied");
            pendingPointA.remove(player.getUniqueId());
            return;
        }

        int bChunkX = clicked.getX() >> 4;
        int bChunkZ = clicked.getZ() >> 4;
        pendingPointA.remove(player.getUniqueId());

        int minChunkX = Math.min(pointA.chunkX(), bChunkX);
        int maxChunkX = Math.max(pointA.chunkX(), bChunkX);
        int minChunkZ = Math.min(pointA.chunkZ(), bChunkZ);
        int maxChunkZ = Math.max(pointA.chunkZ(), bChunkZ);

        // 把选区矩形和岛屿的合法区块集合做交集：遍历"岛屿的合法区块列表"（量级等同于
        // changeBiomeIsland 处理的量级，天然有界），而不是遍历玩家选出的矩形——避免玩家
        // 在两次点击之间飞得很远、拼出一个巨大矩形时被用来当放大镜遍历，属于防御性写法。
        int regionDistance = ConfigLoader.general.getIslandSettings().regionDistance();
        List<ChunkCoordinate> islandChunks = RegionUtils.computeChunksToDelete(islandRegion, regionDistance, island.getSize());

        List<ChunkCoordinate> clipped = new ArrayList<>();
        for (ChunkCoordinate c : islandChunks) {
            if (c.x() >= minChunkX && c.x() <= maxChunkX && c.z() >= minChunkZ && c.z() <= maxChunkZ) {
                clipped.add(c);
            }
        }

        if (clipped.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>选区不在你的岛屿领地范围内。</red>"));
            return;
        }

        removeToolFromMainHand(player);

        double y = player.getLocation().getY() + 1;
        drawSelectionBoundary(world, clipped, y);

        UUID islandId = island.getId();
        UUID playerId = player.getUniqueId();
        int chunkCount = clipped.size();
        List<ChunkCoordinate> finalChunks = clipped;
        AtomicBoolean consumed = new AtomicBoolean(false);

        Component confirmLink = Component.text("点击这里选择群系 →", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.callback(audience -> {
                    // 一次性保护：Options.uses(1) 已经在协议层面限制只能点一次，这里再加一层
                    // 本地保护，双保险防止任何理论上的重复触发（比如极端网络重传）导致重复扣两次。
                    if (!consumed.compareAndSet(false, true)) return;
                    if (!(audience instanceof Player clicker)) return;
                    if (!clicker.getUniqueId().equals(playerId)) return;

                    // 聊天点击回调可能在很久之后才触发，必须重新校验一遍——不能偷懒假设状态没变。
                    clicker.getScheduler().run(Skyllia.getInstance(), t -> {
                        Island currentIsland = SkylliaAPI.getIslandByPlayerId(playerId);
                        if (currentIsland == null || !currentIsland.getId().equals(islandId)) {
                            clicker.sendMessage(Component.text(
                                    "§c选区已失效（你的岛屿状态已发生变化），请重新使用选区工具。"));
                            return;
                        }
                        boolean stillAllowed = SkylliaAPI.getPermissionsManager().hasPermission(
                                clicker, currentIsland, islandSetBiomePermission, null,
                                ConfigLoader.general.getDebugSettings().permission());
                        if (!stillAllowed) {
                            ConfigLoader.language.sendMessage(clicker, "island.player.permission-denied");
                            return;
                        }
                        BiomeCatalogGui.open(clicker, world, currentIsland, finalChunks);
                    }, null);
                }, ClickCallback.Options.builder().uses(1).build()));

        player.sendMessage(Component.text()
                .append(MM.deserialize("<light_purple>[群系选区]</light_purple> <green>选区已确定（共 "
                        + chunkCount + " 个区块）。</green> "))
                .append(confirmLink)
                .build());
    }

    private void removeToolFromMainHand(@NotNull Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!BiomeSelectionToolItem.isSelectionTool(main)) return;
        int newAmount = main.getAmount() - 1;
        player.getInventory().setItemInMainHand(newAmount <= 0 ? null : main.asQuantity(newAmount));
    }

    private void spawnChunkOutline(@NotNull World world, int chunkX, int chunkZ, double y, @NotNull Particle particle) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 16;
        int maxZ = minZ + 16;
        int step = 2;
        for (int x = minX; x <= maxX; x += step) {
            world.spawnParticle(particle, x, y, minZ, 1, 0, 0, 0, 0);
            world.spawnParticle(particle, x, y, maxZ, 1, 0, 0, 0, 0);
        }
        for (int z = minZ; z <= maxZ; z += step) {
            world.spawnParticle(particle, minX, y, z, 1, 0, 0, 0, 0);
            world.spawnParticle(particle, maxX, y, z, 1, 0, 0, 0, 0);
        }
    }

    /**
     * 画出裁剪后选区的外接矩形边界。步长随周长动态放大，避免选区很大时粒子数暴涨——
     * 不过选区上限就是整座岛屿的区块数，和 changeBiomeIsland 处理的量级一样，可以放心。
     */
    private void drawSelectionBoundary(@NotNull World world, @NotNull List<ChunkCoordinate> chunks, double y) {
        int minChunkX = Integer.MAX_VALUE, maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE, maxChunkZ = Integer.MIN_VALUE;
        for (ChunkCoordinate c : chunks) {
            minChunkX = Math.min(minChunkX, c.x());
            maxChunkX = Math.max(maxChunkX, c.x());
            minChunkZ = Math.min(minChunkZ, c.z());
            maxChunkZ = Math.max(maxChunkZ, c.z());
        }
        int minX = minChunkX << 4;
        int minZ = minChunkZ << 4;
        int maxX = (maxChunkX << 4) + 16;
        int maxZ = (maxChunkZ << 4) + 16;

        int perimeter = 2 * ((maxX - minX) + (maxZ - minZ));
        int step = Math.max(2, perimeter / 400);

        for (int x = minX; x <= maxX; x += step) {
            world.spawnParticle(Particle.END_ROD, x, y, minZ, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.END_ROD, x, y, maxZ, 1, 0, 0, 0, 0);
        }
        for (int z = minZ; z <= maxZ; z += step) {
            world.spawnParticle(Particle.END_ROD, minX, y, z, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.END_ROD, maxX, y, z, 1, 0, 0, 0, 0);
        }
    }
}
