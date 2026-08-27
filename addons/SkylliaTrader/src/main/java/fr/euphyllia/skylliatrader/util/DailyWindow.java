package fr.euphyllia.skylliatrader.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 日历日窗口：每天早上 8:00（{@code Asia/Shanghai}）整点刷新。
 * <p>
 * 订单金币/声望上限、回收额度、商店日/周/月限购都走这里。
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
    public static final long DAY_MILLIS = 86_400_000L;

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
        return expired(windowStartAt, nowMillis, 1);
    }

    /**
     * 日/周/月限购是否已跨过当前窗口。
     * <p>
     * {@code periodDays <= 1}：只要 {@code windowStartAt} 早于本业务日 8:00 就算过期
     * （旧滚动窗口写下的任意时刻，今天 8:00 一过就清）。
     * 周/月：从窗口起点起满 {@code periodDays} 个业务日才清，起点按 8:00 对齐。
     * </p>
     */
    public static boolean expired(long windowStartAt, long nowMillis, int periodDays) {
        if (windowStartAt <= 0L) return true;
        long start = currentPeriodStart(nowMillis);
        if (periodDays <= 1) {
            return windowStartAt < start;
        }
        return start - windowStartAt >= periodDays * DAY_MILLIS;
    }
}
