package fr.euphyllia.skyllia.utils;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.event.players.PlayerTeleportSpawnEvent;
import fr.euphyllia.skyllia.api.hooks.PermissionHook;
import fr.euphyllia.skyllia.api.hooks.SpawnHook;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
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

    /**
     * 给玩家下发自己岛屿的独立边境（WorldBorder）。
     * <p>
     * 调用方必须保证当前线程已经拥有目标 region 的归属权（比如已经在玩家自己的
     * {@code EntityScheduler} 回调里），本方法内部不做任何线程调度。
     * </p>
     */
    public static void applyIslandBorder(Player player, @Nullable Island island) {
        if (island == null) return;

        World islandWorld = fr.euphyllia.skyllia.utils.WorldUtils.getWorldConfigs().getFirst().getWorld();
        if (islandWorld == null) return;

        RegionCoordinate region = island.getRegionCoordinate();
        Location center = RegionHelper.getCenterRegion(islandWorld, region.x(), region.z());

        applyBorder(player, center, island.getSize());
    }

    /**
     * 通用版本：给玩家下发一个以任意中心点+半径定义的独立边境（WorldBorder），
     * 不要求跟岛屿绑定——活动区之类"中心点+半径"的其它区域概念可以直接复用。
     * <p>
     * 调用方必须保证当前线程已经拥有目标 region 的归属权，本方法内部不做任何线程调度。
     * </p>
     */
    public static void applyBorder(Player player, Location center, double size) {
        if (hasPermission(player, "skyllia.island.worldborder.bypass")) return;

        WorldBorder border = player.getWorldBorder();
        if (border == null) border = Bukkit.createWorldBorder();
        border.setCenter(center);
        border.setSize(size);
        player.setWorldBorder(border);
    }

    public static boolean hasPermission(Player player, String key) {
        PermissionHook hook = Skyllia.getInstance().getInterneAPI().getPermissionHook();

        boolean result = (hook != null && hook.isAvailable())
                ? hook.hasPermission(player, key)
                : player.hasPermission(key);

        return logPermission(player.getName(), player.locale(), key, result);
    }

    public static boolean hasPermission(CommandSender sender, String key) {
        if (sender instanceof Player player) {
            return hasPermission(player, key);
        }

        boolean result = sender.hasPermission(key);
        return logPermission(sender.getName(), Locale.ENGLISH, key, result);
    }

    private static boolean logPermission(String name, @Nullable Locale locale, String key, boolean result) {
        if (!ConfigLoader.general.getDebugSettings().permission())
            return result;

        String translationKey = result
                ? "debug.permission-has"
                : "debug.permission-missing";

        Map<String, String> placeholders = Map.of(
                "%player%", name,
                "%permission%", key
        );

        Component message = ConfigLoader.language.translate(
                locale,
                translationKey,
                placeholders,
                false
        );

        Bukkit.getConsoleSender().sendMessage(message);
        return result;
    }

}
