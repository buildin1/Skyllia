package fr.euphyllia.skylliaislandlevel.scanner;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skylliaislandlevel.SkylliaIslandLevel;
import fr.euphyllia.skylliaislandlevel.configuration.IslandLevelConfigLoader;
import fr.euphyllia.skylliaislandlevel.configuration.IslandLevelConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class IslandScanner {

    private static final Logger log = LoggerFactory.getLogger(IslandScanner.class);
    private final SkylliaIslandLevel plugin;
    private final ScheduledExecutorService scanScheduler;
    private final ExecutorService scoreExecutor;

    public IslandScanner(SkylliaIslandLevel plugin) {
        this.plugin = plugin;
        IslandLevelConfigManager.ChunkProcessingSettings cfg = IslandLevelConfigLoader.config.getProcessingSettings();
        this.scanScheduler = Executors.newScheduledThreadPool(
                cfg.resolvedScanThreads(),
                r -> {
                    Thread t = new Thread(r, "skyllia-scan-scheduler");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });

        this.scoreExecutor = Executors.newFixedThreadPool(
                Math.max(1, cfg.resolvedScoreThreads()),
                r -> {
                    Thread t = new Thread(r, "skyllia-scan-scorer");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
    }

    public CompletableFuture<ScanResult> scanIsland(Island island, World world) {
        CompletableFuture<ScanResult> result = new CompletableFuture<>();

        List<int[]> chunkCoords = getIslandChunks(island);

        // DEBUG
        log.debug("[Scanner] Island {} — center chunk ({},{}) size={} → {} chunks to scan",
                island.getId(),
                island.getRegionCoordinate().x(), island.getRegionCoordinate().z(),
                island.getSize(),
                chunkCoords.size());

        if (chunkCoords.isEmpty()) {
            result.complete(ScanResult.empty());
            return result;
        }

        Map<Material, Double> blockValues = IslandLevelConfigLoader.config.getBlockValues();
        log.debug("[Scanner] blockValues loaded: {} entries", blockValues.size());
        List<Player> onlineMembers = new ArrayList<>();
        for (Players member : island.getMembers()) {
            Player player = Bukkit.getPlayer(member.getMojangId());
            if (player != null) {
                onlineMembers.add(player);
            }
        }

        ScanProgressNotifier notifier = onlineMembers.isEmpty()
                ? null
                : new ScanProgressNotifier(onlineMembers);

        int total = chunkCoords.size();
        AtomicInteger remaining = new AtomicInteger(total);
        AtomicInteger done = new AtomicInteger(0);
        // 逐材料累计整岛数量，而不是逐区块直接算分数。
        // 单材料计数上限必须作用于整岛总量：按区块算的话，把同一种方块摊到多个区块里
        // 就能绕过上限。这份计数同时也是计分表 GUI「你的岛屿有多少个」的数据来源。
        Map<Material, LongAdder> islandCounts = new ConcurrentHashMap<>();

        IslandLevelConfigManager.ChunkProcessingSettings cfg =
                IslandLevelConfigLoader.config.getProcessingSettings();

        int maxConcurrent = Math.max(1, cfg.resolvedScoreThreads() * 2);
        Semaphore semaphore = new Semaphore(maxConcurrent);

        for (int i = 0; i < chunkCoords.size(); i++) {
            int[] coord = chunkCoords.get(i);
            int chunkX = coord[0];
            int chunkZ = coord[1];
            final long delay = (long) cfg.scanDelayMs() * i;

            this.scanScheduler.schedule(() -> {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (remaining.decrementAndGet() == 0) result.complete(summarize(islandCounts, blockValues));
                    return;
                }

                world.getChunkAtAsync(chunkX, chunkZ, false, false)
                        .thenAcceptAsync(chunk -> {
                            if (chunk == null) {
                                semaphore.release();
                                if (notifier != null) notifier.update(done.incrementAndGet(), total);
                                if (remaining.decrementAndGet() == 0) {
                                    if (notifier != null) notifier.finish();
                                    result.complete(summarize(islandCounts, blockValues));
                                }
                                return;
                            }
                            try {
                                accumulateChunk(world, chunkX, chunkZ, islandCounts);
                            } catch (Throwable e) {
                                log.error("Failed to score chunk ({},{}) for island '{}'",
                                        chunkX, chunkZ, island.getId(), e);
                            } finally {
                                semaphore.release();
                                int nowDone = done.incrementAndGet();
                                if (notifier != null) notifier.update(nowDone, total);
                                if (remaining.decrementAndGet() == 0) {
                                    if (notifier != null) notifier.finish();
                                    result.complete(summarize(islandCounts, blockValues));
                                }
                            }
                        }, scoreExecutor);
            }, delay, TimeUnit.MILLISECONDS);

        }
        return result;
    }

    /**
     * 统计一个区块内各材料的数量，累加进整岛计数表。
     * <p>
     * 这里刻意<b>不算分</b> —— 分数必须等整岛统计完再算一次，否则单材料上限会被
     * 「把同一种方块摊到多个区块」绕过去。
     * </p>
     */
    private void accumulateChunk(World world, int chunkX, int chunkZ, Map<Material, LongAdder> islandCounts) {
        Map<Material, Integer> blockCounts = SkylliaAPI.getWorldNMS().getCountAllBlocksInChunk(world, chunkX, chunkZ);
        if (blockCounts.isEmpty()) {
            log.debug("[Scanner] Chunk ({},{}) → empty", chunkX, chunkZ);
            return;
        }

        log.debug("[Scanner] Chunk ({},{}) → {} block types found", chunkX, chunkZ, blockCounts.size());
        for (Map.Entry<Material, Integer> entry : blockCounts.entrySet()) {
            islandCounts.computeIfAbsent(entry.getKey(), k -> new LongAdder()).add(entry.getValue());
        }
    }

    /**
     * 把整岛的逐材料计数折算成最终分数，逐材料应用计数上限。
     *
     * @return 分数与逐材料数量；后者供计分表 GUI 展示「你的岛屿有多少个 / 贡献多少分」
     */
    private ScanResult summarize(Map<Material, LongAdder> islandCounts, Map<Material, Double> blockValues) {
        IslandLevelConfigManager cfg = IslandLevelConfigLoader.config;
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        double score = 0.0;

        for (Map.Entry<Material, LongAdder> entry : islandCounts.entrySet()) {
            Material material = entry.getKey();
            long raw = entry.getValue().sum();
            counts.put(material, (int) Math.min(raw, Integer.MAX_VALUE));

            Double value = blockValues.get(material);
            if (value == null) continue;

            // 上限 < 0 表示不限制，与加入本机制之前的行为一致
            int cap = cfg.getBlockCap(material);
            long counted = cap < 0 ? raw : Math.min(raw, cap);
            score += value * counted;
        }

        log.debug("[Scanner] 整岛统计完成：{} 种材料，得分 {}", counts.size(), score);
        return new ScanResult(score, Collections.unmodifiableMap(counts));
    }

    /**
     * 一次扫描的结果。
     *
     * @param score  应用计数上限之后的最终分数
     * @param counts 整岛逐材料数量（未截断，展示用）
     */
    public record ScanResult(double score, Map<Material, Integer> counts) {
        public static ScanResult empty() {
            return new ScanResult(0.0, Map.of());
        }
    }

    private List<int[]> getIslandChunks(Island island) {
        RegionCoordinate pos = island.getRegionCoordinate();
        double size = island.getSize();

        int centerChunkX = (pos.x() << 5) + 16;
        int centerChunkZ = (pos.z() << 5) + 16;

        // size 是岛屿的「直径」而非半径，此前直接除 16 会把扫描范围放大一倍，
        // size=100 时要扫 225 个区块而实际只需要 81 个。
        int chunkRadius = (int) Math.ceil(size / 2.0 / 16.0);

        List<int[]> result = new ArrayList<>();
        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                result.add(new int[]{cx, cz});
            }
        }
        return result;
    }

    public void shutdown() {
        scanScheduler.shutdown();
        scoreExecutor.shutdown();
    }
}
