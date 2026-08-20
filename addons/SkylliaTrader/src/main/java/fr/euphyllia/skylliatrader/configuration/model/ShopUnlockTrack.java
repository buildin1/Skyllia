package fr.euphyllia.skylliatrader.configuration.model;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * 商店商品条目的解锁轨道（三选一，不含"累计消费"——那条只影响折扣/限购加成，不解锁商品）。
 * <p>
 * 对应 {@code TrackTiers} 的三张档位表：{@link #TRADE_COUNT} → {@code TrackTiers#tradeCount()}，
 * {@link #ISLAND_LEVEL} → {@code TrackTiers#islandLevel()}，{@link #REPUTATION} →
 * {@code TrackTiers#reputation()}。{@code shop.toml} 里的 {@code unlock-tier} 是这条轨道要求的
 * 原始数值（比如交易次数 10、岛屿等级 5、声望 300），不是档位下标。
 * </p>
 */
public enum ShopUnlockTrack {

    /** 累计交易次数 → 基础生活物资池。 */
    TRADE_COUNT,

    /** 岛屿等级 → 建材大宗池。 */
    ISLAND_LEVEL,

    /** 商会声望 → 稀有池。 */
    REPUTATION;

    /** 按配置字符串解析，无法识别时返回 {@code null}，由调用方决定怎么报错（宁可丢弃整条商品也不要静默降级）。 */
    public static @Nullable ShopUnlockTrack parseOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
