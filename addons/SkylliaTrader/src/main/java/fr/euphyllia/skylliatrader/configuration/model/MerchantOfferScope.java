package fr.euphyllia.skylliatrader.configuration.model;

import fr.euphyllia.skylliatrader.merchant.MerchantOrigin;

/**
 * 一个游商<b>能卖什么</b>的范围描述。
 * <p>
 * 自然刷新的游商和凭证游商<b>共用同一套商品池机制</b>（同一份档位表、同一套解锁判定），
 * 区别只在这个「范围」上——把差异收敛成一个值对象，而不是在商店代码里到处写
 * {@code if (origin == NATURAL)}，是为了让 T3 的商店 GUI 只需要读这一个对象就能决定摆什么，
 * 将来加第三种来源（比如活动商人）也只是多一个工厂方法。
 * </p>
 *
 * <h2>⚠️ T2 的边界</h2>
 * <p>
 * T2 只负责<b>定义并计算出</b>这个范围（生成游商时按来源取一个，供 T3 使用），
 * <b>不实现</b>按范围筛选商品、渲染 GUI、扣款发货——那些都是 T3。
 * </p>
 *
 * @param maxTradeCountTierIndex 交易次数轨最多开放到第几档（0-based 下标）。
 *                               {@link Integer#MAX_VALUE} 表示不限制，按岛屿真实进度走
 * @param islandLevelTrack       是否开放「岛屿等级 → 建材大宗」这条轨
 * @param reputationTrack        是否开放「商会声望 → 稀有池」这条轨
 * @param guidebook              是否额外上架说明书（见 {@link GuidebookConfig}）
 */
public record MerchantOfferScope(
        int maxTradeCountTierIndex,
        boolean islandLevelTrack,
        boolean reputationTrack,
        boolean guidebook
) {

    /**
     * 自然刷新游商的范围：只开交易次数轨的头几档（默认只有第 0 档那批基础生活物资），
     * 不开建材大宗、不开稀有池，额外卖一本说明书。
     * <p>
     * 「只卖基础池」不是为了抠门，而是为了让凭证有意义：如果路过的野生游商什么都卖，
     * 玩家就没有理由去做挑战任务换凭证了。说明书则正好把「想要更多？去拿凭证」这句话说出口。
     * </p>
     *
     * @param maxTradeCountTierIndex 配置项 {@code natural-spawn.max-trade-count-tier}
     * @param guidebookEnabled       配置项 {@code guidebook.enabled}
     */
    public static MerchantOfferScope natural(int maxTradeCountTierIndex, boolean guidebookEnabled) {
        return new MerchantOfferScope(Math.max(0, maxTradeCountTierIndex), false, false, guidebookEnabled);
    }

    /** 凭证游商的范围：四条轨道全开，按岛屿的真实进度决定实际能看到什么。 */
    public static MerchantOfferScope credential() {
        return new MerchantOfferScope(Integer.MAX_VALUE, true, true, false);
    }

    /** 按来源取范围。 */
    public static MerchantOfferScope of(MerchantOrigin origin, int naturalMaxTradeCountTier, boolean guidebookEnabled) {
        return origin == MerchantOrigin.CREDENTIAL
                ? credential()
                : natural(naturalMaxTradeCountTier, guidebookEnabled);
    }
}
