package fr.euphyllia.skylliatrader.configuration.model;

import fr.euphyllia.skylliatrader.merchant.CaravanType;
import java.util.Locale;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * {@code shop.toml} 里一条 {@code [[shop-item]]} 的解析结果。
 * <p>
 * 和 {@code OrderDefinition} 一样是显式配置条目，不做公式批量生成——参见
 * {@code shop.toml} 顶部的字段说明与 {@code ShopConfigManager} 的校验规则。
 * </p>
 * <p>
 * <b>货架是固定全目录，不做随机抽取</b>（HANDOFF 6.6 的 T3 拍板）：玩家解锁某档后，该档全部
 * 商品永远可见可买，价格是这里的固定一口价（会再乘以消费折扣，见
 * {@code fr.euphyllia.skylliatrader.shop.ShopPurchaseService}），不存在"从商品池里随机抽一批
 * 展示"这回事。
 * </p>
 *
 * @param id                  商品唯一标识，构造时已规范化（去空白 + 转小写）
 * @param material            商品对应的 {@link Material}
 * @param displayName         MiniMessage 格式的展示名
 * @param price               基础单价（未打折前），Vault 金币
 * @param unlockTrack         解锁轨道
 * @param unlockTier          该轨道要求达到的原始数值（不是档位下标）
 * @param naturalVisible      是否也在自然刷新的路人商人货架上出现
 * @param purchaseLimitPeriod 限购周期，{@link ShopPurchaseLimitPeriod#NONE} 表示不限购
 * @param purchaseLimitCount  该周期内每岛限购数量；{@code purchaseLimitPeriod == NONE} 时忽略。
 *                            <b>钻石这一条的这个字段只是文档占位值</b>——真实限购数字按岛屿等级
 *                            动态决定，运行时由 {@code ShopPurchaseService} 按
 *                            {@code material == Material.DIAMOND} 特判覆盖，不读这个字段
 * @param extraGate           额外门槛标记，{@link ShopExtraGate#NONE} 表示无（单轨判定）
 * @param caravan             专供商队：非空表示这条商品只出现在该种商队（凭证游商）的货架上，
 *                            {@code null} 表示所有商队通卖。这是货架划分而不是安全边界——
 *                            购买事务只认解锁轨道，条目本身也只会出现在对应商队的界面里
 * @param recyclable          回收站是否收这件商品（可再生物必须 false，见 HANDOFF 5.1）
 */
public record ShopItemDefinition(
        String id,
        Material material,
        String displayName,
        double price,
        ShopUnlockTrack unlockTrack,
        long unlockTier,
        boolean naturalVisible,
        ShopPurchaseLimitPeriod purchaseLimitPeriod,
        int purchaseLimitCount,
        ShopExtraGate extraGate,
        boolean recyclable,
        @Nullable CaravanType caravan
) {

    public ShopItemDefinition {
        // id 规范化规则必须和 OrderDefinition 一致：去首尾空白 + 转小写，
        // 否则 "Sand" 和 "sand" 会被当成两条商品却共用同一个限购计数器 key。
        id = normalizeId(id);
    }

    /** 商品 id 的规范化规则：去首尾空白 + 转小写。加载校验和限购计数持久化都要用它。 */
    public static String normalizeId(String rawId) {
        return rawId.trim().toLowerCase(Locale.ROOT);
    }
}
