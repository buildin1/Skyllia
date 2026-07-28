package fr.euphyllia.skyllia.utils.nms.v26_2;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PlayerNMS extends fr.euphyllia.skyllia.api.utils.nms.PlayerNMS {

    @Override
    public boolean setRespawnPosition(@NotNull Player player, @NotNull Location location) {
        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        World world = location.getWorld();
        if (world == null) return false;

        ResourceKey<Level> dimension = ((CraftWorld) world).getHandle().dimension();
        BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        float yaw = location.getYaw();
        float pitch = location.getPitch();

        return serverPlayer.setRespawnPosition(
                new ServerPlayer.RespawnConfig(
                        LevelData.RespawnData.of(dimension, pos, yaw, pitch),
                        true
                ),
                true,
                com.destroystokyo.paper.event.player.PlayerSetSpawnEvent.Cause.PLUGIN
        );
    }

    @Override
    public void setPortalCooldown(@NotNull Player player, int ticks) {
        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        serverPlayer.setPortalCooldown(ticks);
    }

    @Override
    public void teleport(@NotNull Player player, @NotNull Location location) {
        player.teleportAsync(location);
    }

    @Override
    public @Nullable String getDimensionKey(@NotNull Player player) {
        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        ResourceKey<Level> dimension = serverPlayer.level().dimension();
        return dimension != null ? dimension.identifier().toString() : null;
    }

    @Override
    public @Nullable Location resolveWorldLocation(@NotNull Player player, @NotNull String worldName) {
        org.bukkit.World bukkitWorld = player.getServer().getWorld(worldName);
        if (bukkitWorld == null) return null;
        return new Location(bukkitWorld, 0, 64, 0);
    }
}
