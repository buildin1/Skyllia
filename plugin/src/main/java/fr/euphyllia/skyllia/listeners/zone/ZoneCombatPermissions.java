package fr.euphyllia.skyllia.listeners.zone;

import fr.euphyllia.skyllia.api.InterneAPI;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.zone.ActivityZone;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Optional;

/**
 * 活动区内的 PVP / 打怪权限（allowPvp / allowMobAttack）。跟方块破坏/放置一样，
 * 需要在岛屿伤害权限监听器（{@code EntityDamagePermissions} LOW、
 * {@code DamagePermissions} HIGH）判定完之后再跑，才能覆盖掉"该位置没有岛屿
 * 所以直接取消"的默认判定。
 */
public class ZoneCombatPermissions implements Listener {

    private final InterneAPI interneAPI;

    public ZoneCombatPermissions(InterneAPI interneAPI) {
        this.interneAPI = interneAPI;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        final Player damager = getPlayerFromDamager(event.getDamager());
        if (damager == null || damager.isOp()) return;

        final Location location = victim.getLocation();
        if (!SkylliaAPI.isWorldSkyblock(location.getWorld())) return;

        final Optional<ActivityZone> zoneOpt = interneAPI.getActivityZoneManager()
                .findZoneAt(location.getBlockX(), location.getBlockZ());
        if (zoneOpt.isEmpty()) return;

        final ActivityZone zone = zoneOpt.get();
        if (victim instanceof Player) {
            // 玩家对玩家：PVP 开关
            event.setCancelled(!zone.allowPvp());
            if (!zone.allowPvp()) {
                ConfigLoader.language.sendMessage(damager, "island.zone.pvp-denied");
            }
        } else {
            // 玩家对生物：打怪开关
            event.setCancelled(!zone.allowMobAttack());
            if (!zone.allowMobAttack()) {
                ConfigLoader.language.sendMessage(damager, "island.zone.mob-attack-denied");
            }
        }
    }

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
}
