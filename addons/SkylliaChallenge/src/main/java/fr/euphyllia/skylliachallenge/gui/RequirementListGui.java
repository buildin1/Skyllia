package fr.euphyllia.skylliachallenge.gui;

import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliachallenge.SkylliaChallenge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个挑战文件的「需求」列表：展示每一行原始 DSL、点击删除，或点击「添加」进入
 * {@link RequirementTypeGui} 选择类型并输入参数。每次增删都立即写回 YAML 并触发
 * {@code plugin.reload()}，保证正在运行的挑战管理器与磁盘内容一致。
 */
final class RequirementListGui {

    private static final Logger log = LoggerFactory.getLogger(RequirementListGui.class);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private RequirementListGui() {
    }

    static void open(@NotNull Player player, @NotNull File file, int page, @NotNull Runnable onBack) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        List<String> lines = yml.getStringList("requirements");
        String idStr = yml.getString("id", file.getName());
        NamespacedKey challengeId = idStr != null ? NamespacedKey.fromString(idStr) : null;

        int totalPages = Math.max(1, (int) Math.ceil(lines.size() / (double) AdminGuiUtil.PAGE_SIZE));
        int clamped = Math.max(0, Math.min(page, totalPages - 1));

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>需求列表 - " + idStr));
        AdminGuiUtil.applyBorder(inv);

        int from = clamped * AdminGuiUtil.PAGE_SIZE;
        int to = Math.min(from + AdminGuiUtil.PAGE_SIZE, lines.size());
        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            int slot = AdminGuiUtil.contentSlot(i - from);
            inv.setItem(slot, buildLinePreview(line, i + 1));
            int lineIndex = i;
            holder.bind(slot, e -> {
                List<String> current = new ArrayList<>(YamlConfiguration.loadConfiguration(file).getStringList("requirements"));
                if (lineIndex < current.size()) {
                    current.remove(lineIndex);
                    saveList(file, current);
                    SkylliaChallenge.getInstance().reload();
                }
                open(player, file, clamped, onBack);
            });
        }

        if (clamped > 0) {
            inv.setItem(45, GuiItem.prevPage());
            holder.bind(45, e -> open(player, file, clamped - 1, onBack));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(53, GuiItem.nextPage());
            holder.bind(53, e -> open(player, file, clamped + 1, onBack));
        }

        if (challengeId != null) {
            inv.setItem(4, GuiItem.of(Material.EMERALD, "<!italic><green>+ 添加需求",
                    List.of("<dark_gray>─────────", "<gray>点击选择需求类型</gray>")));
            holder.bind(4, e -> RequirementTypeGui.open(player, challengeId,
                    newLine -> {
                        List<String> current = new ArrayList<>(YamlConfiguration.loadConfiguration(file).getStringList("requirements"));
                        current.add(newLine);
                        saveList(file, current);
                        SkylliaChallenge.getInstance().reload();
                        open(player, file, clamped, onBack);
                    },
                    () -> open(player, file, clamped, onBack)));
        } else {
            inv.setItem(4, GuiItem.of(Material.BARRIER, "<!italic><red>挑战 ID 无效",
                    List.of("<gray>请先在编辑器里设置合法的挑战 ID</gray>")));
        }

        inv.setItem(49, GuiItem.back());
        holder.bind(49, e -> onBack.run());

        player.openInventory(inv);
    }

    private static void saveList(@NotNull File file, @NotNull List<String> list) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        yml.set("requirements", list);
        try {
            yml.save(file);
        } catch (IOException e) {
            log.error("Failed to save requirements to {}", file.getName(), e);
        }
    }

    private static ItemStack buildLinePreview(@NotNull String line, int index) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("#" + index).color(NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text(line).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("左键点击删除").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
            ));
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
