package fr.euphyllia.skyllia.api.utils.nms;

import org.bukkit.World;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public abstract class BiomesImpl {

    public abstract @Nullable Biome getBiome(String biomeName);

    public abstract List<String> getBiomeNameList();

    /**
     * 按维度列出可用群系（主世界只给主世界群系、下界只给下界群系、末地只给末地群系）。
     * <p>
     * v26_2 实现读 {@code minecraft:is_overworld / is_nether / is_end} 标签，
     * 不能从空岛世界自己的 LevelStem 生成器取——那只会给出一个默认群系。
     * 默认实现回退到完整列表——旧 NMS 版本没有实现按维度过滤时功能自然降级，
     * 不会因为多了这个方法而编译失败或抛异常。
     * </p>
     */
    public List<String> getBiomeNameList(World.Environment environment) {
        return getBiomeNameList();
    }

    public abstract String getNameBiome(Biome biome);

    public abstract boolean setBiome(World world, int chunkX, int chunkZ, Biome biome);
}
