package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.join.JoinRequestService;
import fr.euphyllia.skyllia.join.JoinRequestStore;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code /is join <玩家>} 向对方空岛提出加入申请。
 * {@code /is join accept|deny <玩家>} 岛主处理申请。
 * {@code /is join list} 查看待处理申请。
 */
public class JoinSubCommand implements SubCommandInterface {

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }
        if (args.length < 1) {
            ConfigLoader.language.sendMessage(player, "island.join.args-missing");
            return;
        }

        String first = args[0];
        if (first.equalsIgnoreCase("accept")) {
            if (args.length < 2) {
                ConfigLoader.language.sendMessage(player, "island.join.accept-args-missing");
                return;
            }
            JoinRequestService.accept(player, args[1]);
        } else if (first.equalsIgnoreCase("deny") || first.equalsIgnoreCase("decline")) {
            if (args.length < 2) {
                ConfigLoader.language.sendMessage(player, "island.join.deny-args-missing");
                return;
            }
            JoinRequestService.deny(player, args[1]);
        } else if (first.equalsIgnoreCase("list")) {
            list(player);
        } else {
            JoinRequestService.apply(player, first);
        }
    }

    private void list(Player owner) {
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
        List<JoinRequestStore.Request> pending = JoinRequestStore.list(island);
        if (pending.isEmpty()) {
            ConfigLoader.language.sendMessage(owner, "island.join.list-empty");
            return;
        }
        ConfigLoader.language.sendMessage(owner, "island.join.list-header",
                Map.of("%count%", String.valueOf(pending.size())));
        for (JoinRequestStore.Request request : pending) {
            JoinRequestService.sendOwnerDecisionButtons(owner, request.applicantName());
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        var online = Bukkit.getOnlinePlayers();
        if (args.length == 1) {
            String partial = args[0].trim().toLowerCase();
            return Stream.concat(
                    Stream.of("accept", "deny", "list"),
                    online.stream().map(Player::getName)
            ).filter(s -> s.toLowerCase().startsWith(partial)).collect(Collectors.toList());
        }
        if (args.length == 2) {
            String partial = args[1].trim().toLowerCase();
            return online.stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .sorted()
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
