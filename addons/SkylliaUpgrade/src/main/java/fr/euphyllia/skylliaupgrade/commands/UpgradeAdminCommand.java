package fr.euphyllia.skylliaupgrade.commands;

import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skylliaupgrade.SkylliaUpgrade;
import fr.euphyllia.skylliaupgrade.gui.UpgradeAdminGui;
import fr.euphyllia.skylliaupgrade.configuration.UpgradeConfigLoader;
import fr.euphyllia.skylliaupgrade.token.UpgradeTokenItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code /skylliadmin upgrade} — 打开岛屿升级配置菜单（每级半径/材料/门槛可视化编辑）。
 * <p>
 * 另外两个子命令：{@code reload} 重载配置，{@code token <玩家> [数量]} 发放领地拓展令。
 * </p>
 */
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
        if (args.length > 0 && args[0].equalsIgnoreCase("token")) {
            giveToken(sender, args);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("§c此命令只能由玩家执行。"));
            return;
        }
        player.getScheduler().run(SkylliaUpgrade.getInstance(), t -> UpgradeAdminGui.openList(player, 0), null);
    }

    /**
     * {@code /skylliadmin upgrade token <玩家> <等级> [数量]} —— 发放指定等级的领地拓展令。
     * <p>
     * 令牌本来只能靠 SkylliaChallenge 的任务奖励发放（配置里填同款 customModelData），
     * 但那条路要求管理员手动配奖励，服务器没配就等于全服拿不到、岛屿升级卡死在这一环。
     * 这条命令直接调用 {@link UpgradeTokenItem#build(int, int)}，发出来的令牌带 PDC 标记（含等级），
     * 比挑战奖励那条路发的更防伪。
     * </p>
     */
    private void giveToken(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("§c用法：/skylliadmin upgrade token <玩家> <等级> [数量]"));
            sender.sendMessage(Component.text("§7等级 = 这张令牌能用于升到第几级（Lv.N 的升级只认第 N 级令牌）"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("§c玩家不在线或不存在：§f" + args[1]));
            return;
        }
        int tier;
        try {
            tier = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("§c等级必须是整数，收到：§f" + args[2]));
            return;
        }
        int maxLevel = UpgradeConfigLoader.config.getMaxLevel();
        if (tier < 1 || tier > maxLevel) {
            sender.sendMessage(Component.text("§c等级必须在 1 到 " + maxLevel + " 之间。"));
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("§c数量必须是整数，收到：§f" + args[3]));
                return;
            }
            if (amount < 1 || amount > 64) {
                sender.sendMessage(Component.text("§c数量必须在 1 到 64 之间。"));
                return;
            }
        }

        final int finalTier = tier;
        final int finalAmount = amount;
        // 改背包必须回到玩家自己的线程（Folia：子命令跑在异步线程上，直接动背包是非法的）。
        // 第三个参数是玩家中途下线时的回退任务，这里补一句提示，避免管理员以为发成功了。
        target.getScheduler().run(SkylliaUpgrade.getInstance(), t -> {
            ItemStack token = UpgradeTokenItem.build(finalTier, finalAmount);
            var overflow = target.getInventory().addItem(token);
            if (overflow.isEmpty()) {
                target.sendMessage(Component.text("§a你获得了 §d领地拓展令 · Lv." + finalTier + " §fx" + finalAmount + "§a。"));
                sender.sendMessage(Component.text("§a已发放 §d领地拓展令 · Lv." + finalTier + " §fx" + finalAmount + " §a给 §f" + target.getName()));
            } else {
                // 背包满了就掉在脚下，绝不能静默吞掉——那是玩家实打实的升级材料
                overflow.values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                target.sendMessage(Component.text("§a你获得了 §d领地拓展令 · Lv." + finalTier + " §fx" + finalAmount + "§a（背包已满，部分掉落在脚下）。"));
                sender.sendMessage(Component.text("§e已发放给 §f" + target.getName() + "§e，但对方背包已满，多余部分掉落在其脚下。"));
            }
        }, () -> sender.sendMessage(Component.text("§c发放失败：§f" + args[1] + " §c已离线。")));
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) return List.of("reload", "token");
        if (args.length == 2 && args[0].equalsIgnoreCase("token")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("token")) return List.of("1", "8", "16", "64");
        return Collections.emptyList();
    }

    @Override
    public String permission() {
        return "skyllia.admins.commands.upgrade";
    }
}
