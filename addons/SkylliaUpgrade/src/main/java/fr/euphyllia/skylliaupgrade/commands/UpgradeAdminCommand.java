package fr.euphyllia.skylliaupgrade.commands;

import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skylliaupgrade.SkylliaUpgrade;
import fr.euphyllia.skylliaupgrade.gui.UpgradeAdminGui;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/** {@code /skylliadmin upgrade} — 打开岛屿升级配置菜单（每级半径/材料/门槛可视化编辑）。 */
public class UpgradeAdminCommand implements SubCommandInterface {

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!PlayerUtils.hasPermission(sender, permission())) {
            ConfigLoader.language.sendMessage(sender, "island.player.permission-denied");
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            SkylliaUpgrade.getInstance().reloadConfig0();
            sender.sendMessage(Component.text("§a[SkylliaUpgrade] 配置已重新加载。"));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("§c此命令只能由玩家执行。"));
            return;
        }
        Bukkit.getGlobalRegionScheduler().run(SkylliaUpgrade.getInstance(), t -> UpgradeAdminGui.openList(player, 0));
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) return List.of("reload");
        return Collections.emptyList();
    }

    @Override
    public String permission() {
        return "skyllia.admins.commands.upgrade";
    }
}
