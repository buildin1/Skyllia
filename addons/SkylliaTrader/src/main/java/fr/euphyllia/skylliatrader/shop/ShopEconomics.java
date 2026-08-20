package fr.euphyllia.skylliatrader.shop;

import fr.euphyllia.skylliatrader.configuration.model.ShopItemDefinition;
import fr.euphyllia.skylliatrader.configuration.model.ShopPurchaseLimitPeriod;
import fr.euphyllia.skylliatrader.configuration.model.TrackTiers;
import fr.euphyllia.skylliatrader.data.PurchaseCounter;
import org.bukkit.Material;

import java.util.List;

/**
 * "累计消费 → 折扣 + 限购加成"这条轨道的纯计算逻辑（HANDOFF 6.2 维度四 / 9.3 T3 拍板）。
 * <p>
 * 档位表本身来自 {@code config.toml} 的 {@code [track.spending]}（{@link TrackTiers#spending()}），
 * <b>折扣率/限购加成数组按下标对应这张表</b>，不是靠硬编码具体的消费数额去比大小——这样即使
 * 管理员改了 {@code track.spending.tiers} 的具体数字（比如把最后一档从 50000 改成别的值），
 * 折扣/加成的档位数量对不上时才需要改这里的数组长度，数值本身不需要跟着改。
 * </p>
 * <p>
 * ⚠️ <b>T1 遗留的配置默认值已在 T3 修正</b>：{@code TraderConfigManager} 的
 * {@code track.spending} 默认档位表最后一档原本是 {@code 100000}，与 HANDOFF 9.3 公开百科定的
 * {@code 50000}（"消费 1,000/5,000/20,000/50,000 → 折扣 -3%/-6%/-10%/-15%"）不一致，T3 已经把
 * 默认值改成 {@code 50000}。但这只影响"配置文件不存在时首次生成的默认值"——
 * <b>已经跑起来的服务器如果 config.toml 早就存在，这里改了也不会回填过去</b>，需要服主自己去改
 * 线上的 {@code config.toml}（{@code track.spending.tiers}）。
 * 本类刻意<b>不硬编码具体消费数额</b>、只按 {@link TrackTiers#currentTierIndex} 算出的下标去查表，
 * 所以不管配置里最后一档实际是多少，折扣/加成的判定逻辑本身都不受影响——受影响的只是
 * "到底要花多少钱才能碰到最高档"，这是纯配置数值问题，不是代码 bug。
 * </p>
 */
public final class ShopEconomics {

    /** 折扣率，按 {@link TrackTiers#spending()} 的下标对应：0%/3%/6%/10%/15%。 */
    private static final double[] DISCOUNT_RATES = {0.0, 0.03, 0.06, 0.10, 0.15};

    /** 限购额度加成，同样按下标对应：+0/+1/+2/+4/+8。 */
    private static final int[] LIMIT_BONUSES = {0, 1, 2, 4, 8};

    private ShopEconomics() {
    }

    /** 按当前累计消费算折扣率（0.0 ~ 0.15）。 */
    public static double discountRate(double totalSpent, List<Double> spendingTiers) {
        int index = TrackTiers.currentTierIndex(totalSpent, spendingTiers);
        return pick(DISCOUNT_RATES, index);
    }

    /** 按当前累计消费算限购额度加成（只对本来就有限购的商品生效，见调用方）。 */
    public static int limitBonus(double totalSpent, List<Double> spendingTiers) {
        int index = TrackTiers.currentTierIndex(totalSpent, spendingTiers);
        return pick(LIMIT_BONUSES, index);
    }

    private static double pick(double[] table, int index) {
        if (index < 0) return table[0];
        return table[Math.min(index, table.length - 1)];
    }

    private static int pick(int[] table, int index) {
        if (index < 0) return table[0];
        return table[Math.min(index, table.length - 1)];
    }

    /**
     * 折扣后的单价，四舍五入到分（两位小数）。
     *
     * @param basePrice    shop.toml 里的基础单价
     * @param discountRate {@link #discountRate}
     */
    public static double discountedUnitPrice(double basePrice, double discountRate) {
        return round2(basePrice * (1.0 - discountRate));
    }

    /** 四舍五入到分（两位小数），用 {@code Math.round} 而不是 BigDecimal——两位小数的金额场景足够，避免多引入一个类型。 */
    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 商品的限购<b>基础</b>数量（不含消费折扣轨的加成）。
     * <p>
     * 钻石以外的商品直接用 {@code shop.toml} 的 {@code purchase-limit-count}；钻石的限购数字
     * 按岛屿等级动态决定（HANDOFF 9.3 T6：&lt;20 级=1，20-29 级=3，≥30 级=10），
     * shop.toml 里那个字段对钻石只是文档占位值，不参与运行时判定。
     * </p>
     * <p>
     * {@link ShopPurchaseService} 的购买事务和 {@code MerchantShopGui} 的货架展示都要用
     * <b>同一份</b>基础限购数字，所以提到这里共用，避免两处各写一份、改一处忘另一处。
     * </p>
     */
    public static int baseLimitCountFor(ShopItemDefinition item, long islandLevel) {
        if (item.material() == Material.DIAMOND) {
            if (islandLevel < 20) return 1;
            if (islandLevel < 30) return 3;
            return 10;
        }
        return item.purchaseLimitCount();
    }

    /**
     * <b>只读</b>地算出"如果现在要判定，这个计数器当前窗口内的已购数量应该是多少"，不修改
     * {@code counter} 本身。用于 GUI 展示"今日已购 x/y"——GUI 只是把货架摆出来看看，不该有
     * 任何写库副作用；真正的窗口重置只发生在 {@link ShopPurchaseService} 的购买事务临界区里。
     *
     * @param counter 计数器；{@code null} 表示这座岛从没买过这件商品（视为 0）
     */
    public static int currentWindowCount(PurchaseCounter counter, ShopPurchaseLimitPeriod period) {
        if (counter == null) return 0;
        if (!period.resets()) return counter.count;
        long now = System.currentTimeMillis();
        if (counter.windowStartAt == 0L || now - counter.windowStartAt > period.windowMillis()) {
            return 0;
        }
        return counter.count;
    }
}
