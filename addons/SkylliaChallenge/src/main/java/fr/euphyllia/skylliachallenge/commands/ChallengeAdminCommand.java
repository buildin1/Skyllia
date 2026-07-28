package fr.euphyllia.skylliachallenge.commands;

import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skylliachallenge.SkylliaChallenge;
import fr.euphyllia.skylliachallenge.gui.ChallengeAdminGui;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ChallengeAdminCommand(SkylliaChallenge plugin) implements SubCommandInterface {

    /** 管理员权限节点，同时用于命令与主菜单「扩展功能」入口的可见性判断。 */
    public static final String PERMISSION = "skyllia.challenge.reload";

    @Override
    public void onExecute(@NotNull Plugin plugin0, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!PlayerUtils.hasPermission(sender, permission())) {
            ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.no-permission");
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("§c此命令只能由玩家执行。"));
                return;
            }
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> ChallengeAdminGui.openMain(player));
            return;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.unknown-command");
            return;
        }
        plugin.reload();
        ConfigLoader.language.sendMessage(sender, "addons.challenge.admin.reload-success");
    }


    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        return List.of("reload", "gui");
    }

    @Override
    public String permission() {
        return PERMISSION;
    }
}
