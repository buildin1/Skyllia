package fr.euphyllia.skylliatrader.shop;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.configuration.ShopConfigLoader;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.data.DailyRecycleIncome;
import fr.euphyllia.skylliatrader.data.TraderDataService;
import fr.euphyllia.skylliatrader.data.TraderIslandData;
import fr.euphyllia.skylliatrader.configuration.model.ShopItemDefinition;
import fr.euphyllia.skylliatrader.gui.GuiFormat;
import fr.euphyllia.skylliatrader.util.DailyWindow;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回收事务（HANDOFF 9.3 T5）：真实经济，和 {@code ShopPurchaseService} / {@code OrderBoardService}
 * 同等级的风险，设计思路直接照抄它们已经走过审查的"先扣、后发、失败必回滚"范式，只是方向相反——
 * 购买是"先扣钱后发货"，回收是"先扣物品后付钱"，这正好和 {@code OrderBoardService}
 * （先扣材料、再发放订单奖励）方向完全一致，本类照抄它的时序。
 *
 * <h2>只回收游商自己有卖的商品，不接受任意材质</h2>
 * <p>
 * 判定依据是 {@code shop.toml}（{@link ShopConfigLoader}）——该材质必须存在对应的
 * {@link ShopItemDefinition}，且 {@code recyclable = true}，否则拒绝（"这件东西游商不收"）。
 * 建材和农场产物不进回收，量产兑现走订单收购。这条规则和商品是否已解锁无关：
 * 回收不受解锁进度约束（见 {@code MerchantRecycleGui} 类文档）。
 * </p>
 *
 * <h2>回收价：基础原价的 40%，不是折扣后的价格</h2>
 * <p>
 * 严格按 HANDOFF 9.3 拍板：{@code ShopItemDefinition#price()}（折扣前原价）× 0.4，四舍五入到分
 * （{@link #recyclePriceFor}）。<b>刻意不调用 {@code ShopEconomics#discountedUnitPrice}</b>——
 * 用玩家当前的消费折扣价格来算回收价，会让"先攒够消费折扣、再回收套一层折扣的折扣"变成一层
 * 不该有的额外套利，这是明确拍板的规则。同理，回收也<b>不消耗/不参考</b>限购计数、
 * 不影响 {@code tradeCount}/{@code reputation}/{@code totalSpent}——它是"卖"，不是"买"，
 * 不该反向计入消费额统计口径。
 * </p>
 *
 * <h2>为什么没有 {@code TraderDataService#compute} 临界区</h2>
 * <p>
 * {@code ShopPurchaseService}/{@code OrderBoardService} 都要在临界区里做限购余量判定和记账
 * （限购计数/交易次数/消费额/声望/每日收入——全是<b>整座岛屿共享</b>的状态，必须靠岛屿维度的
 * 互斥锁裁决并发）。回收<b>完全不碰任何这类共享状态</b>：不限购、不计入 tradeCount/reputation/
 * totalSpent，唯一需要"权威判定"的只有"这件商品现在的回收单价是多少"——而这直接来自
 * {@code shop.toml} 的内存快照，不是数据库状态，两名玩家同时回收同一种材质也不存在互相抢占的
 * 资源（不像限购商品的"最后一件"）。所以本类<b>刻意省掉了 compute 临界区</b>，物品扣除成功后
 * 直接进 async 重新从配置取一次"当下"的商品定价（不信任玩家点击那一刻的价格快照，理由同
 * {@code OrderBoardService} 的"临界区重新从配置取活订单定义"），随后同步走 Vault 发钱——
 * 这是本轮唯一偏离"照抄 OrderBoardService 六步时序"模板的地方，偏离的理由就是上面这段。
 * </p>
 *
 * <h2>Folia 线程模型</h2>
 * <ol>
 *   <li>GUI 点击回调在 region tick 线程上被调用——入口 {@link #recycle} 只读事件参数就立刻
 *       转 async；</li>
 *   <li>商品定义校验（是否在 {@code shop.toml} 里）、Vault 存款全部在
 *       {@code Bukkit.getAsyncScheduler()} 上；</li>
 *   <li>背包操作（扫描持有量、扣除物品、失败时退还）必须回<b>玩家自己的调度器</b>
 *       （{@code player.getScheduler().run}）——{@code EntityScheduler#run} 在玩家已经完全
 *       下线时会直接返回 {@code null} 且<b>不调用 retired 回调</b>，本类每一次背包操作都
 *       显式检查了这个返回值（T2/T3/T4 已经踩过的坑）。</li>
 * </ol>
 */
public final class RecycleService {

    private static final Logger log = LoggerFactory.getLogger(RecycleService.class);

    /** 回收价折算比例：基础单价（折扣前原价）的 40%，HANDOFF 9.3 已拍板，不要改成读配置。 */
    private static final double RECYCLE_RATE = 0.40;

    private final SkylliaTrader plugin;

    /** 正在走回收流程的玩家，防止连点同一个格子把同一次回收触发两次（理由同 ShopPurchaseService#inFlight）。 */
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();

    public RecycleService(@NotNull SkylliaTrader plugin) {
        this.plugin = plugin;
    }

    /**
     * 商品的回收单价：基础原价 × 40%，四舍五入到分。{@code MerchantRecycleGui} 展示的价格和本类
     * 事务实际结算的价格必须走<b>同一个</b>方法，避免两处各写一份除以 0.4 的算式、改一处忘另一处。
     */
    public static double recyclePriceFor(@NotNull ShopItemDefinition item) {
        return ShopEconomics.round2(item.price() * RECYCLE_RATE);
    }

    /**
     * 这件商品今天的回收额度（金币）。
     * <p>
     * 配置里的 {@code recycle.daily-income-cap-per-item} 只是保底。单价高的商品按「至少能卖几件」
     * 再抬一档，否则会出现「回收单价 2000、每日上限 40、一件都卖不出去」。
     * </p>
     */
    public static double dailyCapFor(@NotNull ShopItemDefinition item) {
        double floor = TraderConfigLoader.config.getDailyRecycleIncomeCap();
        double unit = recyclePriceFor(item);
        int minPieces;
        if (item.price() >= 200.0) {
            minPieces = 1;
        } else if (item.price() >= 50.0) {
            minPieces = 2;
        } else if (item.price() >= 8.0) {
            minPieces = 16;
        } else {
            minPieces = 64;
        }
        return Math.max(floor, ShopEconomics.round2(unit * minPieces));
    }

    /** 某件商品今天还剩多少回收额度（金币）。窗口过期视为满额。 */
    public static double remainingCapFor(@NotNull TraderIslandData data, @NotNull ShopItemDefinition item) {
        double cap = dailyCapFor(item);
        DailyRecycleIncome income = data.recycleIncomeByItem.get(item.id());
        long now = System.currentTimeMillis();
        if (income == null || DailyWindow.expired(income.windowStartAt, now)) {
            return cap;
        }
        return Math.max(0.0, ShopEconomics.round2(cap - income.amount));
    }

    /** 在剩余额度里最多能收几件。单价为 0 时按请求数量全收。 */
    static int maxAffordableQuantity(double remainingGold, double unitPrice, int requested) {
        if (requested <= 0 || remainingGold <= 0) return 0;
        if (unitPrice <= 0) return requested;
        int max = (int) Math.floor((remainingGold + 1e-9) / unitPrice);
        return Math.max(0, Math.min(requested, max));
    }

    /**
     * 玩家在回收 GUI 里点击一件商品。<b>必须在玩家线程或 region tick 线程上调用</b>
     * （只读事件参数，随后立刻转 async）。
     *
     * @param shopItemId 商品 id（必须存在于 {@code shop.toml}，说明书不可回收，
     *                   见类文档"只回收游商自己有卖的商品"）
     * @param mode       点击语义
     */
    public void recycle(@NotNull Player player, @NotNull String shopItemId, @NotNull RecycleMode mode) {
        UUID playerId = player.getUniqueId();
        if (inFlight.putIfAbsent(playerId, Boolean.TRUE) != null) {
            player.sendMessage(Component.text("§e上一次回收还在处理中，请稍等一下。"));
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                recycleAsyncStage(player, shopItemId, mode);
            } catch (Throwable t) {
                // asyncScheduler 会把异常吞成一段控制台堆栈，玩家侧「点了没反应」——
                // 这里必须自己兜底提示，理由同 ShopPurchaseService/OrderBoardService。
                log.error("玩家 {} 回收商品 '{}' 时发生未预期的异常", player.getName(), shopItemId, t);
                player.sendMessage(Component.text("§c回收失败：服务器内部错误，请联系管理员。"));
                inFlight.remove(playerId);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 1 步（async）：校验商品是否可回收 + 确认 Vault 可用
    // ══════════════════════════════════════════════════════════════════════

    private void recycleAsyncStage(Player player, String shopItemId, RecycleMode mode) {
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            fail(player, "§c游商当前不可用：服务器没有安装经济插件（Vault），请联系管理员。");
            return;
        }
        Economy econ = rsp.getProvider();

        String normalizedId = ShopItemDefinition.normalizeId(shopItemId);
        ShopItemDefinition item = ShopConfigLoader.config.findById(normalizedId);
        if (item == null) {
            fail(player, "§c这件东西游商不收。");
            return;
        }
        if (!item.recyclable()) {
            // 可再生物品（树苗/竹子/仙人掌/作物这类农场能无限量产的）一律不回收。
            // 理由见 HANDOFF 5.1 的定价不变量：计分体系早就因为"树场可无限量产"给这些材料
            // 判了 ×0 分，回收却是拿真钱换它们，放开就是一个零成本的无限印钞口
            // （2026-08-21 服主指出，服主拍板"可再生物品一律不收 + 每日上限"）。
            fail(player, "§e" + GuiFormat.legacyName(item.displayName())
                    + " §e可以自己种/养出来，游商不回收这类可再生物资。");
            return;
        }

        var scheduled = player.getScheduler().run(plugin,
                t -> materialScanAndDeductStage(player, normalizedId, item.material(), item.displayName(), mode, econ),
                () -> {
                    // 从这一步开始才会真正碰背包，玩家在此之前下线的话什么都还没发生，
                    // 直接释放 inFlight 即可，不需要任何回滚（理由同 OrderBoardService）。
                    inFlight.remove(player.getUniqueId());
                });
        if (scheduled == null) {
            inFlight.remove(player.getUniqueId());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 2 步（玩家线程）：扫描背包 → 确定实际可回收数量 → 扣除
    // ══════════════════════════════════════════════════════════════════════

    private void materialScanAndDeductStage(Player player, String normalizedId, Material material,
                                             String displayName, RecycleMode mode, Economy econ) {
        PlayerInventory inv = player.getInventory();
        int owned = countMaterial(inv, material);
        if (owned <= 0) {
            fail(player, "§c你的背包里没有 " + GuiFormat.legacyName(displayName) + "§c。");
            return;
        }

        int quantity = switch (mode) {
            case SINGLE -> 1;
            case QUINTUPLE -> Math.min(5, owned);
            case ALL -> owned;
        };

        // 按"价值是否相同"扣除（见 isRecyclable）：改名/加 lore 这类纯外观差异照收，
        // 耐久损耗、附魔这类影响价值的一律不收。countMaterial 和这里用的是同一个判据，
        // 两边口径一致，不会再出现"统计说有、扣的时候一件都扣不到"那种自相矛盾的情况。
        int actuallyRemoved = removeRecyclable(inv, material, quantity);
        if (actuallyRemoved < quantity) {
            // 口径已经统一，正常路径下不该走到这里；真走到了说明背包在
            // countMaterial 和本次扣除之间被并发改动了（玩家同时在别处丢/存物品）。
            if (actuallyRemoved > 0) {
                giveBack(player, material, actuallyRemoved);
            }
            log.error("玩家 {} 回收商品 '{}' 时只扣到 {}/{} 件（已退还已扣部分），"
                    + "可能是背包在扣除过程中被其它代码并发改动",
                    player.getName(), normalizedId, actuallyRemoved, quantity);
            fail(player, "§c物品扣除失败，请重试。");
            return;
        }

        // 物品已经真的从背包里拿走了——从这里开始任何失败都必须"退物品"，见 refundItems。
        Bukkit.getAsyncScheduler().runNow(plugin,
                at -> payStage(player, normalizedId, material, displayName, quantity, econ));
    }

    private int countMaterial(PlayerInventory inv, Material material) {
        int total = 0;
        for (ItemStack stack : inv.getStorageContents()) {
            if (!isRecyclable(stack, material)) continue;
            total += stack.getAmount();
        }
        return total;
    }

    /**
     * 这一叠物品能不能按「全新的 {@code material}」回收。
     * <p>
     * 判据是<b>会不会影响价值</b>，而不是「和一个裸物品完全一致」：
     * </p>
     * <ul>
     *   <li><b>耐久有损耗</b> → 不收。用坏的鞘翅按全新价回收就是白拿钱。</li>
     *   <li><b>带附魔</b>（含附魔书上的存储附魔） → 不收。附魔物品的价值和裸物品不是一回事。</li>
     *   <li><b>只是改了个名字 / 加了 lore</b> → <b>照收</b>。这类差异不改变物品价值，
     *       而且历史上商店发货曾经给每件商品都打过自定义名字（见
     *       {@code ShopPurchaseService#buildDeliveryItem} 的说明），玩家背包里存量的
     *       那批带名字的商品必须还能回收，不能因为一次实现变更就变成死物品。</li>
     * </ul>
     * <p>
     * 之所以自己写匹配而不是继续用 {@code Inventory#removeItem}：后者按
     * {@code ItemStack#isSimilar}（材质 + <b>完整</b>组件）匹配，口径比"价值是否相同"严格得多，
     * 改名这种纯外观差异也会被判成不匹配 —— 这正是 2026-08-21 服主实测到
     * 「买来的沙子回收不了、还提示带有耐久损耗」的直接原因。
     * </p>
     */
    private boolean isRecyclable(@Nullable ItemStack stack, Material material) {
        if (stack == null || stack.getType() != material) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return true;
        if (meta.hasEnchants()) return false;
        if (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants()) return false;
        if (meta instanceof Damageable damageable && damageable.hasDamage()) return false;
        return true;
    }

    /**
     * 从背包里扣掉 {@code amount} 个可回收的 {@code material}，返回<b>实际扣掉的数量</b>。
     * <p>
     * 手写扣除而不是用 {@code Inventory#removeItem}，理由见 {@link #isRecyclable}：
     * 需要按"价值是否相同"而不是"组件是否完全一致"来匹配。
     * </p>
     */
    private int removeRecyclable(PlayerInventory inv, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (!isRecyclable(stack, material)) continue;
            int take = Math.min(remaining, stack.getAmount());
            int left = stack.getAmount() - take;
            contents[i] = left <= 0 ? null : stack.asQuantity(left);
            remaining -= take;
        }
        inv.setStorageContents(contents);
        return amount - remaining;
    }

    private void giveBack(Player player, Material material, int amount) {
        if (amount <= 0) return;
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, amount));
        for (ItemStack over : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), over);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 3 步（async）：重新从配置取活商品定价 → 发钱（不经过 compute 临界区，见类文档）
    // ══════════════════════════════════════════════════════════════════════

    private void payStage(Player player, String normalizedId, Material material, String displayName,
                          int quantity, Economy econ) {
        UUID playerId = player.getUniqueId();

        // 重新从配置里取一份"当下"的商品定义（不信任玩家点击那一刻的价格快照，理由同
        // OrderBoardService「临界区重新从配置取活订单定义」）：物品扣除这段时间里配置可能被
        // /skyllia reload 改过价格，甚至整条商品被管理员删掉——按硬规则 3，商品一旦不在
        // shop.toml 里就不该继续回收，这里必须重新校验一次。
        ShopItemDefinition liveItem = ShopConfigLoader.config.findById(normalizedId);
        if (liveItem == null) {
            log.warn("玩家 {} 回收商品 '{}' 时该商品已从 shop.toml 移除，已退还物品",
                    player.getName(), normalizedId);
            refundItems(player, material, quantity, "商品已下架");
            fail(player, "§c这件东西游商刚刚不收了，物品已退还。");
            return;
        }

        double unitPrice = recyclePriceFor(liveItem);
        double rawPrice = ShopEconomics.round2(unitPrice * quantity);

        // 每种商品各自的每日回收收入上限：仍是<b>整座岛屿共享</b>的状态（5 人岛不能 ×5），
        // 必须在岛屿维度的互斥临界区里判定 + 记账。两名成员同时回收同一种商品时，
        // 否则会各自读到同一个"剩余额度"把该商品的上限刷穿。
        // 本类原先刻意没有临界区（类文档里写过"回收不碰任何共享状态"），加上每日上限之后
        // 那个前提不再成立，这里补上——语义和 OrderBoardService 的每日货币上限完全一致：
        // 超出上限的部分<b>不发钱</b>，但物品已经扣了，所以要把超出的部分按"少发多少钱"
        // 换算成"退回多少物品"是行不通的（物品是整数、单价可能带小数）。因此改成：
        // 上限不足以覆盖本次回收时，直接<b>整笔拒绝并退还物品</b>，让玩家自己少选几件再来，
        // 而不是悄悄少给钱——后者会变成一个玩家完全看不懂的"回收价怎么变了"。
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            refundItems(player, material, quantity, "找不到岛屿");
            fail(player, "§c你还没有空岛，物品已退还。");
            return;
        }
        double cap = dailyCapFor(liveItem);
        int[] acceptedQty = {0};
        Double accepted = plugin.getDataService().compute(island, data -> {
            long now = System.currentTimeMillis();
            DailyRecycleIncome income = data.recycleIncomeByItem
                    .computeIfAbsent(normalizedId, k -> new DailyRecycleIncome());
            if (DailyWindow.expired(income.windowStartAt, now)) {
                income.amount = 0.0;
                income.windowStartAt = DailyWindow.currentPeriodStart(now);
            }
            double remaining = Math.max(0.0, cap - income.amount);
            int take = maxAffordableQuantity(remaining, unitPrice, quantity);
            if (take <= 0) {
                return TraderDataService.Mutation.readOnly(null);
            }
            double pay = ShopEconomics.round2(unitPrice * take);
            acceptedQty[0] = take;
            income.amount = ShopEconomics.round2(income.amount + pay);
            return TraderDataService.Mutation.commit(pay, null);
        });
        if (accepted == null) {
            refundItems(player, material, quantity, "今日回收额度不足");
            fail(player, "§e今天这种商品的回收额度已经用完了（每日上限 §f" + GuiFormat.fmt(cap)
                    + " §e金币），物品已退还。额度每天早上 8 点刷新，换别的商品还能继续卖。");
            return;
        }
        int soldQty = acceptedQty[0];
        int leftover = quantity - soldQty;
        if (leftover > 0) {
            refundItems(player, material, leftover, "超出今日额度退还");
        }
        double totalPrice = accepted;

        try {
            EconomyResponse response = econ.depositPlayer(player, totalPrice);
            if (response == null || !response.transactionSuccess()) {
                String reason = response == null ? "经济插件未返回结果" : response.errorMessage;
                log.error("玩家 {} 回收商品 '{}' 发钱失败：{}，已退还物品并回滚每日额度",
                        player.getName(), normalizedId, reason);
                rollbackDailyIncome(island, normalizedId, totalPrice);
                refundItems(player, material, soldQty, "发钱失败");
                fail(player, "§c回收失败：发钱异常，物品已退还，请稍后重试或联系管理员。");
                return;
            }
        } catch (Throwable t) {
            // econ 是另一个插件的代码，不能假设它只会通过 EconomyResponse 表达失败、
            // 不会直接抛异常（理由同 ShopPurchaseService#chargeStage）。
            log.error("玩家 {} 回收商品 '{}' 发钱阶段抛出异常，已退还物品并回滚每日额度",
                    player.getName(), normalizedId, t);
            rollbackDailyIncome(island, normalizedId, totalPrice);
            refundItems(player, material, soldQty, "发钱异常");
            fail(player, "§c回收失败：经济插件出错，物品已退还，请稍后重试或联系管理员。");
            return;
        }

        // player.sendMessage 是线程安全的（ShopPurchaseService/OrderBoardService 已有同样的用法），
        // 不需要跳回玩家线程。
        String extra = leftover > 0 ? "§e（超出额度的 " + leftover + " 个已退还）" : "";
        player.sendMessage(Component.text("§a回收成功：§f" + GuiFormat.legacyName(displayName) + " §fx" + soldQty
                + " §a，获得 §f" + GuiFormat.fmt(totalPrice) + " §a金币" + extra));
        inFlight.remove(playerId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 回滚
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 把物品退还给玩家，用于"物品已经真的被扣掉，但钱没能发出去"这个最危险的分支。
     * <b>必须回玩家线程</b>；玩家已经彻底下线时没有安全的方式把物品塞回一个离线玩家的背包，
     * 只能把细节完整记进错误日志，交给管理员手动补偿——这条路径和
     * {@code ShopPurchaseService}/{@code OrderBoardService} 对失败退还的处理是同一个思路。
     */
    /**
     * 把已经记进"今日回收收入"的额度扣回去。用于"额度扣了、物品扣了，但钱没发出去"的分支——
     * 不回滚的话玩家会白白损失一次额度（物品退回来了，额度却没了）。
     */
    private void rollbackDailyIncome(Island island, String shopItemId, double amount) {
        if (amount <= 0) return;
        try {
            plugin.getDataService().compute(island, data -> {
                DailyRecycleIncome income = data.recycleIncomeByItem.get(shopItemId);
                if (income == null) {
                    return TraderDataService.Mutation.readOnly(Boolean.TRUE);
                }
                income.amount = Math.max(0.0, ShopEconomics.round2(income.amount - amount));
                return TraderDataService.Mutation.commit(Boolean.TRUE, Boolean.FALSE);
            });
        } catch (Throwable t) {
            // 回滚失败只影响这座岛今天的额度多扣了一点，绝不能让它把"退还物品"那一步顶掉。
            log.error("回滚岛屿 {} 商品 {} 的每日回收额度（{}）失败", island.getId(), shopItemId, amount, t);
        }
    }

    private void refundItems(Player player, Material material, int quantity, String reasonForLog) {
        var scheduled = player.getScheduler().run(plugin, t -> giveBack(player, material, quantity),
                () -> logRefundFailure(player, material, quantity, reasonForLog));
        if (scheduled == null) {
            logRefundFailure(player, material, quantity, reasonForLog);
        }
    }

    private void logRefundFailure(Player player, Material material, int quantity, String reasonForLog) {
        log.error("玩家 {} 的回收物品退还失败：玩家已下线（原因：{}），需要管理员手动补偿 {} x{}",
                player.getName(), reasonForLog, material, quantity);
    }

    private void fail(Player player, String message) {
        player.sendMessage(Component.text(message));
        inFlight.remove(player.getUniqueId());
    }
}
