package fr.euphyllia.skylliaupgrade.commands;

import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skylliaupgrade.SkylliaUpgrade;
import fr.euphyllia.skylliaupgrade.gui.UpgradeGui;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/** {@code /is upgrade} — 打开岛屿升级菜单。 */
public class UpgradeCommand implements SubCommandInterface {

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("§c此命令只能由玩家执行。"));
            return;
        }
        // Folia: 打开界面必须在玩家所属的 region 线程上执行。
        // 不能用 GlobalRegionScheduler —— global region 不是玩家所在的 region。
        player.getScheduler().run(SkylliaUpgrade.getInstance(), t -> UpgradeGui.open(player), null);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
