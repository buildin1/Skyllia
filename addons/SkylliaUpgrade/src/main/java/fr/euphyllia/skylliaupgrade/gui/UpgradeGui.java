package fr.euphyllia.skylliaupgrade.gui;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliaupgrade.SkylliaUpgrade;
import fr.euphyllia.skylliaupgrade.configuration.MaterialCost;
import fr.euphyllia.skylliaupgrade.configuration.UpgradeLevelDefinition;
import fr.euphyllia.skylliaislandlevel.gui.ScoreDetailGui;
import fr.euphyllia.skylliaupgrade.manager.UpgradeManager;
import fr.euphyllia.skylliaupgrade.token.UpgradeTokenItem;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 玩家端「岛屿升级」菜单：当前等级、下一级预览（门槛达成情况）、一键升级。 */
public final class UpgradeGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int[] FILLER_SLOTS = {0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 26};

    private UpgradeGui() {}

    public static void open(@NotNull Player player) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            player.sendMessage(net.kyori.adventure.text.Component.text("§c你还没有岛屿。"));
            return;
        }

        UpgradeManager manager = SkylliaUpgrade.getInstance().getUpgradeManager();
        int currentLevel = manager.getCurrentLevel(island.getId());
        UpgradeManager.UpgradeCheck checkResult = manager.check(player, island);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 27, MM.deserialize("<light_purple>🏝 岛屿升级"));

        for (int slot : FILLER_SLOTS) {
            inv.setItem(slot, GuiItem.filler());
        }

        inv.setItem(11, GuiItem.of(Material.GRASS_BLOCK,
                "<!italic><light_purple>当前等级：Lv." + currentLevel,
                List.of("<dark_gray>─────────",
                        "<gray>岛屿边境：" + formatSize(island.getSize()) + "</gray>",
                        "<gray>成员上限：" + island.getMaxMembers() + "</gray>")));

        UpgradeLevelDefinition next = checkResult.next();
        if (next == null) {
            inv.setItem(15, GuiItem.of(Material.BARRIER,
                    "<!italic><yellow>已达最高等级",
                    List.of("<dark_gray>─────────", "<gray>没有更多可升级的等级了</gray>")));
        } else {
            Material icon = checkResult.ok() ? Material.NETHER_STAR : Material.GRAY_DYE;
            inv.setItem(15, GuiItem.of(icon,
                    "<!italic><light_purple>升级到 Lv." + next.level(),
                    buildPreviewLore(checkResult, next, island)));
            if (checkResult.ok()) {
                holder.bind(15, e -> {
                    UpgradeManager.UpgradeCheck result = manager.performUpgrade(player, island);
                    if (result.ok()) {
                        player.sendMessage(net.kyori.adventure.text.Component.text(
                                "§a岛屿升级成功！当前等级：Lv." + next.level()));
                    } else {
                        player.sendMessage(net.kyori.adventure.text.Component.text(
                                "§c升级失败，请重新检查条件。"));
                    }
                    open(player);
                });
            }
        }

        inv.setItem(13, GuiItem.of(Material.KNOWLEDGE_BOOK,
                "<!italic><aqua>📊 计分表",
                List.of("<dark_gray>─────────", "<gray>查看逐材料计分明细</gray>")));
        holder.bind(13, e -> ScoreDetailGui.open(player));

        inv.setItem(22, GuiItem.close());
        holder.bind(22, e -> player.closeInventory());

        player.openInventory(inv);
    }

    private static List<String> buildPreviewLore(UpgradeManager.UpgradeCheck result,
                                                  UpgradeLevelDefinition next, Island island) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        lore.add("<gray>边境：" + formatSize(island.getSize()) + " → " + formatSize(next.size()) + "</gray>");
        lore.add("<gray>成员上限：" + island.getMaxMembers() + " → " + next.maxMembers() + "</gray>");
        lore.add("<dark_gray>─────────");

        boolean scoreOk = result.currentScore() >= next.scoreThreshold();
        lore.add(mark(scoreOk) + " <gray>岛屿评分：" + fmt(result.currentScore()) + " / " + fmt(next.scoreThreshold()) + "</gray>");

        for (MaterialCost cost : next.materials()) {
            boolean missing = result.missingMaterials().stream().anyMatch(m -> m.material() == cost.material());
            lore.add(mark(!missing) + " <gray>" + materialName(cost.material()) + " x" + cost.amount() + "</gray>");
        }

        boolean tokenOk = result.missingTokens() <= 0;
        lore.add(mark(tokenOk) + " <gray>领地拓展令 x" + next.tokenCount() + "</gray>");

        lore.add("<dark_gray>─────────");
        lore.add(result.ok() ? "<yellow>点击升级</yellow>" : "<dark_gray>条件未满足</dark_gray>");
        return lore;
    }

    private static String mark(boolean ok) {
        return ok ? "<green>✔</green>" : "<red>✘</red>";
    }

    private static String fmt(double value) {
        if (value == Math.floor(value)) return String.valueOf((long) value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatSize(double size) {
        return fmt(size) + " 格";
    }

    private static String materialName(Material material) {
        return "<lang:" + (material.isBlock() ? "block.minecraft." : "item.minecraft.") + material.getKey().getKey() + ">";
    }
}
