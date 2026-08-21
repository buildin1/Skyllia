package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/**
 * 盔甲架物品右键放置的工作区（workaround）。
 * <p>
 * <b>已知引擎 bug</b>（2026-08-21 服主报告并确认）：本服（Folia/Shiroha）上，玩家右键用
 * 物品正常放置出来的盔甲架会变成"方块形态"——没有重力、烧不掉，行为完全不像一个正常实体；
 * 而用 {@code /summon armor_stand} 生成的盔甲架完全正常。已确认这不是 Skyllia 自己代码
 * 造成的：全仓库搜索过，放置阶段没有任何 Skyllia 代码碰过盔甲架，唯一涉及盔甲架的地方
 * 是 {@link fr.euphyllia.skyllia.listeners.permissions.entity.EntityDamagePermissions}
 * 里的伤害保护判定，和放置无关。推断是 Shiroha/Folia 区域化线程模型下，物品放置这条
 * 实体生成路径没有正确挂进所在 region 的实体管理器（summon 命令走的是另一条、被更完整
 * 测试过的生成路径），而这是服务端编译好的原版代码，插件层面改不了、也访问不到对应的
 * 反编译源码来定位具体哪一步漏挂（本机 Shiroha checkout 没有解包出 net.minecraft 包）。
 * 按服主的指示做工作区：拦掉原版放置，换成用 {@link World#spawn} 在同一坐标手动生成一个
 * "真正的"实体——和 summon 走的是同一条 API 路径，行为和 summon 出来的完全一致。
 * </p>
 * <p>
 * 顺带发现并补上一个既有的保护空白：盔甲架放置此前<b>完全没有</b>被 Skyllia 的领地权限
 * 系统覆盖过——不是 {@code BlockPlaceEvent}（Bukkit/Paper 从不为盔甲架触发它，那是给真正
 * 的方块用的）；也不是 {@code HangingPlaceEvent}（那只覆盖画/物品展示框这类悬挂实体，
 * {@code ArmorStand} 不是 {@code Hanging}）；仓库里也没有任何地方监听过
 * {@code EntityPlaceEvent}。也就是说访客理论上可以在任何岛屿上摆盔甲架而不受限制。
 * 这个监听器接管了放置流程之后，顺手补上了和
 * {@link fr.euphyllia.skyllia.listeners.permissions.decor.DecorHangingPlacePermissions}
 * 完全一致的岛屿权限判定（同一套"未知岛屿拒绝 / 权限位 / bypass / 领地边界"检查），
 * 不是范围外的顺手改动——不这么做的话，这个 workaround 反而会把一个从未被真正利用过的
 * 保护空白坐实成一条新的绕过路径。
 * </p>
 * <p>
 * ⚠️ <b>部署须知</b>：新权限节点 {@code skyllia:decor.armorstand.place} 只写进了
 * {@code permissions-v2.toml} 的 {@code [defaults.*]} 区块——这只在"创建岛屿的那一刻"
 * 生效，对已存在的岛屿完全无效（和 T1 部署时 {@code merchant.interact} 遇到的问题一模
 * 一样）。存量岛屿的这个权限位在反序列化后会是 0（false），部署这次更新后需要参照
 * T1 部署须知的做法，给 MEMBER/MODERATOR/CO_OWNER 补一次全局接管：
 * <pre>
 * /skylliadmin perm set MEMBER    skyllia:decor.armorstand.place true
 * /skylliadmin perm set MODERATOR skyllia:decor.armorstand.place true
 * /skylliadmin perm set CO_OWNER  skyllia:decor.armorstand.place true
 * </pre>
 * 否则存量岛屿上除岛主外的所有人放盔甲架都会被拒绝。
 * </p>
 */
public class ArmorStandPlacementFixListener implements Listener {

    private final PermissionId decorArmorStandPlace;

    public ArmorStandPlacementFixListener() {
        this.decorArmorStandPlace = SkylliaAPI.getPermissionRegistry().register(new PermissionNode(
                new NamespacedKey(Skyllia.getInstance(), "decor.armorstand.place"),
                "island.permission.decor_armorstand_place.name",
                "island.permission.decor_armorstand_place.description"
        ));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return; // 避免双手各触发一次
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.ARMOR_STAND) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        Block target = clicked.getRelative(event.getBlockFace());
        if (!target.isPassable()) {
            // 目标位置放不下：交给原版走它自己的"放不下"失败路径就好，不消耗物品、
            // 不生成任何东西，我们不需要介入。
            return;
        }

        Player player = event.getPlayer();
        World world = target.getWorld();

        // 一律先取消原版放置：即便下面的权限判定拒绝了，也绝不能让原版那条会产生
        // "方块形态"盔甲架的坏路径继续跑下去。
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        if (!player.isOp() && SkylliaAPI.isWorldSkyblock(world.getName())) {
            Island island = ListenersUtils.islandAtBlock(world, target.getX(), target.getZ());
            if (island == null) return;

            boolean hasBypass = PlayerUtils.hasPermission(player, "skyllia.player.decor.armorstand.place.bypass");
            boolean hasPermission = hasBypass || SkylliaAPI.getPermissionsManager()
                    .hasPermission(player, island, decorArmorStandPlace, null, ConfigLoader.general.getDebugSettings().permission());
            if (!hasPermission) {
                ConfigLoader.language.sendMessage(player, "island.player.permission-denied");
                return;
            }
            if (!hasBypass && ListenersUtils.isBlockOutsideIsland(island, world, target.getX(), target.getY(), target.getZ(), null)) {
                return;
            }
        }

        spawnArmorStand(player, world, target, item);
    }

    /**
     * 手动生成盔甲架实体，替代被拦掉的原版放置。走 {@link World#spawn}（和 {@code /summon}
     * 同一条 API 路径），不会遇到原版物品放置那条坏路径的"方块形态"问题。
     */
    private void spawnArmorStand(@NotNull Player player, @NotNull World world,
                                  @NotNull Block target, @NotNull ItemStack usedItem) {
        Location spawnLoc = target.getLocation().add(0.5, 0, 0.5);
        spawnLoc.setYaw(player.getLocation().getYaw());
        spawnLoc.setPitch(0f);

        ArmorStand stand = world.spawn(spawnLoc, ArmorStand.class);

        // 物品被重命名过（铁砧改名）的话，原版放置出来的实体会带上这个自定义名字，
        // 这里保持同样的观感，不因为走了 workaround 就少一截。
        ItemMeta meta = usedItem.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            stand.customName(meta.displayName());
            stand.setCustomNameVisible(true);
        }

        world.playSound(spawnLoc, Sound.ENTITY_ARMOR_STAND_PLACE, 1.0f, 1.0f);

        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack inHand = player.getInventory().getItemInMainHand();
            if (inHand.getType() == Material.ARMOR_STAND) {
                int newAmount = inHand.getAmount() - 1;
                player.getInventory().setItemInMainHand(newAmount <= 0 ? null : inHand.asQuantity(newAmount));
            }
        }
    }
}
