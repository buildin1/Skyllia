package fr.euphyllia.skylliatrader.data;

/**
 * 一个岛屿的某个"订单槽位"当前状态。
 * <p>
 * 每座岛屿的游商同时能提供几个订单槽位（数量由 T2 阶段的配置决定），每个槽位在某一时刻
 * 绑定到 orders.toml 里的某一条订单 id，到期后由自然刷新逻辑重新抽取——这套刷新逻辑本身
 * 属于 T2，T1 只把"槽位状态要长什么样"定下来，槽位数组默认先都是空槽（orderId = null）。
 * </p>
 */
public class OrderSlotState {

    /** 槽位下标（0-based）。 */
    public int slotIndex;

    /** 当前绑定的订单 id（对应 orders.toml 的 [[order]].id）；null 表示空槽、尚未分配。 */
    public String orderId;

    /** 本槽位当前订单的分配时间戳（epoch millis）。 */
    public long assignedAt;

    /** 本槽位当前订单的到期时间戳（epoch millis），0 表示尚未设置/不过期。T2 自然刷新逻辑使用。 */
    public long expiresAt;

    public OrderSlotState() {
    }

    public OrderSlotState(int slotIndex) {
        this.slotIndex = slotIndex;
    }
}
