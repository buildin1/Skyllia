package fr.euphyllia.skylliatrader.order;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.bridge.IslandLevelBridge;
import fr.euphyllia.skylliatrader.configuration.OrdersConfigLoader;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.ItemAmount;
import fr.euphyllia.skylliatrader.configuration.model.OrderDefinition;
import fr.euphyllia.skylliatrader.configuration.model.OrderType;
import fr.euphyllia.skylliatrader.data.DailyOrderIncome;
import fr.euphyllia.skylliatrader.data.DailyOrderReputation;
import fr.euphyllia.skylliatrader.data.OrderSlotState;
import fr.euphyllia.skylliatrader.data.TraderDataService;
import fr.euphyllia.skylliatrader.data.TraderIslandData;
import fr.euphyllia.skylliatrader.gui.GuiFormat;
import fr.euphyllia.skylliatrader.shop.ShopEconomics;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商队订单看板的"一键结算"事务：全系统第二高风险的代码，仅次于
 * {@code ShopPurchaseService}（HANDOFF 6.8/9.3）——这是真实的物品消耗 + 真实的奖励发放，
 * 设计思路直接照抄 {@code ShopPurchaseService} 已经走过审查的"先扣、后发、失败必回滚"范式，
 * 不另起炉灶。
 *
 * <h2>结算不用"往格子里摆物品"那套交互</h2>
 * <p>
 * 玩家点一个订单槽位，服务端自动扫描玩家背包（合并统计同材质的所有堆叠），够了就直接扣除、
 * 发放奖励；材料不够就提示缺口，不做任何摆放操作。理由见 HANDOFF：物品格子编辑那套交互
 * 是 T3 第二波刚为"配置录入场景"（管理员编辑订单，物品会被原样退还）专门在核心
 * {@code GuiListener} 加的支持，本质是"草稿"；订单结算是"真实经济"，物品要被真消耗，
 * 把两种完全不同性质的风险混在一起容易出错，所以这里完全不碰物品格子。
 * </p>
 *
 * <h2>时序：为什么是"先扣材料，再进临界区"</h2>
 * <p>
 * {@link TraderDataService#compute} 的临界区不允许做任何阻塞 IO 或碰玩家背包（背包操作必须在
 * 玩家自己的调度器上），所以"扣材料"和"限购/每日收入上限判定+扣减"物理上不可能挤进同一次
 * 调用。两种顺序都要面对"中间失败怎么办"，这里选"先扣材料、再进临界区"，理由：
 * </p>
 * <ol>
 *   <li>材料扣除发生在<b>玩家自己的调度器</b>上，从"再次确认库存足够"到"实际调用
 *       {@code inventory.removeItem}"之间<b>不会让出这个线程</b>——Folia 保证同一时刻只有
 *       这一个线程能碰这名玩家的背包，所以这一步事实上不存在"中途被别的代码抢先改动库存"的
     *   竞态，几乎不可能失败；</li>
 *   <li>而"限购余量 / 每日收入上限"是<b>整座岛屿共享</b>的资源——同一岛屿的两名成员可能同时
 *       对着同一个槽位点结算，各自的库存检查都各自独立通过（互不冲突），但两边都想拿到
 *       "限购内的最后一单"，这个冲突<b>只能</b>在 {@code compute} 的临界区里被真正裁决；</li>
 *   <li>所以让"几乎不会失败"的一步（扣材料）先做，让"真正可能因为并发而失败"的一步
 *       （临界区判定）后做——临界区判定失败时，材料已经被扣了，必须原样退还给玩家
 *       （见 {@link #refundMaterials}），这正是 HANDOFF 点名要求"最需要小心的情况"。</li>
 * </ol>
 * <p>
 * 临界区内部依然会<b>完整重新校验</b>一遍解锁资格/限购余量/订单是否仍绑定在这个槽位/是否已过期
 * ——不能只信更早阶段的检查结果（那些检查在并发下随时可能过期），这是唯一的权威判定点。
 * </p>
 *
 * <h2>Folia 线程模型</h2>
 * <ol>
 *   <li>GUI 点击回调在 region tick 线程上被调用——入口 {@link #redeem} 只读事件参数就立刻
 *       转 async；</li>
 *   <li>订单/岛屿解析、解锁校验、Vault 存款、限购/每日收入上限的临界区判定全部在
 *       {@code Bukkit.getAsyncScheduler()} 上；</li>
 *   <li>背包操作（扫描材料、扣除材料、发放 barter 奖励）必须回<b>玩家自己的调度器</b>
 *       （{@code player.getScheduler().run}）——{@code EntityScheduler#run} 在玩家已经完全
 *       下线时会直接返回 {@code null} 且<b>不调用 retired 回调</b>，本类每一次背包操作都
 *       显式检查了这个返回值（T2/T3 已经踩过的坑）。</li>
 * </ol>
 */
public final class OrderBoardService {

    private static final Logger log = LoggerFactory.getLogger(OrderBoardService.class);
    private static final long DAY_MILLIS = 86_400_000L;

    private final SkylliaTrader plugin;
    private final TraderDataService dataService;

    /** 正在走结算流程的玩家，防止连点同一个槽位把同一次结算触发两次（理由同 ShopPurchaseService#inFlight）。 */
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();

    public OrderBoardService(@NotNull SkylliaTrader plugin, @NotNull TraderDataService dataService) {
        this.plugin = plugin;
        this.dataService = dataService;
    }

    /**
     * 一次结算的最终结果，只在 async 线程内部流转。{@code order} 是<b>临界区里重新拿到的
     * 最新订单定义</b>（不是玩家点击那一刻的快照）——价格/奖励可能在这中间被管理员改过，
     * 发放奖励必须以临界区判定时刻为准。
     */
    private record Settlement(boolean ok, String failMessage, OrderDefinition order,
                               double moneyAwarded, long reputationAwarded) {
        static Settlement fail(String message) {
            return new Settlement(false, message, null, 0.0, 0L);
        }

        static Settlement ok(OrderDefinition order, double moneyAwarded, long reputationAwarded) {
            return new Settlement(true, null, order, moneyAwarded, reputationAwarded);
        }
    }

    /**
     * 玩家在订单看板 GUI 里点击一个槽位。<b>必须在玩家线程或 region tick 线程上调用</b>
     * （只读事件参数，随后立刻转 async）。
     */
    public void redeem(@NotNull Player player, int slotIndex) {
        UUID playerId = player.getUniqueId();
        if (inFlight.putIfAbsent(playerId, Boolean.TRUE) != null) {
            player.sendMessage(Component.text("§e上一次交易还在处理中，请稍等一下。"));
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                redeemAsyncStage(player, slotIndex);
            } catch (Throwable t) {
                log.error("玩家 {} 结算订单看板槽位 {} 时发生未预期的异常", player.getName(), slotIndex, t);
                fail(player, "§c交易失败：服务器内部错误，请联系管理员。");
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 1 步（async）：解析槽位/订单 + 校验解锁资格 + 校验限购余量（信息性预检查）
    // ══════════════════════════════════════════════════════════════════════

    private void redeemAsyncStage(Player player, int slotIndex) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            fail(player, "§c你还没有空岛，先创建一个空岛吧。");
            return;
        }

        TraderIslandData data = dataService.load(island);
        if (slotIndex < 0 || slotIndex >= data.orderSlots.size()) {
            fail(player, "§c这个订单槽位不存在，请重新打开订单看板。");
            return;
        }
        OrderSlotState slot = data.orderSlots.get(slotIndex);
        long now = System.currentTimeMillis();
        if (slot.orderId == null || (slot.expiresAt > 0 && now > slot.expiresAt)) {
            fail(player, "§c这个槽位当前没有可交付的订单，游商正在准备新订单。");
            return;
        }

        OrderDefinition order = findOrder(slot.orderId);
        if (order == null || !order.enabled()) {
            fail(player, "§c该订单已下架，请重新打开订单看板。");
            return;
        }

        long islandLevel = IslandLevelBridge.isAvailable() ? IslandLevelBridge.getIslandLevel(island) : 0L;
        if (islandLevel < order.requiredLevelMin() || data.reputation < order.requiredReputationMin()) {
            fail(player, "§c你的岛屿尚未满足这条订单的解锁条件（等级 §f≥" + order.requiredLevelMin()
                    + " §c/ 声望 §f≥" + order.requiredReputationMin() + "§c）。");
            return;
        }
        if (order.redeemLimitPerIsland() > 0 && data.completionCount(order.id()) >= order.redeemLimitPerIsland()) {
            fail(player, "§c这条订单本岛的终身限购已经用完了。");
            return;
        }

        // money 类型提前确认经济插件可用：避免白白扣了玩家材料，最后却因为没装 Vault 发不出钱。
        Economy econ = null;
        if (order.type() == OrderType.MONEY) {
            RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp == null) {
                fail(player, "§c商队当前不可用：服务器没有安装经济插件（Vault），请联系管理员。");
                return;
            }
            econ = rsp.getProvider();
        }

        Economy finalEcon = econ;
        var scheduled = player.getScheduler().run(plugin,
                t -> materialCheckAndDeductStage(player, island, slotIndex, order, finalEcon),
                () -> {
                    // 从这一步开始才会真正碰背包，玩家在此之前下线的话什么都还没发生，
                    // 直接释放 inFlight 即可，不需要任何回滚。
                    inFlight.remove(player.getUniqueId());
                });
        if (scheduled == null) {
            inFlight.remove(player.getUniqueId());
        }
    }

    private OrderDefinition findOrder(String normalizedId) {
        for (OrderDefinition order : OrdersConfigLoader.config.getOrders()) {
            if (order.id().equals(normalizedId)) return order;
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 2 步（玩家线程）：合并统计背包材料 → 校验足够 → 扣除
    // ══════════════════════════════════════════════════════════════════════

    private void materialCheckAndDeductStage(Player player, Island island, int slotIndex,
                                             OrderDefinition order, Economy econ) {
        PlayerInventory inv = player.getInventory();
        Map<Material, Integer> owned = mergedCounts(inv);

        List<String> deficits = new ArrayList<>();
        for (ItemAmount req : order.takeItems()) {
            int have = owned.getOrDefault(req.material(), 0);
            if (have < req.amount()) {
                deficits.add(GuiFormat.fmt(req.amount() - have) + " 个 " + req.material());
            }
        }
        if (!deficits.isEmpty()) {
            fail(player, "§c材料不足，还差：§f" + String.join("§7，§f", deficits));
            return;
        }

        // 逐项扣除；一旦中途"扣不出"（理论上不该发生，见类文档「时序」一节），
        // 立刻把已经扣掉的部分加回去——绝不能让玩家莫名其妙少了一部分材料却什么都没换到。
        List<ItemAmount> removedSoFar = new ArrayList<>();
        for (ItemAmount req : order.takeItems()) {
            Map<Integer, ItemStack> leftover = inv.removeItem(new ItemStack(req.material(), req.amount()));
            if (!leftover.isEmpty()) {
                for (ItemAmount already : removedSoFar) {
                    giveBack(player, already);
                }
                log.error("玩家 {} 结算订单 '{}' 时材料扣除中途失败（已回滚已扣部分），"
                        + "可能是背包在扣除过程中被其它代码并发改动", player.getName(), order.id());
                fail(player, "§c材料扣除失败，请重试。");
                return;
            }
            removedSoFar.add(req);
        }

        // 材料已经真的从背包里拿走了——从这里开始任何失败都必须"退材料"，见 rollbackAndRefund。
        Bukkit.getAsyncScheduler().runNow(plugin, at -> settleAsyncStage(player, island, slotIndex, order, econ));
    }

    private Map<Material, Integer> mergedCounts(PlayerInventory inv) {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        return counts;
    }

    private void giveBack(Player player, ItemAmount item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(item.material(), item.amount()));
        for (ItemStack over : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), over);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 3 步（async）：临界区权威判定（解锁/限购/每日收入上限）+ 记账
    // ══════════════════════════════════════════════════════════════════════

    private void settleAsyncStage(Player player, Island island, int slotIndex, OrderDefinition orderSnapshot, Economy econ) {
        long islandLevel = IslandLevelBridge.isAvailable() ? IslandLevelBridge.getIslandLevel(island) : 0L;

        Settlement settlement = dataService.compute(island, data ->
                computeSettlement(data, slotIndex, orderSnapshot, islandLevel));

        if (!settlement.ok()) {
            refundMaterials(player, island, orderSnapshot, settlement.failMessage());
            fail(player, settlement.failMessage());
            return;
        }

        deliver(player, island, settlement, econ);
    }

    /**
     * 在一次 {@code compute} 临界区内完成："权威重新校验（订单仍绑定在该槽位/未过期/仍启用/
     * 仍解锁/限购未满）→ 计算每日收入上限截断后的实际金币奖励 → 记账（限购计数 + 声望 +
     * 每日收入）"。<b>只做纯内存操作</b>，不碰 Vault、不碰背包、不发消息。
     */
    private TraderDataService.Mutation<Settlement> computeSettlement(TraderIslandData data, int slotIndex,
                                                                      OrderDefinition orderSnapshot, long islandLevel) {
        if (slotIndex < 0 || slotIndex >= data.orderSlots.size()) {
            return TraderDataService.Mutation.readOnly(
                    Settlement.fail("§c订单槽位不存在（岛屿配置可能发生了变化），材料已退还。"));
        }
        OrderSlotState slot = data.orderSlots.get(slotIndex);
        long now = System.currentTimeMillis();
        if (slot.orderId == null || !orderSnapshot.id().equals(slot.orderId)) {
            return TraderDataService.Mutation.readOnly(
                    Settlement.fail("§c该槽位的订单已经发生变化，本次交易未生效，材料已退还。"));
        }
        if (slot.expiresAt > 0 && now > slot.expiresAt) {
            return TraderDataService.Mutation.readOnly(
                    Settlement.fail("§c该订单已经过期，游商正在准备新订单，材料已退还。"));
        }

        // 重新从配置里取一份"当下"的订单定义（可能被管理员改过价格/奖励），发放以它为准。
        OrderDefinition liveOrder = findOrder(orderSnapshot.id());
        if (liveOrder == null || !liveOrder.enabled()) {
            return TraderDataService.Mutation.readOnly(Settlement.fail("§c该订单已下架，材料已退还。"));
        }
        if (islandLevel < liveOrder.requiredLevelMin() || data.reputation < liveOrder.requiredReputationMin()) {
            return TraderDataService.Mutation.readOnly(Settlement.fail("§c解锁条件不再满足，材料已退还。"));
        }
        if (liveOrder.redeemLimitPerIsland() > 0
                && data.completionCount(liveOrder.id()) >= liveOrder.redeemLimitPerIsland()) {
            return TraderDataService.Mutation.readOnly(Settlement.fail("§c这条订单本岛的限购已经用完了，材料已退还。"));
        }
        // 同槽位冷却（2026-08-21 T4 审查后补）：锁的是"频率"，和上面几条锁"资格/总量"的判定
        // 是两件事——哪怕限购、每日上限都还没用完，短时间内对着同一个槽位反复点击也要被挡住，
        // 否则玩家能在触达每日声望/货币上限之前的几秒钟内就把当天额度全部刷完。
        int cooldownSeconds = TraderConfigLoader.config.getSlotRedeemCooldownSeconds();
        if (cooldownSeconds > 0 && slot.lastRedeemedAt > 0
                && now - slot.lastRedeemedAt < cooldownSeconds * 1000L) {
            long waitSeconds = (cooldownSeconds * 1000L - (now - slot.lastRedeemedAt) + 999) / 1000;
            return TraderDataService.Mutation.readOnly(
                    Settlement.fail("§c这个槽位刚交付过，请等 " + waitSeconds + " 秒再试（材料已退还）。"));
        }

        // ── 先「只算不落账」，把每日上限截断后的实际奖励算出来 ────────────────────
        // 拆成「算」和「记账」两段是为了下面那道「零收益拒绝」闸门：拒绝分支返回的是
        // readOnly（不写库），如果算的过程中已经顺手把滚动窗口重置写进了内存里的 data，
        // 就会出现「内存里窗口已重置、库里还是旧窗口」的不一致（load 可能命中同一个对象）。
        // 所以窗口重置和累加一律推迟到确认要 commit 之后再做。
        double moneyAwarded = 0.0;
        boolean moneyWindowExpired = false;
        if (liveOrder.type() == OrderType.MONEY) {
            DailyOrderIncome income = data.dailyOrderIncome;
            moneyWindowExpired = income.windowStartAt == 0L || now - income.windowStartAt > DAY_MILLIS;
            double used = moneyWindowExpired ? 0.0 : income.amount;
            double cap = TraderConfigLoader.config.getDailyOrderIncomeCap();
            double remainingCap = Math.max(0.0, cap - used);
            moneyAwarded = Math.min(liveOrder.price(), remainingCap);
        }

        long reputationAwarded = 0L;
        boolean repWindowExpired = false;
        if (liveOrder.rewardReputation() > 0) {
            DailyOrderReputation repIncome = data.dailyOrderReputation;
            repWindowExpired = repIncome.windowStartAt == 0L || now - repIncome.windowStartAt > DAY_MILLIS;
            long used = repWindowExpired ? 0L : repIncome.amount;
            long repCap = TraderConfigLoader.config.getDailyOrderReputationCap();
            long remainingRepCap = Math.max(0L, repCap - used);
            reputationAwarded = Math.min(liveOrder.rewardReputation(), remainingRepCap);
        }

        // ── 零收益拒绝（2026-08-26 工单修复）─────────────────────────────────────
        // 原设计是「上限之后订单照样算完成、材料照样扣，只是不发奖励」（见 DailyOrderIncome /
        // DailyOrderReputation 的类文档）。这条规则单独看没问题，和 redeem-limit-per-island
        // 叠加之后却会变成不可逆的损失：玩家在额度耗尽后提交，材料被真实扣除、金币和声望都是 0、
        // 终身限购次数却照样 -1，那一次次数就永远拿不回来了。
        //
        // 判定只针对 MONEY 类型：BARTER 的 give-items 配置校验保证非空
        // （OrdersConfigManager 会丢弃 give-items 为空的 barter 订单），哪怕声望被截断到 0，
        // 玩家换到的物品本身仍是实打实的回报，不算「白交」，不该拦。
        if (liveOrder.type() == OrderType.MONEY && moneyAwarded <= 0.0 && reputationAwarded <= 0L) {
            return TraderDataService.Mutation.readOnly(Settlement.fail(
                    "§c本岛今日的订单额度已用尽（金币和声望都到达每日上限），本单未成交，材料已退还。"
                            + "§7额度按 24 小时滚动窗口恢复，恢复后即可继续交付。"));
        }

        // ── 确认要 commit，这才真正落账 ──────────────────────────────────────────
        if (liveOrder.type() == OrderType.MONEY) {
            DailyOrderIncome income = data.dailyOrderIncome;
            if (moneyWindowExpired) {
                income.amount = 0.0;
                income.windowStartAt = now;
            }
            income.amount = ShopEconomics.round2(income.amount + moneyAwarded);
        }
        if (liveOrder.rewardReputation() > 0) {
            DailyOrderReputation repIncome = data.dailyOrderReputation;
            if (repWindowExpired) {
                repIncome.amount = 0L;
                repIncome.windowStartAt = now;
            }
            repIncome.amount += reputationAwarded;
        }

        data.orderCompletions.merge(liveOrder.id(), 1, Integer::sum);
        data.reputation += reputationAwarded;
        // 冷却戳记只在真正走到 commit 的这一刻写入；后面如果发放奖励失败被回滚，
        // 这个时间戳依然保留（见 OrderSlotState#lastRedeemedAt 的类文档，理由是刻意的）。
        slot.lastRedeemedAt = now;

        Settlement ok = Settlement.ok(liveOrder, moneyAwarded, reputationAwarded);
        return TraderDataService.Mutation.commit(ok,
                Settlement.fail("§c数据保存失败，请稍后重试或联系管理员（材料已退还）。"));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 4 步：发放奖励（money 在 async 走 Vault；barter 回玩家线程发物品）
    // ══════════════════════════════════════════════════════════════════════

    private void deliver(Player player, Island island, Settlement settlement, Economy econ) {
        OrderDefinition order = settlement.order();
        UUID playerId = player.getUniqueId();

        if (order.type() == OrderType.MONEY) {
            // 声望/限购计数/每日收入已经在临界区里落库了；金币现在才真正发——这一步失败
            // 必须把临界区那次改动连同材料一起退回去，绝不能出现"材料没了、声望白拿了、
            // 钱却没到账"这种半吊子成功。
            try {
                if (settlement.moneyAwarded() > 0 && econ == null) {
                    // 防御性兜底：正常路径下 econ 在 redeemAsyncStage 里已经按点击时刻的订单类型
                    // 检查过了，这里为 null 只可能是"点击时该槽位是 barter，临界区判定时管理员
                    // 把同一个订单 id 热改成了 money"这种极端时间窗——概率极低但不能让它变成 NPE。
                    log.error("岛屿 {} 玩家 {} 结算订单 '{}' 时经济插件不可用（订单类型可能在结算过程中被"
                            + "热改），已回滚并退还材料", island.getId(), player.getName(), order.id());
                    rollbackAndRefund(player, island, settlement, "经济插件不可用");
                    fail(player, "§c订单结算失败：经济插件当前不可用，材料已退还，请稍后重试或联系管理员。");
                    return;
                }
                if (settlement.moneyAwarded() > 0) {
                    EconomyResponse resp = econ.depositPlayer(player, settlement.moneyAwarded());
                    if (resp == null || !resp.transactionSuccess()) {
                        String reason = resp == null ? "经济插件未返回结果" : resp.errorMessage;
                        log.error("岛屿 {} 玩家 {} 结算订单 '{}' 发放金币失败：{}，已回滚声望/限购/每日收入计数并退还材料",
                                island.getId(), player.getName(), order.id(), reason);
                        rollbackAndRefund(player, island, settlement, "金币发放失败");
                        fail(player, "§c订单结算失败：金币发放异常，材料已退还，请稍后重试或联系管理员。");
                        return;
                    }
                }
            } catch (Throwable t) {
                log.error("岛屿 {} 玩家 {} 结算订单 '{}' 发放金币时抛出异常，已回滚并退还材料",
                        island.getId(), player.getName(), order.id(), t);
                rollbackAndRefund(player, island, settlement, "金币发放异常");
                fail(player, "§c订单结算失败：经济插件出错，材料已退还，请稍后重试或联系管理员。");
                return;
            }

            // player.sendMessage 是线程安全的（ShopPurchaseService 已有同样的用法），
            // 这条消息又不碰背包，不需要跳回玩家线程。
            notifySuccessMoney(player, order, settlement.moneyAwarded(), settlement.reputationAwarded());
            inFlight.remove(playerId);
            return;
        }

        // barter：把 give-items 发到玩家背包，必须回玩家线程。
        Runnable cancelBecauseOffline = () -> {
            log.warn("玩家 {} 在订单 '{}' 材料扣除/记账后、发放物品奖励前下线，已回滚并退还材料",
                    player.getName(), order.id());
            Bukkit.getAsyncScheduler().runNow(plugin, at ->
                    rollbackAndRefund(player, island, settlement, "玩家在发放奖励前下线"));
            inFlight.remove(playerId);
        };

        var scheduled = player.getScheduler().run(plugin, t -> {
            boolean delivered;
            try {
                for (ItemAmount give : order.giveItems()) {
                    Map<Integer, ItemStack> leftover =
                            player.getInventory().addItem(new ItemStack(give.material(), give.amount()));
                    for (ItemStack over : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), over);
                    }
                }
                delivered = true;
            } catch (Throwable ex) {
                delivered = false;
                log.error("岛屿 {} 玩家 {} 结算订单 '{}' 发放物品奖励失败，已回滚并退还材料",
                        island.getId(), player.getName(), order.id(), ex);
                Bukkit.getAsyncScheduler().runNow(plugin, at ->
                        rollbackAndRefund(player, island, settlement, "物品奖励发放异常"));
                player.sendMessage(Component.text("§c订单结算失败：发放奖励异常，材料已退还，请联系管理员。"));
            }
            inFlight.remove(playerId);

            if (delivered) {
                try {
                    notifySuccessBarter(player, order, settlement.reputationAwarded());
                } catch (Throwable ex) {
                    // 奖励已经真的发出去了，这里出错只记日志，绝不能再触发退款/回滚。
                    log.error("玩家 {} 结算订单 '{}' 成功但播报结果消息时出错（不影响交易本身）",
                            player.getName(), order.id(), ex);
                }
            }
        }, cancelBecauseOffline);

        if (scheduled == null) {
            // 调度当场失败：玩家在「材料扣除」到「这一跳」之间已经彻底下线了，
            // 两个回调都不会跑，必须自己触发和 retired 一样的回滚路径。
            cancelBecauseOffline.run();
        }
    }

    private void notifySuccessMoney(Player player, OrderDefinition order, double moneyAwarded, long reputationAwarded) {
        StringBuilder msg = new StringBuilder("§a订单交付成功：§f" + GuiFormat.legacyName(order.displayName()));
        if (moneyAwarded > 0) {
            msg.append("§a，获得 §f").append(GuiFormat.fmt(moneyAwarded)).append(" §a金币");
        }
        if (moneyAwarded < order.price()) {
            msg.append(moneyAwarded > 0
                    ? "§e（今日订单收入已达上限，本单只发放了部分金币）"
                    : "§e（今日订单收入已达上限，本单未发放金币）");
        }
        appendReputationNote(msg, order, reputationAwarded);
        player.sendMessage(Component.text(msg.toString()));
    }

    private void notifySuccessBarter(Player player, OrderDefinition order, long reputationAwarded) {
        StringBuilder msg = new StringBuilder("§a订单交付成功：§f" + GuiFormat.legacyName(order.displayName())
                + "§a，获得 §f" + GuiFormat.describeItems(order.giveItems()));
        appendReputationNote(msg, order, reputationAwarded);
        player.sendMessage(Component.text(msg.toString()));
    }

    /** 声望播报同样要体现"是否被每日上限截断"，和金币的播报逻辑对称，不能只播报金币那一半。 */
    private void appendReputationNote(StringBuilder msg, OrderDefinition order, long reputationAwarded) {
        if (order.rewardReputation() <= 0) return;
        if (reputationAwarded > 0) {
            msg.append("§a，声望 §f+").append(reputationAwarded);
        }
        if (reputationAwarded < order.rewardReputation()) {
            msg.append(reputationAwarded > 0
                    ? "§e（今日声望获取已达上限，本单只发放了部分声望）"
                    : "§e（今日声望获取已达上限，本单未发放声望）");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 回滚
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 把材料原样退还给玩家。<b>必须回玩家线程</b>；玩家已经彻底下线时没有安全的方式把物品
     * 塞回一个离线玩家的背包，只能把细节完整记进错误日志，交给管理员手动补偿——
     * 这条路径和 {@code ShopPurchaseService} 对 Vault 退款失败的处理是同一个思路。
     */
    private void refundMaterials(Player player, Island island, OrderDefinition order, String reasonForLog) {
        var scheduled = player.getScheduler().run(plugin, t -> {
            for (ItemAmount item : order.takeItems()) {
                giveBack(player, item);
            }
        }, () -> logMaterialRefundFailure(player, island, order, reasonForLog));

        if (scheduled == null) {
            logMaterialRefundFailure(player, island, order, reasonForLog);
        }
    }

    private void logMaterialRefundFailure(Player player, Island island, OrderDefinition order, String reasonForLog) {
        log.error("岛屿 {} 玩家 {} 的订单 '{}' 材料退还失败：玩家已下线（原因：{}），"
                        + "需要管理员手动补偿材料：{}",
                island.getId(), player.getName(), order.id(), reasonForLog, GuiFormat.describeItems(order.takeItems()));
    }

    /**
     * 回滚临界区的记账（限购计数/声望/每日声望&货币收入）+ 退还材料——用于"记账已落库但奖励
     * 没能发出去"的失败分支。<b>不回滚 {@code slot.lastRedeemedAt}</b>（同槽位冷却戳记），
     * 理由见 {@link OrderSlotState#lastRedeemedAt} 的类文档。
     */
    private void rollbackAndRefund(Player player, Island island, Settlement settlement, String reasonForLog) {
        boolean ok = dataService.mutate(island, data -> undoSettlement(
                data, settlement.order(), settlement.moneyAwarded(), settlement.reputationAwarded()));
        if (!ok) {
            log.error("岛屿 {} 的订单结算回滚写库失败（订单 '{}'），限购/声望/每日订单收入计数可能不准确，"
                    + "需要管理员手动核实", island.getId(), settlement.order().id());
        }
        refundMaterials(player, island, settlement.order(), reasonForLog);
    }

    private void undoSettlement(TraderIslandData data, OrderDefinition order, double moneyAwarded, long reputationAwarded) {
        Integer count = data.orderCompletions.get(order.id());
        if (count != null) {
            if (count <= 1) {
                data.orderCompletions.remove(order.id());
            } else {
                data.orderCompletions.put(order.id(), count - 1);
            }
        }
        // 回滚"实际发放的数额"（可能已经被每日上限截断过），不是订单定义里的原始数额——
        // 两者在触达上限那一单会不一致，回滚错了会导致 dailyOrderReputation/dailyOrderIncome
        // 被多减，下一次判定时错误地认为"今天还有更多额度没用"。
        data.reputation = Math.max(0L, data.reputation - reputationAwarded);
        if (reputationAwarded > 0) {
            data.dailyOrderReputation.amount = Math.max(0L, data.dailyOrderReputation.amount - reputationAwarded);
        }
        if (moneyAwarded > 0) {
            data.dailyOrderIncome.amount = Math.max(0.0,
                    ShopEconomics.round2(data.dailyOrderIncome.amount - moneyAwarded));
        }
    }

    // ══════════════════════════════════════════════════════════════════════

    private void fail(Player player, String message) {
        player.sendMessage(Component.text(message));
        inFlight.remove(player.getUniqueId());
    }
}
