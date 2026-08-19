package fr.euphyllia.skyllia.gui;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * /is gui 命令：打开 Skyllia 主菜单。
 * <p>
 * 仅玩家可执行；无参数；无权限限制（权限在各按钮回调里校验）。
 * </p>
 * <p>
 * Folia 注意：SkylliaCommand 的子命令在 async scheduler 执行，但
 * {@link Player#openInventory} 必须在主线程（region tick 线程）触发，
 * 否则会抛 {@code IllegalStateException: InventoryOpenEvent may only be triggered synchronously}。
 * 因此这里通过 {@link org.bukkit.RegionScheduler#run} 调度到主线程。
 * </p>
 */
public class GuiSubCommand implements SubCommandInterface {

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("§c此命令只能由玩家执行。"));
            return;
        }
        // Folia: openInventory 必须在主线程执行
        player.getScheduler().run(Skyllia.getInstance(), t -> MainGui.open(player), null);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
