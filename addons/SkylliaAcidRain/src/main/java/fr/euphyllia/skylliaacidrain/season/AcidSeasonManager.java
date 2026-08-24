package fr.euphyllia.skylliaacidrain.season;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.addons.skylliaacidrain.event.AcidSeasonEndEvent;
import fr.euphyllia.skyllia.api.addons.skylliaacidrain.event.AcidSeasonPlayerSurvivedEvent;
import fr.euphyllia.skyllia.api.addons.skylliaacidrain.event.AcidSeasonPlayerSurvivedOfflineEvent;
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

    /**
     * 判定「熬过这一季」所需的最低在场比例。
     * 0.5 = 整季一半时间在场即可——既不像旧实现那样苛刻到"开始那一 tick 必须在场"，
     * 也不至于让人卡在最后一秒进来白拿。
     */
    private static final double SURVIVE_RATIO = 0.5D;
    private static final long TICKS_PER_DAY = 24000L;
    private static final long MOON_PHASE_COUNT = 8L;

    private static final Map<UUID, SeasonState> seasonStates = new ConcurrentHashMap<>();

    private final SkylliaAcidRain plugin;
    private final SeasonStateStore store;
    private ScheduledTask checkTask;

    public AcidSeasonManager(SkylliaAcidRain plugin) {
        this.plugin = plugin;
        this.store = new SeasonStateStore(plugin.getDataFolder());
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
        SeasonState state = stateFor(world);

        synchronized (state) {
            long fullTime = world.getFullTime();

            if (state.active) {
                // 每轮巡检累计一次「在场时长」。
                // 旧实现只在 startSeason 那一瞬间对世界里的玩家拍一次快照，之后再没有任何
                // 地方往 trackedPlayers 里加人——玩家只要晚进服哪怕 30 秒，就永远算不上
                // 「熬过这一季」，不管之后待多久（2026-08-24 服主反馈：熬了好几次都没计数）。
                // 改成按在场时长累计：中途进来也算，但必须待够 SURVIVE_RATIO 的比例才算数，
                // 免得有人卡在最后一秒进来白拿。死亡过的玩家进了 disqualified，不再累计。
                accumulatePresence(world, state);
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
        SeasonState state = stateFor(world);
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

    /**
     * 取得世界的季节状态；首次访问时从磁盘恢复上一次落盘的内容。
     */
    private SeasonState stateFor(World world) {
        return seasonStates.computeIfAbsent(world.getUID(), key -> restore(key, world));
    }

    /**
     * 从磁盘恢复某个世界的季节状态。
     *
     * <p>恢复 {@code lastTriggerDayIndex} 是关键 —— 它归零正是「满月当天重启会让同一轮
     * 酸雨季反复触发」的直接原因。</p>
     */
    private SeasonState restore(UUID worldId, World world) {
        SeasonState state = new SeasonState();
        SeasonStateStore.Snapshot snapshot = store.load(worldId);
        if (snapshot == null) return state;

        state.lastTriggerDayIndex = snapshot.lastTriggerDayIndex();
        state.seasonEndFullTime = snapshot.endFullTime();
        if (!snapshot.active()) return state;

        if (world.getFullTime() < snapshot.endFullTime()) {
            // 停机期间季节尚未走完 —— 恢复为进行中。
            // presenceTicks 从零开始：重启把所有人强制下线过，重启前的在场时长不算。
            // 判定比例按「剩余时长」算，否则原 duration 没落盘、required 会变成 1 tick，
            // 重启后巡检一次就人人过关。
            long remaining = snapshot.endFullTime() - world.getFullTime();
            state.active = true;
            state.seasonDurationTicks = Math.max(1L, remaining);
            log.info("世界 {} 的酸雨季在重启后恢复，剩余 {} tick",
                    world.getName(), remaining);
        } else {
            // 停机期间季节已自然走完 —— 静默标记结束。
            // 这里刻意不广播、不触发结束事件：向从未见过季节开始的玩家播报「酸雨季结束」只会让人困惑。
            state.active = false;
            store.save(worldId, false, snapshot.endFullTime(), snapshot.lastTriggerDayIndex());
            log.info("世界 {} 的酸雨季在服务器停机期间已自然结束", world.getName());
        }
        return state;
    }

    private void startSeason(World world, SeasonState state) {
        long duration = Math.max(1L, AcidConfigLoader.config.getSeasonDurationTick());

        state.active = true;
        state.seasonEndFullTime = world.getFullTime() + duration;
        state.trackedPlayers.clear();
        state.presenceTicks.clear();
        state.disqualified.clear();
        state.seasonDurationTicks = duration;
        for (Player player : world.getPlayers()) {
            state.trackedPlayers.add(player.getUniqueId());
        }

        store.save(world.getUID(), true, state.seasonEndFullTime, state.lastTriggerDayIndex);

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
        store.save(world.getUID(), false, state.seasonEndFullTime, state.lastTriggerDayIndex);

        // 结算：①没有在本季死过、②累计在场时长达到整季的 SURVIVE_RATIO。
        // 结束那一 tick 不必还在线——中途进服熬够时长、结算时刚好下线/换世界，也该计数。
        long required = (long) Math.ceil(Math.max(1L, state.seasonDurationTicks) * SURVIVE_RATIO);
        Set<UUID> survivors = new HashSet<>();
        for (UUID uuid : state.presenceTicks.keySet()) {
            if (state.disqualified.contains(uuid)) continue;
            if (state.presenceTicks.getOrDefault(uuid, 0L) < required) continue;
            survivors.add(uuid);
        }
        state.trackedPlayers.clear();
        state.presenceTicks.clear();
        state.disqualified.clear();

        log.info("酸雨季在世界 {} 结束，在场过半且未死亡的玩家数：{}", world.getName(), survivors.size());

        new AcidSeasonEndEvent(world).callEvent();

        for (UUID uuid : survivors) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                var scheduled = player.getScheduler().run(plugin, task -> {
                    if (player.isOnline()) {
                        new AcidSeasonPlayerSurvivedEvent(player, world).callEvent();
                    } else {
                        new AcidSeasonPlayerSurvivedOfflineEvent(uuid, world).callEvent();
                    }
                }, () -> new AcidSeasonPlayerSurvivedOfflineEvent(uuid, world).callEvent());
                if (scheduled == null) {
                    new AcidSeasonPlayerSurvivedOfflineEvent(uuid, world).callEvent();
                }
            } else {
                // 结算时不在线：挑战进度按岛屿记，不依赖玩家实体。
                new AcidSeasonPlayerSurvivedOfflineEvent(uuid, world).callEvent();
            }
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
            // 光从 trackedPlayers 里删不够——判定已经改成看 presenceTicks 了，
            // 必须显式记进「本季已失格」，否则死完接着待着照样能攒够时长。
            state.disqualified.add(uuid);
        }
    }

    /**
     * 把玩家在本轮巡检时的在场情况累加进 {@code presenceTicks}。
     * <p>
     * 用巡检间隔（{@code season.check-interval-tick}）作为每次累加的粒度：巡检本身就是按这个
     * 间隔跑的，两次巡检之间玩家一直在世界里，就按一个完整间隔计。粒度粗一点没关系——
     * 判定用的是"占整季的比例"，不是精确秒数。
     * </p>
     */
    private void accumulatePresence(World world, SeasonState state) {
        long step = Math.max(1L, AcidConfigLoader.config.getSeasonCheckIntervalTick());
        for (Player player : world.getPlayers()) {
            UUID uuid = player.getUniqueId();
            if (state.disqualified.contains(uuid)) continue;
            state.trackedPlayers.add(uuid);
            state.presenceTicks.merge(uuid, step, Long::sum);
        }
    }

    private static final class SeasonState {
        volatile boolean active = false;
        volatile long seasonEndFullTime = 0L;
        volatile long lastTriggerDayIndex = -1L;
        volatile long seasonDurationTicks = 0L;
        final Set<UUID> trackedPlayers = ConcurrentHashMap.newKeySet();
        /** 本季每名玩家累计的在场 tick 数。 */
        final Map<UUID, Long> presenceTicks = new ConcurrentHashMap<>();
        /** 本季死过、已失去资格的玩家。 */
        final Set<UUID> disqualified = ConcurrentHashMap.newKeySet();
    }
}
