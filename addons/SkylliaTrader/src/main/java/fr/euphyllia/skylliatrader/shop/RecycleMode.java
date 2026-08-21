package fr.euphyllia.skylliatrader.shop;

/**
 * 回收 GUI 的点击语义（HANDOFF 9.3 T5，思路照抄 {@link PurchaseMode} / 商队订单的"一键结算"）。
 * <ul>
 *   <li>{@link #SINGLE} —— 左键：回收 1 个；</li>
 *   <li>{@link #QUINTUPLE} —— Shift+左键：回收 5 个（背包里不足 5 个就回收实际持有的数量）；</li>
 *   <li>{@link #ALL} —— 右键：回收玩家背包里这个材质的全部数量。</li>
 * </ul>
 * 和 {@link PurchaseMode} 分开建枚举而不是复用：{@code PurchaseMode.FILL} 的语义是"买满限购剩余额度
 * /买一组"，和回收的"背包里有多少收多少"是两件不同的事，硬凑一个枚举表达两种含义容易混淆。
 */
public enum RecycleMode {
    SINGLE, QUINTUPLE, ALL
}
