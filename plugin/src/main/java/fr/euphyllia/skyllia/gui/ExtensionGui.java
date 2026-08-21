package fr.euphyllia.skyllia.gui;

import fr.euphyllia.skyllia.Skyllia;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 主菜单「第 2 页」：addon 通过 {@link GuiExtensionRegistry} 注册的扩展入口，外加两个
 * 核心自带的固定功能——「加入其他岛屿」和「群系修改」选区工具。
 * <p>
 * 版式统一走 {@link GuiPageLayout}：内容区 28 格铺 addon 入口（可翻页，翻页只滚动这个
 * 列表本身），槽 47/50 是不随列表翻页滚动的固定按钮，槽 51 对称于第 1 页「下一页」的
 * 位置，点击返回第 1 页，槽 49 整个关闭菜单。
 * </p>
 * <p>
 * 类名沿用 {@code ExtensionGui}（历史上这里就是「扩展功能」子菜单，只是从子菜单变成了
 * 主菜单第 2 页）——仓库里只有 {@link MainGui} 引用这个类，改名字反而增加不必要的 diff。
 * </p>
 */
public final class ExtensionGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** 「加入其他岛屿」固定按钮槽位（不随 addon 列表翻页滚动）。 */
    private static final int SLOT_JOIN_ISLAND = 47;
    /** 「群系修改」固定按钮槽位。 */
    private static final int SLOT_BIOME_TOOL = 50;
    /** 「返回第 1 页」，对称于第 1 页「下一页」按钮所在的槽位。 */
    private static final int SLOT_BACK_TO_PAGE1 = 51;

    private ExtensionGui() {}

    public static void open(@NotNull Player player, int page) {
        List<GuiExtensionEntry> all = GuiExtensionRegistry.entries().stream()
                .filter(e -> e.isVisibleFor(player))
                .toList();

        int totalPages = GuiPageLayout.totalPages(all.size());
        int clampedPage = GuiPageLayout.clampPage(page, totalPages);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54,
                MM.deserialize("<light_purple>🧩 扩展与工具 <gray>(" + (clampedPage + 1) + "/" + totalPages + ")"));

        GuiPageLayout.fillBorder(inv);

        inv.setItem(GuiPageLayout.SLOT_HEADER, GuiItem.of(Material.NETHER_STAR,
                "<!italic><light_purple>🧩 扩展与工具",
                List.of("<dark_gray>─────────",
                        "<gray>附属模块注册的功能入口</gray>",
                        "<gray>以及核心自带的辅助工具</gray>")));

        if (all.isEmpty()) {
            inv.setItem(31, GuiItem.of(Material.BARRIER,
                    "<!italic><red>暂无扩展功能",
                    List.of("<dark_gray>─────────",
                            "<gray>没有已安装的 addon 注册扩展入口</gray>")));
        } else {
            int fromIndex = clampedPage * GuiPageLayout.PAGE_SIZE;
            int toIndex = Math.min(fromIndex + GuiPageLayout.PAGE_SIZE, all.size());

            for (int i = fromIndex; i < toIndex; i++) {
                GuiExtensionEntry entry = all.get(i);
                int slot = GuiPageLayout.contentSlot(i - fromIndex);

                List<String> lore = new ArrayList<>();
                lore.add("<dark_gray>─────────");
                lore.addAll(entry.lore());
                lore.add("<dark_gray>─────────");
                lore.add("<yellow>点击打开</yellow>");

                inv.setItem(slot, GuiItem.of(entry.icon(), "<!italic>" + entry.displayName(), lore));
                holder.bind(slot, e -> entry.onClick().accept(player));
            }
        }

        // 列表自身的翻页（只滚动 addon 入口，不影响下面两个固定按钮）
        if (clampedPage > 0) {
            inv.setItem(GuiPageLayout.SLOT_PREV_PAGE, GuiItem.prevPage());
            holder.bind(GuiPageLayout.SLOT_PREV_PAGE, e -> open(player, clampedPage - 1));
        }
        if (clampedPage < totalPages - 1) {
            inv.setItem(GuiPageLayout.SLOT_NEXT_PAGE, GuiItem.nextPage());
            holder.bind(GuiPageLayout.SLOT_NEXT_PAGE, e -> open(player, clampedPage + 1));
        }

        // 固定按钮：加入其他岛屿
        inv.setItem(SLOT_JOIN_ISLAND, GuiItem.of(Material.OAK_DOOR,
                "<!italic><light_purple>🌐 加入其他岛屿",
                List.of("<dark_gray>─────────",
                        "<gray>输入岛屿 ID 或玩家名，前往其空岛</gray>",
                        "<dark_gray>─────────",
                        "<yellow>点击输入</yellow>")));
        holder.bind(SLOT_JOIN_ISLAND, e -> GuiTextInput.promptText(Skyllia.getInstance(), player,
                "<light_purple>请在聊天栏输入要访问的岛屿 ID 或玩家名：",
                input -> {
                    String trimmed = input.trim();
                    if (trimmed.isEmpty()) {
                        player.sendMessage(Component.text("§c输入不能为空。"));
                        open(player, clampedPage);
                        return;
                    }
                    // 已经在玩家自己的 region 调度线程上（GuiTextInput 回调保证），可直接执行命令。
                    player.performCommand("is visit " + trimmed);
                },
                () -> open(player, clampedPage)));

        // 固定按钮：群系修改（选区工具）
        inv.setItem(SLOT_BIOME_TOOL, GuiItem.of(Material.WOODEN_HOE,
                "<!italic><light_purple>🧭 群系修改",
                List.of("<dark_gray>─────────",
                        "<gray>获得选区工具，圈出你岛屿内的一片区域</gray>",
                        "<gray>并批量修改其生物群系</gray>",
                        "<dark_gray>─────────",
                        "<yellow>点击领取工具</yellow>")));
        holder.bind(SLOT_BIOME_TOOL, e -> {
            player.closeInventory();
            BiomeSelectionToolItem.giveTo(player);
            player.sendMessage(Component.text("§d[群系选区] §f已获得群系选区工具（木锄头）：" +
                    "§e左键§f一个方块记录起点，§e右键§f另一个方块完成选区。"));
            player.sendMessage(Component.text("§7选区只在你自己岛屿的领地范围内生效，超出部分会被自动裁剪。"));
        });

        // 对称于第 1 页「下一页」的位置：返回第 1 页
        inv.setItem(SLOT_BACK_TO_PAGE1, GuiItem.of(Material.ARROW, "<!italic><gray>◀ 返回第 1 页"));
        holder.bind(SLOT_BACK_TO_PAGE1, e -> MainGui.open(player));

        inv.setItem(GuiPageLayout.SLOT_CLOSE, GuiItem.close());
        holder.bind(GuiPageLayout.SLOT_CLOSE, e -> player.closeInventory());

        player.openInventory(inv);
    }
}
