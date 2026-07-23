package fr.euphyllia.skyllia.listeners.permissions.entity;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.Plugin;

/**
 * 处理玩家与村民交互和交易行为的权限控制。
 * 已从 {@link EntityInteractPermissions} 中拆分为独立权限 entity.trade。
 */
public class EntityTradePermissions implements PermissionModule {

    private PermissionId ENTITY_TRADE;

    /**
     * 玩家右键点击村民时触发。
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteractVillager(final PlayerInteractEntityEvent event) {
        final Entity target = event.getRightClicked();
        if (target.getType() != EntityType.VILLAGER) return;

        final Player player = event.getPlayer();
        final World world = target.getWorld();

        final int bx = Location.locToBlock(target.getX());
        final int by = Location.locToBlock(target.getY());
        final int bz = Location.locToBlock(target.getZ());
        if (!SkylliaAPI.isWorldSkyblock(world) || player.isOp()) return;

        final Island island = ListenersUtils.islandAtBlock(world, bx, bz);
        if (island == null) return;

        final boolean hasBypass = PlayerUtils.hasPermission(player, "skyllia.player.entity.trade.bypass");
        final boolean hasPermission = hasBypass || SkylliaAPI.getPermissionsManager()
                .hasPermission(player, island, ENTITY_TRADE, null, ConfigLoader.general.getDebugSettings().permission());
        if (!hasPermission) {
            event.setCancelled(true);
            return;
        }
        if (!hasBypass) {
            ListenersUtils.isBlockOutsideIsland(island, world, bx, by, bz, event);
        }
    }

    /**
     * 玩家完成与村民的交易时触发。
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerTrade(final PlayerTradeEvent event) {
        final Player player = event.getPlayer();
        final Entity villager = event.getMerchant();
        final World world = villager.getWorld();

        final int bx = Location.locToBlock(villager.getX());
        final int by = Location.locToBlock(villager.getY());
        final int bz = Location.locToBlock(villager.getZ());
        if (!SkylliaAPI.isWorldSkyblock(world) || player.isOp()) return;

        final Island island = ListenersUtils.islandAtBlock(world, bx, bz);
        if (island == null) return;

        final boolean hasBypass = PlayerUtils.hasPermission(player, "skyllia.player.entity.trade.bypass");
        final boolean hasPermission = hasBypass || SkylliaAPI.getPermissionsManager()
                .hasPermission(player, island, ENTITY_TRADE, null, ConfigLoader.general.getDebugSettings().permission());
        if (!hasPermission) {
            event.setCancelled(true);
            return;
        }
        if (!hasBypass) {
            ListenersUtils.isBlockOutsideIsland(island, world, bx, by, bz, event);
        }
    }

    @Override
    public void registerPermissions(PermissionRegistry registry, Plugin owner) {
        this.ENTITY_TRADE = registry.register(new PermissionNode(
                new NamespacedKey(owner, "entity.trade"),
                "island.permission.entity_trade.name",
                "island.permission.entity_trade.description"
        ));
    }
}
