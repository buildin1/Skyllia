package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.skyblock.model.WarpIsland;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skyllia.utils.WorldUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

public class VisitSubCommand implements SubCommandInterface {

    private final Logger logger = LogManager.getLogger(VisitSubCommand.class);

    private final PermissionId ISLAND_VISIT_BYPASS_PERMISSION;

    public VisitSubCommand() {
        this.ISLAND_VISIT_BYPASS_PERMISSION = SkylliaAPI.getPermissionRegistry().register(new PermissionNode(
                new NamespacedKey(Skyllia.getInstance(), "command.island.visit.bypass"),
                "island.permission.command.visit_bypass.name",
                "island.permission.command.visit_bypass.description"
        ));
    }

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }

        if (args.length < 1) {
            ConfigLoader.language.sendMessage(player, "island.visit.args-missing");
            return;
        }

        try {
            String visitPlayer = args[0];

            // 先尝试把参数当成岛屿 ID 解析：能解析成 UUID 且 getIslandByIslandId 命中的话，
            // 直接用这座岛，不再假设这个 UUID 一定是玩家 UUID。解析失败或查无此岛时，
            // 退回原有逻辑（当成玩家 UUID / 玩家名查找），完全向后兼容。
            Island island = null;
            UUID parsedId = null;
            try {
                parsedId = UUID.fromString(visitPlayer);
                island = SkylliaAPI.getIslandByIslandId(parsedId);
            } catch (IllegalArgumentException ignored) {
                // 不是合法 UUID，走玩家名分支
            }

            if (island == null) {
                UUID visitPlayerId = parsedId != null ? parsedId : Bukkit.getPlayerUniqueId(visitPlayer);
                if (visitPlayerId == null) {
                    ConfigLoader.language.sendMessage(player, "island.player.not-found");
                    return;
                }
                island = SkylliaAPI.getIslandByPlayerId(visitPlayerId);
            }

            if (island == null) {
                ConfigLoader.language.sendMessage(player, "island.visit.no-island");
                return;
            }
            // island 上面可能被重新赋值过（岛屿 ID 分支 / 玩家分支），下面的 lambda 需要一个
            // 实际上的最终变量才能捕获。
            final Island targetIsland = island;

            boolean bypass = SkylliaAPI.getPermissionsManager().hasPermission(player, island, ISLAND_VISIT_BYPASS_PERMISSION, null, ConfigLoader.general.getDebugSettings().permission());

            if (!bypass && !player.isOp()) {
                if (island.isPrivateIsland()) {
                    ConfigLoader.language.sendMessage(player, "island.visit.island-private");
                    return;
                }
                Players memberIsland = island.getMember(player.getUniqueId());
                if (memberIsland != null && memberIsland.getRoleType().equals(RoleType.BAN)) {
                    ConfigLoader.language.sendMessage(player, "island.visit.banned");
                    return;
                }
            }

            WarpIsland warpIsland = island.getVisit();

            player.getScheduler().execute(plugin, () -> {
                Location loc;
                if (warpIsland == null || warpIsland.location() == null || warpIsland.location().getWorld() == null) {
                    loc = RegionHelper.getCenterRegion(Bukkit.getWorld(WorldUtils.getWorldConfigs().getFirst().getWorldName()), targetIsland.getRegionCoordinate().x(), targetIsland.getRegionCoordinate().z());
                } else {
                    loc = warpIsland.location().clone();
                }
                loc.add(0, 0.1, 0);
                player.teleportAsync(loc, PlayerTeleportEvent.TeleportCause.PLUGIN).thenRun(() -> {
                    if (PlayerUtils.hasPermission(player, "skyllia.island.worldborder.bypass")) {
                        return;
                    }

                    ConfigLoader.language.sendMessage(player, "island.visit.success", Map.of(
                            "%player%", visitPlayer));

                    Location center = RegionHelper.getCenterRegion(loc.getWorld(), targetIsland.getRegionCoordinate().x(), targetIsland.getRegionCoordinate().z());

                    WorldBorder border = player.getWorldBorder();
                    if (border == null) {
                        border = Bukkit.createWorldBorder();
                    }

                    border.setCenter(center);
                    border.setSize(targetIsland.getSize());
                    player.setWorldBorder(border);
                });
            }, null, 1L);
        } catch (Exception exception) {
            logger.log(Level.FATAL, exception.getMessage(), exception);
            ConfigLoader.language.sendMessage(player, "island.generic.unexpected-error");
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].trim().toLowerCase();
            var onlinePlayers = Bukkit.getOnlinePlayers();
            List<String> list = new ArrayList<>();
            for (Player onlinePlayer : onlinePlayers) {
                String name = onlinePlayer.getName();
                if (name.toLowerCase().startsWith(partial)) {
                    list.add(name);
                }
            }
            return list;
        }
        return Collections.emptyList();
    }
}