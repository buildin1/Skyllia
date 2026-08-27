package fr.euphyllia.skylliaislandlevel.gui;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.GuiPageLayout;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliaislandlevel.SkylliaIslandLevel;
import fr.euphyllia.skylliaislandlevel.configuration.IslandLevelConfigLoader;
import fr.euphyllia.skylliaislandlevel.manager.LevelManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 玩家端「计分表」菜单：展示岛屿最近一次扫描的逐材料计数与各自的分数贡献，
 * 让玩家看懂分数是怎么算出来的，而不只是一个数字。
 */
public final class ScoreDetailGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** 分页版式（边框/内容槽位/翻页数学）统一走核心的 {@link GuiPageLayout}，不再各自抄一份。 */
    private static final int PAGE_SIZE = GuiPageLayout.PAGE_SIZE;

    private ScoreDetailGui() {}

    public static void open(@NotNull Player player) {
        open(player, 0);
    }

    public static void open(@NotNull Player player, int page) {
        open(player, page, null);
    }

    /**
     * @param backAction 「返回」按钮的动作；{@code null} 表示没有上一层（命令直接打开时），
     *                   不显示返回按钮。分页、重新扫描都会把它原样带下去，保证回程不丢。
     *                   用回调而不是直接引用上一层 GUI：调用方是 SkylliaUpgrade，依赖方向
     *                   是 Upgrade → IslandValue，这里反向 import 会成环。
     */
    public static void open(@NotNull Player player, int page, @org.jetbrains.annotations.Nullable Runnable backAction) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            player.sendMessage(Component.text("§c你还没有空岛。"));
            return;
        }

        LevelManager manager = SkylliaIslandLevel.getInstance().getLevelManager();
        Map<Material, Double> blockValues = IslandLevelConfigLoader.config.getBlockValues();
        Map<Material, Integer> counts = manager.getLastCounts(island.getId());

        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : counts.entrySet()) {
            Double value = blockValues.get(e.getKey());
            if (value == null || value <= 0) continue;
            int raw = e.getValue();
            if (raw <= 0) continue;
            int cap = IslandLevelConfigLoader.config.getBlockCap(e.getKey());
            int counted = cap < 0 ? raw : Math.min(raw, cap);
            entries.add(new Entry(e.getKey(), raw, counted, cap, value, value * counted));
        }
        entries.sort(Comparator.comparingDouble(Entry::contribution).reversed());

        int totalPages = GuiPageLayout.totalPages(entries.size());
        int clamped = GuiPageLayout.clampPage(page, totalPages);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>📊 计分表 - " + (totalPages > 1 ? "第 " + (clamped + 1) + "/" + totalPages + " 页" : island.getId())));

        GuiPageLayout.fillBorder(inv);

        double score = manager.getStoredScore(island);
        long level = manager.getStoredLevel(island);
        boolean scanning = manager.isScanning(island.getId());
        inv.setItem(4, GuiItem.of(Material.NETHER_STAR,
                "<!italic><light_purple>当前评分：" + fmt(score),
                List.of("<dark_gray>─────────",
                        "<gray>等级：Lv." + level + "</gray>",
                        "<gray>计分材料种类：" + entries.size() + "</gray>",
                        "<dark_gray>─────────",
                        scanning ? "<yellow>扫描进行中…</yellow>" : "<gray>下方为最近一次扫描结果</gray>")));

        int from = clamped * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, entries.size());
        for (int i = from; i < to; i++) {
            Entry entry = entries.get(i);
            inv.setItem(GuiPageLayout.contentSlot(i - from), buildEntryItem(entry));
        }

        if (entries.isEmpty()) {
            inv.setItem(31, GuiItem.of(Material.BARRIER, "<!italic><gray>暂无计分数据",
                    List.of("<dark_gray>─────────", "<gray>先扫描一次岛屿吧</gray>")));
        }

        if (clamped > 0) {
            inv.setItem(45, GuiItem.prevPage());
            holder.bind(45, e -> open(player, clamped - 1, backAction));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(53, GuiItem.nextPage());
            holder.bind(53, e -> open(player, clamped + 1, backAction));
        }

        if (!scanning) {
            inv.setItem(48, GuiItem.of(Material.SPYGLASS, "<!italic><aqua>🔄 重新扫描",
                    List.of("<dark_gray>─────────", "<gray>点击立即重新扫描本岛</gray>")));
            holder.bind(48, e -> {
                boolean started = manager.triggerScan(island, (s, l) -> player.sendMessage(
                        Component.text("§a扫描完成！分数：" + String.format(Locale.ROOT, "%.2f", s) + " | 等级：" + l)));
                if (started) {
                    player.sendMessage(Component.text("§a正在扫描你的空岛..."));
                }
                open(player, clamped, backAction);
            });
        }

        if (backAction != null) {
            inv.setItem(47, GuiItem.back());
            holder.bind(47, e -> backAction.run());
        }

        inv.setItem(49, GuiItem.close());
        holder.bind(49, e -> player.closeInventory());

        player.openInventory(inv);
    }

    private static org.bukkit.inventory.ItemStack buildEntryItem(Entry entry) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        lore.add("<gray>数量：<white>" + entry.raw() + "</white></gray>");
        if (entry.cap() >= 0 && entry.counted() < entry.raw()) {
            lore.add("<gray>计入上限：<yellow>" + entry.cap() + "</yellow>（已封顶）</gray>");
        } else if (entry.cap() >= 0) {
            lore.add("<gray>计入上限：<white>" + entry.cap() + "</white></gray>");
        }
        lore.add("<gray>单价：<white>" + fmt(entry.value()) + "</white></gray>");
        lore.add("<dark_gray>─────────");
        lore.add("<gray>贡献分数：<green>" + fmt(entry.contribution()) + "</green></gray>");
        return GuiItem.of(displayMaterialFor(entry.material()), "<!italic><light_purple>" + materialName(entry.material()), lore);
    }

    /**
     * 计分表里的材质是从「岛屿方块扫描结果」直接拿来的，有些方块状态没有对应的物品形态
     * （比如灌了水/岩浆/细雪的炼药锅——只有空炼药锅 {@code CAULDRON} 才是能拿在手里的物品，
     * 这三种"已加内容物"的方块状态直接 {@code new ItemStack(material)} 会抛
     * {@code IllegalArgumentException}，2026-08-21 生产服日志实测命中）。计分材料表有 800
     * 多行、大概率是批量生成的，这里不逐条审计，直接对"不是物品"的材质统一兜底成一个可展示的
     * 替代图标——只影响这一格图标长什么样，名字/lore 仍然显示原始材质名，计分逻辑完全不碰。
     */
    private static Material displayMaterialFor(Material material) {
        if (material.isItem()) return material;
        return switch (material) {
            case WATER_CAULDRON, LAVA_CAULDRON, POWDER_SNOW_CAULDRON -> Material.CAULDRON;
            default -> Material.BARRIER;
        };
    }

    private static String materialName(Material material) {
        return "<lang:" + (material.isBlock() ? "block.minecraft." : "item.minecraft.") + material.getKey().getKey() + ">";
    }

    private static String fmt(double value) {
        if (value == Math.floor(value)) return String.valueOf((long) value);
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private record Entry(Material material, int raw, int counted, int cap, double value, double contribution) {}
}
