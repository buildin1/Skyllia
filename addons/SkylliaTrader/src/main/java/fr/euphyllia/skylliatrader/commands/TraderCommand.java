package fr.euphyllia.skylliatrader.commands;

import fr.euphyllia.skylliatrader.gui.TraderProgressGui;
import fr.euphyllia.skylliatrader.gui.order.TraderOrderBoardGui;
import fr.euphyllia.skylliatrader.gui.shop.MerchantRecycleGui;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 玩家端命令：{@code /is trader}（通过 {@code SkylliaAPI.registerCommands(..., "trader")}
 * 挂在核心的 {@code /is} 下面，不是一条独立的 {@code /trader}）。
 * <p>
 * 不带参数 —— 打开只读的进度指南 GUI（T1）；{@code /is trader orders} —— 打开商队订单看板
 * （T4：查看当前订单槽位 + 一键结算，见 {@link TraderOrderBoardGui}）；{@code /is trader sell} ——
 * 打开回收菜单（T5：查看游商收购的全部商品 + 一键回收，见 {@link MerchantRecycleGui}）。
 * </p>
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
        if (args.length > 0 && "orders".equalsIgnoreCase(args[0])) {
            TraderOrderBoardGui.open(player);
            return;
        }
        if (args.length > 0 && "sell".equalsIgnoreCase(args[0])) {
            MerchantRecycleGui.open(player);
            return;
        }
        TraderProgressGui.open(player);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return Stream.of("orders", "sell").filter(s -> s.startsWith(partial)).collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "skyllia.trader.command";
    }
}
