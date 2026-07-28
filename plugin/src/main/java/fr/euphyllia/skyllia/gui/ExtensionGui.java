package fr.euphyllia.skyllia.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 「扩展功能」子菜单：列出所有通过 {@link GuiExtensionRegistry} 注册的 addon 入口。
 * <p>
 * 布局跟 {@link MainGui} 的其它列表子菜单（成员/访客）保持一致：
 * 槽 10-34（7 列网格，跳过边框列）显示条目，槽 49 返回，翻页按钮在槽 45/53。
 * </p>
 */
public final class ExtensionGui {

    private static final int PAGE_SIZE = 28;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ExtensionGui() {}

    public static void open(@NotNull Player player, int page) {
        List<GuiExtensionEntry> all = GuiExtensionRegistry.entries().stream()
                .filter(e -> e.isVisibleFor(player))
                .toList();

        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) PAGE_SIZE));
        int clampedPage = Math.max(0, Math.min(page, totalPages - 1));

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54,
                MM.deserialize("<light_purple>🧩 扩展功能"));

        List<Integer> filler = new ArrayList<>();
        for (int i : new int[]{0, 1, 2, 3, 5, 6, 8, 9, 17, 18, 26, 27, 35, 36, 44, 46, 47, 48, 50, 51, 52}) {
            filler.add(i);
        }
        for (int slot : filler) {
            inv.setItem(slot, GuiItem.filler());
        }

        if (all.isEmpty()) {
            inv.setItem(22, GuiItem.of(Material.BARRIER,
                    "<!italic><red>暂无扩展功能",
                    List.of("<dark_gray>─────────",
                            "<gray>没有已安装的 addon 注册扩展入口</gray>")));
        } else {
            int fromIndex = clampedPage * PAGE_SIZE;
            int toIndex = Math.min(fromIndex + PAGE_SIZE, all.size());

            int slot = 10;
            int col = 0;
            for (int i = fromIndex; i < toIndex; i++) {
                GuiExtensionEntry entry = all.get(i);

                List<String> lore = new ArrayList<>();
                lore.add("<dark_gray>─────────");
                lore.addAll(entry.lore());
                lore.add("<dark_gray>─────────");
                lore.add("<yellow>点击打开</yellow>");

                inv.setItem(slot, GuiItem.of(entry.icon(), "<!italic>" + entry.displayName(), lore));
                holder.bind(slot, e -> entry.onClick().accept(player));

                col++;
                slot++;
                if (col == 7) {
                    slot += 2;
                    col = 0;
                }
            }
        }

        // 翻页
        if (clampedPage > 0) {
            inv.setItem(45, GuiItem.prevPage());
            holder.bind(45, e -> open(player, clampedPage - 1));
        }
        if (clampedPage < totalPages - 1) {
            inv.setItem(53, GuiItem.nextPage());
            holder.bind(53, e -> open(player, clampedPage + 1));
        }

        inv.setItem(49, GuiItem.back());
        holder.bind(49, e -> MainGui.open(player));

        player.openInventory(inv);
    }
}
