package fr.euphyllia.skylliatrader.gui.admin;

import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.GuiPageLayout;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliatrader.gui.GuiFormat;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.configuration.OrdersConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.OrderDefinition;
import fr.euphyllia.skylliatrader.configuration.model.OrderType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理端「订单列表」：分页展示 orders.toml 里全部 {@code [[order]]} 条目。
 * <p>
 * T1 阶段只做只读展示 + 分页；<b>T3 第二波</b>补上了新增/编辑/删除的真正入口——
 * 点"+ 新增订单"或点开任意一条已有订单都会跳转到 {@link TraderOrderEditGui} 详情编辑页，
 * 具体的增删改逻辑、物品格子的 Folia 安全处理见该类的类注释。
 * </p>
 * <p>
 * 版式（边框/内容槽位/翻页数学）走核心的 {@link GuiPageLayout}——这套代码原本在这里和
 * {@code ScoreDetailGui} 里各有一份一字不差的复制品。线程模型见 {@code TraderProgressGui}。
 * </p>
 */
public final class TraderOrderListGui {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private TraderOrderListGui() {
    }

    public static void open(@NotNull Player player, int page) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;
        // 订单表是内存里的 volatile 快照，不读库；只需要把 openInventory 调回玩家线程。
        player.getScheduler().run(plugin, t -> render(player, page), null);
    }

    private static void render(@NotNull Player player, int page) {
        List<OrderDefinition> orders = OrdersConfigLoader.config.getOrders();

        int totalPages = GuiPageLayout.totalPages(orders.size());
        int clamped = GuiPageLayout.clampPage(page, totalPages);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>📦 商队订单列表"
                + (totalPages > 1 ? " - 第 " + (clamped + 1) + "/" + totalPages + " 页" : "")));

        GuiPageLayout.fillBorder(inv);

        int from = clamped * GuiPageLayout.PAGE_SIZE;
        int to = Math.min(from + GuiPageLayout.PAGE_SIZE, orders.size());
        for (int i = from; i < to; i++) {
            OrderDefinition order = orders.get(i);
            int slot = GuiPageLayout.contentSlot(i - from);
            inv.setItem(slot, buildOrderItem(order));
            holder.bind(slot, e -> TraderOrderEditGui.open(player, TraderOrderEditGui.OrderDraft.from(order)));
        }

        if (orders.isEmpty()) {
            inv.setItem(31, GuiItem.of(Material.BARRIER, "<!italic><gray>orders.toml 里还没有任何订单",
                    List.of("<dark_gray>─────────", "<gray>去 config/orders.toml 添加 [[order]]</gray>")));
        }

        // 翻页统一走 open()：它已经把 openInventory 调度到下一 tick 了。
        // 直接调 render() 也能工作，但那等于在 InventoryClickEvent 里同步开新界面，
        // 是 Bukkit 一贯不推荐的做法（事件还没处理完就换掉玩家的界面）。
        if (clamped > 0) {
            inv.setItem(GuiPageLayout.SLOT_PREV_PAGE, GuiItem.prevPage());
            holder.bind(GuiPageLayout.SLOT_PREV_PAGE, e -> open(player, clamped - 1));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(GuiPageLayout.SLOT_NEXT_PAGE, GuiItem.nextPage());
            holder.bind(GuiPageLayout.SLOT_NEXT_PAGE, e -> open(player, clamped + 1));
        }

        inv.setItem(GuiPageLayout.SLOT_HEADER, GuiItem.of(Material.EMERALD, "<!italic><green>+ 新增订单",
                List.of("<dark_gray>─────────", "<gray>在 GUI 里直接新建一条订单</gray>",
                        "<gray>不点“确认保存”退出的话不会写进 orders.toml</gray>")));
        holder.bind(GuiPageLayout.SLOT_HEADER, e -> TraderOrderEditGui.open(player, TraderOrderEditGui.OrderDraft.fresh()));

        inv.setItem(GuiPageLayout.SLOT_CLOSE, GuiItem.back());
        holder.bind(GuiPageLayout.SLOT_CLOSE, e -> TraderAdminMainGui.open(player));

        player.openInventory(inv);
    }

    private static ItemStack buildOrderItem(OrderDefinition order) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        lore.add("<gray>id：<white>" + order.id() + "</white></gray>");
        lore.add("<gray>状态：" + (order.enabled() ? "<green>启用</green>" : "<red>停用</red>"));
        lore.add("<gray>类型：<white>" + (order.type() == OrderType.MONEY ? "金币收购" : "物物交换") + "</white></gray>");

        // 两种类型都用 take-items 描述"岛屿交出去什么"，区别只在商队怎么付账。
        lore.add("<gray>岛屿支付：<white>" + GuiFormat.describeItems(order.takeItems()) + "</white></gray>");
        if (order.type() == OrderType.MONEY) {
            lore.add("<gray>商队支付：<white>" + GuiFormat.fmt(order.price()) + " 金币</white>（整单总价）</gray>");
        } else {
            lore.add("<gray>商队支付：<white>" + GuiFormat.describeItems(order.giveItems()) + "</white></gray>");
        }

        lore.add("<gray>声望奖励：<white>" + order.rewardReputation() + "</white></gray>");
        lore.add("<gray>刷新权重：<white>" + order.weight() + "</white></gray>");
        lore.add("<gray>终身限购：<white>" + (order.redeemLimitPerIsland() == 0 ? "不限" : order.redeemLimitPerIsland() + " 单") + "</white>（按岛屿计）</gray>");
        lore.add("<gray>门槛：<white>等级≥" + order.requiredLevelMin() + " 声望≥" + order.requiredReputationMin() + "</white></gray>");
        lore.add("<dark_gray>─────────");
        lore.add("<yellow>点击</yellow> <gray>进入编辑</gray>");

        return GuiItem.of(order.iconMaterial(), "<!italic><light_purple>" + order.displayName(), lore);
    }
}
