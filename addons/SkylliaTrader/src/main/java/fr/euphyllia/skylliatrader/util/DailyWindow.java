package fr.euphyllia.skylliatrader.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 每日额度的日历日窗口：每天早上 8:00（{@code Asia/Shanghai}）整点刷新。
 * <p>
 * 用于订单金币/声望上限和回收额度，<b>不用</b>于商店限购——商店限购仍是
 * {@code ShopPurchaseLimitPeriod} 的滚动窗口。
 * </p>
 * <p>
 * 业务日从当天 8:00 开始，到次日 8:00 之前算同一天。7:59 还算「昨天」，
 * 8:00:00 起算新的一天。上海时区没有夏令时，8 点永远是 8 点。
 * </p>
 * <p>
 * 存量数据里 {@code windowStartAt} 可能是旧滚动窗口写下的任意时刻。
 * {@link #expired(long, long)} 只比较它是否早于当前业务日的 8:00，
 * 不需要迁移字段。
 * </p>
 */
public final class DailyWindow {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int RESET_HOUR = 8;

    private DailyWindow() {
    }

    /** 当前业务日的起始时间戳（当天或昨天的 8:00，epoch millis）。 */
    public static long currentPeriodStart(long nowMillis) {
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(ZONE);
        ZonedDateTime start = now.withHour(RESET_HOUR).withMinute(0).withSecond(0).withNano(0);
        if (now.isBefore(start)) {
            start = start.minusDays(1);
        }
        return start.toInstant().toEpochMilli();
    }

    /**
     * 这个计数器是不是已经跨过最近一次 8:00。{@code windowStartAt <= 0}
     * （含从未用过）视为已过期，调用方按「新窗口、用量 0」处理。
     */
    public static boolean expired(long windowStartAt, long nowMillis) {
        return windowStartAt <= 0L || windowStartAt < currentPeriodStart(nowMillis);
    }
}
