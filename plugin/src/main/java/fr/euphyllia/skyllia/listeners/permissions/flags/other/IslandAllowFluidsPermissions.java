package fr.euphyllia.skyllia.listeners.permissions.flags.other;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.FlagId;
import fr.euphyllia.skyllia.api.permissions.FlagNode;
import fr.euphyllia.skyllia.api.permissions.IslandFlagRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.FlagModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.listeners.ListenersUtils;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.plugin.Plugin;

import static fr.euphyllia.skyllia.api.commands.SubCommandInterface.log;

public class IslandAllowFluidsPermissions implements FlagModule {

    private FlagId ISLAND_ALLOW_FLUIDS;

    @EventHandler(ignoreCancelled = true)
    public void onFromTo(final BlockFromToEvent event) {
        final Block to = event.getToBlock();
        final World world = to.getWorld();

        final int bx = to.getX();
        final int by = to.getY();
        final int bz = to.getZ();

        final Island island = ListenersUtils.islandAtBlock(world, bx, bz);
        if (island == null) {
            log.warn("液体{}在{}位置岛屿无效，无法进行{}", event.getBlock(), event.getToBlock().getLocation(), event.getEventName());
            event.setCancelled(true);
            return;
        }

        final String worldName = world.getName();
        if (!SkylliaAPI.getPermissionsManager().hasFlag(island, ISLAND_ALLOW_FLUIDS, worldName)) {
            log.warn("液体{}在{}岛上没有启用ISLAND_ALLOW_FLUIDS，无法进行{}", event.getBlock(), island.getOwner().getLastKnowName(), event.getEventName());
            event.setCancelled(true);
            return;
        }

        ListenersUtils.isBlockOutsideIsland(island, world, bx, by, bz, event);
    }

    @Override
    public void registerFlags(IslandFlagRegistry registry, Plugin owner) {
        this.ISLAND_ALLOW_FLUIDS = registry.register(new FlagNode(
                new NamespacedKey(owner, "island.allow.fluids"),
                "island.flag.allow_fluids.name",
                "island.flag.allow_fluids.description"
        ));
    }
}
