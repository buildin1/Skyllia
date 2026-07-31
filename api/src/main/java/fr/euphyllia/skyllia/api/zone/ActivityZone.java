package fr.euphyllia.skyllia.api.zone;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * 一个管理员划定的活动区（商店、PVP 场地等）。
 * <p>
 * {@code contentRadius} 是玩家在区域内看到的可见边境半径；{@code bufferRadius}
 * 只用于阻止新岛屿在附近生成，不会作为可见边境展示给玩家（数值上通常比
 * {@code contentRadius} 更大，代表"内容范围 + 缓冲带"）。
 * </p>
 */
public record ActivityZone(
        int id,
        String name,
        int centerX,
        int centerZ,
        double contentRadius,
        double bufferRadius,
        boolean allowBreak,
        boolean allowPlace,
        boolean allowPvp,
        boolean allowMobAttack,
        @Nullable UUID createdBy,
        @Nullable Instant createdAt
) {

    /** 给定一个方块坐标，判断是否落在这个活动区的内容范围内（正方形，跟 WorldBorder 一致）。 */
    public boolean containsContent(int blockX, int blockZ) {
        return Math.abs(blockX - centerX) <= contentRadius && Math.abs(blockZ - centerZ) <= contentRadius;
    }
}
