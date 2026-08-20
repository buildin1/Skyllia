package fr.euphyllia.skylliatrader.commands;

import fr.euphyllia.skylliatrader.gui.TraderProgressGui;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 玩家端命令：{@code /is trader}（通过 {@code SkylliaAPI.registerCommands(..., "trader")}
 * 挂在核心的 {@code /is} 下面，不是一条独立的 {@code /trader}）。
 * T1 只有一个功能——打开只读的进度指南 GUI；真正的购买/订单在 T2-T3 加。
 */
public class TraderCommand implements fr.euphyllia.skyllia.api.commands.SubCommandInterface {

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(permission())) {
            sender.sendMessage(Component.text("§c你没有权限使用这个命令。"));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("§c该命令只能由玩家执行。"));
            return;
        }
        TraderProgressGui.open(player);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        return List.of();
    }

    @Override
    public String permission() {
        return "skyllia.trader.command";
    }
}
