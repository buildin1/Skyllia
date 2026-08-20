package fr.euphyllia.skylliatrader.configuration.model;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * 商品的"额外门槛"标记。T6（HANDOFF 9.3）唯一要求的通用机制就这一条，不做成通用多轨框架——
 * 只有下界合金锭需要"声望 ≥1500 <b>且</b> 岛屿等级 ≥45 同时满足才可见"这种双轨判定，
 * 其余商品都是 {@link #NONE}（单轨判定，只看 {@code unlock-track}/{@code unlock-tier}）。
 * <p>
 * <b>与远古残骸的区别（HANDOFF 9.3 明确点出）</b>：远古残骸只受声望单轨门槛控制
 * （声望 ≥1500 起每周 0→2），不叠加岛屿等级门槛；只有"合成好的下界合金锭"这个更省事的商品
 * 才双重把关。写代码时不要把这条规则误套到远古残骸身上。
 * </p>
 */
public enum ShopExtraGate {

    /** 无额外门槛，单轨判定。 */
    NONE,

    /**
     * 下界合金锭专用：除了 {@code unlock-track = REPUTATION, unlock-tier = 1500} 之外，
     * 还要求岛屿等级 ≥45（硬编码常量，见 {@code fr.euphyllia.skylliatrader.shop.ShopPurchaseService}
     * 里的 {@code NETHERITE_MIN_ISLAND_LEVEL}）。任一条件不满足就完全不显示在货架上，
     * 不做成"可见但限购 0"——那样玩家会以为是 bug。
     */
    NETHERITE_INGOT_DUAL;

    /**
     * 按配置字符串解析。空值/缺失（可选字段，绝大多数商品都不填）返回 {@link #NONE}；
     * <b>非空但无法识别的字符串返回 {@code null}</b>，由调用方报错丢弃整条商品——
     * 管理员手滑把 {@code NETHERITE_INGOT_DUAL} 写错，不该被静默当成"无门槛"处理，
     * 那会让一条本该藏起来的稀有商品直接漏出来。
     */
    public static @Nullable ShopExtraGate parseOrDefault(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return NONE;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
