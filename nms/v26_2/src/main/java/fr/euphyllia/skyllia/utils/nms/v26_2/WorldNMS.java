package fr.euphyllia.skyllia.utils.nms.v26_2;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.coordinate.ChunkCoordinate;
import fr.euphyllia.skyllia.api.world.WorldFeedback;
import io.papermc.paper.FeatureHooks;
import io.papermc.paper.world.PaperWorldLoader;
import io.papermc.paper.world.migration.WorldFolderMigration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.generator.CraftWorldInfo;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static net.minecraft.server.MinecraftServer.getServer;

public class WorldNMS extends fr.euphyllia.skyllia.api.utils.nms.WorldNMS {

    private static final Logger log = LoggerFactory.getLogger(WorldNMS.class);

    @Override
    public WorldFeedback.FeedbackWorld createWorld(WorldCreator creator) {
        return createWorldInternal(creator, null, null);
    }

    @Override
    public WorldFeedback.FeedbackWorld createWorld(WorldCreator creator, WorldConfig worldConfig) {
        if (!worldConfig.hasCustomHeight()) {
            return createWorldInternal(creator, null, null);
        }
        net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> holder =
                WorldHeightUtil.registerCustomDimension(creator.name(), worldConfig);
        return createWorldInternal(creator, worldConfig, holder);
    }

    private WorldFeedback.FeedbackWorld createWorldInternal(WorldCreator creator, WorldConfig worldConfig, net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> customHeightHolder) {
        Preconditions.checkArgument(creator != null, "WorldCreator cannot be null");

        CraftServer craftServer = (CraftServer) Bukkit.getServer();
        DedicatedServer console = craftServer.getServer();
        Preconditions.checkState(console.getAllLevels().iterator().hasNext(), "Cannot create additional worlds on STARTUP");

        String name = creator.name();
        ChunkGenerator chunkGenerator = creator.generator();
        BiomeProvider biomeProvider = creator.biomeProvider();
        File folder = new File(craftServer.getWorldContainer(), name);
        World world = craftServer.getWorld(name);

        // Paper start
        World worldByKey = craftServer.getWorld(creator.key());
        if (world != null || worldByKey != null) {
            if (world != worldByKey) {
                return WorldFeedback.Feedback.WORLD_DUPLICATED.toFeedbackWorld();
            }
        }
        // Paper end

        if ((folder.exists()) && (!folder.isDirectory())) {
            return WorldFeedback.Feedback.WORLD_FOLDER_INVALID.toFeedbackWorld();
        }

        if (chunkGenerator == null) {
            chunkGenerator = craftServer.getGenerator(name);
        }

        if (biomeProvider == null) {
            biomeProvider = craftServer.getBiomeProvider(name);
        }

        ResourceKey<LevelStem> actualDimension = switch (creator.environment()) {
            case NORMAL -> LevelStem.OVERWORLD;
            case NETHER -> LevelStem.NETHER;
            case THE_END -> LevelStem.END;
            default -> throw new IllegalArgumentException("Illegal dimension (" + creator.environment() + ")");
        };

        final ResourceKey<net.minecraft.world.level.Level> dimensionKey = CraftNamespacedKey.toResourceKey(Registries.DIMENSION, creator.key());
        WorldLoader.DataLoadContext context = console.worldLoaderContext;
        RegistryAccess.Frozen registryAccess = context.datapackDimensions();
        net.minecraft.core.Registry<LevelStem> contextLevelStemRegistry = registryAccess.lookupOrThrow(Registries.LEVEL_STEM);
        final LevelStem configuredStem = console.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).getValue(actualDimension);
        if (configuredStem == null) {
            throw new IllegalStateException("Missing configured level stem " + actualDimension);
        }

        try {
            WorldFolderMigration.migrateApiWorld(
                    console.storageSource,
                    console.registryAccess(),
                    name,
                    actualDimension,
                    dimensionKey
            );
        } catch (final IOException ex) {
            throw new RuntimeException("Failed to migrate legacy world " + name, ex);
        }

        PaperWorldLoader.LoadedWorldData loadedWorldData = PaperWorldLoader.loadWorldData(
                console,
                dimensionKey,
                name
        );
        final PrimaryLevelData primaryLevelData = (PrimaryLevelData) console.getWorldData();

        WorldGenSettings worldGenSettings = LevelStorageSource.readExistingSavedData(console.storageSource, dimensionKey, console.registryAccess(), WorldGenSettings.TYPE)
                .result()
                .orElse(null);

        if (worldGenSettings == null) {
            WorldOptions worldOptions = new WorldOptions(creator.seed(), creator.generateStructures(), creator.bonusChest());

            DedicatedServerProperties.WorldDimensionData properties = new DedicatedServerProperties.WorldDimensionData(GsonHelper.parse((creator.generatorSettings().isEmpty()) ? "{}" : creator.generatorSettings()), creator.type().name().toLowerCase(Locale.ROOT));
            WorldDimensions worldDimensions = properties.create(context.datapackWorldgen());

            WorldDimensions.Complete complete = worldDimensions.bake(contextLevelStemRegistry);
            if (complete.dimensions().getValue(actualDimension) == null) {
                throw new IllegalStateException("Missing generated level stem " + actualDimension + " for world " + name);
            }

            worldGenSettings = new WorldGenSettings(worldOptions, worldDimensions);
            registryAccess = complete.dimensionsRegistryAccess();
            loadedWorldData.levelOverrides().setHardcore(creator.hardcore());
            loadedWorldData = new PaperWorldLoader.LoadedWorldData(
                    loadedWorldData.bukkitName(),
                    loadedWorldData.uuid(),
                    loadedWorldData.pdc(),
                    loadedWorldData.levelOverrides()
            );
        }
        final WorldGenSettings genSettingsFinal = worldGenSettings;

        contextLevelStemRegistry = registryAccess.lookupOrThrow(Registries.LEVEL_STEM);

        if (console.options.has("forceUpgrade")) {
            net.minecraft.server.Main.forceUpgrade(console.storageSource, DataFixers.getDataFixer(), console.options.has("eraseCache"), () -> true, registryAccess, console.options.has("recreateRegionFiles"));
        }

        long biomeZoomSeed = BiomeManager.obfuscateSeed(genSettingsFinal.options().seed());
        LevelStem customStem = genSettingsFinal.dimensions().get(actualDimension).orElse(null);
        if (customStem == null) {
            customStem = contextLevelStemRegistry.getValue(actualDimension);
        }
        // Custom height: override LevelStem with patched DimensionType
        if (customHeightHolder != null && customStem != null) {
            customStem = new LevelStem(customHeightHolder, customStem.generator());
        }
        if (customStem == null) {
            throw new IllegalStateException("Missing level stem for world " + name + " using key " + actualDimension);
        }

        WorldInfo worldInfo = new CraftWorldInfo(loadedWorldData.bukkitName(), CraftNamespacedKey.fromMinecraft(dimensionKey.identifier()), genSettingsFinal.options().seed(), primaryLevelData.enabledFeatures(), creator.environment(), customStem.type().value(), customStem.generator(), getServer().registryAccess(), loadedWorldData.uuid());
        if (biomeProvider == null && chunkGenerator != null) {
            biomeProvider = chunkGenerator.getDefaultBiomeProvider(worldInfo);
        }

        final SavedDataStorage savedDataStorage = new SavedDataStorage(console.storageSource.getDimensionPath(dimensionKey).resolve(LevelResource.DATA.id()), console.getFixerUpper(), console.registryAccess());
        savedDataStorage.set(WorldGenSettings.TYPE, new WorldGenSettings(genSettingsFinal.options(), genSettingsFinal.dimensions()));
        List<CustomSpawner> list = ImmutableList.of(
                new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(), new VillageSiege(), new WanderingTraderSpawner(savedDataStorage)
        );

        ServerLevel serverLevel = new ServerLevel(
                console,
                Util.backgroundExecutor(),
                console.storageSource,
                genSettingsFinal,
                dimensionKey,
                customStem,
                primaryLevelData.isDebugWorld(),
                biomeZoomSeed,
                creator.environment() == World.Environment.NORMAL ? list : ImmutableList.of(),
                true,
                actualDimension,
                creator.environment(),
                chunkGenerator,
                biomeProvider,
                savedDataStorage,
                loadedWorldData
        );

        console.addLevel(serverLevel);
        console.initWorld(serverLevel, creator);

        serverLevel.setSpawnSettings(true);

        craftServer.getServer().prepareLevel(serverLevel);

        return WorldFeedback.Feedback.SUCCESS.toFeedbackWorld(serverLevel.getWorld());
    }

    @Override
    public Map<Material, Integer> getCountAllBlocksInChunk(World world, int chunkX, int chunkZ) {
        Map<Material, Integer> blockCounts = new java.util.EnumMap<>(Material.class);
        net.minecraft.server.level.ServerLevel nms = ((CraftWorld) world).getHandle();

        LevelChunk chunk = nms.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            chunk = nms.getChunkSource().getChunk(chunkX, chunkZ, true);
        }
        if (chunk == null) {
            return blockCounts;
        }

        final LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return blockCounts;
        }

        for (LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) continue;

            section.getStates().count((state, count) -> {
                if (state.isAir()) return;
                Material mat = state.getBukkitMaterial();
                blockCounts.merge(mat, count, Integer::sum);
            });
        }

        return blockCounts;
    }

    @Override
    public void resetChunk(World craftWorld, ChunkCoordinate position) {
        final ServerLevel nms = ((CraftWorld) craftWorld).getHandle();
        final int chunkX = position.x();
        final int chunkZ = position.z();

        LevelChunk chunk = nms.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            chunk = nms.getChunkSource().getChunk(chunkX, chunkZ, true);
        }
        if (chunk == null) {
            TickThread.ensureTickThread(nms, chunkX, chunkZ, "Cannot reset chunk asynchronously");
            return;
        }

        boolean hasAnyPlayer = false;
        for (Entity entity : FeatureHooks.getChunkEntities(nms, chunkX, chunkZ)) {
            if (entity instanceof Player) {
                hasAnyPlayer = true;
            } else {
                entity.remove();
            }
        }

        final LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return;
        }

        final int baseX = chunkX << 4;
        final int baseZ = chunkZ << 4;
        final BlockState airState = Blocks.AIR.defaultBlockState();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            final LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            final int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            final int baseY = sectionY << 4;

            for (int ly = 0; ly < 16; ly++) {
                final int worldY = baseY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    final int worldZ = baseZ + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        final int worldX = baseX + lx;

                        final BlockState state = section.getBlockState(lx, ly, lz);
                        if (state.isAir()) {
                            continue;
                        }
                        final BlockPos blockPos = new BlockPos(worldX, worldY, worldZ);

                        Block block = craftWorld.getBlockAt(worldX, worldY, worldZ);
                        org.bukkit.block.BlockState bukkitState = block.getState(false);
                        if (bukkitState instanceof PersistentDataHolder) {
                            PersistentDataContainer container = ((PersistentDataHolder) bukkitState).getPersistentDataContainer();
                            for (NamespacedKey key : container.getKeys()) {
                                container.remove(key);
                            }
                        }
                        chunk.removeBlockEntity(blockPos);
                        section.setBlockState(lx, ly, lz, airState, false);
                    }
                }
            }

            section.recalcBlockCounts();
        }

        if (hasAnyPlayer) {
            LevelChunk finalChunk = chunk;
            nms.getChunkSource().chunkMap.getPlayers(new ChunkPos(chunkX, chunkZ), false)
                    .forEach(player -> {
                        player.connection.send(
                                new ClientboundLevelChunkWithLightPacket(
                                        finalChunk,
                                        nms.getLightEngine(),
                                        null,
                                        null
                                )
                        );
                    });
        }

        chunk.markUnsaved();
    }

    /**
     * Gets the current location TPS.
     *
     * @param location the location for which to get the TPS
     * @return current location TPS (5s, 15s, 1m, 5m, 15m in Folia-Server), or null if the region doesn't exist
     */
    @Override
    public double @Nullable [] getTPS(Location location) {
        return fr.euphyllia.skyllia.utils.nms.v1_21_R7.WorldNMS.getTPSHelper(location);
    }

    /**
     * Gets the current chunk TPS.
     *
     * @param chunk the chunk for which to get the TPS
     * @return current location TPS (5s, 15s, 1m, 5m, 15m in Folia-Server), or null if the region doesn't exist
     */
    @Override
    public double @Nullable [] getTPS(Chunk chunk) {
        return fr.euphyllia.skyllia.utils.nms.v1_21_R7.WorldNMS.getTPSHelper(chunk);
    }

    /**
     * Gets the average tick times for a specific location.
     *
     * @param location the location for which to get the average tick times
     * @return an array of average tick times, or null if the region doesn't exist
     */
    @Override
    public double @Nullable [] getAverageTickTimes(Location location) {
        try {
            return fr.euphyllia.skyllia.utils.nms.v1_21_R7.WorldNMS.getAverageTickTimesHelper(location);
        } catch (Throwable t) {
            // Shiroha 26.2 的 TickRegionScheduler$RegionScheduleHandle 与上游 Folia 签名不一致（NoSuchMethodError），
            // v26_2 无法直接编译访问该内部 API（paperDevBundle 不含此类），故退化返回 null 而非崩溃 /is tps
            return null;
        }
    }

    /**
     * Gets the average tick times for a specific chunk.
     *
     * @param chunk the chunk for which to get the average tick times
     * @return an array of average tick times, or null if the region doesn't exist
     */
    @Override
    public double @Nullable [] getAverageTickTimes(Chunk chunk) {
        try {
            return fr.euphyllia.skyllia.utils.nms.v1_21_R7.WorldNMS.getAverageTickTimesHelper(chunk);
        } catch (Throwable t) {
            // 见上方 getAverageTickTimes(Location) 的说明
            return null;
        }
    }

    @Override
    public List<Entity> getEntities(World craftWorld, final @Nullable Entity except, final BoundingBox boundingBox, Predicate<? super Entity> filter) {
        final ServerLevel nms = ((CraftWorld) craftWorld).getHandle();
        AABB bb = new AABB(boundingBox.getMinX(), boundingBox.getMinY(), boundingBox.getMinZ(), boundingBox.getMaxX(), boundingBox.getMaxY(), boundingBox.getMaxZ());
        final List<net.minecraft.world.entity.Entity> entityList = new java.util.ArrayList<>();

        net.minecraft.world.entity.Entity exceptNms = except != null ? ((CraftEntity) except).getHandle() : null;
        nms.moonrise$getEntityLookup().getEntities(exceptNms, bb, entityList, Predicates.alwaysTrue());

        List<Entity> bukkitEntityList = new ArrayList<>(entityList.size());

        for (net.minecraft.world.entity.Entity entity : entityList) {
            Entity bukkitEntity = entity.getBukkitEntity();
            if (filter == null || filter.test(bukkitEntity)) {
                bukkitEntityList.add(bukkitEntity);
            }
        }

        return bukkitEntityList;
    }

    @Override
    public void remapPortalDimensions(@Nullable World overworld, @Nullable World nether, @Nullable World end) {
        try {
            MinecraftServer server = getServer();

            java.lang.reflect.Field levelsField = MinecraftServer.class.getDeclaredField("levels");
            levelsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<ResourceKey<Level>, ServerLevel> oldLevels =
                    (java.util.Map<ResourceKey<Level>, ServerLevel>) levelsField.get(server);

            log.info("[Skyllia-维度映射] levels map class={}, size={}", oldLevels.getClass().getName(), oldLevels.size());

            // 创建可变副本（避免 UnmodifiableMap）
            java.util.Map<ResourceKey<Level>, ServerLevel> newLevels = new java.util.HashMap<>(oldLevels);

            if (overworld != null) {
                ServerLevel sl = ((CraftWorld) overworld).getHandle();
                newLevels.put(Level.OVERWORLD, sl);
                log.info("[Skyllia-维度映射] OVERWORLD -> {}", overworld.getName());
            }
            if (nether != null) {
                ServerLevel sl = ((CraftWorld) nether).getHandle();
                newLevels.put(Level.NETHER, sl);
                log.info("[Skyllia-维度映射] NETHER -> {}", nether.getName());

                // 创建新 DimensionType（coordinateScale=1.0）注入现存的 Holder.Reference.value，
                // 同时更新 Registry 的 toId / byValue 映射，确保 ClientboundLoginPacket 编码能找到 ID
                try {
                    net.minecraft.world.level.dimension.DimensionType original = sl.dimensionType();
                    net.minecraft.world.level.dimension.DimensionType modified = new net.minecraft.world.level.dimension.DimensionType(
                            original.hasFixedTime(),
                            original.hasSkyLight(),
                            original.hasCeiling(),
                            original.hasEnderDragonFight(),
                            1.0,  // coordinateScale = 1.0 → 1:1
                            original.minY(),
                            original.height(),
                            original.logicalHeight(),
                            original.infiniburn(),
                            original.ambientLight(),
                            original.monsterSettings(),
                            original.skybox(),
                            original.cardinalLightType(),
                            original.attributes(),
                            original.timelines(),
                            original.defaultClock()
                    );

                    // 1. 改 Holder.Reference.value
                    net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> holder = sl.dimensionTypeRegistration();
                    java.lang.reflect.Field valueField = holder.getClass().getDeclaredField("value");
                    valueField.setAccessible(true);
                    valueField.set(holder, modified);

                    // 2. 同步更新 Registry.toId / byValue（否则 packet 编码查不到 modified 的 ID）
                    net.minecraft.core.Registry<net.minecraft.world.level.dimension.DimensionType> registry =
                            (net.minecraft.core.Registry<net.minecraft.world.level.dimension.DimensionType>)
                            server.registryAccess().lookup(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
                                    .orElseThrow(() -> new RuntimeException("Missing dimension type registry"));
                    int id = registry.getId(original); // 在原 map 里查 original 的 ID

                    java.lang.reflect.Field toIdField = net.minecraft.core.MappedRegistry.class.getDeclaredField("toId");
                    java.lang.reflect.Field byValueField = net.minecraft.core.MappedRegistry.class.getDeclaredField("byValue");
                    toIdField.setAccessible(true);
                    byValueField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    it.unimi.dsi.fastutil.objects.Reference2IntMap<net.minecraft.world.level.dimension.DimensionType> toId =
                            (it.unimi.dsi.fastutil.objects.Reference2IntMap<net.minecraft.world.level.dimension.DimensionType>) toIdField.get(registry);
                    @SuppressWarnings("unchecked")
                    java.util.Map<net.minecraft.world.level.dimension.DimensionType, net.minecraft.core.Holder.Reference<net.minecraft.world.level.dimension.DimensionType>> byValue =
                            (java.util.Map<net.minecraft.world.level.dimension.DimensionType, net.minecraft.core.Holder.Reference<net.minecraft.world.level.dimension.DimensionType>>) byValueField.get(registry);

                    toId.put(modified, id);
                    byValue.remove(original);
                    byValue.put(modified, (net.minecraft.core.Holder.Reference<net.minecraft.world.level.dimension.DimensionType>) holder);

                    log.info("[Skyllia-维度映射] {} coordinateScale 8.0 -> 1.0 (1:1)", nether.getName());
                } catch (Exception e2) {
                    log.error("[Skyllia-维度映射] 注入 coordinateScale=1.0 失败", e2);
                }
            }
            if (end != null) {
                ServerLevel sl = ((CraftWorld) end).getHandle();
                newLevels.put(Level.END, sl);
                log.info("[Skyllia-维度映射] END -> {} (coordinateScale={})", end.getName(),
                        sl.dimensionType().coordinateScale());
            }

            // 替换整个 field（因为原 map 可能是 UnmodifiableMap）
            levelsField.set(server, java.util.Collections.unmodifiableMap(newLevels));

            // 验证
            ServerLevel viaGetOverworld = server.getLevel(Level.OVERWORLD);
            ServerLevel viaGetNether = server.getLevel(Level.NETHER);
            ServerLevel viaGetEnd = server.getLevel(Level.END);
            log.info("[Skyllia-维度映射] 验证 server.getLevel OVERWORLD={} NETHER={} END={}",
                    viaGetOverworld != null ? viaGetOverworld.getWorld().getName() : "null",
                    viaGetNether != null ? viaGetNether.getWorld().getName() : "null",
                    viaGetEnd != null ? viaGetEnd.getWorld().getName() : "null");

        } catch (Exception e) {
            log.error("[Skyllia-维度映射] 失败", e);
        }
    }

    @Override
    public void adjustEndPortalSpawnPoint(@org.jetbrains.annotations.NotNull org.bukkit.entity.Player player) {
        try {
            net.minecraft.world.entity.Entity nmsEntity = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle();
            net.minecraft.server.level.ServerLevel currentLevel = (net.minecraft.server.level.ServerLevel) nmsEntity.level();

            // 只在从非末地世界进入末地门时处理
            if (currentLevel.dimension() == net.minecraft.world.level.Level.END) return;

            org.bukkit.Location loc = player.getLocation();
            int targetX = loc.getBlockX();
            int targetZ = loc.getBlockZ();
            // Shiroha 26.2 用 atBottomCenterOf(END_SPAWN_POINT.below()) 算落点，
            // 即 finalY = END_SPAWN_POINT.y - 1。要玩家落 Y = targetY + 1，
            // 需 END_SPAWN_POINT.y = targetY + 2
            int targetY = loc.getBlockY();
            int endSpawnY = targetY + 2;

            // 用 Unsafe 做 final 写 + storeFence，跨线程可见
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);

            java.lang.reflect.Field xField = net.minecraft.core.Vec3i.class.getDeclaredField("x");
            java.lang.reflect.Field yField = net.minecraft.core.Vec3i.class.getDeclaredField("y");
            java.lang.reflect.Field zField = net.minecraft.core.Vec3i.class.getDeclaredField("z");
            long xOffset = unsafe.objectFieldOffset(xField);
            long yOffset = unsafe.objectFieldOffset(yField);
            long zOffset = unsafe.objectFieldOffset(zField);

            // Full memory barrier：写入 buffer → 主存，异步线程读主存拿到新值
            unsafe.storeFence();
            unsafe.putInt(net.minecraft.server.level.ServerLevel.END_SPAWN_POINT, xOffset, targetX);
            unsafe.putInt(net.minecraft.server.level.ServerLevel.END_SPAWN_POINT, yOffset, endSpawnY);
            unsafe.putInt(net.minecraft.server.level.ServerLevel.END_SPAWN_POINT, zOffset, targetZ);
            unsafe.storeFence();

            log.info("[Skyllia-末地门] 设 END_SPAWN_POINT -> ({}, {}, {}) 玩家={} (主世界 Y={}, 期望落点 Y={})",
                    targetX, endSpawnY, targetZ, player.getName(), targetY, targetY + 1);
        } catch (Exception e) {
            log.error("[Skyllia-末地门] 改 END_SPAWN_POINT 失败", e);
        }
    }

    /**
     * 走原版刷怪蛋那条 {@code EntityType.spawn(..., SPAWN_ITEM_USE, tryMoveDown)}。
     * 取消时返回 {@code null}，不会像 {@code World#spawn} 那样交回一只已 discard 的实体。
     */
    @Override
    public @Nullable WanderingTrader spawnWanderingTraderLikeEgg(
            @org.jetbrains.annotations.NotNull Location location,
            @Nullable Consumer<WanderingTrader> beforeAdd) {
        if (location.getWorld() == null) return null;
        try {
            ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
            BlockPos spawnPos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
            net.minecraft.world.entity.PostSpawnProcessor<net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader> processor =
                    entity -> {
                        if (beforeAdd == null) return;
                        if (entity.getBukkitEntity() instanceof WanderingTrader bukkit) {
                            beforeAdd.accept(bukkit);
                        }
                    };
            net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader nms =
                    net.minecraft.world.entity.EntityTypes.WANDERING_TRADER.spawn(
                            level,
                            processor,
                            spawnPos,
                            net.minecraft.world.entity.EntitySpawnReason.SPAWN_ITEM_USE,
                            true,
                            false,
                            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG);
            if (nms == null || nms.isRemoved()) return null;
            if (nms.getBukkitEntity() instanceof WanderingTrader trader && trader.isValid()) {
                return trader;
            }
            return null;
        } catch (Exception e) {
            log.error("刷怪蛋路径生成游商失败：{}", location, e);
            return null;
        }
    }
}