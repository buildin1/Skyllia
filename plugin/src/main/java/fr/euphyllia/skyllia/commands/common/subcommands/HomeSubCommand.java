package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.model.WarpIsland;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skyllia.utils.WorldUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class HomeSubCommand implements SubCommandInterface {

    /** 落点不安全时，围绕原始坐标搜索安全地面的最大半径（方块）。与死亡重生兜底保持一致。 */
    private static final int SAFE_SPOT_SEARCH_RADIUS = 16;

    private final Logger logger = LogManager.getLogger(HomeSubCommand.class);

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }

        try {
            Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
            if (island == null) {
                ConfigLoader.language.sendMessage(player, "island.player.no-island");
                return;
            }

            WarpIsland warpIsland = island.getWarpByName("home");
            double rayon = island.getSize();
            World islandWorld = WorldUtils.getWorldConfigs().getFirst().getWorld();

            Location loc;
            if (island.hasCustomSpawn()) {
                loc = island.getSpawnLocation(islandWorld);
            } else if (warpIsland != null && warpIsland.location() != null) {
                loc = warpIsland.location().clone();
            } else {
                loc = island.getSpawnLocation(islandWorld);
            }
            loc.add(0.5, 0.1, 0.5);

            final Location target = loc;
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
                if (!Boolean.TRUE.equals(success) || !player.isOnline()) {
                    return;
                }

                // 落地后的处理必须回到玩家自己的 EntityScheduler 上：此时线程才拥有目标
                // region 的分区所有权，读取方块才是安全的（Folia 会校验 TickThread）。
                player.getScheduler().run(plugin, task -> {
                    player.setVelocity(new Vector(0, 0, 0));
                    player.setFallDistance(0);

                    // 安全落点校验。此前 /is home 直接传送到存储坐标、不做任何检查：
                    // 岛主如果在 y=64 设过家，后来把岛建到 y=-63，玩家就会被丢在半空
                    // 一路摔死（setFallDistance 只清掉传送前累计的下落距离，落地后重新
                    // 开始计算）。这里复用与死亡重生完全相同的一套判定。
                    if (!WorldUtils.isSafeLocation(target)) {
                        Location safe = WorldUtils.findNearbySafeLocation(target, SAFE_SPOT_SEARCH_RADIUS);
                        if (safe != null) {
                            player.teleportAsync(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
                            player.sendMessage(MiniMessage.miniMessage().deserialize(
                                    "<yellow>你设置的家所在位置不安全，已自动落到附近的安全地面。</yellow>"));
                        } else {
                            logger.warn("[Skyllia-home] 玩家 {} 的家 {} 不安全，且附近 {} 格内找不到安全落点",
                                    player.getName(), target, SAFE_SPOT_SEARCH_RADIUS);
                            player.sendMessage(MiniMessage.miniMessage().deserialize(
                                    "<red>你设置的家悬空或不安全，且附近找不到可落脚的地面，请用 /is sethome 重新设置。</red>"));
                        }
                    }

                    ConfigLoader.language.sendMessage(player, "island.home.success");

                    if (PlayerUtils.hasPermission(player, "skyllia.island.worldborder.bypass")) {
                        return;
                    }

                    Location center = RegionHelper.getCenterRegion(
                            islandWorld,
                            island.getRegionCoordinate().x(),
                            island.getRegionCoordinate().z()
                    );

                    WorldBorder border = player.getWorldBorder();
                    if (border == null) {
                        border = Bukkit.createWorldBorder();
                    }
                    border.setCenter(center);
                    border.setSize(rayon);
                    player.setWorldBorder(border);
                }, null);
            });
        } catch (Exception exception) {
            logger.log(Level.FATAL, exception.getMessage(), exception);
            ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        return Collections.emptyList();
    }
}