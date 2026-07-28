package fr.euphyllia.skyllia.api.utils.nms;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class PlayerNMS {

    @Deprecated(forRemoval = true, since = "3.0")
    @ApiStatus.ScheduledForRemoval(inVersion = "4.0")
    void setOwnWorldBorder(JavaPlugin main, Player player, @NotNull Location centerBorder, double borderSize, int warningBlocks, int warningTime) {
        throw new UnsupportedOperationException("Stop using this method. Use player.setWorldBorder() instead.");
    }

    /**
     * 通过 NMS 设置玩家的重生点（维度 + 坐标），
     * 会写入 player data 持久化存储。
     *
     * @param player   玩家
     * @param location 重生位置（world 决定维度）
     * @return true 如果设置成功
     */
    public boolean setRespawnPosition(@NotNull Player player, @NotNull Location location) {
        throw new UnsupportedOperationException("setRespawnPosition not implemented for this version");
    }

    /**
     * 通过 NMS 设置玩家的传送门冷却（单位：tick）。
     * 冷却期间玩家站在传送门内不会触发二次传送。
     *
     * @param player 玩家
     * @param ticks  冷却刻数（300 = 15 秒 = 原版默认）
     */
    public void setPortalCooldown(@NotNull Player player, int ticks) {
        throw new UnsupportedOperationException("setPortalCooldown not implemented for this version");
    }

    /**
     * 通过 NMS 直接传送玩家到目标位置（全局区域线程安全）。
     * 绕过 Bukkit 的 PlayerTeleportEvent。
     *
     * @param player   玩家
     * @param location 目标位置
     */
    public void teleport(@NotNull Player player, @NotNull Location location) {
        throw new UnsupportedOperationException("teleport not implemented for this version");
    }

    /**
     * 获取玩家当前所在的世界 ResourceKey 名称。
     * 用于维度检查（如 minecraft:overworld）。
     *
     * @param player 玩家
     * @return 维度 key 的字符串形式，如 "minecraft:overworld"
     */
    public @Nullable String getDimensionKey(@NotNull Player player) {
        return null;
    }

    /**
     * 根据世界名查找对应的 ResourceKey，用于跨维度传送。
     *
     * @param worldName 世界名
     * @return 解析后的 Location（带正确的维度）
     */
    public @Nullable Location resolveWorldLocation(@NotNull Player player, @NotNull String worldName) {
        return null;
    }
}
