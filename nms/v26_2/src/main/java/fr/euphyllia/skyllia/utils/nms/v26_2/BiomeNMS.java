package fr.euphyllia.skyllia.utils.nms.v26_2;

import fr.euphyllia.skyllia.api.utils.nms.BiomesImpl;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class BiomeNMS extends BiomesImpl {

    private static final HashMap<Biome, Holder<net.minecraft.world.level.biome.Biome>> biomeTypeToNMSCache = new HashMap<>();
    private static final Logger log = LogManager.getLogger(BiomeNMS.class);

    @Override
    public @Nullable Biome getBiome(String biomeName) {
        biomeName = biomeName.trim().toLowerCase(Locale.ROOT);
        var biomeRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);

        if (biomeName.contains(":")) {
            NamespacedKey key = NamespacedKey.fromString(biomeName);
            if (key != null) {
                Biome biome = biomeRegistry.get(key);
                if (biome != null) return biome;
            }
        }

        Biome biome = biomeRegistry.get(NamespacedKey.minecraft(biomeName));
        if (biome != null) return biome;

        return Bukkit.getUnsafe().get(RegistryKey.BIOME, NamespacedKey.fromString(biomeName.toLowerCase(Locale.ROOT)));
    }

    @Override
    public List<String> getBiomeNameList() {
        Registry<@org.jetbrains.annotations.NotNull Biome> biomeRegistry = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME);

        List<String> list = new ArrayList<>();
        for (Biome biome : biomeRegistry) {
            NamespacedKey key = biome.getKey();
            list.add(key.toString());
        }
        return list;
    }

    /**
     * 按维度过滤：从 LevelStem 注册表拿该维度<b>原版生成器</b>的 BiomeSource，
     * {@code possibleBiomes()} 就是这个维度自然生成会用到的全部群系——datapack /
     * 核心（Shiroha）往对应维度注入的自定义群系会自动被包含，不用维护硬编码清单。
     * 不能用空岛世界自己的生成器查：那是固定群系的虚空生成器，只会给出一个群系。
     * 任何一步异常都回退到完整列表，宁可少过滤也不能让图鉴开不出来。
     */
    @Override
    public List<String> getBiomeNameList(World.Environment environment) {
        ResourceKey<net.minecraft.world.level.dimension.LevelStem> stemKey = switch (environment) {
            case NORMAL -> net.minecraft.world.level.dimension.LevelStem.OVERWORLD;
            case NETHER -> net.minecraft.world.level.dimension.LevelStem.NETHER;
            case THE_END -> net.minecraft.world.level.dimension.LevelStem.END;
            default -> null;
        };
        if (stemKey == null) return getBiomeNameList();

        try {
            net.minecraft.world.level.dimension.LevelStem stem = ((CraftServer) Bukkit.getServer()).getServer()
                    .registryAccess()
                    .lookupOrThrow(Registries.LEVEL_STEM)
                    .getValue(stemKey);
            if (stem == null) return getBiomeNameList();

            List<String> out = new ArrayList<>();
            for (Holder<net.minecraft.world.level.biome.Biome> holder : stem.generator().getBiomeSource().possibleBiomes()) {
                holder.unwrapKey().ifPresent(key -> out.add(key.identifier().toString()));
            }
            return out.isEmpty() ? getBiomeNameList() : out;
        } catch (Exception e) {
            log.error("按维度列出群系失败（environment={}），回退到完整列表", environment, e);
            return getBiomeNameList();
        }
    }

    @Override
    public String getNameBiome(Biome biome) {
        NamespacedKey key = biome.getKey();
        return key != null ? key.toString() : "unknown";
    }

    @Override
    public boolean setBiome(World world, int chunkX, int chunkZ, Biome biome) {
        try {
            net.minecraft.server.level.ServerLevel nms = ((CraftWorld) world).getHandle();

            LevelChunk chunk = nms.getChunkSource().getChunkNow(chunkX, chunkZ);

            if (chunk == null) {
                chunk = nms.getChunkSource().getChunk(chunkX, chunkZ, true);
            }

            if (chunk == null) {
                return false;
            }

            final LevelChunkSection[] sections = chunk.getSections();
            if (sections.length == 0) {
                return false;
            }

            var biomeHolder = biomeTypeToNMSCache.computeIfAbsent(biome, b -> ((CraftServer) Bukkit.getServer()).getServer().registryAccess()
                    .lookupOrThrow(Registries.BIOME)
                    .getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(getNameBiome(biome))))
            );

            for (LevelChunkSection section : sections) {
                for (int x = 0; x < 4; x++) {
                    for (int y = 0; y < 4; y++) {
                        for (int z = 0; z < 4; z++) {
                            section.setNoiseBiome(x, y, z, biomeHolder);
                        }
                    }
                }
            }

            chunk.markUnsaved();
            return true;
        } catch (Exception exception) {
            log.error("Failed to set biome", exception);
            return false;
        }
    }
}
