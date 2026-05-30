package fr.euphyllia.skyllia.listeners.permissions.block;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

import static fr.euphyllia.skyllia.api.commands.SubCommandInterface.log;

public class BlockPhysicalPermissions implements PermissionModule {

    private PermissionId BLOCK_PHYSICAL;

    @EventHandler(ignoreCancelled = true)
    public void onPhysical(final PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) return;

        final Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        final Player player = event.getPlayer();
        final Location location = clicked.getLocation();

        if (!SkylliaAPI.isWorldSkyblock(location.getWorld()) || player.isOp()) return;

        final int chunkX = location.getBlockX() >> 4;
        final int chunkZ = location.getBlockZ() >> 4;
        final Island island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
        if (island == null) {
            //log.warn("玩家{}在{}位置岛屿无效，无法进行{}", player.getName(), player.getLocation(), event.getEventName());
            event.setCancelled(true);
            return;
        }

        final boolean hasBypass = player.hasPermission("skyllia.player.physical.bypass");
        final boolean hasPermission = hasBypass || SkylliaAPI.getPermissionsManager().hasPermission(player, island, BLOCK_PHYSICAL, null, ConfigLoader.general.getDebugSettings().permission());
        if (!hasPermission) {
            //log.warn("玩家{}在{}岛上没有BLOCK_PHYSICAL权限，无法进行{}", player.getName(), island.getOwner().getLastKnowName(), event.getEventName());
            event.setCancelled(true);
            return;
        }
        if (!hasBypass && ListenersUtils.isBlockOutsideIsland(island, location, event)) {
            return;
        }
    }

    @Override
    public void registerPermissions(PermissionRegistry registry, Plugin owner) {
        this.BLOCK_PHYSICAL = registry.register(new PermissionNode(
                new NamespacedKey(owner, "block.physical"),
                "island.permission.block_physical.name",
                "island.permission.block_physical.description"
        ));
    }
}
