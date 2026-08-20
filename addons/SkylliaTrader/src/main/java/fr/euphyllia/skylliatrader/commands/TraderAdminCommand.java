package fr.euphyllia.skylliatrader.commands;

import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.TrackTiers;
import fr.euphyllia.skylliatrader.gui.admin.TraderAdminMainGui;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 管理端命令树：{@code /skylliadmin trader ...}。
 * 通过 {@code SkylliaAPI.registerAdminCommands(new TraderAdminCommand(), "trader")} 挂进
 * Skyllia 核心的 {@code /skylliadmin} 子命令注册表（见 {@code SkylliaAdminCommand}），
 * 不需要自己开一个新的顶层命令。
 * <p>
 * T1 只做两件事：{@code gui} 打开统一管理 GUI 入口，{@code tiers} 打印四轨当前档位配置。
 * 订单/商人/凭证的具体增删改由 T2-T4 陆续填进 {@link TraderAdminMainGui} 这个框架里。
 * </p>
 */
public class TraderAdminCommand implements SubCommandInterface {

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(permission())) {
            sender.sendMessage(Component.text("§c你没有权限使用这个命令。"));
            return;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "gui" -> handleGui(sender);
            case "tiers" -> handleTiers(sender);
            default -> sendUsage(sender);
        }
    }

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("§c该子命令只能由玩家执行（需要打开 GUI）。"));
            return;
        }
        TraderAdminMainGui.open(player);
    }

    private void handleTiers(CommandSender sender) {
        TrackTiers tiers = TraderConfigLoader.config.getTrackTiers();
        sender.sendMessage(Component.text("§d═══ SkylliaTrader 四轨当前档位配置 ═══"));
        sender.sendMessage(Component.text("§7交易次数：§f" + tiers.tradeCount()));
        sender.sendMessage(Component.text("§7岛屿等级：§f" + tiers.islandLevel()));
        sender.sendMessage(Component.text("§7商会声望：§f" + tiers.reputation()));
        sender.sendMessage(Component.text("§7累计消费（只给折扣/限购加成）：§f" + tiers.spending()));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("§d用法：§f/skylliadmin trader <gui|tiers>"));
        sender.sendMessage(Component.text("§7  gui   打开游商管理 GUI"));
        sender.sendMessage(Component.text("§7  tiers 查看四轨当前档位配置"));
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return Stream.of("gui", "tiers")
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "skyllia.trader.admin";
    }
}
