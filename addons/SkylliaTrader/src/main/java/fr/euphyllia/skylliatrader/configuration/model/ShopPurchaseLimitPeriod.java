package fr.euphyllia.skylliatrader.configuration.model;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * 商品限购的周期。<b>全部按"滚动窗口"判定，不对齐自然日/自然周/自然月</b>——
 * 避免时区、月份天数这些复杂度，和项目里 {@code respawn-cooldown-seconds} 这类相对时长的
 * 既有写法保持一致（见 {@code TraderConfigManager}）。
 * <p>
 * 判定方式：记一个 {@code windowStartAt}（epoch millis），如果
 * {@code now - windowStartAt > periodMillis()} 就重置计数并把 {@code windowStartAt} 设成
 * {@code now}，然后再判定这次购买会不会超过限购数量。{@link #NONE} 和 {@link #LIFETIME}
 * 没有"重置"这个概念，见各自方法上的说明。
 * </p>
 */
public enum ShopPurchaseLimitPeriod {

    /** 不限购。{@code purchase-limit-count} 字段被忽略。 */
    NONE,

    /** 每 24 小时一个滚动窗口。 */
    DAILY,

    /** 每 7 天一个滚动窗口。 */
    WEEKLY,

    /** 每 30 天一个滚动窗口（近似月，不是真实日历月）。 */
    MONTHLY,

    /** 终身限购：窗口永不重置，{@code count} 只加不清。典型用途：锻造模板这类"一次性"商品。 */
    LIFETIME;

    private static final long DAY_MILLIS = 86_400_000L;

    /**
     * 本周期的窗口长度（毫秒）。{@link #NONE} 和 {@link #LIFETIME} 没有意义的窗口长度，
     * 调用前必须先用 {@link #resets()} 判断，这两种情况下本方法返回值不应被使用。
     */
    public long windowMillis() {
        return switch (this) {
            case DAILY -> DAY_MILLIS;
            case WEEKLY -> 7 * DAY_MILLIS;
            case MONTHLY -> 30 * DAY_MILLIS;
            case NONE, LIFETIME -> Long.MAX_VALUE;
        };
    }

    /** 是否存在"限购数量"这个概念（{@link #NONE} 没有，其余都有）。 */
    public boolean limited() {
        return this != NONE;
    }

    /** 窗口是否会随时间滚动重置（{@link #LIFETIME} 永不重置，其余滚动周期都会）。 */
    public boolean resets() {
        return this != NONE && this != LIFETIME;
    }

    /** 按配置字符串解析，无法识别时返回 {@code null}，由调用方决定怎么报错。 */
    public static @Nullable ShopPurchaseLimitPeriod parseOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
