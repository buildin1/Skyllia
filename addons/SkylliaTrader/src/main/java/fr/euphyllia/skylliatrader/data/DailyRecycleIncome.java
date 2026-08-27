package fr.euphyllia.skylliatrader.data;

/**
 * 一座岛屿「某件商品今日通过回收获得的货币总额」计数器
 * （{@code TraderIslandData#recycleIncomeByItem} 的 value）。
 * <p>
 * 2026-08-24 起按<b>单个物品</b>计，不再全岛所有商品加总。
 * 旧字段 {@code TraderIslandData#dailyRecycleIncome} 只为 Gson 兼容保留，判定不再读它。
 * <p>
 * 用途：堵住回收系统的通胀口。回收价是按商品售价的固定比例算的，而售价反映的是
 * "从游商手里买它有多稀缺"，不是"玩家自己产它有多难"——对可再生物品来说后者约等于 0。
 * 主要防线是 {@code shop.toml} 的 {@code recyclable = false}（把树苗/竹子/仙人掌这类
 * 农场可无限量产的商品直接排除在回收之外，对齐 HANDOFF 5.1 计分体系给这些材料判 ×0 分的
 * 同一条铁律）；本计数器是<b>第二道保险</b>：即便将来新增商品时漏标了 recyclable，
 * 或者某件"不算可再生但其实能大批量刷"的商品被找到，每日上限也能把损失兜在一个可控范围内。
 * </p>
 * <p>
 * 结构和判定逻辑与 {@link DailyOrderIncome} 完全一致（每天早上 8 点整点刷新）。
 * <b>刻意不复用同一个类实例</b>：订单收入和回收收入是两条独立的额度，共用一个计数器会让
 * "今天订单赚得多"直接压制回收额度，那不是设计意图。
 * </p>
 */
public class DailyRecycleIncome {

    /** 当前窗口内已经通过回收获得的货币总额。 */
    public double amount = 0.0;

    /** 当前窗口的起始时间戳（epoch millis）。 */
    public long windowStartAt = 0L;
}
