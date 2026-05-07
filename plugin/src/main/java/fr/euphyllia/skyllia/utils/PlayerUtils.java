package fr.euphyllia.skyllia.utils;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.event.players.PlayerTeleportSpawnEvent;
import fr.euphyllia.skyllia.api.hooks.SpawnHook;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;

public class PlayerUtils {

    public static void teleportPlayerSpawn(Player player) {
        if (!ConfigLoader.general.getSpawnSettings().enabled()) return;
        player.getScheduler().execute(SkylliaAPI.getPlugin(), () -> {
            if (!player.isOnline()) return;

            SpawnHook spawnHook = Skyllia.getInstance().getInterneAPI().getSpawnHook();
            if (spawnHook != null && spawnHook.isAvailable()) {
                Location hookLocation = spawnHook.getSpawnLocation(player);
                if (hookLocation != null) {
                    PlayerTeleportSpawnEvent playerTeleportSpawnEvent = new PlayerTeleportSpawnEvent(player, hookLocation);
                    Bukkit.getPluginManager().callEvent(playerTeleportSpawnEvent);
                    if (playerTeleportSpawnEvent.isCancelled()) {
                        return;
                    }
                    player.teleportAsync(playerTeleportSpawnEvent.getFinalLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                    return;
                }
            }

            Location location = ConfigLoader.general.getSpawnLocation();
            if (location == null || location.getWorld() == null)
                location = Bukkit.getWorlds().getFirst().getSpawnLocation();
            PlayerTeleportSpawnEvent playerTeleportSpawnEvent = new PlayerTeleportSpawnEvent(player, location);
            Bukkit.getPluginManager().callEvent(playerTeleportSpawnEvent);
            if (playerTeleportSpawnEvent.isCancelled()) {
                return;
            }
            player.teleportAsync(playerTeleportSpawnEvent.getFinalLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        }, null, 1L);

    }

    public static boolean hasPermission(Player player, String key) {
        var result = player.hasPermission(key);

        if (!ConfigLoader.general.getDebugSettings().permission())
            return result;

        String translationKey = result
                ? "debug.permission-has"
                : "debug.permission-missing";

        Map<String, String> placeholders = Map.of(
                "<player>", player.getName(),
                "<permission>", key
        );

        Component message = ConfigLoader.language.translate(
                player.locale(),
                translationKey,
                placeholders,
                false
        );

        Bukkit.getConsoleSender().sendMessage(message);

        return result;
    }
}
