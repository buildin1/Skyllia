package fr.euphyllia.skyllia.managers.world;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.coordinate.ChunkCoordinate;
import fr.euphyllia.skyllia.api.coordinate.RegionCoordinate;
import fr.euphyllia.skyllia.api.event.IslandBiomeChangeProgressEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.utils.RegionUtils;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.configuration.manager.GeneralConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@ApiStatus.Internal
public class WorldModifier {

    private static final Logger log = LoggerFactory.getLogger(WorldModifier.class);

    private final JavaPlugin plugin;
    private final ScheduledExecutorService deleteScheduler;
    private final ScheduledExecutorService biomeScheduler;

    public WorldModifier(JavaPlugin plugin) {
        this.plugin = plugin;
        GeneralConfigManager.ChunkProcessingSettings cfg = ConfigLoader.general.getIslandSettings().chunkProcessing();

        this.deleteScheduler = Executors.newScheduledThreadPool(
                cfg.resolvedDeleteThreads(),
                r -> {
                    Thread t = new Thread(r, "skyllia-delete-processor");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
        );

        this.biomeScheduler = Executors.newScheduledThreadPool(
                cfg.resolvedBiomeThreads(),
                r -> {
                    Thread t = new Thread(r, "skyllia-biome-processor");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
        );

        log.info("WorldModifier initialized — delete: {} thread(s) / {}ms delay, biome: {} thread(s) / {}ms delay",
                cfg.resolvedDeleteThreads(), cfg.deleteDelayMs(),
                cfg.resolvedBiomeThreads(), cfg.biomeDelayMs());
    }

    public void shutdown() {
        deleteScheduler.shutdown();
        biomeScheduler.shutdown();
    }

    public void deleteIsland(@NotNull Island island, @NotNull World world, int regionDistance, Consumer<Boolean> onFinish) {
        RegionCoordinate position = island.getRegionCoordinate();
        Biome defaultBiome = resolveDefaultBiome(world);
        List<ChunkCoordinate> chunks = RegionUtils.computeChunksToDelete(position, regionDistance, island.getSize());
        if (chunks.isEmpty()) {
            if (onFinish != null) onFinish.accept(true);
            return;
        }
        AtomicInteger toDelete = new AtomicInteger(chunks.size());
        AtomicBoolean failed = new AtomicBoolean(false);
        for (int i = 0; i < chunks.size(); i++) {
            final ChunkCoordinate chunkPos = chunks.get(i);
            final long delay = (long) i * ConfigLoader.general.getIslandSettings().chunkProcessing().deleteDelayMs();
            deleteScheduler.schedule(() -> {
                world.getChunkAtAsync(chunkPos.x(), chunkPos.z()).thenAccept(chunk -> {
                    try {
                        SkylliaAPI.getWorldNMS().resetChunk(world, chunkPos);
                        if (!SkylliaAPI.getBiomesImpl().setBiome(world, chunkPos.x(), chunkPos.z(), defaultBiome)) {
                            failed.set(true);
                        }
                    } catch (Exception e) {
                        failed.set(true);
                    }
                    if (toDelete.decrementAndGet() == 0 && onFinish != null) {
                        onFinish.accept(!failed.get());
                    }
                });
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private Biome resolveDefaultBiome(@NotNull World world) {
        WorldConfig worldConfig = ConfigLoader.worldManager.getWorldConfig(world.getName());
        Biome fallback = switch (world.getEnvironment()) {
            case NETHER -> Biome.NETHER_WASTES;
            case THE_END -> Biome.THE_END;
            default -> Biome.PLAINS;
        };

        if (worldConfig == null || worldConfig.getBiomeId() == null || worldConfig.getBiomeId().isBlank()) {
            return fallback;
        }

        Biome configuredBiome = SkylliaAPI.getBiomesImpl().getBiome(worldConfig.getBiomeId());
        return configuredBiome != null ? configuredBiome : fallback;
    }

    public CompletableFuture<Boolean> changeBiomeChunk(@NotNull World world, int chunkX, int chunkZ, @NotNull Biome biome) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        world.getChunkAtAsync(chunkX, chunkZ).thenAccept(ignored -> {
                    try {
                        // setBiome 自吞异常、以返回值报告失败——必须检查，否则改失败的区块
                        // 会被静默当成成功（2026-08 反馈：改成蘑菇岛后部分区块仍刷史莱姆，
                        // 玩家全程没收到任何失败提示）
                        future.complete(SkylliaAPI.getBiomesImpl().setBiome(world, chunkX, chunkZ, biome));
                    } catch (Exception e) {
                        future.complete(false);
                    }
                }
        );
        return future;
    }

    public CompletableFuture<Boolean> changeBiomeIsland(@NotNull World world, @NotNull Biome biome,
                                                        @NotNull Island island, int regionDistance) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        RegionCoordinate islandPos = island.getRegionCoordinate();
        List<ChunkCoordinate> chunks = new ArrayList<>();
        RegionUtils.spiralStartCenter(islandPos, regionDistance, island.getSize(), chunks::add);
        if (chunks.isEmpty()) {
            future.complete(true);
            return future;
        }
        AtomicInteger remaining = new AtomicInteger(chunks.size());
        AtomicBoolean failed = new AtomicBoolean(false);
        for (int i = 0; i < chunks.size(); i++) {
            ChunkCoordinate chunk = chunks.get(i);
            final int cX = chunk.x();
            final int cZ = chunk.z();
            final long delay = (long) i * ConfigLoader.general.getIslandSettings().chunkProcessing().biomeDelayMs();
            biomeScheduler.schedule(() -> {
                world.getChunkAtAsync(cX, cZ).thenAccept(ignored -> {
                    try {
                        // setBiome 自吞异常、以返回值报告失败——不检查会把改失败的区块静默当成成功
                        if (!SkylliaAPI.getBiomesImpl().setBiome(world, cX, cZ, biome)) {
                            log.warn("整岛改群系：区块 ({}, {}) 修改失败（世界 {}）", cX, cZ, world.getName());
                            failed.set(true);
                        }
                    } catch (Exception e) {
                        failed.set(true);
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            future.complete(!failed.get());
                        }
                    }
                });
            }, delay, TimeUnit.MILLISECONDS);
        }
        return future;
    }

    /**
     * Changes the biome of an arbitrary, caller-supplied list of chunks (typically a selection
     * clipped to an island's own territory — see the biome selection tool GUI flow).
     * <p>
     * Reuses the exact same throttled scheduling as {@link #changeBiomeIsland(World, Biome, Island, int)}
     * (same {@link #biomeScheduler}, same {@code biomeDelayMs} spacing) so the two code paths never
     * diverge in how aggressively they hit the server. Fires {@link IslandBiomeChangeProgressEvent}
     * after each chunk completes, mirroring the event this operation is meant to reuse.
     * </p>
     *
     * @param world  The world the chunks belong to.
     * @param chunks The exact chunks to modify (already clipped to the caller's authorized area).
     * @param biome  The biome to apply.
     * @param island The island this change is attributed to, for the progress event; may be {@code null}.
     * @return A future completing with {@code true} if every chunk succeeded.
     */
    public CompletableFuture<Boolean> changeBiomeRegion(@NotNull World world, @NotNull List<ChunkCoordinate> chunks,
                                                         @NotNull Biome biome, Island island) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (chunks.isEmpty()) {
            future.complete(true);
            return future;
        }
        int total = chunks.size();
        AtomicInteger remaining = new AtomicInteger(total);
        AtomicBoolean failed = new AtomicBoolean(false);
        for (int i = 0; i < chunks.size(); i++) {
            ChunkCoordinate chunk = chunks.get(i);
            final int cX = chunk.x();
            final int cZ = chunk.z();
            final long delay = (long) i * ConfigLoader.general.getIslandSettings().chunkProcessing().biomeDelayMs();
            biomeScheduler.schedule(() -> {
                world.getChunkAtAsync(cX, cZ).thenAccept(ignored -> {
                    try {
                        // setBiome 自吞异常、以返回值报告失败——不检查会把改失败的区块静默当成成功
                        if (!SkylliaAPI.getBiomesImpl().setBiome(world, cX, cZ, biome)) {
                            log.warn("选区改群系：区块 ({}, {}) 修改失败（世界 {}）", cX, cZ, world.getName());
                            failed.set(true);
                        }
                    } catch (Exception e) {
                        failed.set(true);
                    } finally {
                        int left = remaining.decrementAndGet();
                        if (island != null) {
                            // IslandBiomeChangeProgressEvent 标记为异步事件（super(true)），
                            // 而这个 thenAccept 回调本身跑在对应 region 的 tick 线程上（和
                            // setBiome 一样）——在 tick 线程上同步 callEvent 一个异步事件，
                            // Bukkit/Folia 的线程校验会直接抛 IllegalStateException（仓库里
                            // SkyblockLoadEvent 的既有触发方式已经验证过这条规则，见
                            // SQLiteIslandData/MariaDBIslandData/PostgreSQLIslandData）。
                            // 必须挪到真正的异步调度线程上触发。
                            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                                try {
                                    new IslandBiomeChangeProgressEvent(island, left, total).callEvent();
                                } catch (Exception eventException) {
                                    // 事件监听方出错不应影响群系修改本身
                                }
                            });
                        }
                        if (left == 0) {
                            future.complete(!failed.get());
                        }
                    }
                });
            }, delay, TimeUnit.MILLISECONDS);
        }
        return future;
    }
}
