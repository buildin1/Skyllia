package fr.euphyllia.skylliatrader.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一座岛屿全部的游商相关持久化状态，整体序列化成一个 JSON 字符串存进
 * {@code IslandCustomDataQuery} 的一个 key 里（见 {@link TraderDataService} 顶部的设计说明）。
 * <p>
 * <b>硬规则：这里的每一个计数字段都是"整座岛屿"共享的一份，绝不能按玩家拆分。</b>
 * 交易次数、消费额、声望、订单终身完成次数如果哪天被改成"每个玩家一份"，
 * 限购和进度就形同虚设——同一座岛换个人操作就能刷穿门槛，这是上线后无法回退的设计错误。
 * </p>
 * <p>
 * <b>加字段时的注意事项</b>：Gson 反序列化老数据时，JSON 里没有的字段会保留这里写的
 * 字段初始值（本类有隐式无参构造，Gson 会调用它，字段初始化器会正常执行），所以
 * <em>新字段的初始值必须是一个"对老岛屿也正确"的值</em>。反例见 {@link #credentialSlots}：
 * 如果它用 {@code 0} 作初始值，所有存量岛屿反序列化后凭证槽位就都变成 0，一个游商都召不出来。
 * </p>
 */
public class TraderIslandData {

    /**
     * 数据结构版本号，作为将来做数据迁移时的锚点。
     * 老数据（写入时还没有这个字段）反序列化后会保留这里的初始值 1，正好等于 T1 的结构版本。
     * 以后如果某次改动无法靠"加字段 + 安全默认值"向后兼容，就在这里升版本号并写迁移逻辑。
     */
    public int schemaVersion = 1;

    /** 累计交易次数（购买 + 完成订单都算一次），驱动"交易次数"解锁轨道。 */
    public long tradeCount = 0L;

    /** 累计消费额（花在游商商店购买上的 Vault 金额），只影响折扣/限购加成，不解锁新商品。 */
    public double totalSpent = 0.0;

    /** 商会声望，只能通过完成商队订单获得，驱动"商会声望"解锁轨道（稀有资源）。 */
    public long reputation = 0L;

    /**
     * 本岛的游商<b>总</b>名额上限（三种商队加起来最多几个常驻商人）。
     * <p>
     * <b>{@code -1} 表示"本岛从未被单独发放/回收过凭证"</b>，此时一律回退到 config.toml 的
     * {@code credential.max-merchants-per-island}（见 {@link #effectiveCredentialSlots(int)}）。
     * 之所以不用 0 当"未初始化"：T4 要做的是"按岛发放/回收凭证"，届时 0 是一个合法值
     * （表示这座岛的凭证被管理员收光了），必须和"没记录过"区分开；而且如果用 0 当哨兵，
     * 所有存量岛屿反序列化后都会拿到 0，等于一次静默的全服凭证清零。
     * </p>
     * <p>
     * <b>它不是主判定。</b>规格要求的是「每岛<b>每种</b>商队最多 1 个」，主判定是
     * config.toml 的 {@code credential.max-per-caravan}（见 {@link #occupiedSlots(long)} 的调用方
     * {@code MerchantService}）。总名额只是叠在分类型上限之上的一道额外天花板，
     * 用来支撑 HANDOFF.md 7.1 里「岛屿等级 10-20 级：凭证游商名额 1→2」这类按岛调整的玩法：
     * 默认 3 = 三种商队各 1 个，等于不生效；调成 2 就表示这座岛只能同时留 2 个常驻商人，
     * 至于是哪两种由玩家自己选。<b>两条上限是「与」的关系，任何一条不满足都召唤失败。</b>
     * </p>
     */
    public int credentialSlots = -1;

    /**
     * 本岛最近一次有游商死亡的时间戳（epoch millis），0 表示从未死过。
     * <p>
     * 用于 config.toml 的 {@code credential.respawn-cooldown-seconds} 判定：死亡后这段时间内
     * 不允许重新召唤，防止玩家故意打死自己岛上的游商来刷新订单/规避限购。
     * </p>
     * <p>
     * 冷却<b>挂在岛屿维度而不是 {@link MerchantRecord} 上</b>：游商死亡时对应的记录会被
     * 从 {@link #merchants} 里整条移除（否则死掉的游商会一直占着名额，等于永久占位；
     * T2 起这个移除动作走 {@code TraderDataService#mutate}），记录一没冷却时间戳也就跟着没了。挂在岛上既能活过记录移除，语义也更严
     * ——一次作弊性击杀会锁住这座岛的全部重召，正好是这条冷却想要的效果。
     * </p>
     */
    public long lastMerchantDeathAt = 0L;

    /**
     * 当前已召唤的<b>凭证</b>游商列表（含尚未生成完成的占位记录）。
     * <p>
     * 自然刷新的游商<b>不</b>出现在这里——它们不占名额，见 {@link MerchantRecord} 类注释。
     * 上限有两条，见 {@link #credentialSlots}。
     * </p>
     */
    public List<MerchantRecord> merchants = new ArrayList<>();

    /** 当前各订单槽位的状态；槽位数量、自然刷新时机由 T2 决定，这里只保存快照。 */
    public List<OrderSlotState> orderSlots = new ArrayList<>();

    /**
     * 每条订单在本岛的"终身完成次数"，key 为规范化后的 orders.toml 订单 id
     * （见 {@code OrderDefinition.normalizeId}）。
     * 用于 {@code redeem-limit-per-island} 限购判定——之所以是 Map 而不是挂在订单本身，
     * 是因为订单定义来自全局配置文件（所有岛共用同一份），完成次数必须是岛屿私有数据。
     */
    public Map<String, Integer> orderCompletions = new HashMap<>();

    /**
     * 本岛实际生效的凭证槽位数：有单独记录时用记录值，{@code -1}（未初始化）时回退到配置默认值。
     *
     * @param configuredDefault config.toml 的 {@code credential.max-merchants-per-island}
     */
    public int effectiveCredentialSlots(int configuredDefault) {
        return credentialSlots < 0 ? configuredDefault : credentialSlots;
    }

    /**
     * 当前真正占着名额的记录数（正式记录 + 尚未超时的占位）。
     * <p>
     * <b>不能直接用 {@code merchants.size()}</b>：超时的占位记录是崩溃残留的垃圾，
     * 算进去会让名额永远回不来。
     * </p>
     *
     * @param now 当前时间戳（epoch millis）
     */
    public int occupiedSlots(long now) {
        int count = 0;
        for (MerchantRecord record : merchants) {
            if (record.occupiesSlot(now)) count++;
        }
        return count;
    }

    /**
     * 某种商队当前占着名额的记录数。规格是「每种最多 1 个」，所以正常情况下只会是 0 或 1；
     * 返回计数而不是 boolean，是为了让上限本身可配置（{@code credential.max-per-caravan}）。
     *
     * @param caravanName {@code CaravanType} 的枚举名
     * @param now         当前时间戳（epoch millis）
     */
    public int occupiedSlotsFor(String caravanName, long now) {
        int count = 0;
        for (MerchantRecord record : merchants) {
            if (caravanName.equals(record.caravan) && record.occupiesSlot(now)) count++;
        }
        return count;
    }

    /** 按占位 id 找记录；找不到返回 {@code null}。回滚与转正都靠它精确定位，理由见 {@link MerchantRecord}。 */
    public MerchantRecord findByClaimId(String claimId) {
        for (MerchantRecord record : merchants) {
            if (claimId.equals(record.claimId)) return record;
        }
        return null;
    }

    /** 某条订单在本岛已完成的次数。 */
    public int completionCount(String normalizedOrderId) {
        return orderCompletions.getOrDefault(normalizedOrderId, 0);
    }
}
