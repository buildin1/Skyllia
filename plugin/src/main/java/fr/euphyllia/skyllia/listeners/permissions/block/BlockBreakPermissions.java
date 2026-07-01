package fr.euphyllia.skyllia.listeners.permissions.block;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;

import static fr.euphyllia.skyllia.api.commands.SubCommandInterface.log;

public class BlockBreakPermissions implements PermissionModule {

    private PermissionId BLOCK_BREAK;

    @EventHandler(ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        final World world = block.getWorld();

        if (!SkylliaAPI.isWorldSkyblock(world.getName())|| player.isOp()) return;

        final int bx = block.getX();
        final int by = block.getY();
        final int bz = block.getZ();
        final Island island = ListenersUtils.islandAtBlock(world, bx, bz);
        if (island == null) {
            //log.warn("玩家{}在{}位置岛屿无效，无法进行{}", player.getName(), player.getLocation(), event.getEventName());
            event.setCancelled(true);
            return;
        }

        if (isSpawnProtected(island, world, bx, by, bz)
                && !PlayerUtils.hasPermission(player, "skyllia.island.spawn.break.bypass")) {
            event.setCancelled(true);
            ConfigLoader.language.sendMessage(player, "island.spawn.block-protected");
            return;
        }

        final boolean hasBypass = PlayerUtils.hasPermission(player, "skyllia.player.break.bypass");
        final boolean hasPermission = hasBypass || SkylliaAPI.getPermissionsManager()
                .hasPermission(player, island, BLOCK_BREAK, null, ConfigLoader.general.getDebugSettings().permission());
        if (!hasPermission) {
            event.setCancelled(true);
            return;
        }
        if (!hasBypass) {
            ListenersUtils.isBlockOutsideIsland(island, world, bx, by, bz, event);
        }
    }

    private boolean isSpawnProtected(Island island, World world, int bx, int by, int bz) {
        Location spawn = island.getSpawnLocation(world);
        if (spawn == null || spawn.getWorld() == null || !spawn.getWorld().equals(world)) {
            return false;
        }
        int sx = spawn.getBlockX();
        int sy = spawn.getBlockY();
        int sz = spawn.getBlockZ();
        if (bx != sx || bz != sz) {
            return false;
        }
        return by == sy || by == sy - 1;
    }

    @Override
    public void registerPermissions(PermissionRegistry registry, Plugin owner) {
        this.BLOCK_BREAK = registry.register(new PermissionNode(
                new NamespacedKey(owner, "block.break"),
                "island.permission.block_break.name",
                "island.permission.block_break.description"
        ));
    }
}