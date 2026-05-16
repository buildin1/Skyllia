package fr.euphyllia.skylliaislandlevel.cache;

import fr.euphyllia.skyllia.api.utils.ExpiringValue;
import fr.euphyllia.skylliaislandlevel.api.IslandLevelRecord;
import fr.euphyllia.skylliaislandlevel.configuration.IslandLevelConfigLoader;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class LevelPapiCache {

    private final ConcurrentHashMap<UUID, ExpiringValue<Double>> scoreByIsland = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ExpiringValue<Long>> levelByIsland = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();

    private final AtomicReference<ExpiringValue<List<IslandLevelRecord>>> topCache = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<List<IslandLevelRecord>>> topInFlight = new AtomicReference<>();

    private static <K, V> @Nullable V getIfValid(ConcurrentHashMap<K, ExpiringValue<V>> map, K key) {
        ExpiringValue<V> ev = map.get(key);
        if (ev == null) return null;
        if (ev.isExpired()) {
            map.remove(key, ev);
            return null;
        }
        return ev.get();
    }

    public @Nullable Double getScoreIfValid(UUID islandId) {
        return getIfValid(scoreByIsland, islandId);
    }

    public @Nullable Long getLevelIfValid(UUID islandId) {
        return getIfValid(levelByIsland, islandId);
    }

    public void put(UUID islandId, double score, long level) {
        long ttl = IslandLevelConfigLoader.config.getTimerIntervalSecondsTop();
        scoreByIsland.put(islandId, ExpiringValue.of(score, ttl, TimeUnit.SECONDS));
        levelByIsland.put(islandId, ExpiringValue.of(level, ttl, TimeUnit.SECONDS));
    }

    public void invalidate(UUID islandId) {
        scoreByIsland.remove(islandId);
        levelByIsland.remove(islandId);
        inFlight.remove(islandId);
        topCache.set(null);
    }

    public void clear() {
        scoreByIsland.clear();
        levelByIsland.clear();
        inFlight.clear();
        topCache.set(null);
        topInFlight.set(null);
    }

    public double getScoreOrDefaultAndRefresh(Plugin plugin, UUID islandId,
                                              Supplier<double[]> dbLoaderSync, double fallback) {
        Double cached = getScoreIfValid(islandId);
        if (cached != null) return cached;
        refreshAsync(plugin, islandId, dbLoaderSync);
        return fallback;
    }

    public long getLevelOrDefaultAndRefresh(Plugin plugin, UUID islandId,
                                            Supplier<double[]> dbLoaderSync, long fallback) {
        Long cached = getLevelIfValid(islandId);
        if (cached != null) return cached;
        refreshAsync(plugin, islandId, dbLoaderSync);
        return fallback;
    }

    public void refreshAsync(Plugin plugin, UUID islandId, Supplier<double[]> dbLoaderSync) {
        inFlight.computeIfAbsent(islandId, id -> {
            CompletableFuture<Void> f = new CompletableFuture<>();
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try {
                    double[] result = dbLoaderSync.get();
                    if (result != null && result.length == 2) {
                        put(id, result[0], (long) result[1]);
                    }
                    f.complete(null);
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                } finally {
                    inFlight.remove(id);
                }
            });
            return f;
        });
    }

    public @Nullable List<IslandLevelRecord> getTopIfValid() {
        ExpiringValue<List<IslandLevelRecord>> ev = topCache.get();
        if (ev == null) return null;
        if (ev.isExpired()) {
            topCache.compareAndSet(ev, null);
            return null;
        }
        return ev.get();
    }

    public void putTop(List<IslandLevelRecord> top) {
        long ttl = IslandLevelConfigLoader.config.getTimerIntervalSecondsTop();
        topCache.set(ExpiringValue.of(top, ttl, TimeUnit.SECONDS));
    }

    public List<IslandLevelRecord> getTopOrDefaultAndRefresh(Plugin plugin,
                                                             Supplier<List<IslandLevelRecord>> dbLoaderSync,
                                                             List<IslandLevelRecord> fallback) {
        List<IslandLevelRecord> cached = getTopIfValid();
        if (cached != null) return cached;
        refreshTopAsync(plugin, dbLoaderSync);
        return fallback;
    }

    public void refreshTopAsync(Plugin plugin, Supplier<List<IslandLevelRecord>> dbLoaderSync) {
        if (topInFlight.get() != null) return;
        CompletableFuture<List<IslandLevelRecord>> f = new CompletableFuture<>();
        if (!topInFlight.compareAndSet(null, f)) return;

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                List<IslandLevelRecord> top = dbLoaderSync.get();
                if (top != null) putTop(top);
                f.complete(top);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            } finally {
                topInFlight.compareAndSet(f, null);
            }
        });
    }
}
