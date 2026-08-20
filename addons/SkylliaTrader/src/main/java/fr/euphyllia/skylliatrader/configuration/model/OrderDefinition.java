package fr.euphyllia.skylliatrader.configuration.model;

import java.util.List;
import java.util.Locale;

/**
 * orders.toml 里一条 {@code [[order]]} 的解析结果。
 * <p>
 * T1 阶段只负责把配置读成这个不可变对象供管理端 GUI 展示；真正"执行一次订单交易"
 * （扣物品、发放金币/物品、加声望、写终身完成次数）是 T2 的范围，这里不实现。
 * </p>
 *
 * <h2>为什么 money 类型也用 take-items</h2>
 * <p>
 * money 类型原本只有 {@code material + price} 两个字段，这有三个说不通的地方：
 * </p>
 * <ol>
 *   <li>没有数量，就定义不出"完成一次订单"——"收购 64 个圆石支付 32 金币"根本写不出来；</li>
 *   <li>{@code redeem-limit-per-island = 5} 到底是"最多卖 5 次"还是"最多卖 5 个"没有答案；</li>
 *   <li>和 barter 的 {@link #takeItems} 不对称，T2 的"扣玩家物品"逻辑没法共用一套代码。</li>
 * </ol>
 * <p>
 * 所以现在两种类型都用 {@link #takeItems} 描述"岛屿要交出去的东西"，区别只在商队怎么付账：
 * money 付 {@link #price} 金币，barter 付 {@link #giveItems} 物品。
 * </p>
 *
 * @param id                     订单唯一标识，构造时已规范化（去空白 + 转小写），
 *                               直接用作持久化里"终身完成次数"的计数键
 * @param enabled                是否启用；关闭的订单不会出现在可刷新的订单池里
 * @param displayName            MiniMessage 格式的展示名
 * @param type                   {@link OrderType#MONEY} 或 {@link OrderType#BARTER}
 * @param price                  MONEY 类型专用：<b>完成这一整单</b>商队支付的 Vault 总金额，
 *                               不是单价——单价乘数量的算法一旦引入，"改 take-items 数量会
 *                               连带改总价"就成了隐式行为，管理员配起来很难对账
 * @param giveItems              BARTER 类型专用：商队支付给岛屿的物品，最多 4 组；MONEY 下为空
 * @param takeItems              两种类型共用：岛屿要交给商队的物品，最多 4 组，不能为空
 * @param rewardReputation       完成一次订单获得的商会声望（不小于 0）；
 *                               barter 类型下填 0 即为"纯物物兑换，不换声望"
 * @param weight                 自然刷新时的抽取权重（T2 使用），T1 只读不用
 * @param redeemLimitPerIsland   同一岛屿"终身"最多完成<b>几单</b>，0 = 不限；
 *                               计数必须挂在岛屿维度，不能挂玩家维度
 * @param requiredLevelMin       解锁该订单需要的岛屿等级门槛
 * @param requiredReputationMin  解锁该订单需要的商会声望门槛
 */
public record OrderDefinition(
        String id,
        boolean enabled,
        String displayName,
        OrderType type,
        double price,
        List<ItemAmount> giveItems,
        List<ItemAmount> takeItems,
        int rewardReputation,
        int weight,
        int redeemLimitPerIsland,
        int requiredLevelMin,
        int requiredReputationMin
) {

    /** give-items / take-items 各自最多允许配置的 material+amount 组数。 */
    public static final int MAX_ITEMS_PER_SIDE = 4;

    public OrderDefinition {
        // id 在这里就规范化，避免"订单定义里用原样大小写、完成次数存储里用小写"两套 key
        // ——那会让 "Iron-Buy" 和 "iron-buy" 变成两条订单却共用同一个限购计数器。
        id = normalizeId(id);
        giveItems = List.copyOf(giveItems);
        takeItems = List.copyOf(takeItems);
    }

    /**
     * 订单 id 的规范化规则：去首尾空白 + 转小写。
     * <p>
     * 配置解析和持久化计数都必须用它，两边不能各写一套——这是本类和
     * {@code TraderIslandData#orderCompletions} 之间唯一的 key 约定。
     * </p>
     */
    public static String normalizeId(String rawId) {
        return rawId.trim().toLowerCase(Locale.ROOT);
    }

    /** GUI / 日志里给这条订单挑一个代表性图标用的材料。 */
    public org.bukkit.Material iconMaterial() {
        if (type == OrderType.BARTER && !giveItems.isEmpty()) {
            return giveItems.get(0).material();
        }
        return takeItems.isEmpty() ? org.bukkit.Material.PAPER : takeItems.get(0).material();
    }
}
