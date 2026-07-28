package fr.euphyllia.skylliaupgrade.gui;

import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.GuiTextInput;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliaupgrade.SkylliaUpgrade;
import fr.euphyllia.skylliaupgrade.configuration.MaterialCost;
import fr.euphyllia.skylliaupgrade.configuration.UpgradeConfigLoader;
import fr.euphyllia.skylliaupgrade.configuration.UpgradeLevelDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 管理员端「岛屿升级」配置菜单：每一级的半径/成员上限/评分门槛/令牌数/材料
 * 都可以直接在 GUI 里点按钮 + 聊天栏输入数值来编辑，改完立即持久化到 upgrades.toml，
 * 不需要手动改配置文件。
 */
public final class UpgradeAdminGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PAGE_SIZE = 28;

    private UpgradeAdminGui() {}

    // ═══════════════════════════════════════════════════════════
    //  等级列表
    // ═══════════════════════════════════════════════════════════

    public static void openList(@NotNull Player player, int page) {
        List<UpgradeLevelDefinition> levels = UpgradeConfigLoader.config.getAllLevels();
        int totalPages = Math.max(1, (int) Math.ceil(levels.size() / (double) PAGE_SIZE));
        int clampedPage = Math.max(0, Math.min(page, totalPages - 1));

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>⚙ 岛屿升级配置"));

        for (int i : new int[]{0, 1, 2, 3, 5, 6, 8, 9, 17, 18, 26, 27, 35, 36, 44, 46, 47, 48, 50, 51, 52}) {
            inv.setItem(i, GuiItem.filler());
        }

        int fromIndex = clampedPage * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, levels.size());

        int slot = 10;
        int col = 0;
        for (int i = fromIndex; i < toIndex; i++) {
            UpgradeLevelDefinition def = levels.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("<dark_gray>─────────");
            lore.add("<gray>边境：" + fmt(def.size()) + " 格</gray>");
            lore.add("<gray>成员上限：" + def.maxMembers() + "</gray>");
            lore.add("<gray>评分门槛：" + fmt(def.scoreThreshold()) + "</gray>");
            lore.add("<gray>令牌：" + def.tokenCount() + "</gray>");
            lore.add("<gray>材料种类：" + def.materials().size() + "</gray>");
            lore.add("<dark_gray>─────────");
            lore.add("<yellow>点击编辑</yellow>");

            inv.setItem(slot, GuiItem.of(Material.PAPER, "<!italic><light_purple>Lv." + def.level(), lore));
            int level = def.level();
            holder.bind(slot, e -> openLevelEditor(player, level));

            col++;
            slot++;
            if (col == 7) {
                slot += 2;
                col = 0;
            }
        }

        if (clampedPage > 0) {
            inv.setItem(45, GuiItem.prevPage());
            holder.bind(45, e -> openList(player, clampedPage - 1));
        }
        if (clampedPage < totalPages - 1) {
            inv.setItem(53, GuiItem.nextPage());
            holder.bind(53, e -> openList(player, clampedPage + 1));
        }

        inv.setItem(49, GuiItem.close());
        holder.bind(49, e -> player.closeInventory());

        player.openInventory(inv);
    }

    // ═══════════════════════════════════════════════════════════
    //  单个等级的编辑器
    // ═══════════════════════════════════════════════════════════

    public static void openLevelEditor(@NotNull Player player, int level) {
        UpgradeLevelDefinition def = UpgradeConfigLoader.config.getLevel(level);
        if (def == null) {
            player.sendMessage(Component.text("§c该等级不存在。"));
            openList(player, 0);
            return;
        }

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 36, MM.deserialize("<light_purple>⚙ 编辑 Lv." + level));

        for (int i : new int[]{0, 1, 2, 6, 7, 8, 9, 17, 18, 26, 27, 28, 29, 33, 34, 35}) {
            inv.setItem(i, GuiItem.filler());
        }

        inv.setItem(10, GuiItem.of(Material.MAP, "<!italic><light_purple>边境半径：" + fmt(def.size()) + " 格",
                List.of("<dark_gray>─────────", "<gray>点击输入新的数值</gray>")));
        holder.bind(10, e -> GuiTextInput.promptNumber(SkylliaUpgrade.getInstance(), player,
                "<yellow>请输入 Lv." + level + " 的新边境半径（数字）：",
                value -> {
                    UpgradeConfigLoader.config.setLevelSize(level, value);
                    openLevelEditor(player, level);
                },
                () -> openLevelEditor(player, level)));

        inv.setItem(12, GuiItem.of(Material.GOLDEN_APPLE, "<!italic><light_purple>成员上限：" + def.maxMembers(),
                List.of("<dark_gray>─────────", "<gray>点击输入新的数值</gray>")));
        holder.bind(12, e -> GuiTextInput.promptNumber(SkylliaUpgrade.getInstance(), player,
                "<yellow>请输入 Lv." + level + " 的新成员上限（整数）：",
                value -> {
                    UpgradeConfigLoader.config.setLevelMaxMembers(level, (int) Math.round(value));
                    openLevelEditor(player, level);
                },
                () -> openLevelEditor(player, level)));

        inv.setItem(14, GuiItem.of(Material.EXPERIENCE_BOTTLE, "<!italic><light_purple>评分门槛：" + fmt(def.scoreThreshold()),
                List.of("<dark_gray>─────────", "<gray>需要达到的岛屿 score</gray>", "<gray>点击输入新的数值</gray>")));
        holder.bind(14, e -> GuiTextInput.promptNumber(SkylliaUpgrade.getInstance(), player,
                "<yellow>请输入 Lv." + level + " 的新评分门槛（数字）：",
                value -> {
                    UpgradeConfigLoader.config.setLevelScoreThreshold(level, value);
                    openLevelEditor(player, level);
                },
                () -> openLevelEditor(player, level)));

        inv.setItem(16, GuiItem.of(Material.NAME_TAG, "<!italic><light_purple>领地拓展令数量：" + def.tokenCount(),
                List.of("<dark_gray>─────────", "<gray>点击输入新的数值</gray>")));
        holder.bind(16, e -> GuiTextInput.promptNumber(SkylliaUpgrade.getInstance(), player,
                "<yellow>请输入 Lv." + level + " 需要的领地拓展令数量（整数）：",
                value -> {
                    UpgradeConfigLoader.config.setLevelTokenCount(level, (int) Math.round(value));
                    openLevelEditor(player, level);
                },
                () -> openLevelEditor(player, level)));

        List<String> materialsLore = new ArrayList<>();
        materialsLore.add("<dark_gray>─────────");
        if (def.materials().isEmpty()) {
            materialsLore.add("<gray>（无材料要求）</gray>");
        } else {
            for (MaterialCost cost : def.materials()) {
                materialsLore.add("<gray>• " + cost.material().name() + " x" + cost.amount() + "</gray>");
            }
        }
        materialsLore.add("<dark_gray>─────────");
        materialsLore.add("<gray>点击整体重新输入</gray>");
        materialsLore.add("<gray>格式：MATERIAL:数量,MATERIAL:数量</gray>");
        inv.setItem(21, GuiItem.of(Material.CHEST, "<!italic><light_purple>材料需求", materialsLore));
        holder.bind(21, e -> GuiTextInput.promptText(SkylliaUpgrade.getInstance(), player,
                "<yellow>请输入 Lv." + level + " 的材料列表，格式 MATERIAL:数量，多个用英文逗号分隔，例如 IRON_BLOCK:64,DIAMOND:8："
                        + "\n<gray>（输入 空 或 none 清空材料要求）",
                text -> {
                    String trimmed = text.trim();
                    List<MaterialCost> parsed = new ArrayList<>();
                    if (!trimmed.equalsIgnoreCase("none") && !trimmed.equals("空")) {
                        for (String part : trimmed.split(",")) {
                            if (part.isBlank()) continue;
                            try {
                                parsed.add(MaterialCost.parse(part.trim()));
                            } catch (Exception ex) {
                                player.sendMessage(Component.text("§c跳过无效条目 '" + part.trim() + "'：" + ex.getMessage()));
                            }
                        }
                    }
                    UpgradeConfigLoader.config.setLevelMaterials(level, parsed);
                    openLevelEditor(player, level);
                },
                () -> openLevelEditor(player, level)));

        inv.setItem(31, GuiItem.back());
        holder.bind(31, e -> openList(player, 0));

        player.openInventory(inv);
    }

    private static String fmt(double value) {
        if (value == Math.floor(value)) return String.valueOf((long) value);
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
