package fr.euphyllia.skylliatrader.merchant;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 一个游商是怎么来的。这决定了它的<b>生命周期</b>和<b>能卖什么</b>，两件事都靠它区分：
 *
 * <table border="1">
 *   <caption>两种来源的差异</caption>
 *   <tr><th></th><th>{@link #NATURAL}</th><th>{@link #CREDENTIAL}</th></tr>
 *   <tr><td>占凭证名额</td><td>否</td><td>是（每岛每种商队 1 个）</td></tr>
 *   <tr><td>岛屿数据里有记录</td><td>否，全在内存 + 实体 PDC</td><td>有，是名额判定的权威源</td></tr>
 *   <tr><td>停留时长</td><td>配置的分钟数，到点自己走</td><td>永久常驻，只会被杀死</td></tr>
 *   <tr><td>尊重岛屿标志</td><td>是（{@code skyllia:island.spawn.passive.wandering_villager}）</td><td>否，付了凭证就该给</td></tr>
 *   <tr><td>商品范围</td><td>只有交易次数轨的基础档 + 说明书</td><td>四轨全部档位</td></tr>
 * </table>
 *
 * <p>
 * 枚举名会写进实体 PDC，改名会让重启后已存在的游商识别不出来源，参见
 * {@link CaravanType} 上同样的告诫。
 * </p>
 */
public enum MerchantOrigin {

    /** 岛上有人在线时按概率自然刷出来的临时游商。 */
    NATURAL,

    /** 玩家用商队凭证召唤出来的永久常驻游商。 */
    CREDENTIAL;

    /** 按枚举名解析，无法识别时返回 {@code null}（理由同 {@link CaravanType#parseOrNull}）。 */
    public static @Nullable MerchantOrigin parseOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
