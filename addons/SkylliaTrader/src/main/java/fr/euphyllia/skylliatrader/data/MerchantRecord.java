package fr.euphyllia.skylliatrader.data;

import fr.euphyllia.skylliatrader.merchant.CaravanType;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 一个<b>凭证</b>游商在岛屿数据里的持久化记录，同时兼任「凭证名额」的占位符。
 * <p>
 * <b>自然刷新的游商没有记录</b>：它们不占名额、不永久存在，全部状态都在内存 + 实体 PDC 里，
 * 一条数据库都不写（见 {@code MerchantOrigin}）。所以这张表里的每一条都必然是凭证游商。
 * </p>
 * <p>
 * 用普通可变字段（而不是 record）是因为它要被 Gson 直接反序列化/序列化，
 * 且会频繁原地更新（占位 → 填入实体 UUID）；record 的不可变性
 * 在这种"整条记录被内部字段级更新"的场景反而要写更多样板代码。
 * </p>
 *
 * <h2>两种状态：占位（claim）与正式（active）</h2>
 * <p>
 * 规格要求的凭证顺序是「CAS 占位 → 生成成功 → 才消耗凭证」，所以一条记录会先以
 * <b>占位态</b>写进数据库（{@link #entityUuid} 为 {@code null}、{@link #claimExpiresAt} &gt; 0），
 * 等实体真的在区域线程上生成成功之后再被改成<b>正式态</b>
 * （填入 {@link #entityUuid}、{@link #claimExpiresAt} 归零）。
 * </p>
 * <p>
 * <b>{@link #claimExpiresAt} 是防「占位泄漏」的唯一手段</b>：占位写库成功之后、实体生成完成之前，
 * 服务器可能崩溃/被强杀，那条占位记录就会永远躺在数据库里，把这座岛的该商队名额锁死，
 * 而玩家手上的凭证还在——表现为「凭证怎么用都提示已有商人，但岛上一个商人都没有」。
 * 加了过期时间之后，超时的占位会被下一次召唤视为空位直接顶掉。
 * </p>
 * <p>
 * <b>{@link #claimId} 是回滚的定位依据</b>：生成失败要回滚占位时，绝不能按「商队类型」去删——
 * 那一瞬间可能已经有另一名成员的占位/正式记录顶上了（我们的占位刚过期或刚被清理），
 * 按类型删会把别人的合法记录一起删掉。按 claimId 删则只会命中自己写进去的那一条。
 * </p>
 */
public class MerchantRecord {

    /**
     * 本条记录属于哪种商队（{@link CaravanType} 的枚举名）。
     * <p>
     * <b>老数据里没有这个字段</b>（T1 的 MerchantRecord 没有商队概念），反序列化后是 {@code null}。
     * {@link #caravanType()} 会把 null 解析成 null 而不是回退到某个商队——理由见
     * {@link CaravanType#parseOrNull}。不过 T1 阶段根本没有写入路径，实际上不会存在老记录。
     * </p>
     */
    public String caravan;

    /**
     * 游商实体的 UUID。{@code null} 表示<b>这条记录还只是一个占位</b>（实体尚未生成成功）。
     * <p>
     * 用 {@code null} 而不是空串：Gson 序列化 null 字段时默认整个字段都不写进 JSON，
     * 省几个字节是次要的，主要是「读出来是 null」和「读出来是空串」在 Java 侧要写两种判断，
     * 只留一种表示法能少一类漏判。对外的「无商人」语义由「数组里没有这条记录」承载，
     * 规格 6.6 说的「用空串不用 remove」在本实现里对应「整块 JSON 一直在、只是数组里少一项」，
     * 见 {@code MerchantKeys} 类注释。
     * </p>
     */
    public String entityUuid;

    /** 占位的唯一标识，用于精确回滚；理由见类注释。 */
    public String claimId;

    /**
     * 占位失效时间戳（epoch millis）。{@code 0} 表示这已经是一条正式记录、不会过期。
     * 大于 0 且已经过了这个时间的记录会被当成「空位」，可以被新的召唤顶掉。
     */
    public long claimExpiresAt;

    /** 所在世界名。占位态时就是预定的召唤世界。 */
    public String world;

    public double x;
    public double y;
    public double z;

    /** 召唤时间戳（epoch millis）。 */
    public long summonedAt;

    /** 用凭证召唤出这个商人的玩家 UUID，仅用于管理端展示与排查，不参与任何判定。 */
    public String summonerUuid;

    // 注意：这里曾经有一个 lastDeathAt 字段，但游商死亡时整条记录会从 merchants 列表里被移除
    // （走 TraderDataService#mutate，形如 mutate(island, d -> d.merchants.removeIf(...))），
    // 字段还没来得及被读就跟着记录一起没了。
    // 重召冷却改为记在岛屿维度的 TraderIslandData#lastMerchantDeathAt 上，见那边的说明。

    public MerchantRecord() {
    }

    /** 建一条占位记录。 */
    public static MerchantRecord claim(CaravanType caravanType, String claimId, String world,
                                       double x, double y, double z,
                                       String summonerUuid, long now, long claimTimeoutMillis) {
        MerchantRecord record = new MerchantRecord();
        record.caravan = caravanType.name();
        record.entityUuid = null;
        record.claimId = claimId;
        record.claimExpiresAt = now + claimTimeoutMillis;
        record.world = world;
        record.x = x;
        record.y = y;
        record.z = z;
        record.summonedAt = now;
        record.summonerUuid = summonerUuid;
        return record;
    }

    /** 解析商队类型；字段缺失或无法识别时返回 {@code null}。 */
    public @Nullable CaravanType caravanType() {
        return CaravanType.parseOrNull(caravan);
    }

    /** 解析实体 UUID；占位态或字段损坏时返回 {@code null}。 */
    public @Nullable UUID entityId() {
        if (entityUuid == null || entityUuid.isBlank()) return null;
        try {
            return UUID.fromString(entityUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 这条记录是不是还处于占位态（实体尚未生成成功）。 */
    public boolean isClaim() {
        return entityUuid == null || entityUuid.isBlank();
    }

    /**
     * 这条记录当前是否真的占着名额。
     * <p>
     * 正式记录永远占着；占位记录只在没超时之前占着——超时的占位是服务器崩在
     * 「占位成功、生成未完成」中间留下的垃圾，必须能被顶掉，理由见类注释。
     * </p>
     */
    public boolean occupiesSlot(long now) {
        if (!isClaim()) return true;
        return claimExpiresAt > now;
    }

    /** 占位成功之后把记录转正。 */
    public void promote(UUID entityId, String worldName, double px, double py, double pz) {
        this.entityUuid = entityId.toString();
        this.claimExpiresAt = 0L;
        this.world = worldName;
        this.x = px;
        this.y = py;
        this.z = pz;
    }
}
