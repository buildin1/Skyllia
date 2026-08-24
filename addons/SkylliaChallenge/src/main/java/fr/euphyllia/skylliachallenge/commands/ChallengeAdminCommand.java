package fr.euphyllia.skylliachallenge.commands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skylliachallenge.SkylliaChallenge;
import fr.euphyllia.skylliachallenge.challenge.Challenge;
import fr.euphyllia.skylliachallenge.gui.ChallengeAdminGui;
import fr.euphyllia.skylliachallenge.storage.ProgressStorage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public record ChallengeAdminCommand(SkylliaChallenge plugin) implements SubCommandInterface {

    /** 管理员权限节点，同时用于命令与主菜单「扩展功能」入口的可见性判断。 */
    public static final String PERMISSION = "skyllia.challenge.reload";

    @Override
    public void onExecute(@NotNull Plugin plugin0, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!PlayerUtils.hasPermission(sender, permission())) {
            ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.no-permission");
            return;
        }
        if (args.length == 0) {
            ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.unknown-command");
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui" -> handleGui(sender);
            case "reload" -> {
                plugin.reload();
                ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.reload-success");
            }
            case "complete" -> handleComplete(sender, args);
            default -> ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.unknown-command");
        }
    }

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("§c此命令只能由玩家执行。"));
            return;
        }
        player.getScheduler().run(plugin, t -> ChallengeAdminGui.openMain(player), null);
    }

    /**
     * {@code /skylliadmin challenge complete <玩家> <挑战ID>}
     * 跳过需求与冷却，给该玩家所在岛屿记一次完成并发放奖励。
     */
    private void handleComplete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.complete-usage");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.player-offline",
                    Map.of("%player%", args[1]));
            return;
        }

        Island island = SkylliaAPI.getIslandByPlayerId(target.getUniqueId());
        if (island == null) {
            ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.no-island",
                    Map.of("%player%", target.getName()));
            return;
        }

        Challenge challenge = resolveChallenge(args[2]);
        if (challenge == null) {
            ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.challenge-not-found",
                    Map.of("%challenge%", args[2]));
            return;
        }

        int before = ProgressStorage.getTimesCompleted(island.getId(), challenge.getId());
        target.getScheduler().run(plugin, task -> {
            plugin.getChallengeManager().forceComplete(island, challenge, target);
            int after = ProgressStorage.getTimesCompleted(island.getId(), challenge.getId());
            ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.complete-success", Map.of(
                    "%player%", target.getName(),
                    "%challenge_name%", challenge.getName(),
                    "%challenge%", challenge.getId().asString(),
                    "%before%", String.valueOf(before),
                    "%after%", String.valueOf(after)
            ));
            ConfigLoader.language.sendMessage(target, "addons.challenge.player.complete",
                    Map.of("%challenge_name%", challenge.getName()));
        }, null);
    }

    @Nullable
    private Challenge resolveChallenge(String raw) {
        String idStr = raw.trim();
        NamespacedKey key = NamespacedKey.fromString(idStr.toLowerCase(Locale.ROOT));
        if (key != null) {
            Challenge found = plugin.getChallengeManager().getChallenge(key);
            if (found != null) return found;
        }
        if (!idStr.contains(":")) {
            NamespacedKey skyllia = NamespacedKey.fromString("skyllia:" + idStr.toLowerCase(Locale.ROOT));
            if (skyllia != null) {
                Challenge found = plugin.getChallengeManager().getChallenge(skyllia);
                if (found != null) return found;
            }
        }
        String needle = idStr.toLowerCase(Locale.ROOT);
        Challenge match = null;
        for (Challenge challenge : plugin.getChallengeManager().getChallenges()) {
            if (challenge.getId().asString().equalsIgnoreCase(needle)
                    || challenge.getId().getKey().equalsIgnoreCase(needle)) {
                if (match != null) return null;
                match = challenge;
            }
        }
        return match;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin0, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return List.of("reload", "gui", "complete").stream()
                    .filter(s -> s.startsWith(partial))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("complete")) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("complete")) {
            String partial = args[2].toLowerCase(Locale.ROOT);
            List<String> ids = new ArrayList<>();
            for (Challenge challenge : plugin.getChallengeManager().getChallenges()) {
                String full = challenge.getId().asString();
                if (full.toLowerCase(Locale.ROOT).startsWith(partial)
                        || challenge.getId().getKey().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    ids.add(full);
                }
            }
            ids.sort(String.CASE_INSENSITIVE_ORDER);
            return ids;
        }
        return List.of();
    }

    @Override
    public String permission() {
        return PERMISSION;
    }
}
