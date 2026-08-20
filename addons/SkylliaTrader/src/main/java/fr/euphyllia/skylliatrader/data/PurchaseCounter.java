package fr.euphyllia.skylliatrader.data;

/**
 * 一条商店商品在一座岛屿上的限购计数器（{@code TraderIslandData#shopPurchaseCounts} 的 value）。
 * <p>
 * 周期判定用<b>滚动窗口</b>，不对齐自然日/自然周/自然月，理由见
 * {@code ShopPurchaseLimitPeriod} 的类注释。字段含义：
 * </p>
 * <ul>
 *   <li>{@link #count} —— 当前窗口内已购买的数量；</li>
 *   <li>{@link #windowStartAt} —— 当前窗口的起始时间戳（epoch millis）。</li>
 * </ul>
 * <p>
 * <b>两个字段的初始值都是 0</b，对老岛屿/首次购买该商品都正确：{@code count = 0} 表示"这个窗口
 * 还没买过"，{@code windowStartAt = 0} 会让第一次判定时 {@code now - windowStartAt} 必然超过
 * 任何周期长度，从而触发一次"重置"（把 windowStartAt 设成 now），效果等价于"从现在开始一个新窗口"。
 * </p>
 */
public class PurchaseCounter {

    /** 当前窗口内已购买的数量。 */
    public int count = 0;

    /** 当前窗口的起始时间戳（epoch millis）。 */
    public long windowStartAt = 0L;
}
