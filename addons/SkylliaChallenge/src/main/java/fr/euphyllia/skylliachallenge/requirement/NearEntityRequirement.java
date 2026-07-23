package fr.euphyllia.skylliachallenge.requirement;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.model.Position;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class NearEntityRequirement implements ChallengeRequirement {

    private final Plugin plugin;
    private final EntityType type;
    private final int amount;
    private final double radius;

    public NearEntityRequirement(Plugin plugin, EntityType type, int amount, double radius) {
        this.plugin = plugin;
        this.type = type;
        this.amount = amount;
        this.radius = radius;
    }

    @Override
    public boolean isMet(Player player, Island island) {
        Location loc = player.getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;

        // 如果当前线程已经拥有该区块，直接执行以避开死锁
        if (Bukkit.isOwnedByCurrentRegion(loc.getWorld(), chunkX, chunkZ)) {
            return checkNearby(island, loc);
        }

        // 否则，提交到区域线程并等待结果
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, loc, () -> {
            try {
                future.complete(checkNearby(island, loc));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            return false;
        }
    }

    private boolean checkNearby(Island island, Location loc) {
        List<Entity> nearby = (List<Entity>) loc.getWorld().getNearbyEntities(
                loc, radius, radius, radius, e -> e.getType() == type);
        Position islandPos = island.getPosition();
        nearby.removeIf(e -> {
            Location eLoc = e.getLocation();
            int cx = eLoc.getBlockX() >> 4;
            int cz = eLoc.getBlockZ() >> 4;
            Position entityRegion = RegionHelper.getRegionFromChunk(cx, cz);
            return entityRegion.x() != islandPos.x() || entityRegion.z() != islandPos.z();
        });
        return nearby.size() >= amount;
    }

    @Override
    public Component getDisplay(Locale locale) {
        String entityKey = "entity." + type.getKey().getNamespace() + "." + type.getKey().getKey();
        return ConfigLoader.language.translate(locale, "addons.challenge.requirement.nearby_entity.display", Map.of(
                "%entity_type%", "<lang:" + entityKey + ">",
                "%radius%", String.valueOf((int) radius),
                "%amount%", String.valueOf(amount)
        ), false);
    }
}