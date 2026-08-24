package fr.euphyllia.skyllia.join;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.cache.commands.CommandCacheExecution;
import fr.euphyllia.skyllia.cache.commands.InviteCacheExecution;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 入岛申请：玩家向岛主提出申请，岛主上线后提醒，同意后走现有邀请接受流程。
 */
public final class JoinRequestService {

    private JoinRequestService() {
    }

    public static void apply(@NotNull Player applicant, @NotNull String targetName) {
        if (CommandCacheExecution.isAlreadyExecute(applicant.getUniqueId(), "create")
                || CommandCacheExecution.isAlreadyExecute(applicant.getUniqueId(), "delete")) {
            ConfigLoader.language.sendMessage(applicant, "island.generic.command-in-progress");
            return;
        }

        UUID targetId = Bukkit.getPlayerUniqueId(targetName);
        if (targetId == null) {
            ConfigLoader.language.sendMessage(applicant, "island.player.not-found");
            return;
        }
        if (targetId.equals(applicant.getUniqueId())) {
            ConfigLoader.language.sendMessage(applicant, "island.join.apply-yourself");
            return;
        }

        Island targetIsland = SkylliaAPI.getIslandByPlayerId(targetId);
        if (targetIsland == null) {
            ConfigLoader.language.sendMessage(applicant, "island.join.target-no-island",
                    Map.of("%player%", targetName));
            return;
        }

        Island own = SkylliaAPI.getIslandByPlayerId(applicant.getUniqueId());
        if (own != null && own.getId().equals(targetIsland.getId())) {
            ConfigLoader.language.sendMessage(applicant, "island.join.already-member");
            return;
        }

        UUID existing = JoinRequestStore.outgoingIslandId(applicant.getUniqueId());
        if (existing != null) {
            if (existing.equals(targetIsland.getId())) {
                ConfigLoader.language.sendMessage(applicant, "island.join.already-applied");
                return;
            }
            Island old = SkylliaAPI.getIslandByIslandId(existing);
            if (old != null) {
                JoinRequestStore.remove(old, applicant.getUniqueId());
            }
        }

        if (!JoinRequestStore.put(targetIsland, applicant.getUniqueId(), applicant.getName())) {
            ConfigLoader.language.sendMessage(applicant, "island.generic.unexpected-error");
            return;
        }

        ConfigLoader.language.sendMessage(applicant, "island.join.applied",
                Map.of("%player%", targetName));

        Players owner = targetIsland.getOwner();
        UUID ownerId = owner != null ? owner.getMojangId() : targetId;
        Player ownerOnline = Bukkit.getPlayer(ownerId);
        if (ownerOnline != null && ownerOnline.isOnline()) {
            ConfigLoader.language.sendMessage(ownerOnline, "island.join.applied-notify-owner",
                    Map.of("%player%", applicant.getName()));
        }
    }

    public static void accept(@NotNull Player owner, @NotNull String applicantName) {
        Island island = SkylliaAPI.getIslandByPlayerId(owner.getUniqueId());
        if (island == null) {
            ConfigLoader.language.sendMessage(owner, "island.player.no-island");
            return;
        }
        Players self = island.getMember(owner.getUniqueId());
        if (self == null || self.getRoleType() != RoleType.OWNER) {
            ConfigLoader.language.sendMessage(owner, "island.only-owner");
            return;
        }

        UUID applicantId = Bukkit.getPlayerUniqueId(applicantName);
        if (applicantId == null) {
            ConfigLoader.language.sendMessage(owner, "island.player.not-found");
            return;
        }

        JoinRequestStore.Request request = JoinRequestStore.get(island, applicantId);
        if (request == null) {
            ConfigLoader.language.sendMessage(owner, "island.join.no-request",
                    Map.of("%player%", applicantName));
            return;
        }

        JoinRequestStore.remove(island, applicantId);
        InviteCacheExecution.addInviteCache(island.getId(), applicantId);
        JoinRequestStore.approveInvite(island.getId(), applicantId);
        ConfigLoader.language.sendMessage(owner, "island.join.accepted",
                Map.of("%player%", applicantName));

        Player applicant = Bukkit.getPlayer(applicantId);
        if (applicant != null && applicant.isOnline()) {
            ConfigLoader.language.sendMessage(applicant, "island.join.accepted-notify-applicant",
                    Map.of("%player%", owner.getName()));
        }
    }

    public static void deny(@NotNull Player owner, @NotNull String applicantName) {
        Island island = SkylliaAPI.getIslandByPlayerId(owner.getUniqueId());
        if (island == null) {
            ConfigLoader.language.sendMessage(owner, "island.player.no-island");
            return;
        }
        Players self = island.getMember(owner.getUniqueId());
        if (self == null || self.getRoleType() != RoleType.OWNER) {
            ConfigLoader.language.sendMessage(owner, "island.only-owner");
            return;
        }

        UUID applicantId = Bukkit.getPlayerUniqueId(applicantName);
        if (applicantId == null) {
            ConfigLoader.language.sendMessage(owner, "island.player.not-found");
            return;
        }

        JoinRequestStore.Request request = JoinRequestStore.get(island, applicantId);
        if (request == null) {
            ConfigLoader.language.sendMessage(owner, "island.join.no-request",
                    Map.of("%player%", applicantName));
            return;
        }

        JoinRequestStore.remove(island, applicantId);
        ConfigLoader.language.sendMessage(owner, "island.join.denied",
                Map.of("%player%", applicantName));

        Player applicant = Bukkit.getPlayer(applicantId);
        if (applicant != null && applicant.isOnline()) {
            ConfigLoader.language.sendMessage(applicant, "island.join.denied-notify-applicant",
                    Map.of("%player%", owner.getName()));
        }
    }

    public static void notifyOwnerOnJoin(@NotNull Player owner) {
        Island island = SkylliaAPI.getIslandByPlayerId(owner.getUniqueId());
        if (island == null) return;
        Players self = island.getMember(owner.getUniqueId());
        if (self == null || self.getRoleType() != RoleType.OWNER) return;

        List<JoinRequestStore.Request> pending = JoinRequestStore.list(island);
        if (pending.isEmpty()) return;

        String names = pending.stream()
                .map(JoinRequestStore.Request::applicantName)
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        ConfigLoader.language.sendMessage(owner, "island.join.pending-on-login",
                Map.of("%count%", String.valueOf(pending.size()), "%players%", names));
    }
}
