package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 盯着开了 /is fly 的玩家：走出/传送出自己的空岛就暂停飞行，回来恢复。见 {@link IslandFlight}。
 * 没开飞行的玩家每个事件只做一次集合查询就返回。
 */
public class IslandFlightListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) return;

        Player player = event.getPlayer();
        if (!IslandFlight.isEnabled(player.getUniqueId())) return;
        IslandFlight.refresh(player, to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        refreshNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refreshNextTick(event.getPlayer());
    }

    /** 从创造切回生存时原版会清掉 allowFlight，下一 tick 按位置补回来。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        refreshNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (IslandFlight.consumeFallGrace(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        IslandFlight.forget(event.getPlayer().getUniqueId());
    }

    /** 传送/换世界时事件里拿到的还不是最终状态，延迟一 tick 在玩家线程上按落点重新判断。 */
    private void refreshNextTick(Player player) {
        if (!IslandFlight.isEnabled(player.getUniqueId())) return;
        player.getScheduler().runDelayed(SkylliaAPI.getPlugin(),
                task -> IslandFlight.refresh(player, player.getLocation()), null, 1L);
    }
}
