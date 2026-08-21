package fr.euphyllia.skylliatrader.gui.shop;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.GuiPageLayout;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.bridge.IslandLevelBridge;
import fr.euphyllia.skylliatrader.configuration.ShopConfigLoader;
import fr.euphyllia.skylliatrader.configuration.ShopConfigManager;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.GuidebookConfig;
import fr.euphyllia.skylliatrader.configuration.model.ShopItemDefinition;
import fr.euphyllia.skylliatrader.configuration.model.ShopPurchaseLimitPeriod;
import fr.euphyllia.skylliatrader.configuration.model.TrackTiers;
import fr.euphyllia.skylliatrader.data.PurchaseCounter;
import fr.euphyllia.skylliatrader.data.TraderIslandData;
import fr.euphyllia.skylliatrader.gui.GuiFormat;
import fr.euphyllia.skylliatrader.merchant.MerchantOrigin;
import fr.euphyllia.skylliatrader.shop.PurchaseMode;
import fr.euphyllia.skylliatrader.shop.ShopEconomics;
import fr.euphyllia.skylliatrader.shop.ShopVisibility;
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
 * 玩家端「商店」GUI：全自定义界面，不用原版 Merchant（HANDOFF 6.7）。
 * <p>
 * 货架是<b>固定全目录</b>，不做随机抽取（HANDOFF 6.6 的 T3 拍板）：这里展示的每一项都来自
 * {@code shop.toml} 的固定配置，价格是配置里的一口价（乘以消费折扣）。
 * </p>
 *
 * <h2>两种来源展示不同的货架</h2>
 * <ul>
 *   <li>{@link MerchantOrigin#NATURAL} —— 只展示 {@code natural-visible = true} 的商品
 *       + 说明书，全部正常可点，没有"锁定预告"这回事（路人商人本来就是固定的基础生活池）；</li>
 *   <li>{@link MerchantOrigin#CREDENTIAL} —— 按 {@link ShopVisibility#classify} 三态展示：
 *       已解锁的正常摆出来，紧邻下一档的灰显+lore 提示还差多少，更远的档位直接不出现
 *       （避免一次性甩一整面墙的锁头吓退新人，见 HANDOFF 6.7 的教学价值设计）。</li>
 * </ul>
 *
 * <h2>已知的简化：GUI 内数据是打开时的快照</h2>
 * <p>
 * 翻页复用同一份内存里的 {@code entries} 列表，不重新查库——省掉了每次翻页都异步读库的开销，
 * 代价是"今日已购 x/y"这类数字在同一次打开会话里不会随purchase()动态刷新；玩家买完东西后
 * 要重新打开菜单才能看到最新数字。这是刻意的取舍：<b>购买事务本身（{@code ShopPurchaseService}）
 * 才是权威判定</b>，GUI 上的数字只是展示，不会因为没实时刷新而导致买超限购——`
 * 购买时服务端会按最新数据重新校验一遍，GUI 快照过期最多让玩家看到一个"过期的今日已购数字"，
 * 不会让限购被绕过。
 * </p>
 *
 * <h2>线程模型</h2>
 * <p>
 * 调用方（{@code MerchantLifecycleListener}）已经在 {@code Bukkit.getAsyncScheduler()} 上，
 * 所以本类的 {@link #openFromAsync} <b>假定调用者已经在 async 线程</b>，直接读库不用再跳一次；
 * 读完数据后用 {@code player.getScheduler().run} 跳回玩家线程建界面 + openInventory
 * （见 {@code TraderProgressGui} 的同一套线程模型说明）。翻页只是内存操作，同样要经过
 * {@code player.getScheduler().run} 延迟一 tick 再开新界面，理由同 {@code TraderOrderListGui}：
 * 不在 {@code InventoryClickEvent} 处理过程中同步换界面。
 * </p>
 */
public final class MerchantShopGui {

    private static final Logger log = LoggerFactory.getLogger(MerchantShopGui.class);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private MerchantShopGui() {
    }

    /** 一条货架条目的展示数据，纯内存快照，见类文档"已知的简化"一节。 */
    private record ShopEntry(String id, Material material, String displayName, boolean locked, String lockHint,
                             double unitPrice, double basePrice, boolean limited, int remaining,
                             int effectiveLimit, ShopPurchaseLimitPeriod period) {
    }

    /**
     * 打开商店。<b>假定调用者已经在 async 线程上</b>（见类文档线程模型一节）。
     *
     * @param island 玩家所在的岛屿（调用方已经解析过，不重复查一次）
     * @param origin 这个游商的来源，决定货架范围
     */
    public static void openFromAsync(@NotNull Player player, @NotNull Island island,
                                     @NotNull MerchantOrigin origin, int page) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;
        try {
            TraderIslandData data = plugin.getDataService().load(island);
            long islandLevel = IslandLevelBridge.isAvailable() ? IslandLevelBridge.getIslandLevel(island) : 0L;
            TrackTiers tiers = TraderConfigLoader.config.getTrackTiers();
            List<ShopEntry> entries = buildEntries(origin, data, islandLevel, tiers);

            player.getScheduler().run(plugin, t -> render(player, island, origin, entries, page), null);
        } catch (Throwable t) {
            // 理由同 TraderProgressGui：asyncScheduler 会把异常吞成一段控制台堆栈，
            // 玩家侧「点了没反应」是最难排查的故障，这里必须自己兜底提示。
            log.error("为玩家 {} 打开游商商店失败", player.getName(), t);
            player.sendMessage(Component.text("§c读取商店数据失败，请稍后重试或联系管理员。"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 构建货架条目
    // ══════════════════════════════════════════════════════════════════════

    private static List<ShopEntry> buildEntries(MerchantOrigin origin, TraderIslandData data,
                                                 long islandLevel, TrackTiers tiers) {
        List<ShopEntry> entries = new ArrayList<>();

        if (origin == MerchantOrigin.NATURAL) {
            // 路人商人：固定基础池，没有锁定预告这回事。
            for (ShopItemDefinition item : ShopConfigLoader.config.getItems()) {
                if (item.naturalVisible()) {
                    entries.add(buildUnlockedEntry(item, data, tiers, islandLevel));
                }
            }
            GuidebookConfig guide = TraderConfigLoader.config.getGuidebook();
            if (guide != null && guide.enabled()) {
                entries.add(buildGuidebookEntry(guide, data));
            }
            return entries;
        }

        // 凭证游商：四轨全开，按三态展示（已解锁/预告下一档/隐藏）。
        for (ShopItemDefinition item : ShopConfigLoader.config.getItems()) {
            ShopVisibility.State state = ShopVisibility.classify(item, data, islandLevel, tiers);
            switch (state) {
                case UNLOCKED -> entries.add(buildUnlockedEntry(item, data, tiers, islandLevel));
                case PREVIEW -> entries.add(buildPreviewEntry(item, data, islandLevel));
                case HIDDEN -> { /* 不展示 */ }
            }
        }
        // 说明书只在路人商人货架上出现（MerchantOfferScope.credential().guidebook() == false），
        // 凭证游商不额外卖——它是新手引导道具，凭证游商的玩家早就不需要这本书了。
        return entries;
    }

    private static ShopEntry buildUnlockedEntry(ShopItemDefinition item, TraderIslandData data,
                                                 TrackTiers tiers, long islandLevel) {
        double discount = ShopEconomics.discountRate(data.totalSpent, tiers.spending());
        double unitPrice = ShopEconomics.discountedUnitPrice(item.price(), discount);

        boolean limited = item.purchaseLimitPeriod().limited();
        int remaining = 0;
        int effectiveLimit = 0;
        if (limited) {
            int base = ShopEconomics.baseLimitCountFor(item, islandLevel);
            int bonus = ShopEconomics.limitBonus(data.totalSpent, tiers.spending());
            effectiveLimit = base + bonus;
            PurchaseCounter counter = data.shopPurchaseCounts.get(item.id());
            int used = ShopEconomics.currentWindowCount(counter, item.purchaseLimitPeriod());
            remaining = Math.max(0, effectiveLimit - used);
        }

        return new ShopEntry(item.id(), item.material(), item.displayName(), false, null,
                unitPrice, item.price(), limited, remaining, effectiveLimit, item.purchaseLimitPeriod());
    }

    private static ShopEntry buildPreviewEntry(ShopItemDefinition item, TraderIslandData data, long islandLevel) {
        long remainingToUnlock = ShopVisibility.remainingToUnlock(item, data, islandLevel);
        // ⚠️ 这里只能用 MiniMessage 标签，绝对不能混 §x 这类老式颜色码。
        // 这段文案会被 buildItem 拼进 lore 再交给 GuiItem.of → MiniMessage.deserialize，
        // 而服务端运行时的 adventure-text-minimessage 是 5.x，遇到 § 会直接抛
        // ParsingException（4.x 只是打警告，本仓库编译依赖恰好是 4.26.1，所以编译期发现不了）。
        // 一旦抛出，整个 render() 中断 → 商店界面根本打不开，玩家侧表现为"右键游商没反应"，
        // 异常只会静默落在控制台。2026-08-21 在本地测试服实测复现并定位。
        String hint = "还差 <white>" + remainingToUnlock + "</white> <gray>"
                + ShopVisibility.trackLabel(item.unlockTrack()) + " 解锁</gray>";
        return new ShopEntry(item.id(), item.material(), item.displayName(), true, hint,
                0, item.price(), false, 0, 0, item.purchaseLimitPeriod());
    }

    private static ShopEntry buildGuidebookEntry(GuidebookConfig guide, TraderIslandData data) {
        boolean limited = guide.purchaseLimit() > 0;
        ShopPurchaseLimitPeriod period = limited ? ShopPurchaseLimitPeriod.LIFETIME : ShopPurchaseLimitPeriod.NONE;
        int remaining = 0;
        int effectiveLimit = 0;
        if (limited) {
            // 说明书的限购加成沿用和普通商品一样的规则（ShopPurchaseService#reserve 对所有
            // 限购商品统一套用消费折扣轨的加成，没有对说明书特殊剔除），这里的展示必须和
            // 真正的购买事务保持一致，否则玩家会看到一个和实际限购对不上的数字。
            effectiveLimit = guide.purchaseLimit();
            PurchaseCounter counter = data.shopPurchaseCounts.get(ShopConfigManager.GUIDEBOOK_RESERVED_ID);
            int used = ShopEconomics.currentWindowCount(counter, period);
            remaining = Math.max(0, effectiveLimit - used);
        }
        return new ShopEntry(ShopConfigManager.GUIDEBOOK_RESERVED_ID, guide.material(), "游商指南（说明书）",
                false, null, guide.price(), guide.price(), limited, remaining, effectiveLimit, period);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 渲染
    // ══════════════════════════════════════════════════════════════════════

    private static void render(Player player, Island island, MerchantOrigin origin,
                               List<ShopEntry> entries, int page) {
        int totalPages = GuiPageLayout.totalPages(entries.size());
        int clamped = GuiPageLayout.clampPage(page, totalPages);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        String title = (origin == MerchantOrigin.NATURAL ? "🛒 游商货架" : "🛒 商队货架")
                + (totalPages > 1 ? " - 第 " + (clamped + 1) + "/" + totalPages + " 页" : "");
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>" + title));

        GuiPageLayout.fillBorder(inv);

        int from = clamped * GuiPageLayout.PAGE_SIZE;
        int to = Math.min(from + GuiPageLayout.PAGE_SIZE, entries.size());
        for (int i = from; i < to; i++) {
            ShopEntry entry = entries.get(i);
            int slot = GuiPageLayout.contentSlot(i - from);
            inv.setItem(slot, buildItem(entry));
            if (!entry.locked()) {
                holder.bind(slot, event -> {
                    PurchaseMode mode = event.isRightClick() ? PurchaseMode.FILL
                            : (event.isShiftClick() ? PurchaseMode.QUINTUPLE : PurchaseMode.SINGLE);
                    SkylliaTrader plugin = SkylliaTrader.getInstance();
                    if (plugin == null) return;
                    plugin.getShopPurchaseService().purchase(player, origin, entry.id(), mode);
                });
            }
        }

        if (entries.isEmpty()) {
            inv.setItem(31, GuiItem.of(Material.BARRIER, "<!italic><gray>这里暂时没有可展示的商品",
                    List.of("<dark_gray>─────────", "<gray>去交易/升级/攒声望后再来看看吧</gray>")));
        }

        if (clamped > 0) {
            inv.setItem(GuiPageLayout.SLOT_PREV_PAGE, GuiItem.prevPage());
            holder.bind(GuiPageLayout.SLOT_PREV_PAGE, e -> openPage(player, island, origin, entries, clamped - 1));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(GuiPageLayout.SLOT_NEXT_PAGE, GuiItem.nextPage());
            holder.bind(GuiPageLayout.SLOT_NEXT_PAGE, e -> openPage(player, island, origin, entries, clamped + 1));
        }

        inv.setItem(GuiPageLayout.SLOT_HEADER, GuiItem.of(Material.PAPER, "<!italic><yellow>购物说明",
                List.of("<dark_gray>─────────",
                        "<yellow>左键</yellow><gray> 购买 x1</gray>",
                        "<yellow>Shift+左键</yellow><gray> 购买 x5（余量不足 5 就买剩余额度）</gray>",
                        "<yellow>右键</yellow><gray> 有限购的买满剩余额度，没限购的买一组</gray>",
                        "<dark_gray>─────────",
                        "<gray>灰色商品表示还没解锁，看 lore 了解还差多少</gray>")));

        inv.setItem(GuiPageLayout.SLOT_CLOSE, GuiItem.close());
        holder.bind(GuiPageLayout.SLOT_CLOSE, e -> player.closeInventory());

        player.openInventory(inv);
    }

    /** 翻页：数据已经在内存里，不重新查库，只需要延迟一 tick 换界面（理由同 TraderOrderListGui）。 */
    private static void openPage(Player player, Island island, MerchantOrigin origin,
                                 List<ShopEntry> entries, int page) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;
        player.getScheduler().run(plugin, t -> render(player, island, origin, entries, page), null);
    }

    private static ItemStack buildItem(ShopEntry entry) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");

        if (entry.locked()) {
            lore.add("<gray>" + entry.lockHint() + "</gray>");
            lore.add("<dark_gray>─────────");
            lore.add("<dark_gray>解锁后可在此购买</dark_gray>");
            return GuiItem.of(entry.material(), "<!italic><dark_gray>🔒 " + entry.displayName(), lore);
        }

        if (entry.unitPrice() < entry.basePrice()) {
            lore.add("<gray>单价：<white>" + GuiFormat.fmt(entry.unitPrice()) + " 金币</white> <strikethrough><dark_gray>"
                    + GuiFormat.fmt(entry.basePrice()) + "</dark_gray></strikethrough> <green>(已打折)</green></gray>");
        } else {
            lore.add("<gray>单价：<white>" + GuiFormat.fmt(entry.unitPrice()) + " 金币</white></gray>");
        }

        if (entry.limited()) {
            int used = Math.max(0, entry.effectiveLimit() - entry.remaining());
            lore.add("<gray>限购（" + periodLabel(entry.period()) + "）：<white>" + used + "/" + entry.effectiveLimit()
                    + "</white></gray>");
            if (entry.remaining() <= 0) {
                lore.add("<red>本周期额度已用完</red>");
            }
        } else {
            lore.add("<gray>不限购</gray>");
        }

        lore.add("<dark_gray>─────────");
        lore.add("<yellow>左键</yellow><gray> x1</gray>  <yellow>Shift+左键</yellow><gray> x5</gray>");
        lore.add("<yellow>右键</yellow><gray> " + (entry.limited() ? "买满剩余额度" : "买一组（最多 64）") + "</gray>");

        return GuiItem.of(entry.material(), "<!italic><white>" + entry.displayName(), lore);
    }

    private static String periodLabel(ShopPurchaseLimitPeriod period) {
        return switch (period) {
            case DAILY -> "每日";
            case WEEKLY -> "每周";
            case MONTHLY -> "每月";
            case LIFETIME -> "终身";
            case NONE -> "";
        };
    }
}
