package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.managers.skyblock.SkyblockManager;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.skyblock.model.WarpIsland;
import fr.euphyllia.skyllia.cache.commands.CommandCacheExecution;
import fr.euphyllia.skyllia.cache.commands.InviteCacheExecution;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InviteSubCommand implements SubCommandInterface {

    private final Logger logger = LogManager.getLogger(InviteSubCommand.class);

    private final PermissionId ISLAND_INVITE_PERMISSION;

    public InviteSubCommand() {
        this.ISLAND_INVITE_PERMISSION = SkylliaAPI.getPermissionRegistry().register(new PermissionNode(
                new NamespacedKey(Skyllia.getInstance(), "command.island.invite"),
                "island.permission.command.invite.name",
                "island.permission.command.invite.description"
        ));
    }

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }

        if (args.length < 1) {
            ConfigLoader.language.sendMessage(player, "island.invite.args-missing");
            return;
        }
        String type = args[0];

        if (type.equalsIgnoreCase("accept")) {
            if (args.length < 2) {
                ConfigLoader.language.sendMessage(player, "island.invite.accept-args-missing");
                return;
            }
            String playerOrOwner = args[1];
            boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
            acceptPlayer(player, playerOrOwner, confirmed);
        } else if (type.equalsIgnoreCase("decline")) {
            if (args.length < 2) {
                ConfigLoader.language.sendMessage(player, "island.invite.decline-args-missing");
                return;
            }
            String playerOrOwner = args[1];
            declinePlayer(player, playerOrOwner);
        } else if (type.equalsIgnoreCase("delete")) {
            if (args.length < 2) {
                ConfigLoader.language.sendMessage(player, "island.invite.remove-args-missing");
                return;
            }
            String playerOrOwner = args[1];
            deleteInvitePlayer(player, playerOrOwner);
        } else {
            invitePlayer(player, args[0]);
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        var onlinePlayers = Bukkit.getOnlinePlayers();
        if (args.length == 1) {
            String partial = args[0].trim().toLowerCase();
            return Stream.concat(
                    Stream.of("accept", "decline", "delete"),
                    onlinePlayers
                            .stream()
                            .map(Player::getName)
            ).filter(cmd -> cmd.toLowerCase().startsWith(partial)).collect(Collectors.toList());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("accept")) {
            // 已有岛屿的玩家需要二次确认，把 confirm 补出来，避免玩家不知道有这个参数
            if ("confirm".startsWith(args[2].trim().toLowerCase())) {
                return List.of("confirm");
            }
            return List.of();
        } else if (args.length == 2) {
            String partial = args[1].trim().toLowerCase();

            return onlinePlayers.stream()
                    .map(CommandSender::getName)
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .sorted()
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private void deleteInvitePlayer(Player ownerIsland, String playerInvited) {
        Island island = SkylliaAPI.getIslandByPlayerId(ownerIsland.getUniqueId());

        if (island == null) {
            ConfigLoader.language.sendMessage(ownerIsland, "island.player.no-island");
            return;
        }

        boolean allowed = SkylliaAPI.getPermissionsManager().hasPermission(ownerIsland, island, ISLAND_INVITE_PERMISSION, null, ConfigLoader.general.getDebugSettings().permission());
        if (!allowed) {
            ConfigLoader.language.sendMessage(ownerIsland, "island.player.permission-denied");
            return;
        }

        UUID playerInvitedId = Bukkit.getPlayerUniqueId(playerInvited);
        if (playerInvitedId == null) {
            ConfigLoader.language.sendMessage(ownerIsland, "island.player.not-found");
            return;
        }

        InviteCacheExecution.removeInviteCache(island.getId(), playerInvitedId);
        ConfigLoader.language.sendMessage(ownerIsland, "island.invite.invite-deleted", Map.of(
                "%s", playerInvited));
    }

    private void invitePlayer(Player ownerIsland, String playerInvited) {
        try {
            UUID playerInvitedId = Bukkit.getPlayerUniqueId(playerInvited);
            if (playerInvitedId == null) {
                ConfigLoader.language.sendMessage(ownerIsland, "island.player.not-found");
                return;
            }

            if (ownerIsland.getUniqueId().equals(playerInvitedId)) {
                ConfigLoader.language.sendMessage(ownerIsland, "island.invite.invite-yourself");
                return;
            }

            Island island = SkylliaAPI.getIslandByPlayerId(ownerIsland.getUniqueId());
            if (island == null) {
                ConfigLoader.language.sendMessage(ownerIsland, "island.player.no-island");
                return;
            }

            boolean allowed = SkylliaAPI.getPermissionsManager().hasPermission(ownerIsland, island, ISLAND_INVITE_PERMISSION, null, ConfigLoader.general.getDebugSettings().permission());
            if (!allowed) {
                ConfigLoader.language.sendMessage(ownerIsland, "island.player.permission-denied");
                return;
            }

            InviteCacheExecution.addInviteCache(island.getId(), playerInvitedId);
            ConfigLoader.language.sendMessage(ownerIsland, "island.invite.player-invited", Map.of(
                    "%s", playerInvited));
            Player bPlayerInvited = Bukkit.getPlayer(playerInvitedId);
            if (bPlayerInvited != null && bPlayerInvited.isOnline()) {
                ConfigLoader.language.sendMessage(bPlayerInvited, "island.invite.player-notified", Map.of("%player_invite%", ownerIsland.getName()));
            }
        } catch (Exception e) {
            logger.log(Level.FATAL, e.getMessage(), e);
            ConfigLoader.language.sendMessage(ownerIsland, "island.generic.unexpected-error");
        }
    }

    private void acceptPlayer(Player playerWantJoin, String ownerIslandName, boolean confirmed) {
        try {CommandCacheExecution.isAlreadyExecute(playerWantJoin.getUniqueId(), "create")
                    || CacheExecution.isAlreadyExecute(playerWantJoin.getUniqueId(), "create")
                    || fr.euphyllia.skyllia.cache.commands.CommandCacheExecution.isAlreadyExecute(playerWantJoin.getUniqueId(), "delete")) {
                ConfigLoader.language.sendMessage(playerWantJoin, "island.generic.command-in-progress");
                return;
            }

            // 玩家已有岛屿时不再直接拒绝。
            //
            // 删岛流程改成「删完立刻重建新岛」之后，玩家永远处于「有岛」状态，
            // 原先那句 already-on-island 的判定就永远成立，导致全服没有人能加入任何岛屿。
            //
            // 现在按身份区分：岛主需要二次确认并解散自己的岛；只是别人岛上的成员则直接退出旧岛即可，
            // 绝不能把别人的岛删掉。
            Island islandPlayer = SkylliaAPI.getIslandByPlayerId(playerWantJoin.getUniqueId());
            boolean isOwnerOfOldIsland = false;
            if (islandPlayer != null) {
                Players self = islandPlayer.getMember(playerWantJoin.getUniqueId());
                isOwnerOfOldIsland = self != null && self.getRoleType().equals(RoleType.OWNER);

                if (isOwnerOfOldIsland
                        && ConfigLoader.general.getIslandSettings().preventDeletionIfHasMembers()) {
                    long others = islandPlayer.getMembers().stream()
                            .filter(m -> !m.getMojangId().equals(playerWantJoin.getUniqueId()))
                            .count();
                    if (others > 0) {
                        ConfigLoader.language.sendMessage(playerWantJoin, "island.player.delete-has-members");
                        return;
                    }
                }

                if (!confirmed) {
                    ConfigLoader.language.sendMessage(playerWantJoin,
                            isOwnerOfOldIsland ? "island.invite.join-confirm-delete" : "island.invite.join-confirm-leave",
                            Map.of("%player_invite%", ownerIslandName));
                    return;
                }
            }

            UUID ownerId = Bukkit.getPlayerUniqueId(ownerIslandName);
            if (ownerId == null) {
                ConfigLoader.language.sendMessage(playerWantJoin, "island.player.not-found");
                return;
            }

            Island islandOwner = SkylliaAPI.getIslandByPlayerId(ownerId);
            if (islandOwner == null) {
                ConfigLoader.language.sendMessage(playerWantJoin, "island.invite.island-not-found");
                return;
            }

            boolean invited = InviteCacheExecution.isInvitedCache(islandOwner.getId(), playerWantJoin.getUniqueId())
                    || fr.euphyllia.skyllia.join.JoinRequestStore.hasApprovedInvite(
                            islandOwner.getId(), playerWantJoin.getUniqueId());
            if (!invited) {
                ConfigLoader.language.sendMessage(playerWantJoin, "island.invite.invite-not-found");
                return;
            }

            int maxMembers = islandOwner.getMaxMembers();
            int currentMembers = islandOwner.getMembers().size();

            if (currentMembers < maxMembers) {
                // 先处理旧岛，再加入新岛：解散会把岛上的玩家清到出生点，
                // 顺序反了会把刚传送过去的人又踢回出生点。
                SkyblockManager skyblockManager = Skyllia.getInstance().getInterneAPI().getSkyblockManager();
                Island oldIsland = islandPlayer;
                if (oldIsland != null) {
                    if (isOwnerOfOldIsland) {
                        if (!DeleteSubCommand.freeIslandForOwner(playerWantJoin, oldIsland, skyblockManager)) {
                            ConfigLoader.language.sendMessage(playerWantJoin, "island.generic.unexpected-error");
                            return;
                        }
                    } else {
                        // 只是成员：退出旧岛即可，不动别人的岛屿。
                        Players self = oldIsland.getMember(playerWantJoin.getUniqueId());
                        if (self != null) {
                            self.setRoleType(RoleType.VISITOR);
                            oldIsland.updateMember(self);
                        }
                    }
                }

                InviteCacheExecution.removeInviteCache(islandOwner.getId(), playerWantJoin.getUniqueId());
                fr.euphyllia.skyllia.join.JoinRequestStore.clearApprovedInvite(
                        islandOwner.getId(), playerWantJoin.getUniqueId());

                Players newPlayer = new Players(
                        playerWantJoin.getUniqueId(),
                        playerWantJoin.getName(),
                        islandOwner.getId(),
                        RoleType.MEMBER
                );

                boolean updated = islandOwner.updateMember(newPlayer);
                if (!updated) {
                    ConfigLoader.language.sendMessage(playerWantJoin, "island.generic.unexpected-error");
                    return;
                }

                ConfigLoader.language.sendMessage(playerWantJoin, "island.invite.join-success");

                Player ownerOnline = Bukkit.getPlayer(ownerId);
                if (ownerOnline != null && ownerOnline.isOnline()) {
                    ConfigLoader.language.sendMessage(ownerOnline, "island.invite.accept-notify-owner",
                            Map.of("%player_accept%", playerWantJoin.getName()));
                }

                // 旧岛区块留到后台慢慢删。必须放在传送之前：下面若因为目标岛屿没有 home
                // 而提前 return，旧岛就再也没人清理，会永远占着那块 region。
                // notifyOnSuccess=false：玩家刚收到「加入成功」，再补一条「空岛删除成功」只会让人以为出了问题。
                if (oldIsland != null && isOwnerOfOldIsland) {
                    DeleteSubCommand.deleteOldIslandChunks(skyblockManager, oldIsland, playerWantJoin, false);
                }

                if (ConfigLoader.general.getIslandSettings().teleportWhenAcceptingInvitation()) {
                    WarpIsland home = islandOwner.getWarpByName("home"); // cache warps TTL 5s
                    if (home == null || home.location() == null || home.location().getWorld() == null) {
                        ConfigLoader.language.sendMessage(playerWantJoin, "island.invite.home-not-found");
                        return;
                    }
                    playerWantJoin.teleportAsync(home.location(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                }
            } else {
                ConfigLoader.language.sendMessage(playerWantJoin, "island.invite.member-limit-reached");
            }
        } catch (Exception e) {
            logger.log(Level.FATAL, e.getMessage(), e);
            ConfigLoader.language.sendMessage(playerWantJoin, "island.generic.unexpected-error");
        }
    }

    private void declinePlayer(Player playerWantDecline, String ownerIslandName) {
        try {
            UUID ownerId = Bukkit.getPlayerUniqueId(ownerIslandName);
            if (ownerId == null) {
                ConfigLoader.language.sendMessage(playerWantDecline, "island.player.not-found");
                return;
            }

            Island islandOwner = SkylliaAPI.getIslandByPlayerId(ownerId);
            if (islandOwner == null) {
                ConfigLoader.language.sendMessage(playerWantDecline, "island.invite.island-not-found");
                return;
            }

            boolean invited = InviteCacheExecution.isInvitedCache(islandOwner.getId(), playerWantDecline.getUniqueId())
                    || fr.euphyllia.skyllia.join.JoinRequestStore.hasApprovedInvite(
                            islandOwner.getId(), playerWantDecline.getUniqueId());
            if (!invited) {
                ConfigLoader.language.sendMessage(playerWantDecline, "island.invite.invite-not-found");
                return;
            }

            InviteCacheExecution.removeInviteCache(islandOwner.getId(), playerWantDecline.getUniqueId());
            fr.euphyllia.skyllia.join.JoinRequestStore.clearApprovedInvite(
                    islandOwner.getId(), playerWantDecline.getUniqueId());
            ConfigLoader.language.sendMessage(playerWantDecline, "island.invite.decline-success",
                    Map.of("%player_invite%", ownerIslandName));

            Player ownerOnline = Bukkit.getPlayer(ownerId);
            if (ownerOnline != null && ownerOnline.isOnline()) {
                ConfigLoader.language.sendMessage(ownerOnline, "island.invite.decline-notify-owner",
                        Map.of("%player_decline%", playerWantDecline.getName()));
            }
        } catch (Exception e) {
            logger.log(Level.FATAL, e.getMessage(), e);
            ConfigLoader.language.sendMessage(playerWantDecline, "island.generic.unexpected-error");
        }
    }
}
