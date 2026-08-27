package fr.euphyllia.skylliatrader.data;

/**
 * 一条商店商品在一座岛屿上的限购计数器（{@code TraderIslandData#shopPurchaseCounts} 的 value）。
 * <p>
 * 周期判定用早上 8:00 对齐的业务日窗口，见 {@code DailyWindow}。字段含义：
 * </p>
 * <ul>
 *   <li>{@link #count} —— 当前窗口内已购买的数量；</li>
 *   <li>{@link #windowStartAt} —— 当前窗口的起始时间戳（epoch millis），重置时写成当天 8:00。</li>
 * </ul>
 * <p>
 * <b>两个字段的初始值都是 0</b>，对老岛屿/首次购买该商品都正确：{@code count = 0} 表示"这个窗口
 * 还没买过"，{@code windowStartAt = 0} 会被当成已过期，效果等价于"从当前业务日起一个新窗口"。
 * 旧滚动窗口写下的任意时刻，跨过下一次 8:00 也会清掉，不用迁数据。
 * </p>
 */
public class PurchaseCounter {

    /** 当前窗口内已购买的数量。 */
    public int count = 0;

    /** 当前窗口的起始时间戳（epoch millis）。 */
    public long windowStartAt = 0L;
}
