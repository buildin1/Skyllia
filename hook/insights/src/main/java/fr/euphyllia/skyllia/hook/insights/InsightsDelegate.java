package fr.euphyllia.skyllia.hook.insights;

import dev.frankheijden.insights.api.addons.InsightsAddon;
import dev.frankheijden.insights.api.addons.Region;
import dev.frankheijden.insights.api.addons.SimpleCuboidRegion;
import dev.frankheijden.insights.api.objects.math.Vector3;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Optional;

public class InsightsDelegate implements InsightsAddon {

    @Override
    public String getPluginName() {
        return SkylliaAPI.getPlugin().getName();
    }

    @Override
    public String getAreaName() {
        return SkylliaAPI.getPlugin().getName();
    }

    @Override
    public String getVersion() {
        return SkylliaAPI.getPlugin().getPluginMeta().getVersion();
    }

    @Override
    public Optional<Region> getRegion(Location location) {
        World world = location.getWorld();
        if (world == null || !SkylliaAPI.isWorldSkyblock(world)) return Optional.empty();

        final int chunkX = location.getBlockX() >> 4;
        final int chunkZ = location.getBlockZ() >> 4;
        final Island island = SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
        if (island == null) return Optional.empty();

        Location pos1 = island.getMinimumPoint(world);
        Location pos2 = island.getMaximumPoint(world);

        Vector3 min = new Vector3(pos1.getBlockX(), pos1.getBlockY(), pos1.getBlockZ());
        Vector3 max = new Vector3(pos2.getBlockX(), pos2.getBlockY(), pos2.getBlockZ());

        String key = island.getId() + ":" + world.getName();

        return Optional.of(new SimpleCuboidRegion(world, min, max, getAreaName(), key));
    }
}