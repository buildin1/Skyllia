package fr.euphyllia.skyllia.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GUI 物品构建辅助工具。
 * 集中处理 MiniMessage 文本 → Adventure Component 的转换，统一按钮风格。
 */
public final class GuiItem {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private GuiItem() {}

    /** 创建一个带名称和 lore 的物品 */
    public static @NotNull ItemStack of(@NotNull Material material,
                                        @Nullable String displayName,
                                        @Nullable List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (displayName != null && !displayName.isEmpty()) {
            meta.displayName(MM.deserialize(displayName));
        }
        if (lore != null && !lore.isEmpty()) {
            List<Component> loreComponents = new ArrayList<>(lore.size());
            for (String line : lore) {
                loreComponents.add(MM.deserialize(line));
            }
            meta.lore(loreComponents);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** 创建无 lore 的物品 */
    public static @NotNull ItemStack of(@NotNull Material material, @Nullable String displayName) {
        return of(material, displayName, null);
    }

    /** 创建纯装饰用的填充物（紫色玻璃板） */
    public static @NotNull ItemStack filler() {
        return of(Material.PURPLE_STAINED_GLASS_PANE, "<!italic><dark_gray> ");
    }

    /** 创建带指定颜色的装饰填充物 */
    public static @NotNull ItemStack filler(@NotNull String colorTag) {
        return of(Material.PURPLE_STAINED_GLASS_PANE, "<!italic>" + colorTag + " ");
    }

    /** 创建真实玩家头颅 */
    public static @NotNull ItemStack playerHead(@NotNull UUID playerId,
                                                @Nullable String displayName,
                                                @Nullable List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            // 使用 OfflinePlayer 设置头颅所有者（Folia 兼容）
            org.bukkit.OfflinePlayer offline = org.bukkit.Bukkit.getOfflinePlayer(playerId);
            skullMeta.setOwningPlayer(offline);
            if (displayName != null && !displayName.isEmpty()) {
                skullMeta.displayName(MM.deserialize(displayName));
            }
            if (lore != null && !lore.isEmpty()) {
                List<Component> loreComponents = new ArrayList<>(lore.size());
                for (String line : lore) {
                    loreComponents.add(MM.deserialize(line));
                }
                skullMeta.lore(loreComponents);
            }
            item.setItemMeta(skullMeta);
        }
        return item;
    }

    /** 开关状态按钮：开启（绿色羊毛） */
    public static @NotNull ItemStack enabled(@Nullable String displayName, @Nullable List<String> lore) {
        return of(Material.GREEN_WOOL, "<green>✔ " + (displayName == null ? "" : displayName), lore);
    }

    /** 开关状态按钮：关闭（红色羊毛） */
    public static @NotNull ItemStack disabled(@Nullable String displayName, @Nullable List<String> lore) {
        return of(Material.RED_WOOL, "<red>✘ " + (displayName == null ? "" : displayName), lore);
    }

    /** 危险操作按钮 */
    public static @NotNull ItemStack danger(@NotNull Material material,
                                            @Nullable String displayName,
                                            @Nullable List<String> lore) {
        return of(material, "<red>⚠ " + (displayName == null ? "" : displayName), lore);
    }

    /** 「返回」按钮（箭头） */
    public static @NotNull ItemStack back() {
        return of(Material.ARROW, "<!italic><gray>← 返回",
                List.of("<dark_gray>点击返回上级菜单"));
    }

    /** 「关闭」按钮（屏障） */
    public static @NotNull ItemStack close() {
        return of(Material.BARRIER, "<!italic><red>✖ 关闭",
                List.of("<dark_gray>点击关闭菜单"));
    }

    /** 「上一页」按钮 */
    public static @NotNull ItemStack prevPage() {
        return of(Material.ARROW, "<!italic><gray>← 上一页");
    }

    /** 「下一页」按钮 */
    public static @NotNull ItemStack nextPage() {
        return of(Material.ARROW, "<!italic><gray>下一页 →");
    }

    /** 占位按钮：功能尚未实现，灰色禁用态 */
    public static @NotNull ItemStack placeholder(@NotNull Material material,
                                                 @Nullable String displayName,
                                                 @Nullable String description) {
        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add("<dark_gray>─────────");
        if (description != null && !description.isEmpty()) {
            lore.add("<dark_gray>" + description + "</dark_gray>");
        }
        lore.add("<dark_gray>─────────");
        lore.add("<dark_gray>🔒 开发中，敬请期待</dark_gray>");
        return of(material, "<!italic><dark_gray>" + (displayName == null ? "" : displayName), lore);
    }
}
