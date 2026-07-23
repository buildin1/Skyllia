package fr.euphyllia.skyllia.listeners.permissions.player;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;

public class DamagePermissions implements PermissionModule {

    private PermissionId PLAYER_DAMAGE;

    // 使用 HIGH 优先级 + ignoreCancelled = true，确保在 EntityDamagePermissions 之后执行
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(final EntityDamageByEntityEvent event) {
        // 只处理玩家之间的伤害
        if (!(event.getEntity() instanceof Player victim)) return;

        Player damager = getPlayerFromDamager(event.getDamager());
        if (damager == null) return;

        // OP 可绕过
        if (damager.isOp()) return;

        final Location location = victim.getLocation();
        if (!SkylliaAPI.isWorldSkyblock(location.getWorld())) return;

        final Island island = getIslandAt(location);
        if (island == null) {
            // 无人区禁止 PvP（EntityDamagePermissions 也会处理，这里作为双重保障）
            event.setCancelled(true);
            return;
        }

        // 此时 entity.damage 权限必定为 true（否则事件已被取消，不会执行到此）
        // 检查 player.damage 权限
        if (!hasPlayerDamagePermission(damager, island)) {
            event.setCancelled(true);
        }
    }

    private Player getPlayer(org.bukkit.entity.Entity entity) {
        if (entity instanceof Player player) return player;
        return null;
    }

    private Player getPlayerFromDamager(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private Island getIslandAt(Location location) {
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        return SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
    }

    private boolean hasPlayerDamagePermission(Player player, Island island) {
        return SkylliaAPI.getPermissionsManager().hasPermission(
                player, island, PLAYER_DAMAGE,
                null,
                ConfigLoader.general.getDebugSettings().permission()
        );
    }

    @Override
    public void registerPermissions(PermissionRegistry registry, Plugin owner) {
        this.PLAYER_DAMAGE = registry.register(new PermissionNode(
                new NamespacedKey(owner, "player.damage"),
                "island.permission.player_damage.name",
                "island.permission.player_damage.description"
        ));
    }
}