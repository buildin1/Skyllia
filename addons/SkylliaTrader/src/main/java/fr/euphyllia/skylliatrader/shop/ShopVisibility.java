package fr.euphyllia.skylliatrader.shop;

import fr.euphyllia.skylliatrader.configuration.model.ShopExtraGate;
import fr.euphyllia.skylliatrader.configuration.model.ShopItemDefinition;
import fr.euphyllia.skylliatrader.configuration.model.ShopUnlockTrack;
import fr.euphyllia.skylliatrader.configuration.model.TrackTiers;
import fr.euphyllia.skylliatrader.data.TraderIslandData;
import org.bukkit.Material;

import java.util.List;

/**
 * 商品在"凭证游商"货架上的可见性判定与分类（HANDOFF 第六步的锁定商品展示策略）。
 * <p>
 * <b>路人商人（自然刷新）不用这套逻辑</b>——它的货架就是 {@code naturalVisible == true} 的商品
 * 原样列出，没有"预告下一档"这回事，见 {@code MerchantShopGui} 里对 {@code MerchantOrigin.NATURAL}
 * 的单独分支。
 * </p>
 * <p>
 * 三态设计的理由（"四维度的教学价值全在 lore 里"，HANDOFF 6.7）：一次性甩出一整面墙的锁头会吓退
 * 新人，但完全不告诉玩家"还有什么在后面"又失去了教学价值。折中方案是只预告<b>紧邻的下一档</b>：
 * </p>
 * <ul>
 *   <li>{@link #UNLOCKED} —— 已解锁，正常显示、可购买；</li>
 *   <li>{@link #PREVIEW} —— 恰好落在"当前档位的下一档"，灰显 + lore 提示还差多少解锁，不能点击购买；</li>
 *   <li>{@link #HIDDEN} —— 更远的档位（或双轨门槛未同时满足的下界合金锭），完全不出现在货架上。</li>
 * </ul>
 */
public final class ShopVisibility {

    /** 下界合金锭双轨门槛里"岛屿等级"这一半（HANDOFF 9.3 T6：声望 ≥1500 且岛屿等级 ≥45）。 */
    public static final long NETHERITE_MIN_ISLAND_LEVEL = 45L;

    public enum State {
        UNLOCKED, PREVIEW, HIDDEN
    }

    private ShopVisibility() {
    }

    /**
     * 商品当前是否<b>已解锁可购买</b>。购买事务用这个做最终的服务端权威校验——
     * GUI 只按它决定摆不摆得出来，玩家不该有任何绕开它直接购买的路径，但服务端仍要自己再判一遍
     * （防止 GUI 状态与购买时刻的岛屿状态不一致，比如刚好在这一瞬间掉出档位）。
     */
    public static boolean isUnlocked(ShopItemDefinition item, TraderIslandData data, long islandLevel) {
        if (item.extraGate() == ShopExtraGate.NETHERITE_INGOT_DUAL) {
            // 下界合金锭：声望轨的 unlock-track/unlock-tier 仍然要满足（=1500），
            // 额外叠加岛屿等级 ≥45。两条都满足才算解锁，任一不满足就是隐藏，没有"半解锁"状态。
            return trackValue(item.unlockTrack(), data, islandLevel) >= item.unlockTier()
                    && islandLevel >= NETHERITE_MIN_ISLAND_LEVEL;
        }
        return trackValue(item.unlockTrack(), data, islandLevel) >= item.unlockTier();
    }

    /** GUI 渲染用：这条商品应该以哪种状态出现在凭证游商的货架上。 */
    public static State classify(ShopItemDefinition item, TraderIslandData data, long islandLevel, TrackTiers tiers) {
        if (item.extraGate() == ShopExtraGate.NETHERITE_INGOT_DUAL) {
            // 双轨门槛商品不做"预告下一档"：任一条件不满足就完全不显示，
            // 不做成"可见但限购 0"——这是 HANDOFF 9.3 的明确要求。
            return isUnlocked(item, data, islandLevel) ? State.UNLOCKED : State.HIDDEN;
        }

        if (item.material() == Material.DIAMOND) {
            // 钻石是本类"预告下一档"默认策略的唯一例外：HANDOFF 9.3 T6 原话是"声望 <300 时
            // 这条商品完全不出现在玩家能看到的货架列表里（不是看得见但买不了）"——这句话点名
            // 排除的正是 PREVIEW 状态本身（灰显 + lore 就是"看得见但买不了"）。其余单轨商品
            // 仍然走下面的预告逻辑，只有钻石在 HIDDEN/UNLOCKED 之间二选一，没有中间态。
            return isUnlocked(item, data, islandLevel) ? State.UNLOCKED : State.HIDDEN;
        }

        long value = trackValue(item.unlockTrack(), data, islandLevel);
        if (value >= item.unlockTier()) return State.UNLOCKED;

        List<Long> tierList = tierListFor(item.unlockTrack(), tiers);
        int currentIndex = TrackTiers.currentTierIndex(value, tierList);
        Long nextTierValue = (currentIndex + 1 < tierList.size()) ? tierList.get(currentIndex + 1) : null;

        // 商品的 unlock-tier 落在"当前档位的下一档"区间内（含跳档配置的容错：用 <= 而不是 ==，
        // 万一 shop.toml 的 unlock-tier 没有精确对上 config.toml 的档位表，也不会直接把它归到
        // "更远的隐藏档"——宁可多预告一条，也不要吓跑玩家）。
        if (nextTierValue != null && item.unlockTier() <= nextTierValue) {
            return State.PREVIEW;
        }
        return State.HIDDEN;
    }

    /** 距解锁还差多少（PREVIEW 状态才有意义）。 */
    public static long remainingToUnlock(ShopItemDefinition item, TraderIslandData data, long islandLevel) {
        return Math.max(0L, item.unlockTier() - trackValue(item.unlockTrack(), data, islandLevel));
    }

    /** 轨道的中文名，lore 展示用。 */
    public static String trackLabel(ShopUnlockTrack track) {
        return switch (track) {
            case TRADE_COUNT -> "交易次数";
            case ISLAND_LEVEL -> "岛屿等级";
            case REPUTATION -> "商会声望";
        };
    }

    private static long trackValue(ShopUnlockTrack track, TraderIslandData data, long islandLevel) {
        return switch (track) {
            case TRADE_COUNT -> data.tradeCount;
            case ISLAND_LEVEL -> islandLevel;
            case REPUTATION -> data.reputation;
        };
    }

    private static List<Long> tierListFor(ShopUnlockTrack track, TrackTiers tiers) {
        return switch (track) {
            case TRADE_COUNT -> tiers.tradeCount();
            case ISLAND_LEVEL -> tiers.islandLevel();
            case REPUTATION -> tiers.reputation();
        };
    }
}
