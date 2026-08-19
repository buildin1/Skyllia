package fr.euphyllia.skylliaislandlevel.gui;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.gui.GuiItem;
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
    private static final int PAGE_SIZE = 28;
    private static final int[] BORDER = {0, 1, 2, 3, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 46, 47, 50, 51, 52};
    private static final int[] CONTENT_SLOTS = buildContentSlots();

    private ScoreDetailGui() {}

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

    public static void open(@NotNull Player player) {
        open(player, 0);
    }

    public static void open(@NotNull Player player, int page) {
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

        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) PAGE_SIZE));
        int clamped = Math.max(0, Math.min(page, totalPages - 1));

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>📊 计分表 - " + (totalPages > 1 ? "第 " + (clamped + 1) + "/" + totalPages + " 页" : island.getId())));

        for (int slot : BORDER) {
            inv.setItem(slot, GuiItem.filler());
        }

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
            inv.setItem(CONTENT_SLOTS[i - from], buildEntryItem(entry));
        }

        if (entries.isEmpty()) {
            inv.setItem(31, GuiItem.of(Material.BARRIER, "<!italic><gray>暂无计分数据",
                    List.of("<dark_gray>─────────", "<gray>先扫描一次岛屿吧</gray>")));
        }

        if (clamped > 0) {
            inv.setItem(45, GuiItem.prevPage());
            holder.bind(45, e -> open(player, clamped - 1));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(53, GuiItem.nextPage());
            holder.bind(53, e -> open(player, clamped + 1));
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
                open(player, clamped);
            });
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
        return GuiItem.of(entry.material(), "<!italic><light_purple>" + materialName(entry.material()), lore);
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
