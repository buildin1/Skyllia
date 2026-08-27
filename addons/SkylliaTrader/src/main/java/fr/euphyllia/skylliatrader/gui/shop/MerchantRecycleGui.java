package fr.euphyllia.skylliatrader.gui.shop;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.gui.GuiItem;
import fr.euphyllia.skyllia.gui.GuiPageLayout;
import fr.euphyllia.skyllia.gui.SkylliaGuiHolder;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.configuration.ShopConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.ShopItemDefinition;
import fr.euphyllia.skylliatrader.data.TraderIslandData;
import fr.euphyllia.skylliatrader.gui.GuiFormat;
import fr.euphyllia.skylliatrader.shop.RecycleMode;
import fr.euphyllia.skylliatrader.shop.RecycleService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家端「回收」GUI（HANDOFF 9.3 T5）：展示游商当前收购的全部商品（= {@code shop.toml} 的固定
 * 全目录），点击一条触发一键回收（{@link RecycleService#recycle}）。
 *
 * <h2>不做"往格子里摆物品"那套交互</h2>
 * <p>
 * 理由和 {@code TraderOrderBoardGui} 一样：这是真实经济（物品真被消耗、金币真被发放），
 * GUI 只负责展示 + 触发，真正的背包扫描/扣除/发钱全部在 {@link RecycleService} 里完成。
 * </p>
 *
 * <h2>回收和解锁进度无关</h2>
 * <p>
 * 这里展示的是 {@code shop.toml} <b>全部</b>条目，不按 {@code ShopVisibility} 的三态过滤——
 * 游商收东西不应该要求玩家先解锁购买资格，那是"买"的门槛，回收是"卖"，两码事。
 * 玩家哪怕一件商品都没解锁到，只要背包里有对应材质，一样能拿来换钱。
 * 说明书不在列表里：它不是 {@code shop.toml} 的条目（保留 id，见
 * {@code ShopConfigManager#GUIDEBOOK_RESERVED_ID}），HANDOFF 9.3 的回收范围是
 * "游商自己有在卖的商品"，说明书走的是完全独立的 {@code guidebook.*} 配置，不计入这个范围。
 * </p>
 *
 * <h2>已知的简化：GUI 内数据是打开时的快照</h2>
 * <p>
 * 展示的"背包当前持有数量"是打开那一刻的快照，翻页复用同一份内存列表，不重新扫描背包
 * ——理由和 {@code MerchantShopGui}/{@code TraderOrderBoardGui} 一样：真正的权威判定在
 * {@link RecycleService} 的事务里（点击时重新扫描背包 + 重新从配置取活商品定价），
 * GUI 快照过期最多让玩家看到一个"过期的持有数量"，不会让回收价格或范围被绕过。
 * </p>
 *
 * <h2>线程模型</h2>
 * <p>
 * 权限校验/岛屿解析需要同步阻塞 JDBC，必须在 {@code Bukkit.getAsyncScheduler()} 上做
 * （照抄 {@code TraderOrderBoardGui#open}）；但"背包当前持有量"必须读玩家自己的活体 Inventory，
 * 这个操作和 {@code shop.toml} 商品列表的内存读取一起放在<b>玩家自己的调度器</b>
 * （{@code player.getScheduler().run}）上完成，不需要再单独 async 一次
 * （{@code shop.toml} 是纯内存配置，不是数据库读取）。
 * </p>
 */
public final class MerchantRecycleGui {

    private static final Logger log = LoggerFactory.getLogger(MerchantRecycleGui.class);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private MerchantRecycleGui() {
    }

    /** 一条回收条目的展示数据，纯内存快照，见类文档"已知的简化"一节。 */
    private record RecycleEntry(String id, Material material, String displayName, double unitPrice, int owned,
                                double remainingCap) {
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
                // 和商店/订单看板走同一套权限口径：岛主关掉某个角色的 merchant.interact
                // 就该真的挡住，回收也是一种"和游商交易"。
                if (!SkylliaAPI.getPermissionsManager()
                        .hasPermission(player, island, plugin.getPermissions().merchantInteract())) {
                    player.sendMessage(Component.text("§c你在这座岛上没有和游商交易的权限。"));
                    return;
                }

                // 背包扫描必须在玩家自己的调度器上做（见类文档线程模型），shop.toml 列表是纯内存
                // 读取，一起放进这一跳即可，不需要为它单独再跳一次 async。
                TraderIslandData data = plugin.getDataService().load(island);
                player.getScheduler().run(plugin, t -> {
                    List<RecycleEntry> entries = buildEntries(player.getInventory(), data);
                    render(player, entries, 0);
                }, null);
            } catch (Throwable t) {
                // 理由同 TraderProgressGui/MerchantShopGui/TraderOrderBoardGui：asyncScheduler
                // 会把异常吞成一段控制台堆栈，玩家侧「点了没反应」是最难排查的故障。
                log.error("为玩家 {} 打开回收菜单失败", player.getName(), t);
                player.sendMessage(Component.text("§c读取回收数据失败，请稍后重试或联系管理员。"));
            }
        });
    }

    private static List<RecycleEntry> buildEntries(PlayerInventory inv, TraderIslandData data) {
        List<RecycleEntry> entries = new ArrayList<>();
        for (ShopItemDefinition item : ShopConfigLoader.config.getItems()) {
            // 不可回收的商品（可再生物资，见 shop.toml 的 recyclable 字段）根本不进货架：
            // 摆出来让玩家点一下再被拒绝，只会让人以为是 bug。
            if (!item.recyclable()) continue;
            int owned = countMaterial(inv, item.material());
            double remaining = RecycleService.remainingCapFor(data, item.id());
            entries.add(new RecycleEntry(item.id(), item.material(), item.displayName(),
                    RecycleService.recyclePriceFor(item), owned, remaining));
        }
        return entries;
    }

    private static int countMaterial(PlayerInventory inv, Material material) {
        int total = 0;
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack == null || stack.getType() != material) continue;
            total += stack.getAmount();
        }
        return total;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 渲染
    // ══════════════════════════════════════════════════════════════════════

    private static void render(Player player, List<RecycleEntry> entries, int page) {
        int totalPages = GuiPageLayout.totalPages(entries.size());
        int clamped = GuiPageLayout.clampPage(page, totalPages);

        SkylliaGuiHolder holder = new SkylliaGuiHolder(SkylliaGuiHolder.GuiType.EXTENSION);
        String title = "♻ 游商回收" + (totalPages > 1 ? " - 第 " + (clamped + 1) + "/" + totalPages + " 页" : "");
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<light_purple>" + title));

        GuiPageLayout.fillBorder(inv);

        int from = clamped * GuiPageLayout.PAGE_SIZE;
        int to = Math.min(from + GuiPageLayout.PAGE_SIZE, entries.size());
        for (int i = from; i < to; i++) {
            RecycleEntry entry = entries.get(i);
            int slot = GuiPageLayout.contentSlot(i - from);
            inv.setItem(slot, buildItem(entry));
            holder.bind(slot, event -> {
                RecycleMode mode = event.isRightClick() ? RecycleMode.ALL
                        : (event.isShiftClick() ? RecycleMode.QUINTUPLE : RecycleMode.SINGLE);
                SkylliaTrader plugin = SkylliaTrader.getInstance();
                if (plugin == null) return;
                plugin.getRecycleService().recycle(player, entry.id(), mode);
            });
        }

        if (entries.isEmpty()) {
            inv.setItem(31, GuiItem.of(Material.BARRIER, "<!italic><gray>游商目前不收购任何东西",
                    List.of("<dark_gray>─────────", "<gray>请联系管理员检查 shop.toml</gray>")));
        }

        if (clamped > 0) {
            inv.setItem(GuiPageLayout.SLOT_PREV_PAGE, GuiItem.prevPage());
            holder.bind(GuiPageLayout.SLOT_PREV_PAGE, e -> openPage(player, entries, clamped - 1));
        }
        if (clamped < totalPages - 1) {
            inv.setItem(GuiPageLayout.SLOT_NEXT_PAGE, GuiItem.nextPage());
            holder.bind(GuiPageLayout.SLOT_NEXT_PAGE, e -> openPage(player, entries, clamped + 1));
        }

        inv.setItem(GuiPageLayout.SLOT_HEADER, GuiItem.of(Material.HOPPER, "<!italic><yellow>回收说明",
                List.of("<dark_gray>─────────",
                        "<gray>只回收游商自己有卖的商品（价格 = 原价的 40%）</gray>",
                        "<gray>回收和解锁进度无关，背包里有就能卖</gray>",
                        "<gray>每种商品每天各有独立额度，卖沙子不影响卖烈焰棒</gray>",
                        "<dark_gray>─────────",
                        "<yellow>左键</yellow><gray> 回收 x1</gray>",
                        "<yellow>Shift+左键</yellow><gray> 回收 x5（不足 5 个就卖背包里实际的数量）</gray>",
                        "<yellow>右键</yellow><gray> 回收背包里这个材质的全部数量</gray>")));

        // 返回游商进度指南（它是回收站在菜单树里的上一层，命令直开的玩家也落到这个枢纽页）
        inv.setItem(47, GuiItem.back());
        holder.bind(47, e -> fr.euphyllia.skylliatrader.gui.TraderProgressGui.open(player));

        inv.setItem(GuiPageLayout.SLOT_CLOSE, GuiItem.close());
        holder.bind(GuiPageLayout.SLOT_CLOSE, e -> player.closeInventory());

        player.openInventory(inv);
    }

    /** 翻页：数据已经在内存里，不重新扫描背包，只需要延迟一 tick 换界面（理由同 MerchantShopGui）。 */
    private static void openPage(Player player, List<RecycleEntry> entries, int page) {
        SkylliaTrader plugin = SkylliaTrader.getInstance();
        if (plugin == null) return;
        player.getScheduler().run(plugin, t -> render(player, entries, page), null);
    }

    private static ItemStack buildItem(RecycleEntry entry) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>─────────");
        lore.add("<gray>今日剩余额度：<white>" + GuiFormat.fmt(entry.remainingCap()) + " 金币</white></gray>");
        lore.add("<gray>回收单价：<white>" + GuiFormat.fmt(entry.unitPrice()) + " 金币</white>（原价的 40%）</gray>");
        lore.add("<gray>背包持有：<white>" + entry.owned() + "</white> 个</gray>");

        if (entry.owned() <= 0) {
            lore.add("<dark_gray>─────────");
            lore.add("<dark_gray>背包里没有这件东西</dark_gray>");
        } else {
            lore.add("<dark_gray>─────────");
            lore.add("<yellow>左键</yellow><gray> x1</gray>  <yellow>Shift+左键</yellow><gray> x5</gray>");
            lore.add("<yellow>右键</yellow><gray> 全部回收（" + entry.owned() + " 个）</gray>");
        }

        return GuiItem.of(entry.material(), "<!italic><white>" + entry.displayName(), lore);
    }
}
