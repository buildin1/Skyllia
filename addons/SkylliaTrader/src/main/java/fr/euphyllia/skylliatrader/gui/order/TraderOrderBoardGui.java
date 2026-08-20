package fr.euphyllia.skylliatrader.gui.order;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.GuiPageLayout;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.configuration.OrdersConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.OrderDefinition;
import fr.euphyllia.skylliatrader.configuration.model.OrderType;
import fr.euphyllia.skylliatrader.data.OrderSlotState;
import fr.euphyllia.skylliatrader.data.TraderIslandData;
import fr.euphyllia.skylliatrader.gui.GuiFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家端「商队订单看板」：展示岛屿当前的订单槽位，点击一个槽位触发一键结算
 * （{@link fr.euphyllia.skylliatrader.order.OrderBoardService#redeem}）。
 * <p>
 * <b>不做"往格子里摆物品"那套交互</b>——GUI 只负责展示 + 触发，真正的材料扫描/扣除/发放
 * 全部在 {@code OrderBoardService} 里完成，见该类文档。这里展示的数字是打开时的快照，
 * 理由和 {@code MerchantShopGui} 一样：结算事务本身才是权威判定，快照过期最多让玩家看到
 * 一个"过期的进度数字"，不会让限购/每日收入上限被绕过。
 * </p>
 * <p>
 * 线程模型照抄 {@code MerchantShopGui} / {@code TraderProgressGui}：先在 async 线程上把数据
 * 一次取完，再回玩家线程建界面 + openInventory。
 * </p>
 */
public final class TraderOrderBoardGui {

    private static final Logger log = LoggerFactory.getLogger(TraderOrderBoardGui.class);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private TraderOrderBoardGui() {
    }

    /** 一个订单槽位的展示数据，纯内存快照。{@code order == null} 表示空槽/订单已过期/订单已被下架。 */
    private record SlotEntry(int slotIndex, OrderDefinition order, int completions, long expiresAt) {
    }

    public static void open(@NotNull Player player) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
                if (island == null) {
                    player.sendMessage(Component.text("§c你还没有空岛，先创建一个空岛吧。"));
                    return;
                }
                // 和 MerchantLifecycleListener 打开商店 GUI 走同一套权限口径：岛主关掉某个角色的
                // merchant.interact 就该真的挡住，不能"关了商店但订单看板还能照常用"
                // （2026-08-21 审查发现：此前订单看板完全没查这个权限，是一处权限口径不一致）。
                if (!SkylliaAPI.getPermissionsManager()
                        .hasPermission(player, island, plugin.getPermissions().merchantInteract())) {
                    player.sendMessage(Component.text("§c你在这座岛上没有和游商交易的权限。"));
                    return;
                }

                TraderIslandData data = plugin.getDataService().load(island);
                List<SlotEntry> entries = buildEntries(data);

                player.getScheduler().run(plugin, t -> render(player, entries, 0), null);
            } catch (Throwable t) {
                // 理由同 TraderProgressGui/MerchantShopGui：asyncScheduler 会把异常吞成一段
                // 控制台堆栈，玩家侧「点了没反应」是最难排查的故障，必须自己兜底提示。
                log.error("为玩家 {} 打开商队订单看板失败", player.getName(), t);
                player.sendMessage(Component.text("§c读取订单数据失败，请稍后重试或联系管理员。"));
            }
        });
    }

    private static List<SlotEntry> buildEntries(TraderIslandData data) {
        List<OrderDefinition> pool = OrdersConfigLoader.config.getOrders();
        long now = System.currentTimeMillis();

        List<SlotEntry> entries = new ArrayList<>(data.orderSlots.size());
        for (OrderSlotState slot : data.orderSlots) {
            OrderDefinition order = null;
            if (slot.orderId != null && !(slot.expiresAt > 0 && now > slot.expiresAt)) {
                order = findOrder(pool, slot.orderId);
            }
            int completions = order != null ? data.completionCount(order.id()) : 0;
            entries.add(new SlotEntry(slot.slotIndex, order, completions, slot.expiresAt));
        }
        return entries;
    }

    private static OrderDefinition findOrder(List<OrderDefinition> pool, String normalizedId) {
        for (OrderDefinition order : pool) {
            if (order.id().equals(normalizedId)) return order;
        }
        return null;
    }

    private static void render(Player player, List<SlotEntry> entries, int page) {
        int totalPages = GuiPageLayout.totalPages(entries.size());
        int clamped = GuiPageLayout.clampPage(page, totalPages);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        String title = "📦 商队订单看板" + (totalPages > 1 ? " - 第 " + (clamped + 1) + "/" + totalPages + " 页" : "");
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>" + title));

        GuiPageLayout.fillBorder(inv);

        int from = clamped * GuiPageLayout.PAGE_SIZE;
        int to = Math.min(from + GuiPageLayout.PAGE_SIZE, entries.size());
        for (int i = from; i < to; i++) {
            SlotEntry entry = entries.get(i);
            int guiSlot = GuiPageLayout.contentSlot(i - from);
            inv.setItem(guiSlot, buildItem(entry));
            if (entry.order() != null) {
                SkylliaTrader plugin = SkylliaTrader.getInstance();
                if (plugin != null) {
                    holder.bind(guiSlot, e -> plugin.getOrderBoardService().redeem(player, entry.slotIndex()));
                }
            }
        }

        if (entries.isEmpty()) {
            inv.setItem(31, GuiItem.of(Material.BARRIER, "<!italic><gray>这座岛还没有订单槽位",
                    List.of("<dark_gray>─────────", "<gray>请稍后重新打开，或联系管理员</gray>")));
        }

        if (clamped > 0) {
            inv.setItem(GuiPageLayout.SLOT_PREV_PAGE, GuiItem.prevPage());
            holder.bind(GuiPageLayout.SLOT_PREV_PAGE, e -> openPage(player, entries, clamped - 1));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(GuiPageLayout.SLOT_NEXT_PAGE, GuiItem.nextPage());
            holder.bind(GuiPageLayout.SLOT_NEXT_PAGE, e -> openPage(player, entries, clamped + 1));
        }

        inv.setItem(GuiPageLayout.SLOT_HEADER, GuiItem.of(Material.PAPER, "<!italic><yellow>订单说明",
                List.of("<dark_gray>─────────",
                        "<gray>点击一个订单：服务端自动扫描你的背包</gray>",
                        "<gray>材料够就直接扣除并发放奖励，不够会提示缺口</gray>",
                        "<gray>材料可以来自背包里的多组堆叠，会自动合并计算</gray>",
                        "<dark_gray>─────────",
                        "<gray>声望只能通过完成订单获得，不会衰减</gray>")));

        inv.setItem(GuiPageLayout.SLOT_CLOSE, GuiItem.close());
        holder.bind(GuiPageLayout.SLOT_CLOSE, e -> player.closeInventory());

        player.openInventory(inv);
    }

    /** 翻页：数据已经在内存里，不重新查库，只需要延迟一 tick 换界面（理由同 MerchantShopGui）。 */
    private static void openPage(Player player, List<SlotEntry> entries, int page) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;
        player.getScheduler().run(plugin, t -> render(player, entries, page), null);
    }

    private static ItemStack buildItem(SlotEntry entry) {
        OrderDefinition order = entry.order();
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");

        if (order == null) {
            lore.add("<gray>游商正在准备新订单，请稍后再来看看</gray>");
            return GuiItem.of(Material.BARRIER, "<!italic><gray>（空槽位）", lore);
        }

        lore.add("<gray>需要交付：<white>" + GuiFormat.describeItems(order.takeItems()) + "</white></gray>");
        if (order.type() == OrderType.MONEY) {
            lore.add("<gray>奖励：<white>" + GuiFormat.fmt(order.price()) + " 金币</white>"
                    + (order.rewardReputation() > 0 ? " <light_purple>+" + order.rewardReputation() + " 声望</light_purple>" : "") + "</gray>");
        } else {
            lore.add("<gray>奖励：<white>" + GuiFormat.describeItems(order.giveItems()) + "</white>"
                    + (order.rewardReputation() > 0 ? " <light_purple>+" + order.rewardReputation() + " 声望</light_purple>" : "") + "</gray>");
        }

        if (order.redeemLimitPerIsland() > 0) {
            lore.add("<gray>本岛已完成：<white>" + entry.completions() + " / " + order.redeemLimitPerIsland() + "</white>（终身限购）</gray>");
        } else {
            lore.add("<gray>本岛已完成：<white>" + entry.completions() + "</white> 次（不限）</gray>");
        }

        if (entry.expiresAt() > 0) {
            long remainMinutes = Math.max(0, (entry.expiresAt() - System.currentTimeMillis()) / 60_000L);
            lore.add("<gray>距离下次换单：<white>约 " + (remainMinutes / 60) + " 小时 " + (remainMinutes % 60) + " 分钟</white></gray>");
        }

        lore.add("<dark_gray>─────────");
        lore.add("<yellow>点击</yellow><gray> 一键结算（材料够就自动扣除并发放奖励）</gray>");

        return GuiItem.of(order.iconMaterial(), "<!italic><white>" + order.displayName(), lore);
    }
}
