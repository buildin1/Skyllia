package fr.euphyllia.skylliatrader.data;

/**
 * 已召唤的一个游商实体的持久化记录。
 * <p>
 * 用普通可变字段（而不是 record）是因为它要被 Gson 直接反序列化/序列化，
 * 且 T2 起会频繁原地更新（比如实体重新生成后 UUID 变化）；record 的不可变性
 * 在这种"整条记录被内部字段级更新"的场景反而要写更多样板代码。
 * </p>
 */
public class MerchantRecord {

    /** 游商实体的 UUID；生物被清理/重启后重新生成时会变化。 */
    public String entityUuid;

    /** 所在世界名。 */
    public String world;

    public double x;
    public double y;
    public double z;

    /** 召唤时间戳（epoch millis）。 */
    public long summonedAt;

    // 注意：这里曾经有一个 lastDeathAt 字段，但游商死亡时整条记录会从 merchants 列表里被移除
    // （T2 起走 TraderDataService#mutate，形如 mutate(island, d -> d.merchants.removeIf(...))），
    // 字段还没来得及被读就跟着记录一起没了。
    // 重召冷却改为记在岛屿维度的 TraderIslandData#lastMerchantDeathAt 上，见那边的说明。

    public MerchantRecord() {
    }

    public MerchantRecord(String entityUuid, String world, double x, double y, double z, long summonedAt) {
        this.entityUuid = entityUuid;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.summonedAt = summonedAt;
    }
}
