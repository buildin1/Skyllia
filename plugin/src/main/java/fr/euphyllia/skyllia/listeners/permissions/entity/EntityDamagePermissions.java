package fr.euphyllia.skyllia.listeners.permissions.entity;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.modules.PermissionModule;
import fr.euphyllia.skyllia.api.service.TrustService;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.plugin.Plugin;

public class EntityDamagePermissions implements PermissionModule {

    private PermissionId ENTITY_DAMAGE;

    // 通用方法：从攻击者中提取玩家（支持弹射物）
    private Player getPlayerFromDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    // 处理普通的实体伤害事件（盔甲架、画、物品展示框等）
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        // 玩家之间的伤害完全交由 DamagePermissions 处理，此处直接放行
        if (event.getEntity() instanceof Player) return;

        Player player = getPlayerFromDamager(event.getDamager());
        if (player == null || player.isOp()) return;

        final Entity target = event.getEntity();
        final Location location = target.getLocation();

        if (!SkylliaAPI.isWorldSkyblock(location.getWorld()) || player.isOp()) return;

        final Island island = getIslandAt(location);
        if (island == null) {
            event.setCancelled(true);
            return;
        }

        if (!hasEntityDamagePermission(player, island)) {
            event.setCancelled(true);
            return;
        }

        // 受保护实体：仅岛主/成员/信任玩家可伤害
        if (isProtectedDecorEntity(target) && !isPrivilegedPlayer(player, island)) {
            event.setCancelled(true);
        }
    }

    // 处理船只和矿车破坏事件
    @EventHandler(ignoreCancelled = true)
    public void onVehicleDestroy(final VehicleDestroyEvent event) {
        Entity attacker = event.getAttacker();
        if (attacker == null) return; // 可能是环境破坏（如碰撞），放行

        Player player = getPlayerFromDamager(attacker);
        if (player == null || player.isOp()) return; // 不是玩家行为，放行

        final Entity vehicle = event.getVehicle();
        final Location location = vehicle.getLocation();

        if (!SkylliaAPI.isWorldSkyblock(location.getWorld()) || player.isOp()) return;

        final Island island = getIslandAt(location);
        if (island == null) {
            event.setCancelled(true);
            return;
        }

        if (!hasEntityDamagePermission(player, island)) {
            event.setCancelled(true);
            return;
        }

        if (!isPrivilegedPlayer(player, island)) {
            event.setCancelled(true);
        }
    }

    // 工具方法：根据位置获取空岛
    private Island getIslandAt(Location location) {
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        return SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
    }

    // 检查玩家是否拥有 ENTITY_DAMAGE 权限（考虑绕过权限）
    private boolean hasEntityDamagePermission(Player player, Island island) {
        return SkylliaAPI.getPermissionsManager().hasPermission(
                player, island, ENTITY_DAMAGE,
                null,
                ConfigLoader.general.getDebugSettings().permission()
        );
    }

    private boolean isProtectedDecorEntity(Entity entity) {
        return entity instanceof Minecart
                || entity instanceof Boat
                || entity instanceof ArmorStand
                || entity instanceof ItemFrame
                || entity instanceof Painting
                || entity instanceof LeashHitch;
    }

    // 判断玩家是否为岛屿的岛主、成员或信任玩家
    private boolean isPrivilegedPlayer(Player player, Island island) {
        Players owner = island.getOwner();
        if (owner != null && owner.getMojangId().equals(player.getUniqueId())) return true;
        if (island.getMember(player.getUniqueId()) != null) return true;
        TrustService trustService = SkylliaAPI.getTrustService();
        return trustService != null && trustService.isTrusted(island.getId(), player.getUniqueId());
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