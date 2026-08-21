package fr.euphyllia.skylliatrader.data;

/**
 * 一座岛屿"今日通过商队订单获得的声望总额"计数器（{@code TraderIslandData#dailyOrderReputation}）。
 * <p>
 * 用途：2026-08-21 T4 审查发现的高危补丁——声望是唯一不能用钱或等级替代、且不会衰减、
 * 没有任何事后回收手段的资源，此前只靠 {@code redeem-limit-per-island}（订单终身限购）
 * 节流，而那次修复只是把"无限"改成"一个偏宽松的有限值"，不是真正锁定产出速率。
 * 这里补一个每日滚动窗口上限（{@code order-board.daily-reputation-cap}），一旦当日已获得
 * 声望达到上限，超额部分不再发放（订单依然算完成、材料依然被扣，只是不再加声望）——
 * 和 {@link DailyOrderIncome} 对货币做的事完全对称，只是对象换成了声望。
 * </p>
 * <p>
 * 用<b>独立的类</b>而不是复用 {@link DailyOrderIncome}：{@code reputation} 字段在
 * {@code TraderIslandData} 上是 {@code long}，声望不应该出现小数，用 {@code double} 的
 * {@link DailyOrderIncome} 会引入不必要的取整/精度问题，两个概念也不共享任何行为，
 * 拆开比强行复用更清楚。
 * </p>
 * <p>
 * 滚动窗口（不对齐自然日/时区）的判定逻辑和 {@link DailyOrderIncome} 完全一致：
 * {@code now - windowStartAt > 24小时} 就重置。两个字段初始值都是 0，对老岛屿/首次完成
 * 订单都正确（{@code windowStartAt = 0} 会让第一次判定必然触发"重置"，等价于"从现在开始
 * 一个新窗口"）。
 * </p>
 */
public class DailyOrderReputation {

    /** 当前窗口内已经通过订单结算获得的声望总额。 */
    public long amount = 0L;

    /** 当前窗口的起始时间戳（epoch millis）。 */
    public long windowStartAt = 0L;
}
