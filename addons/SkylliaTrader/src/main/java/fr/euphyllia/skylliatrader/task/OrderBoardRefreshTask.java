package fr.euphyllia.skylliatrader.task;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.bridge.IslandLevelBridge;
import fr.euphyllia.skylliatrader.configuration.OrdersConfigLoader;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.OrderDefinition;
import fr.euphyllia.skylliatrader.data.OrderSlotState;
import fr.euphyllia.skylliatrader.data.TraderDataService;
import fr.euphyllia.skylliatrader.data.TraderIslandData;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 商队订单看板的巡检任务：定期检查全服岛屿的订单槽位，把空槽/过期槽位按权重重新抽取
 * orders.toml 里的订单填上。结构照抄 {@link NaturalSpawnTask}（HANDOFF 要求的既有巡检范式）。
 *
 * <h2>为什么遍历"所有岛屿"而不是"有游商的岛屿"</h2>
 * <p>
 * 订单看板在数据模型上是<b>挂在岛屿本身</b>的一份状态（{@code TraderIslandData#orderSlots}），
 * 不依赖岛上是否真的站着一个游商实体——{@code TraderIslandListener} 在岛屿首次被本插件见到时
 * 就会把槽位数组补齐（见 {@code ensureOrderSlots}），玩家用 {@code /is trader orders} 随时能打开
 * 看板 GUI，不要求岛上先召唤/刷出一个游商。如果改成"只处理有常驻/自然游商的岛屿"，还需要
 * 额外判定"游商在不在"，而这个判定本身就有一堆边缘情况（自然游商停留几分钟就走、凭证游商
 * 可能被打死还没重召），徒增复杂度却换不来任何正确性或性能上的好处——反正每座岛屿的
 * 订单数据本来就已经在数据库里，遍历它不会比先判"有没有游商"更贵。
 * </p>
 *
 * <h2>为什么先做一次廉价预判再决定要不要进临界区</h2>
 * <p>
 * {@link TraderDataService#mutate} 每调用一次就是一次读库 + 一次写库。如果每一轮巡检都对
 * <b>全部</b>岛屿无条件调用 mutate，大多数轮次里绝大多数岛屿其实什么槽位都没到期，这些
 * UPDATE 全是白写——正是 config.toml 里"别频繁到造成不必要的数据库压力"那句警告想避免的。
 * 所以这里先用 {@link TraderDataService#load} 做一次只读预判：<b>这次预判结果不是最终判定</b>，
 * 真正决定"刷不刷"的逻辑在 {@link #refreshSlots} 里、且跑在 {@code mutate} 的临界区内，会按
 * 当时最新的状态重新判一遍。预判读到的哪怕是几毫秒前的过期快照，最坏结果也只是
 * "该刷新的岛这一轮没进临界区，下一轮巡检还会再判一次"——不会导致任何数据错误，
 * 因为它只影响"要不要尝试"，不影响"真的刷新时改了什么"。
 * </p>
 */
public final class OrderBoardRefreshTask {

    private static final Logger log = LoggerFactory.getLogger(OrderBoardRefreshTask.class);

    private OrderBoardRefreshTask() {
    }

    /**
     * 挂上定时巡检。
     * <p>
     * ⚠️ 巡检间隔在<b>启动时</b>读一次就固定了（{@code runAtFixedRate} 的周期没法在运行中改），
     * 理由和 {@link NaturalSpawnTask#start} 一致；刷新周期本身（{@code order-board.refresh-hours}）
     * 和每日收入上限都是每次判定时现读的，reload 立即生效。
     * </p>
     */
    public static void start(@NotNull SkylliaTrader plugin, @NotNull TraderDataService dataService) {
        int intervalMinutes = TraderConfigLoader.config.getOrderBoardCheckIntervalMinutes();
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            try {
                tick(dataService);
            } catch (Throwable t) {
                // 巡检任务抛出去的异常会被 asyncScheduler 吞掉，而且下一轮还会照常跑——
                // 不打日志的话，订单看板会静默停止刷新，玩家只会看到槽位一直是老样子。
                log.error("订单看板巡检出错", t);
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    private static void tick(TraderDataService dataService) {
        List<Island> islands = SkylliaAPI.getAllIslandsValid();
        for (Island island : islands) {
            try {
                refreshIslandIfNeeded(dataService, island);
            } catch (Throwable t) {
                // 单座岛屿处理失败不该影响其它岛屿——理由同 NaturalSpawnTask 的既有范式。
                log.error("巡检岛屿 {} 的订单看板刷新失败", island.getId(), t);
            }
        }
    }

    private static void refreshIslandIfNeeded(TraderDataService dataService, Island island) {
        int slotCount = TraderConfigLoader.config.getOrderSlotsPerIsland();
        // 补齐槽位数组占位（如果这座岛还没被 TraderIslandListener 处理过）。
        // ensureOrderSlots 走的是和 mutate 相同的那把岛屿互斥锁，和下面的刷新不会互相覆盖。
        dataService.ensureOrderSlots(island, slotCount);

        TraderIslandData preview = dataService.load(island);
        long now = System.currentTimeMillis();
        boolean anyNeedsRefresh = false;
        for (OrderSlotState slot : preview.orderSlots) {
            if (needsRefresh(slot, now)) {
                anyNeedsRefresh = true;
                break;
            }
        }
        if (!anyNeedsRefresh) return;

        long islandLevel = IslandLevelBridge.isAvailable() ? IslandLevelBridge.getIslandLevel(island) : 0L;
        boolean saved = dataService.mutate(island, data -> refreshSlots(data, islandLevel, now));
        if (!saved) {
            log.error("岛屿 {} 的订单看板刷新写库失败，下一轮巡检会重试", island.getId());
        }
    }

    private static boolean needsRefresh(OrderSlotState slot, long now) {
        return slot.orderId == null || (slot.expiresAt > 0 && now > slot.expiresAt);
    }

    /**
     * 在 {@code mutate} 临界区内执行的真正刷新逻辑：<b>只做纯内存操作</b>，不碰数据库/网络
     * （{@code TraderDataService} 对 mutator 的硬性要求）。
     */
    private static void refreshSlots(TraderIslandData data, long islandLevel, long now) {
        long refreshMillis = TraderConfigLoader.config.getOrderRefreshHours() * 3_600_000L;
        List<OrderDefinition> pool = OrdersConfigLoader.config.getOrders();

        // 同一时刻已经绑定在别的槽位上、且还没过期的订单 id 排除掉，避免同一块看板上同时出现
        //两条一模一样的订单——不是 HANDOFF 明令要求，但明显更符合玩家体验，成本也很低。
        Set<String> alreadyBound = new HashSet<>();
        for (OrderSlotState slot : data.orderSlots) {
            if (slot.orderId != null && !(slot.expiresAt > 0 && now > slot.expiresAt)) {
                alreadyBound.add(slot.orderId);
            }
        }

        for (OrderSlotState slot : data.orderSlots) {
            if (!needsRefresh(slot, now)) continue;

            OrderDefinition picked = pickWeighted(pool, islandLevel, data.reputation, alreadyBound);
            if (picked == null) {
                // 没有任何符合条件的订单可抽（orders.toml 全部被停用，或者这座岛的等级/声望
                // 太低连门槛最低的订单都够不到）——保持空槽，下一轮巡检再试，不抛异常。
                slot.orderId = null;
                slot.assignedAt = 0L;
                slot.expiresAt = 0L;
                continue;
            }

            slot.orderId = picked.id();
            slot.assignedAt = now;
            slot.expiresAt = now + refreshMillis;
            alreadyBound.add(picked.id());
        }
    }

    /**
     * 按 {@code weight} 做加权随机抽取一条符合条件（启用中 + 岛屿等级/声望达标）的订单。
     * <p>
     * 先按"排除已经绑在别的槽位上的订单"抽一遍；如果这样抽完发现无货可抽（比如全岛屿资格
     * 订单本来就只有 2 条，却要填 3 个槽位），放宽这条去重限制再抽一次——好过让槽位空着。
     * </p>
     *
     * @return 抽中的订单；没有任何订单满足条件时返回 {@code null}
     */
    private static OrderDefinition pickWeighted(List<OrderDefinition> pool, long islandLevel,
                                                 long reputation, Set<String> exclude) {
        OrderDefinition picked = pickWeightedFrom(eligibleOrders(pool, islandLevel, reputation, exclude));
        if (picked != null) return picked;
        return pickWeightedFrom(eligibleOrders(pool, islandLevel, reputation, Set.of()));
    }

    private static List<OrderDefinition> eligibleOrders(List<OrderDefinition> pool, long islandLevel,
                                                         long reputation, Set<String> exclude) {
        List<OrderDefinition> eligible = new ArrayList<>();
        for (OrderDefinition order : pool) {
            if (!order.enabled()) continue;
            if (order.requiredLevelMin() > islandLevel) continue;
            if (order.requiredReputationMin() > reputation) continue;
            if (exclude.contains(order.id())) continue;
            eligible.add(order);
        }
        return eligible;
    }

    private static OrderDefinition pickWeightedFrom(List<OrderDefinition> eligible) {
        long totalWeight = 0;
        for (OrderDefinition order : eligible) {
            totalWeight += Math.max(1, order.weight());
        }
        if (eligible.isEmpty() || totalWeight <= 0) return null;

        long roll = ThreadLocalRandom.current().nextLong(totalWeight);
        long cursor = 0;
        for (OrderDefinition order : eligible) {
            cursor += Math.max(1, order.weight());
            if (roll < cursor) return order;
        }
        // 理论上到不了这里（roll < totalWeight 恒成立），留一个防御性兜底而不是抛异常。
        return eligible.get(eligible.size() - 1);
    }
}
