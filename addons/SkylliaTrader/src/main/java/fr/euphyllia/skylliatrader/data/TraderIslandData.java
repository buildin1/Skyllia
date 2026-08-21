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
     * <b>{@code -1} 表示"本岛从未被单独发放/回收过凭证"</b>，此时一律回退到按岛屿等级
     * 动态算出来的默认值（{@code TraderConfigManager#defaultCredentialSlotsForLevel(long)}，
     * 见 {@link #effectiveCredentialSlots(int)}）。T6 之前这里回退的是 config.toml 里一个
     * 写死的静态数字（{@code credential.max-merchants-per-island}）；T6 起改成按当前岛屿
     * 等级算（HANDOFF 7.1/9.3："10 级前 1 个，10 级起 2 个，30 级起 3 个"），
     * {@link #effectiveCredentialSlots(int)} 本身"有单独记录就用记录值"这条分支完全不变，
     * 变的只是调用方传进来的默认值参数从哪来。
     * 之所以不用 0 当"未初始化"：T4 已经做了"按岛发放/回收凭证"，0 是一个合法值
     * （表示这座岛的凭证被管理员收光了），必须和"没记录过"区分开；而且如果用 0 当哨兵，
     * 所有存量岛屿反序列化后都会拿到 0，等于一次静默的全服凭证清零。
     * </p>
     * <p>
     * <b>它不是主判定。</b>规格要求的是「每岛<b>每种</b>商队最多 1 个」，主判定是
     * config.toml 的 {@code credential.max-per-caravan}（见 {@link #occupiedSlots(long)} 的调用方
     * {@code MerchantService}）。总名额只是叠在分类型上限之上的一道额外天花板，
     * 用来支撑 HANDOFF.md 7.1 里「岛屿等级 10-20 级：凭证游商名额 1→2」这类按岛调整的玩法：
     * 等级 ≥30 级时默认值为 3 = 三种商队各 1 个，等于不生效；被管理员单独调成 2 就表示这座岛
     * 只能同时留 2 个常驻商人，至于是哪两种由玩家自己选。
     * <b>两条上限是「与」的关系，任何一条不满足都召唤失败。</b>
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
     * 商店每条商品在本岛的限购计数器，key 为规范化后的 {@code shop.toml} 商品 id
     * （说明书用保留 key，见 {@code ShopConfigManager#GUIDEBOOK_RESERVED_ID}）。
     * <p>
     * <b>初始值是空 Map</b>，对老岛屿正确：反序列化后没有任何商品的计数记录，
     * 等价于"这座岛在 T3 上线前从没买过任何东西"——第一次购买时按
     * {@link PurchaseCounter} 的说明会自动建立一个新窗口，不需要迁移逻辑。
     * </p>
     * <p>
     * <b>限购判定与扣减必须在 {@code TraderDataService#compute} 的同一个临界区内完成</b>
     * （见该类文档），不能先 load 判一次、再 mutate 写一次——那会重新打开 T2 已经用 CAS
     * 占位修过的那一类并发窗口：两名成员同时买最后一件限购商品，两边都会判定通过。
     * </p>
     */
    public Map<String, PurchaseCounter> shopPurchaseCounts = new HashMap<>();

    /**
     * 本岛"今日通过商队订单获得的货币总额"滚动窗口计数器（T4 新增）。
     * <p>
     * <b>初始值是一份全零的新对象</b>，对老岛屿正确：反序列化后没有任何窗口记录，
     * 等价于"这座岛在 T4 上线前从没通过订单拿过钱"——第一次完成 money 类型订单时会按
     * {@link DailyOrderIncome} 的说明自动开一个新窗口，不需要迁移逻辑。
     * </p>
     * <p>
     * <b>判定与扣减（其实是"加算"）必须在 {@code TraderDataService#compute} 的同一个临界区内
     * 完成</b>，理由和 {@link #shopPurchaseCounts} 一样：两名成员同时结算两条不同的 money 订单，
     * 如果分开读一次判一次，两边都可能读到"还没超额"的旧状态，导致当日实际发放的金币总和
     * 超过配置的上限。
     * </p>
     */
    public DailyOrderIncome dailyOrderIncome = new DailyOrderIncome();

    /**
     * 本岛"今日通过商队订单获得的声望总额"（2026-08-21 T4 审查后补，见 {@link DailyOrderReputation}
     * 类文档）。判定与累加同样必须在 {@code TraderDataService#compute} 的同一个临界区内完成，
     * 理由和 {@link #dailyOrderIncome} 完全一样。
     */
    public DailyOrderReputation dailyOrderReputation = new DailyOrderReputation();

    /**
     * 本岛"今日通过回收获得的货币总额"，用于回收收入的每日上限（见 {@link DailyRecycleIncome}）。
     * <p>
     * 和订单的两个计数器一样，字段带默认初始化，老岛屿反序列化时拿到的是一个全零的新实例，
     * 语义正确（"这个窗口还没回收过"），不需要额外的数据迁移。
     * </p>
     */
    public DailyRecycleIncome dailyRecycleIncome = new DailyRecycleIncome();

    /**
     * 本岛实际生效的凭证槽位数：有单独记录时用记录值，{@code -1}（未初始化）时回退到默认值。
     *
     * @param defaultSlots 未被管理员单独设置过时使用的默认值。T6 起调用方应该传
     *                      {@code TraderConfigManager#defaultCredentialSlotsForLevel(long)}
     *                      按当前岛屿等级算出来的结果，而不是 config.toml 里的静态数字——
     *                      这个方法本身不关心默认值是怎么算出来的，只负责"有记录就用记录值"
     *                      这一条分支。
     */
    public int effectiveCredentialSlots(int defaultSlots) {
        return credentialSlots < 0 ? defaultSlots : credentialSlots;
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

    /**
     * 取（不存在则新建并登记）某条商品的限购计数器。<b>只应该在 {@code TraderDataService#compute}
     * 的临界区内调用</b>——新建计数器本身就是一次状态改动，必须和外层的判定/扣减在同一次写库里。
     *
     * @param normalizedShopItemId 规范化后的商品 id（见 {@code ShopItemDefinition#normalizeId}）
     */
    public PurchaseCounter getOrCreatePurchaseCounter(String normalizedShopItemId) {
        return shopPurchaseCounts.computeIfAbsent(normalizedShopItemId, k -> new PurchaseCounter());
    }
}
