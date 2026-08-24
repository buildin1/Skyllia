package fr.euphyllia.skyllia.api.utils.nms;

import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.coordinate.ChunkCoordinate;
import fr.euphyllia.skyllia.api.skyblock.model.Position;
import fr.euphyllia.skyllia.api.world.WorldFeedback;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Provides methods for interacting with the Minecraft world using NMS (net.minecraft.server) classes.
 */
public abstract class WorldNMS {

    /**
     * Creates a new world using the specified WorldCreator.
     *
     * @param creator The WorldCreator to use for creating the world.
     * @return A FeedbackWorld object containing feedback about the world creation process.
     */
    public abstract WorldFeedback.FeedbackWorld createWorld(WorldCreator creator);

    /**
     * Creates a new world with optional custom height settings.
     * Implementations that support custom height should override this method.
     * The default implementation ignores the WorldConfig height settings and
     * falls back to {@link #createWorld(WorldCreator)}.
     *
     * @param creator     The WorldCreator to use for creating the world.
     * @param worldConfig The world configuration, potentially containing custom height settings.
     * @return A FeedbackWorld object containing feedback about the world creation process.
     */
    public WorldFeedback.FeedbackWorld createWorld(WorldCreator creator, WorldConfig worldConfig) {
        return createWorld(creator);
    }

    public abstract Map<Material, Integer> getCountAllBlocksInChunk(@NotNull World world, int chunkX, int chunkZ);

    /**
     * Resets a chunk at the specified chunk coordinate in the given world.
     *
     * @param craftWorld The world where the chunk is to be reset.
     * @param chunk      The chunk coordinate.
     */
    public abstract void resetChunk(World craftWorld, ChunkCoordinate chunk);

    /**
     * Resets a chunk at the specified position in the given world.
     *
     * @param craftWorld The world where the chunk is to be reset.
     * @param position   The position of the chunk to reset.
     * @deprecated Use {@link #resetChunk(World, ChunkCoordinate)} instead.
     */
    @Deprecated(forRemoval = true, since = "3.x")
    @ApiStatus.ScheduledForRemoval(inVersion = "4.x")
    public void resetChunk(World craftWorld, Position position) {
        resetChunk(craftWorld, new ChunkCoordinate(position.x(), position.z()));
    }

    /**
     * Gets the current location TPS.
     *
     * @param location the location for which to get the TPS
     * @return current location TPS (5s, 15s, 1m, 5m, 15m in Folia-Server), or null if the region doesn't exist
     */
    public abstract double @Nullable [] getTPS(Location location);

    /**
     * Gets the current chunk TPS.
     *
     * @param chunk the chunk for which to get the TPS
     * @return current location TPS (5s, 15s, 1m, 5m, 15m in Folia-Server), or null if the region doesn't exist
     */
    public abstract double @Nullable [] getTPS(Chunk chunk);

    /**
     * Gets the average tick times for a specific location.
     *
     * @param location the location for which to get the average tick times
     * @return an array of average tick times, or null if the region doesn't exist
     */
    public abstract double @Nullable [] getAverageTickTimes(Location location);

    /**
     * Gets the average tick times for a specific chunk.
     *
     * @param chunk the chunk for which to get the average tick times
     * @return an array of average tick times, or null if the region doesn't exist
     */
    public abstract double @Nullable [] getAverageTickTimes(Chunk chunk);

    public List<Entity> getEntities(World craftWorld, final @Nullable Entity except, final BoundingBox bb, Predicate<? super Entity> filter) {
        return List.of();
    }

    /**
     * 反射修改 MinecraftServer.levels，将原版维度映射到空岛世界。
     * 调用后 NetherPortalBlock 的维度解析会使用空岛世界。
     *
     * @param overworld 空岛主世界
     * @param nether    空岛地狱（可 null）
     * @param end       空岛末地（可 null）
     */
    public void remapPortalDimensions(@Nullable World overworld, @Nullable World nether, @Nullable World end) {
    }

    /**
     * 玩家进入末地门时调用，修改 {@code ServerLevel.END_SPAWN_POINT}
     * 为玩家当前坐标，使后续异步末地门路径使用 1:1 坐标传送。
     * <p>
     * 默认实现为空，由各 NMS 版本覆盖。
     *
     * @param player 进入末地门的玩家
     */
    public void adjustEndPortalSpawnPoint(@NotNull Player player) {
    }

    /**
     * 按原版刷怪蛋的生成链路放下一个游商：{@code EntityType.spawn(..., SPAWN_ITEM_USE, tryMoveDown)}。
     * <p>
     * 和 {@code World#spawn} 的关键差别：
     * </p>
     * <ul>
     *   <li>会做刷怪蛋那套碰撞落点校正（{@code tryMoveDown}），不会卡进方块或从方块顶面挤出去；</li>
     *   <li>会走 {@code Mob#finalizeSpawn}；</li>
     *   <li>事件被取消时返回 {@code null}（Paper 的 {@code World#spawn} 会把这个信息丢掉，
     *       交回一只已经 {@code discard} 的实体）。</li>
     * </ul>
     * <p>
     * {@code beforeAdd} 在实体加入世界<b>之前</b>调用，等价于刷怪蛋的 {@code PostSpawnProcessor}，
     * 也等价于 {@code World#spawn} 的 pre-spawn 回调——PDC 标记必须在这里打。
     * </p>
     * <p>
     * 默认实现返回 {@code null}，由各 NMS 版本覆盖。调用方把 {@code null} 当成生成失败。
     * </p>
     */
    public @Nullable WanderingTrader spawnWanderingTraderLikeEgg(
            @NotNull Location location,
            @Nullable Consumer<WanderingTrader> beforeAdd) {
        return spawnWanderingTraderLikeEgg(location, beforeAdd, true);
    }

    /**
     * @param tryMoveDown {@code true} 走刷怪蛋落点校正；凭证召唤传 {@code false}，
     *                    落点必须就是调用方给的坐标（右键方块 Y+2）。
     */
    public @Nullable WanderingTrader spawnWanderingTraderLikeEgg(
            @NotNull Location location,
            @Nullable Consumer<WanderingTrader> beforeAdd,
            boolean tryMoveDown) {
        return null;
    }
}
