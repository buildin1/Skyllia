package fr.euphyllia.skyllia.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * 二次确认菜单：危险操作（删除/离开/转让/踢出等）的弹窗确认。
 * <p>
 * 流程：
 * <ol>
 *   <li>调用 {@link #open} 显示确认菜单，玩家原菜单被替换</li>
 *   <li>点击「确认」→ 执行 {@code confirmAction}，关闭菜单</li>
 *   <li>点击「取消」→ 不执行，返回 {@code returnTo}（若非 null）或关闭</li>
 * </ol>
 * </p>
 */
public final class ConfirmGui {

    private ConfirmGui() {}

    /**
     * 打开二次确认菜单。
     *
     * @param player        目标玩家
     * @param title         菜单标题（MiniMessage 字符串，将前缀红色 ⚠）
     * @param warningLore   警告说明文本（每行一条）
     * @param confirmAction 确认时执行的操作
     * @param returnTo      取消时的返回回调（可为 null，表示仅关闭）
     */
    public static void open(@NotNull Player player,
                            @NotNull String title,
                            @NotNull java.util.List<String> warningLore,
                            @NotNull Runnable confirmAction,
                            Runnable returnTo) {
        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.CONFIRM);
        // 用 27 格（9×3）的菜单
        Inventory inv = Bukkit.createInventory(holder, 27,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<red>⚠ " + title));

        // 填充边框（红色玻璃板）
        for (int i = 0; i < 27; i++) {
            // 跳过中间一行（9-17）的按钮位置
            if (i == 11 || i == 13 || i == 15) continue;
            inv.setItem(i, GuiItem.of(Material.RED_STAINED_GLASS_PANE, "<!italic><dark_gray> "));
        }

        // 第 2 行：取消 | 警告信息 | 确认
        // 槽 11: 取消
        java.util.List<String> cancelLore = new java.util.ArrayList<>();
        cancelLore.add("<dark_gray>─────────");
        cancelLore.add("<gray>不执行任何操作");
        if (returnTo != null) {
            cancelLore.add("<yellow>点击</yellow> <gray>返回上级菜单</gray>");
        } else {
            cancelLore.add("<yellow>点击</yellow> <gray>关闭菜单</gray>");
        }
        inv.setItem(11, GuiItem.of(Material.ARROW, "<!italic><green>✖ 取消", cancelLore));
        holder.bind(11, event -> {
            event.getWhoClicked().closeInventory();
            if (returnTo != null) {
                // 延迟 1 tick 防止与 InventoryClose 冲突
                Bukkit.getGlobalRegionScheduler().runDelayed(
                        fr.euphyllia.skyllia.Skyllia.getInstance(), t -> returnTo.run(), 1L);
            }
        });

        // 槽 13: 警告信息（书面物品）
        java.util.List<String> warnLore = new java.util.ArrayList<>();
        warnLore.add("<dark_gray>─────────");
        warnLore.addAll(warningLore);
        inv.setItem(13, GuiItem.of(Material.WRITTEN_BOOK,
                "<!italic><red>⚠ 警告：不可逆操作", warnLore));

        // 槽 15: 确认
        java.util.List<String> confirmLore = new java.util.ArrayList<>();
        confirmLore.add("<dark_gray>─────────");
        confirmLore.add("<red>此操作不可撤销！");
        confirmLore.add("<red>请确认你已了解后果。");
        confirmLore.add("<dark_gray>─────────");
        confirmLore.add("<yellow>点击</yellow> <red>立即执行</red>");
        inv.setItem(15, GuiItem.danger(Material.TNT, "确认执行", confirmLore));
        holder.bind(15, event -> {
            event.getWhoClicked().closeInventory();
            try {
                confirmAction.run();
            } catch (Exception e) {
                fr.euphyllia.skyllia.Skyllia.getInstance().getLogger().severe(
                        "[Skyllia-GUI] 确认操作执行失败: " + e.getMessage());
                event.getWhoClicked().sendMessage(net.kyori.adventure.text.Component.text(
                        "§c操作执行失败，请查看控制台。"));
            }
        });

        player.openInventory(inv);
    }
}
