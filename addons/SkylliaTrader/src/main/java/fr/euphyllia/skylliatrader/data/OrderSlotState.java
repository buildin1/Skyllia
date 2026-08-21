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

    /**
     * 这个槽位上一次成功<b>通过临界区判定</b>的时间戳（epoch millis），0 表示从未结算过。
     * <p>
     * 2026-08-21 T4 审查后补：一键结算没有物品摆放交互、点击之间原本没有任何冷却，玩家可以
     * 对着同一个还没到期的槽位反复点击。这个字段配合 {@code order-board.slot-redeem-cooldown-seconds}
     * 强制"结算完这个槽位之后要等一会才能再结算同一个槽位"，和每日声望/货币上限是两道独立
     * 的闸门——上限锁死"总量"，冷却锁死"频率"，两者缺一都堵不住"坐着连点刷声望"这个漏洞。
     * </p>
     * <p>
     * <b>只在临界区判定通过（{@code computeSettlement} 走到 commit）时写入，且之后
     * 任何原因导致的回滚（{@code undoSettlement}，比如奖励发放失败）都不会把它撤销回去</b>——
     * 这是刻意的：临界区判定通过就意味着材料已经被真实消耗、这次尝试已经"发生"过一次，
     * 冷却本来就是用来限制"尝试的频率"而不是"成功的频率"，如果失败/回滚会把冷却一起撤销，
     * 玩家只要故意触发一次可控的失败（比如背包塞满导致物品奖励发不出去）就能绕开冷却立刻
     * 重试，变成新的连点入口。所以 {@code undoSettlement} 只回滚经济记账，不碰这个字段。
     * </p>
     */
    public long lastRedeemedAt;

    public OrderSlotState() {
    }

    public OrderSlotState(int slotIndex) {
        this.slotIndex = slotIndex;
    }
}
