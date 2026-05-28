package fr.euphyllia.skyllia.hook.canvas.player;

import fr.euphyllia.skyllia.api.event.players.PlayerRespawnSkylliaEvent;
import io.canvasmc.canvas.event.PlayerRespawnAsyncEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class PlayerRespawnHooks implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerRespawnAsync(final PlayerRespawnAsyncEvent event) {
        if (event.isBedSpawn() || event.isAnchorSpawn()) return;

        PlayerRespawnSkylliaEvent skylliaEvent = new PlayerRespawnSkylliaEvent(
                event.getPlayer(),
                event.getRespawnLocation()
        );
        skylliaEvent.callEvent();

        if (!skylliaEvent.isCancelled()) {
            event.setRespawnLocation(skylliaEvent.getRespawnLocation());
        }
    }
}
