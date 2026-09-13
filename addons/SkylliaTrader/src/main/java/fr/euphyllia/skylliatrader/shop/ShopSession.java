package fr.euphyllia.skylliatrader.shop;

import fr.euphyllia.skylliatrader.configuration.model.ShopItemDefinition;
import fr.euphyllia.skylliatrader.merchant.CaravanType;
import fr.euphyllia.skylliatrader.merchant.MerchantOrigin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一次打开的商店货架是「谁的货架」：来源 + 商队 + 是否管理员测试模式。
 * <p>
 * GUI 渲染和购买事务都要按同一套规则判断「这件商品在不在这个货架上」，
 * 把规则收在 {@link #shelves} 一处，避免 GUI 摆出来的和服务端认的对不上。
 * </p>
 *
 * @param origin    游商来源，决定走基础池还是四轨解锁
 * @param caravan   商队类型；{@code null} 表示老实体 PDC 读不出商队，只给通卖商品
 * @param adminTest 管理员测试凭证打开的货架：无视解锁/限购/岛屿权限，不写岛屿数据
 */
public record ShopSession(@NotNull MerchantOrigin origin, @Nullable CaravanType caravan, boolean adminTest) {

    public static ShopSession merchant(@NotNull MerchantOrigin origin, @Nullable CaravanType caravan) {
        return new ShopSession(origin, caravan, false);
    }

    public static ShopSession adminTest(@NotNull MerchantOrigin origin, @Nullable CaravanType caravan) {
        return new ShopSession(origin, caravan, true);
    }

    /**
     * 这件商品是否摆在这个货架上（不管解锁与否）。
     * <ul>
     *   <li>路人游商：只看 {@code natural-visible}，<b>不按商队筛</b>——路人游商一律是主世界商队，
     *       按商队筛的话烈焰棒、下界疣这两样断链材料会从基础池里消失，新人开不了酿造；</li>
     *   <li>凭证游商：通卖商品（caravan 为 null）+ 本商队专供商品。</li>
     * </ul>
     */
    public boolean shelves(@NotNull ShopItemDefinition item) {
        if (origin == MerchantOrigin.NATURAL) return item.naturalVisible();
        return item.caravan() == null || item.caravan() == caravan;
    }

    /** 货架名，GUI 标题和提示用。 */
    public String shelfLabel() {
        if (origin == MerchantOrigin.NATURAL) return "路人游商";
        return caravan != null ? caravan.defaultDisplayName() : "商队";
    }
}
