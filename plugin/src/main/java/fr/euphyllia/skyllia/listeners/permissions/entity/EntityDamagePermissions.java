package fr.euphyllia.skyllia.listeners.permissions.entity;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;

import static fr.euphyllia.skyllia.api.commands.SubCommandInterface.log;

public class EntityDamagePermissions implements PermissionModule {

    private PermissionId ENTITY_DAMAGE;

    @EventHandler(ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Player player;
        if (damager instanceof Player) {
            player = (Player) damager;
        } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            player = shooter;
        } else {
            return;
        }

        final Entity target = event.getEntity();
        final Location location = target.getLocation();

        if (!SkylliaAPI.isWorldSkyblock(location.getWorld()) || player.isOp()) return;

        final int chunkX = location.getBlockX() >> 4;
        final int chunkZ = location.getBlockZ() >> 4;
        final Island island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
        if (island == null) {
            //log.warn("玩家{}在{}位置岛屿无效，无法进行{}", player.getName(), player.getLocation(), event.getEventName());
            event.setCancelled(true);
            return;
        }

        final boolean hasPermission = SkylliaAPI.getPermissionsManager().hasPermission(player, island, ENTITY_DAMAGE, "skyllia.player.entity.damage.bypass", ConfigLoader.general.getDebugSettings().permission());
        if (!hasPermission) {
            //log.warn("玩家{}在{}岛上没有ENTITY_DAMAGE权限，无法进行{}", player.getName(), island.getOwner().getLastKnowName(), event.getEventName());
            event.setCancelled(true);
        }
    }

    @Override
    public void registerPermissions(PermissionRegistry registry, Plugin owner) {
        this.ENTITY_DAMAGE = registry.register(new PermissionNode(
                new NamespacedKey(owner, "entity.damage"),
                "island.permission.entity_damage.name",
                "island.permission.entity_damage.description"
        ));
    }
}
