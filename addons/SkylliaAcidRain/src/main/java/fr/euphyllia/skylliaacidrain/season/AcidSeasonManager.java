package fr.euphyllia.skylliaacidrain.season;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.addons.skylliaacidrain.event.AcidSeasonEndEvent;
import fr.euphyllia.skyllia.api.addons.skylliaacidrain.event.AcidSeasonPlayerSurvivedEvent;
import fr.euphyllia.skyllia.api.addons.skylliaacidrain.event.AcidSeasonStartEvent;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skylliaacidrain.SkylliaAcidRain;
import fr.euphyllia.skylliaacidrain.configuration.AcidConfigLoader;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the periodic "acid season" (酸雨季) cycle: on a schedule tied to
 * vanilla's moon phase, flips each Skyblock world between a harmless state
 * and an active acid season during which {@code AcidListener}'s damage tick
 * is allowed to apply.
 *
 * <p>Folia-safe: the periodic check runs on the global region scheduler and
 * only touches per-player state through each player's own entity scheduler.</p>
 */
public class AcidSeasonManager implements Listener {

    private static final Logger log = LoggerFactory.getLogger(AcidSeasonManager.class);
    private static final long TICKS_PER_DAY = 24000L;
    private static final long MOON_PHASE_COUNT = 8L;

    private static final Map<UUID, SeasonState> seasonStates = new ConcurrentHashMap<>();

    private final SkylliaAcidRain plugin;
    private ScheduledTask checkTask;

    public AcidSeasonManager(SkylliaAcidRain plugin) {
        this.plugin = plugin;
    }

    /**
     * Returns whether world is currently within an active acid season.
     */
    public static boolean isSeasonActive(World world) {
        SeasonState state = seasonStates.get(world.getUID());
        return state != null && state.active;
    }

    /**
     * (Re)starts the periodic moon-phase check task, cancelling any previous one.
     * Safe to call again on config reload.
     */
    public void restart() {
        stop();
        long interval = Math.max(1L, AcidConfigLoader.config.getSeasonCheckIntervalTick());
        this.checkTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, task -> tick(), interval, interval);
    }

    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }

    private void tick() {
        if (!AcidConfigLoader.config.isSeasonEnabled()) return;

        for (WorldConfig worldConfig : SkylliaAPI.getRegisteredWorlds()) {
            World world = worldConfig.getWorld();
            if (world == null) continue;
            evaluateWorld(world);
        }
    }

    private void evaluateWorld(World world) {
        SeasonState state = seasonStates.computeIfAbsent(world.getUID(), key -> new SeasonState());

        synchronized (state) {
            long fullTime = world.getFullTime();

            if (state.active) {
                if (fullTime >= state.seasonEndFullTime) {
                    endSeason(world, state);
                }
                return;
            }

            long dayIndex = fullTime / TICKS_PER_DAY;
            long moonPhase = dayIndex % MOON_PHASE_COUNT;
            if (moonPhase != 0 || dayIndex == state.lastTriggerDayIndex) return; // 0 = 满月之夜

            long everyN = Math.max(1, AcidConfigLoader.config.getSeasonEveryNFullMoons());
            long fullMoonIndex = dayIndex / MOON_PHASE_COUNT;
            if (fullMoonIndex % everyN != 0) return;

            state.lastTriggerDayIndex = dayIndex;
            startSeason(world, state);
        }
    }

    /**
     * Force-starts a season for a world right now, bypassing the moon-phase gate.
     * Intended for admin testing.
     */
    public void forceStartSeason(World world) {
        SeasonState state = seasonStates.computeIfAbsent(world.getUID(), key -> new SeasonState());
        synchronized (state) {
            startSeason(world, state);
        }
    }

    /**
     * Force-ends the active season for a world right now, if any.
     *
     * @return true if a season was active and has been ended, false if the world wasn't in a season.
     */
    public boolean forceEndSeason(World world) {
        SeasonState state = seasonStates.get(world.getUID());
        if (state == null) return false;
        synchronized (state) {
            if (!state.active) return false;
            endSeason(world, state);
        }
        return true;
    }

    private void startSeason(World world, SeasonState state) {
        long duration = Math.max(1L, AcidConfigLoader.config.getSeasonDurationTick());

        state.active = true;
        state.seasonEndFullTime = world.getFullTime() + duration;
        state.trackedPlayers.clear();
        for (Player player : world.getPlayers()) {
            state.trackedPlayers.add(player.getUniqueId());
        }

        log.info("酸雨季在世界 {} 开始，持续 {} tick，参与追踪玩家数：{}", world.getName(), duration, state.trackedPlayers.size());

        // startSeason() 总是从 global region 线程调用（tick() 的定时任务本身，或 admin 命令里的 runAtFixedRate/run 包装），
        // 因此可以直接同步触发事件，无需再包一层调度。
        new AcidSeasonStartEvent(world).callEvent();

        if (AcidConfigLoader.config.isSeasonBroadcastEnabled()) {
            broadcastToWorld(world, AcidConfigLoader.config.getSeasonStartMessage());
        }
    }

    private void endSeason(World world, SeasonState state) {
        state.active = false;

        Set<UUID> survivors = new HashSet<>();
        for (UUID uuid : state.trackedPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && world.getUID().equals(player.getWorld().getUID())) {
                survivors.add(uuid);
            }
        }
        state.trackedPlayers.clear();

        log.info("酸雨季在世界 {} 结束，存活并全程在场的玩家数：{}", world.getName(), survivors.size());

        new AcidSeasonEndEvent(world).callEvent();

        for (UUID uuid : survivors) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) {
                    new AcidSeasonPlayerSurvivedEvent(player, world).callEvent();
                }
            }, null);
        }

        if (AcidConfigLoader.config.isSeasonBroadcastEnabled()) {
            broadcastToWorld(world, AcidConfigLoader.config.getSeasonEndMessage());
        }
    }

    private void broadcastToWorld(World world, String miniMessageText) {
        if (miniMessageText == null || miniMessageText.isBlank()) return;
        Component message = MiniMessage.miniMessage().deserialize(miniMessageText);
        for (Player player : world.getPlayers()) {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) player.sendMessage(message);
            }, null);
        }
    }

    /**
     * A death (any cause) during an active season disqualifies the player from the
     * "survived the season" event for every season currently tracking them.
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        for (SeasonState state : seasonStates.values()) {
            state.trackedPlayers.remove(uuid);
        }
    }

    private static final class SeasonState {
        volatile boolean active = false;
        volatile long seasonEndFullTime = 0L;
        volatile long lastTriggerDayIndex = -1L;
        final Set<UUID> trackedPlayers = ConcurrentHashMap.newKeySet();
    }
}
