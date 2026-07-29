package fr.euphyllia.skylliachallenge.commands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skylliachallenge.SkylliaChallenge;
import fr.euphyllia.skylliachallenge.gui.ChallengeAdminGui;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ChallengeCommand(SkylliaChallenge plugin) implements SubCommandInterface {

    @Override
    public void onExecute(@NotNull Plugin plugin0, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            ConfigLoader.language.sendMessage(sender, "addons.challenge.player.player-only");
            return;
        }

        // /is challenge admin gui —— 管理员配置界面，故意不放进 /is gui 的扩展菜单
        if (args.length >= 2 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("gui")) {
            if (!PlayerUtils.hasPermission(p, "skyllia.challenge.reload")) {
                ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.no-permission");
                return;
            }
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> ChallengeAdminGui.openMain(p));
            return;
        }

        Island island = SkylliaAPI.getIslandByPlayerId(p.getUniqueId());
        if (island == null) {
            ConfigLoader.language.sendMessage(sender, "addons.challenge.player.no-island");
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("levels")) {
            plugin.getChallengeLevelManager().openGui(p);
            return;
        }
        plugin.getChallengeManager().openGui(p);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("levels", "admin");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return List.of("gui");
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "skyllia.challenge.use";
    }
}
