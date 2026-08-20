package fr.euphyllia.skylliatrader.configuration.model;

import java.util.List;

/**
 * 四条解锁轨道当前配置的档位表快照。
 * <p>
 * 交易次数 / 岛屿等级 / 商会声望三条轨道决定"能不能买某类商品"；
 * 消费轨道只影响折扣与限购加成（T3 实现），不参与解锁判定，
 * 但档位数值本身在 T1 就一起落地，方便管理端统一展示四条轨道。
 * </p>
 */
public record TrackTiers(
        List<Long> tradeCount,
        List<Long> islandLevel,
        List<Long> reputation,
        List<Double> spending
) {

    /**
     * 找到 {@code value} 当前所处的档位下标（0-based）。
     * 档位表必须从小到大排列；返回值是"小于等于 value 的最大档位"的下标，
     * 一个档位都够不上时返回 -1。
     */
    public static int currentTierIndex(long value, List<Long> tiers) {
        int index = -1;
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i) <= value) {
                index = i;
            } else {
                break;
            }
        }
        return index;
    }

    public static int currentTierIndex(double value, List<Double> tiers) {
        int index = -1;
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i) <= value) {
                index = i;
            } else {
                break;
            }
        }
        return index;
    }
}
