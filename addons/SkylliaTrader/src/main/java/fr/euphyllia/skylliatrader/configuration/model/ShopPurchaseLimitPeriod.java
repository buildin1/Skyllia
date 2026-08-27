package fr.euphyllia.skylliatrader.configuration.model;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * 商品限购的周期。日/周/月按早上 8:00（{@code Asia/Shanghai}）对齐，见 {@code DailyWindow}，
 * 不再用「从上次购买起滚 24 小时」——玩家会卡在「昨天 10 点买过、今天 8 点任务刷新了却还买不了」。
 * <p>
 * {@link #NONE} 和 {@link #LIFETIME} 没有"重置"这个概念，见各自方法上的说明。
 * </p>
 */
public enum ShopPurchaseLimitPeriod {

    /** 不限购。{@code purchase-limit-count} 字段被忽略。 */
    NONE,

    /** 每个业务日（早 8:00 起）一个窗口。 */
    DAILY,

    /** 每 7 个业务日一个窗口，从窗口起始那天的 8:00 起算。 */
    WEEKLY,

    /** 每 30 个业务日一个窗口（近似月，不是真实日历月）。 */
    MONTHLY,

    /** 终身限购：窗口永不重置，{@code count} 只加不清。典型用途：锻造模板这类"一次性"商品。 */
    LIFETIME;

    /**
     * 本周期覆盖几个业务日。{@link #NONE} 和 {@link #LIFETIME} 返回 0，调用前先用 {@link #resets()}。
     */
    public int periodDays() {
        return switch (this) {
            case DAILY -> 1;
            case WEEKLY -> 7;
            case MONTHLY -> 30;
            case NONE, LIFETIME -> 0;
        };
    }

    /** 是否存在"限购数量"这个概念（{@link #NONE} 没有，其余都有）。 */
    public boolean limited() {
        return this != NONE;
    }

    /** 窗口是否会随时间重置（{@link #LIFETIME} 永不重置，日/周/月都会）。 */
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
