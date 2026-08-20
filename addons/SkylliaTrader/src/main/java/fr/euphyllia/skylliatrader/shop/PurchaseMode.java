package fr.euphyllia.skylliatrader.shop;

/**
 * 商店 GUI 的点击语义（HANDOFF 6.7）：放弃拖拽/合成台式的批量购买，用点击语义代替。
 * <ul>
 *   <li>{@link #SINGLE} —— 左键：买 1 个；</li>
 *   <li>{@link #QUINTUPLE} —— Shift+左键：买 5 个（限购余量不足 5 时，买余量能买的最大数量，
 *       不会因为凑不够 5 就整单拒绝）；</li>
 *   <li>{@link #FILL} —— 右键：<b>有限购</b>的商品买到本周期限购上限剩余的全部额度；
 *       <b>无限购</b>的商品买一组（最多 64 个，不可堆叠到 64 的物品退化成买能堆的最大数量，
 *       比如成书这类 maxStackSize=1 的物品退化成买 1 个）。</li>
 * </ul>
 */
public enum PurchaseMode {
    SINGLE, QUINTUPLE, FILL
}
