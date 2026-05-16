package fr.euphyllia.skylliaislandlevel.scanner;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.model.Position;
import fr.euphyllia.skylliaislandlevel.SkylliaIslandLevel;
import fr.euphyllia.skylliaislandlevel.configuration.IslandLevelConfigLoader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class IslandScanner {

    private static final Logger log = LoggerFactory.getLogger(IslandScanner.class);
    private final SkylliaIslandLevel plugin;

    public IslandScanner(SkylliaIslandLevel plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Double> scanIsland(Island island, World world) {
        CompletableFuture<Double> result = new CompletableFuture<>();

        List<int[]> chunkCoords = getIslandChunks(island);

        // DEBUG
        log.debug("[Scanner] Island {} — center chunk ({},{}) size={} → {} chunks to scan",
                island.getId(),
                island.getPosition().x(), island.getPosition().z(),
                island.getSize(),
                chunkCoords.size());

        if (chunkCoords.isEmpty()) {
            result.complete(0.0);
            return result;
        }

        Map<Material, Double> blockValues = IslandLevelConfigLoader.config.getBlockValues();

        // DEBUG
        log.debug("[Scanner] blockValues loaded: {} entries", blockValues.size());

        scanChunksRecursively(world, island, chunkCoords, 0, 0.0, blockValues, result);
        return result;
    }

    private void scanChunksRecursively(World world, Island island, List<int[]> chunks, int index, double accumulatedScore, Map<Material, Double> blockValues, CompletableFuture<Double> resultFuture) {
        if (index >= chunks.size()) {
            resultFuture.complete(accumulatedScore);
            return;
        }

        int[] coord = chunks.get(index);
        int chunkX = coord[0];
        int chunkZ = coord[1];

        Runnable scanTask = () -> {
            double chunkScore = 0.0;
            try {
                chunkScore = scoreChunk(world, chunkX, chunkZ, blockValues);
            } catch (Throwable e) {
                log.error("Failed to scan chunk at ({}, {}) for island '{}'", chunkX, chunkZ, island.getId(), e);
            }

            final double nextScore = accumulatedScore + chunkScore;
            scanChunksRecursively(world, island, chunks, index + 1, nextScore, blockValues, resultFuture);
        };

        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            scanTask.run();
            return;
        }
        Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, t -> scanTask.run());
    }

    private double scoreChunk(World world, int chunkX, int chunkZ, Map<Material, Double> blockValues) {
        Map<Material, Integer> blockCounts = SkylliaAPI.getWorldNMS().getCountAllBlocksInChunk(world, chunkX, chunkZ);

        if (!blockCounts.isEmpty()) {
            log.debug("[Scanner] Chunk ({},{}) → {} block types found, sample: {}",
                    chunkX, chunkZ, blockCounts.size(),
                    blockCounts.entrySet().stream().limit(5)
                            .map(e -> e.getKey() + "×" + e.getValue())
                            .collect(java.util.stream.Collectors.joining(", ")));
        } else {
            log.debug("[Scanner] Chunk ({},{}) → empty", chunkX, chunkZ);
        }

        if (blockCounts.isEmpty()) return 0.0;

        double score = 0.0;
        for (Map.Entry<Material, Integer> entry : blockCounts.entrySet()) {
            Double value = blockValues.get(entry.getKey());
            if (value != null) {
                score += value * entry.getValue();
            }
        }

        if (score > 0) log.debug("[Scanner] Chunk ({},{}) → score={}", chunkX, chunkZ, score);

        return score;
    }

    private List<int[]> getIslandChunks(Island island) {
        Position pos = island.getPosition();
        double size = island.getSize();

        int centerChunkX = (pos.x() << 5) + 16;
        int centerChunkZ = (pos.z() << 5) + 16;

        int chunkRadius = (int) Math.ceil(size / 16.0);

        List<int[]> result = new ArrayList<>();
        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                result.add(new int[]{cx, cz});
            }
        }
        return result;
    }
}
