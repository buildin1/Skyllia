package fr.euphyllia.skylliachallenge.requirement;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.model.Position;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BlockNearRequirement implements ChallengeRequirement {

    private final Plugin plugin;
    private final Material material;
    private final int amount;
    private final double radius;

    public BlockNearRequirement(Plugin plugin, Material material, int amount, double radius) {
        this.plugin = plugin;
        this.material = material;
        this.amount = amount;
        this.radius = radius;
    }

    @Override
    public boolean isMet(Player player, Island island) {
        Location loc = player.getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;

        // 当前线程已拥有该区域则直接执行，避免死锁
        if (Bukkit.isOwnedByCurrentRegion(loc.getWorld(), chunkX, chunkZ)) {
            return checkBlocks(island, loc);
        }

        // 否则提交到区域线程并等待结果
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, loc, () -> {
            try {
                future.complete(checkBlocks(island, loc));
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

    private boolean checkBlocks(Island island, Location loc) {
        World world = loc.getWorld();
        Position islandPos = island.getPosition();
        int centerX = loc.getBlockX();
        int centerY = loc.getBlockY();
        int centerZ = loc.getBlockZ();
        int r = (int) Math.ceil(radius);
        int count = 0;

        for (int x = centerX - r; x <= centerX + r; x++) {
            for (int y = Math.max(world.getMinHeight(), centerY - r);
                 y <= Math.min(world.getMaxHeight() - 1, centerY + r); y++) {
                for (int z = centerZ - r; z <= centerZ + r; z++) {
                    if (world.getBlockAt(x, y, z).getType() != material) continue;

                    int cx = x >> 4;
                    int cz = z >> 4;
                    Position blockRegion = RegionHelper.getRegionFromChunk(cx, cz);
                    if (blockRegion.x() == islandPos.x() && blockRegion.z() == islandPos.z()) {
                        count++;
                        if (count >= amount) return true;
                    }
                }
            }
        }
        return count >= amount;
    }

    @Override
    public Component getDisplay(Locale locale) {
        // 构建方块翻译键，例如 block.minecraft.diamond_block
        String blockKey = "block." + material.getKey().getNamespace() + "." + material.getKey().getKey();
        return ConfigLoader.language.translate(locale, "addons.challenge.requirement.nearby_block.display", Map.of(
                "%block%", "<lang:" + blockKey + ">",
                "%radius%", String.valueOf((int) radius),
                "%amount%", String.valueOf(amount)
        ), false);
    }
}