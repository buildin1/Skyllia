package fr.euphyllia.skyllia.listeners.permissions.inventory;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

public class InventoryOpenPermissions implements PermissionModule {

    private PermissionId INVENTORY_OPEN;

    @EventHandler(ignoreCancelled = true)
    public void onOpen(final InventoryOpenEvent event) {
        final HumanEntity human = event.getPlayer();
        if (!(human instanceof Player player)) return;

        // 只拦截物理容器，虚拟容器（如插件GUI）直接放行
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof Container)) return;

        final Location location = player.getLocation();
        if (!SkylliaAPI.isWorldSkyblock(location.getWorld()) || player.isOp()) return;

        final int chunkX = location.getBlockX() >> 4;
        final int chunkZ = location.getBlockZ() >> 4;
        final Island island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
        if (island == null) {
            event.setCancelled(true);
            return;
        }

        final boolean hasPermission = SkylliaAPI.getPermissionsManager().hasPermission(
                player, island, INVENTORY_OPEN,
                null,
                ConfigLoader.general.getDebugSettings().permission());
        if (!hasPermission) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        // 仅处理右键点击方块
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        final Player player = event.getPlayer();
        final org.bukkit.block.Block block = event.getClickedBlock();
        if (block == null || player.isOp()) return;

        // 只拦截容器方块（箱子、熔炉、发射器等）
        if (!(block.getState() instanceof Container)) return;

        final Location location = player.getLocation();
        if (!SkylliaAPI.isWorldSkyblock(location.getWorld())) return;

        final int chunkX = location.getBlockX() >> 4;
        final int chunkZ = location.getBlockZ() >> 4;
        final Island island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
        if (island == null) {
            event.setCancelled(true);
            return;
        }

        final boolean hasPermission = SkylliaAPI.getPermissionsManager().hasPermission(
                player, island, INVENTORY_OPEN,
                null,
                ConfigLoader.general.getDebugSettings().permission());
        if (!hasPermission) {
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @Override
    public void registerPermissions(PermissionRegistry registry, Plugin owner) {
        this.INVENTORY_OPEN = registry.register(new PermissionNode(
                new NamespacedKey(owner, "inventory.open"),
                "island.permission.inventory_open.name",
                "island.permission.inventory_open.description"
        ));
    }
}