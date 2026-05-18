package fr.euphyllia.skylliaislandlevel.manager;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.database.IslandCustomDataQuery;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliaislandlevel.SkylliaIslandLevel;
import fr.euphyllia.skylliaislandlevel.api.IslandLevelRecord;
import fr.euphyllia.skylliaislandlevel.cache.LevelPapiCache;
import fr.euphyllia.skylliaislandlevel.configuration.IslandLevelConfigLoader;
import fr.euphyllia.skylliaislandlevel.scanner.IslandScanner;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class LevelManager {

    private static final Logger log = LoggerFactory.getLogger(LevelManager.class);
    private static final String KEY_SCORE = "score";
    private static final String KEY_LEVEL = "level";
    private final Set<UUID> scanningIslands = ConcurrentHashMap.newKeySet();
    private final IslandScanner scanner;
    private final LevelPapiCache papiCache;
    private final LevelFormulaEvaluator evaluator;

    private final NamespacedKey namespaceKey;
    private final SkylliaIslandLevel plugin;

    public LevelManager(SkylliaIslandLevel plugin, LevelPapiCache papiCache) {
        this.plugin = plugin;
        this.papiCache = papiCache;
        this.namespaceKey = new NamespacedKey(plugin, "data");
        this.scanner = new IslandScanner(plugin);
        this.evaluator = new LevelFormulaEvaluator(
                IslandLevelConfigLoader.config.getLevelExpression()
        );
        startTimer();
    }

    private void startTimer() {
        int intervalSecs = IslandLevelConfigLoader.config.getTimerIntervalSeconds();
        if (intervalSecs <= 0) return;
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduledTask -> {
            try {
                autoScanAll();
            } catch (Throwable t) {
                log.error("Error occurred during scheduled island scan", t);
            }
        }, 1, intervalSecs, TimeUnit.SECONDS);
    }

    private void autoScanAll() {
        var onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) return;

        List<Island> islandsLoaded = new ArrayList<>();
        for (Player p : onlinePlayers) {
            Island island = SkylliaAPI.getIslandByPlayerId(p.getUniqueId());
            if (island != null && !islandsLoaded.contains(island)) {
                islandsLoaded.add(island);
            }
        }

        for (Island island : islandsLoaded) {
            if (scanningIslands.contains(island.getId())) continue;
            triggerScan(island, null);
        }
    }

    public boolean triggerScan(Island island, ScanCallback callback) {
        if (!scanningIslands.add(island.getId())) {
            log.warn("Scan for island '{}' is already in progress, skipping trigger.", island.getId());
            return false;
        }

        World world = pickWorld();

        if (world == null) {
            scanningIslands.remove(island.getId());
            log.warn("Failed to find a world for scanning island '{}', aborting scan.", island.getId());
            return false;
        }

        scanner.scanIsland(island, world).whenComplete((score, err) -> {
            try {
                if (err != null) {
                    log.error("Scan error for island {}", island.getId(), err);
                    return;
                }
                long currentLevel = getStoredLevel(island);
                long newLevel = evaluator.evaluate(
                        score, currentLevel, IslandLevelConfigLoader.config.getMinLevel(), island
                );
                log.debug("[LevelManager] score={} currentLevel={} newLevel={} formulaValid={}",
                        score, currentLevel, newLevel, evaluator.isValid());
                saveData(island, score, newLevel);
                papiCache.invalidate(island.getId());

                if (callback != null) {
                    callback.onComplete(score, newLevel);
                }
            } finally {
                scanningIslands.remove(island.getId());
            }
        });

        return true;
    }

    public boolean isScanning(UUID islandId) {
        return scanningIslands.contains(islandId);
    }

    public double getStoredScore(Island island) {
        IslandCustomDataQuery q = SkylliaAPI.getIslandCustomDataQuery();
        try {
            Double raw = q.get(namespaceKey, island, KEY_SCORE, PersistentDataType.DOUBLE);
            return raw != null ? raw : 0.0;
        } catch (Exception e) {
            q.set(namespaceKey, island, KEY_SCORE, PersistentDataType.DOUBLE, 0.0);
            return 0.0;
        }
    }


    public long getStoredLevel(Island island) {
        IslandCustomDataQuery q = SkylliaAPI.getIslandCustomDataQuery();
        try {
            Long raw = q.get(namespaceKey, island, KEY_LEVEL, PersistentDataType.LONG);
            return raw != null ? raw : 0L;
        } catch (Exception e) {
            q.set(namespaceKey, island, KEY_LEVEL, PersistentDataType.LONG, 0L);
            return 0L;
        }
    }

    public List<IslandLevelRecord> getTopIslands() {
        List<IslandLevelRecord> cached = papiCache.getTopIfValid();
        if (cached != null) return cached;

        List<IslandLevelRecord> top = SkylliaAPI.getAllIslandsValid().stream()
                .map(island -> {
                    double score = getStoredScore(island);
                    long level = getStoredLevel(island);
                    papiCache.put(island.getId(), score, level);
                    return new IslandLevelRecord(island.getId(), score, level);
                })
                .sorted(Comparator.comparingLong(IslandLevelRecord::level).reversed())
                .collect(Collectors.toList());

        papiCache.putTop(top);
        return top;
    }


    private void saveData(Island island, double score, long level) {
        IslandCustomDataQuery q = SkylliaAPI.getIslandCustomDataQuery();
        q.set(namespaceKey, island, KEY_SCORE, PersistentDataType.DOUBLE, score);
        q.set(namespaceKey, island, KEY_LEVEL, PersistentDataType.LONG, level);
        log.debug("[SkylliaIslandLevel] Island {} → score={}, level={}", island.getId(), score, level);
    }

    private World pickWorld() {
        for (World w : Bukkit.getWorlds()) {
            if (SkylliaAPI.isWorldSkyblock(w)) return w;
        }
        return null;
    }

    @FunctionalInterface
    public interface ScanCallback {
        void onComplete(double score, long level);
    }
}
