package fr.euphyllia.skylliachallenge.gui;

import fr.euphyllia.skyllia.gui.GuiItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 管理员 GUI 列表页共用的 54 格布局常量（边框 + 每页 28 个内容格，7 列一行），
 * 与 {@code UpgradeAdminGui} 的列表页布局保持一致，避免各列表界面重复写同一套槽位算式。
 */
final class AdminGuiUtil {

    static final int PAGE_SIZE = 28;
    private static final int[] BORDER = {0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 46, 47, 48, 50, 51, 52};
    private static final int[] CONTENT_SLOTS = buildContentSlots();

    private AdminGuiUtil() {
    }

    private static int[] buildContentSlots() {
        int[] slots = new int[PAGE_SIZE];
        int slot = 10;
        int col = 0;
        int idx = 0;
        while (idx < PAGE_SIZE) {
            slots[idx++] = slot;
            col++;
            slot++;
            if (col == 7) {
                slot += 2;
                col = 0;
            }
        }
        return slots;
    }

    static int contentSlot(int indexOnPage) {
        return CONTENT_SLOTS[indexOnPage];
    }

    static void applyBorder(@NotNull Inventory inv) {
        for (int i : BORDER) {
            inv.setItem(i, GuiItem.filler());
        }
    }

    /**
     * 保留 {@code base}（可能是自定义物品，如 Nexo/Oraxen 物品）的材质/自定义模型数据，
     * 只覆盖显示名称与 lore；用于列表页展示挑战/等级图标时不丢失自定义外观。
     */
    static @NotNull ItemStack decorate(@NotNull ItemStack base, @NotNull String miniMessageName,
                                        @NotNull List<String> miniMessageLore) {
        ItemStack copy = base.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            MiniMessage mm = MiniMessage.miniMessage();
            meta.displayName(mm.deserialize("<!italic>" + miniMessageName));
            List<Component> loreComponents = miniMessageLore.stream()
                    .map(line -> (Component) mm.deserialize("<!italic>" + line))
                    .toList();
            meta.lore(loreComponents);
            copy.setItemMeta(meta);
        }
        return copy;
    }
}
