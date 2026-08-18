package fr.euphyllia.skyllia.utils;

import fr.euphyllia.skyllia.api.InterneAPI;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.world.WorldFeedback;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ce code provient d'ici : <a href="https://github.com/Folia-Inquisitors/MoreFoWorld/blob/master/src/Skyllia/java/me/hsgamer/morefoworld/WorldUtil.java">MoreFoWorld</a> et CraftBukkit
 */
public final class WorldUtils {

    private static final Logger logger = LogManager.getLogger(WorldUtils.class);

    public static WorldFeedback.FeedbackWorld addWorld(InterneAPI interneAPI, WorldCreator creator) {
        return interneAPI.getWorldNMS().createWorld(creator);
    }

    public static WorldFeedback.FeedbackWorld addWorld(InterneAPI interneAPI, WorldCreator creator, WorldConfig worldConfig) {
        return interneAPI.getWorldNMS().createWorld(creator, worldConfig);
    }

    public static Boolean isWorldSkyblock(String name) {
        Map<String, WorldConfig> configs = ConfigLoader.worldManager.getWorldConfigs();
        return configs.containsKey(name);
    }

    public static List<WorldConfig> getWorldConfigs() {
        return new ArrayList<>(ConfigLoader.worldManager.getWorldConfigs().values());
    }

    public static @Nullable WorldConfig getWorldConfig(String worldName) {
        return ConfigLoader.worldManager.getWorldConfig(worldName);
    }

    /**
     * Checks if a location is safe (solid ground with 2 breathable blocks)
     *
     * @param location Location to check
     * @return True if location is safe
     */
    public static boolean isSafeLocation(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);

        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }

        if (isDangerous(feet) || isDangerous(head)) {
            return false;
        }

        if (!ground.getType().isSolid()) {
            return false;
        }

        if (isDangerous(ground)) {
            return false;
        }

        return true;
    }

    private static boolean isDangerous(Block block) {
        return switch (block.getType()) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK,
                 SWEET_BERRY_BUSH, WITHER_ROSE,
                 CACTUS, POWDER_SNOW,
                 NETHER_PORTAL, END_PORTAL, END_GATEWAY -> true;
            default -> false;
        };
    }

    /**
     * 围绕给定位置按环形向外扩散，搜索一个可以安全落脚的位置。
     * <p>
     * 用每一列的最高非空气方块作为候选 Y，避免逐格扫描整根 Y 轴。
     * </p>
     * <p>
     * <b>Folia 线程要求</b>：调用方必须已经拥有 {@code origin} 所在区域的分区所有权
     * （例如已经在玩家传送落地后的 {@code EntityScheduler} 回调里）。跨 region 同步读取
     * 方块会触发 {@code TickThread.ensureTickThread} 校验失败。
     * </p>
     *
     * @param origin 搜索中心
     * @param radius 最大搜索半径（方块）
     * @return 找到的安全位置；半径内确实没有安全地面时返回 {@code null}
     */
    public static @Nullable Location findNearbySafeLocation(Location origin, int radius) {
        World world = origin.getWorld();
        if (world == null) return null;

        int centerX = origin.getBlockX();
        int centerZ = origin.getBlockZ();

        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // 只扫环形边界，内圈在更小的 r 时已经查过了
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;

                    int x = centerX + dx;
                    int z = centerZ + dz;
                    int y = world.getHighestBlockYAt(x, z) + 1;
                    Location candidate = new Location(world, x + 0.5, y, z + 0.5,
                            origin.getYaw(), origin.getPitch());
                    if (isSafeLocation(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }
}
