package fr.euphyllia.skyllia.gui;

import fr.euphyllia.skyllia.Skyllia;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * 「群系修改」选区工具：外观是一把木锄头，但靠 PDC 标记与普通木锄头精确区分。
 * <p>
 * <b>安全边界</b>：{@link fr.euphyllia.skyllia.listeners.bukkitevents.player.BiomeSelectionToolListener}
 * 一律先调用 {@link #isSelectionTool(ItemStack)} 再介入交互事件，绝不会仅凭
 * {@link Material#WOODEN_HOE} 就拦截玩家手上的普通木锄头（会误伤正常耕地）。
 * </p>
 */
public final class BiomeSelectionToolItem {

    /** PDC 标记键：{@code skyllia:biome_selection_tool}。 */
    public static final NamespacedKey TOOL_KEY = new NamespacedKey(Skyllia.getInstance(), "biome_selection_tool");

    private BiomeSelectionToolItem() {}

    /** 构建一把全新的选区工具物品。 */
    public static @NotNull ItemStack build() {
        ItemStack item = GuiItem.of(Material.WOODEN_HOE,
                "<!italic><light_purple>🧭 群系选区工具",
                List.of(
                        "<dark_gray>─────────",
                        "<gray>左键方块：<yellow>记录起点 A</yellow></gray>",
                        "<gray>右键方块：<yellow>记录终点 B 并完成选区</yellow></gray>",
                        "<dark_gray>─────────",
                        "<dark_gray>选区仅在你自己的岛屿领地内生效</dark_gray>",
                        "<dark_gray>超出部分会被自动裁剪</dark_gray>"));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(TOOL_KEY, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 该物品是否是本工具（精确到 PDC 标记，而不是仅判断材质）。 */
    public static boolean isSelectionTool(ItemStack stack) {
        if (stack == null || stack.getType() != Material.WOODEN_HOE) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(TOOL_KEY, PersistentDataType.BYTE);
    }

    /**
     * 把工具放进玩家背包；背包满时掉落在玩家脚下并提示，绝不静默吞掉物品。
     */
    public static void giveTo(@NotNull Player player) {
        ItemStack tool = build();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(tool);
        if (!leftover.isEmpty()) {
            for (ItemStack overflow : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
            player.sendMessage(Component.text("§e背包已满，群系选区工具已掉落在你脚下。"));
        }
    }
}
