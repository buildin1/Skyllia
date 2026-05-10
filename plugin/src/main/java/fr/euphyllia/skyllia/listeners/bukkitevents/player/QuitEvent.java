package fr.euphyllia.skyllia.listeners.bukkitevents.player;

import fr.euphyllia.skyllia.cache.commands.CacheCommands;
import fr.euphyllia.skyllia.cache.commands.CommandCacheExecution;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class QuitEvent implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        CommandCacheExecution.purgePlayer(playerId);
        CacheCommands.invalidatePlayer(playerId);
    }

}
