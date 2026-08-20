package fr.euphyllia.skylliatrader.gui.admin;

import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.GuiPageLayout;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliatrader.gui.GuiFormat;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.configuration.OrdersConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.ItemAmount;
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
 * T1 阶段只做只读展示 + 分页。新增/编辑/删除三个按钮已经摆在界面上（见 {@link #render}
 * 里的 4/49 两个槽位），但点击后只是提示"该功能将在 T3 加入"，不做任何实际操作——
 * 原因见类顶部下面这段：
 * </p>
 * <p>
 * <b>Folia GUI 安全提醒（TODO，留给 T3）</b>：一旦要做"编辑订单的物品字段"这种把
 * material 摆进虚拟格子代表配置数据的界面，必须在 {@code InventoryCloseEvent} 以及一切
 * 异常关闭路径（玩家掉线、被传送、插件被重载）上做原子化处理——格子里的物品要么正确
 * 写回 orders.toml 后清空，要么完整退回操作者背包，绝不能出现物品凭空消失或被复制。
 * 当前 {@code SkylliaGuiHolder}/{@code GuiListener} 默认会取消所有点击和拖拽（见
 * {@code GuiListener#onClick}），只要 T3 的编辑界面不主动把 giveItems/takeItems
 * 摆成"真物品放进格子"的交互方式，就不会触发这个风险；如果要做，必须仿
 * {@code GuiTextInput} 的思路专门处理好 {@code InventoryCloseEvent} 的原子写回/退回。
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
            inv.setItem(GuiPageLayout.contentSlot(i - from), buildOrderItem(orders.get(i)));
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

        // 新增/编辑/删除：T1 先摆出来，点了只提示"T3 实现"，不做任何写入（见类注释）。
        inv.setItem(GuiPageLayout.SLOT_HEADER, GuiItem.placeholder(Material.EMERALD, "+ 新增订单", "在 GUI 里直接新建一条订单"));
        holder.bind(GuiPageLayout.SLOT_HEADER, e -> player.sendMessage(net.kyori.adventure.text.Component.text(
                "§7新增/编辑订单的 GUI 将在 T3 阶段加入，目前请直接编辑 config/orders.toml 后 /skyllia reload。")));

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
        lore.add("<gray>岛屿支付：<white>" + describeItems(order.takeItems()) + "</white></gray>");
        if (order.type() == OrderType.MONEY) {
            lore.add("<gray>商队支付：<white>" + GuiFormat.fmt(order.price()) + " 金币</white>（整单总价）</gray>");
        } else {
            lore.add("<gray>商队支付：<white>" + describeItems(order.giveItems()) + "</white></gray>");
        }

        lore.add("<gray>声望奖励：<white>" + order.rewardReputation() + "</white></gray>");
        lore.add("<gray>刷新权重：<white>" + order.weight() + "</white></gray>");
        lore.add("<gray>终身限购：<white>" + (order.redeemLimitPerIsland() == 0 ? "不限" : order.redeemLimitPerIsland() + " 单") + "</white>（按岛屿计）</gray>");
        lore.add("<gray>门槛：<white>等级≥" + order.requiredLevelMin() + " 声望≥" + order.requiredReputationMin() + "</white></gray>");
        lore.add("<dark_gray>─────────");
        lore.add("<dark_gray>编辑/删除将在 T3 加入</dark_gray>");

        return GuiItem.of(order.iconMaterial(), "<!italic><light_purple>" + order.displayName(), lore);
    }

    private static String describeItems(List<ItemAmount> items) {
        if (items.isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            ItemAmount item = items.get(i);
            sb.append(item.material()).append(" x").append(item.amount());
        }
        return sb.toString();
    }
}
