package fr.euphyllia.skylliatrader.data;

/**
 * 一座岛屿"今日通过商队订单获得的货币总额"计数器（{@code TraderIslandData#dailyOrderIncome}）。
 * <p>
 * 用途：HANDOFF 9.3 拍板的每日订单货币收入上限——一旦当日已获得金额达到
 * {@code config.toml} 的 {@code order-board.daily-order-income-cap}，超额部分只给声望不给钱
 * （订单依然算完成、材料依然被扣、声望依然发），防止订单收入比打工赚得还多。
 * </p>
 * <p>
 * <b>2026-08-26 工单修复的例外</b>：如果货币额度和 {@link DailyOrderReputation} 的声望额度
 * <b>同时</b>都已用尽，money 类型订单不再"照样算完成"，而是在
 * {@code OrderBoardService#computeSettlement} 里被直接拒绝——不扣材料、不记完成次数。
 * 原来那条"照样算完成"的规则单独看没问题，和 {@code redeem-limit-per-island} 叠加之后
 * 却会变成不可逆损失：零收益还要吃掉一次终身限购次数。同一批修复里 money 订单的终身限购
 * 已经全部取消（orders.toml 改成 0），但拒绝逻辑仍然要保留——它挡的是"白扣材料"，
 * 和限购次数是两回事。
 * </p>
 * <p>
 * 窗口按日历日对齐，每天早上 8:00（{@code Asia/Shanghai}）整点刷新，见
 * {@code DailyWindow}。商店日/周/月限购也走同一套窗口。
 * </p>
 * <p>
 * <b>两个字段初始值都是 0</b>，对老岛屿/首次完成订单都正确：{@code amount = 0} 表示
 * "这个窗口还没通过订单拿过钱"，{@code windowStartAt = 0} 会让第一次判定必然触发
 * "重置"（把 windowStartAt 设成当前业务日的 8:00）。
 * </p>
 */
public class DailyOrderIncome {

    /** 当前窗口内已经通过订单结算获得的货币总额。 */
    public double amount = 0.0;

    /** 当前窗口的起始时间戳（epoch millis）。 */
    public long windowStartAt = 0L;
}
