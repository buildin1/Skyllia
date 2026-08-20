package fr.euphyllia.skylliatrader.merchant;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 游商实体身上的 PDC 标记。
 *
 * <h2>为什么实体也要打标记（岛屿数据里不是已经存了 UUID 吗）</h2>
 * <p>
 * 两边存的<b>不是同一件事</b>，职责必须分清楚，否则重启后一定出问题：
 * </p>
 * <ul>
 *   <li><b>岛屿数据（{@code TraderIslandData.merchants}）是「名额」的权威源</b>。
 *       名额判定要能在<b>实体所在区块没加载</b>的时候做出来（玩家可以在别的世界、
 *       甚至在另一座岛上用凭证），所以它必须存在数据库里，而不是问实体。
 *       CAS 占位也只有落在数据库这一份上才有原子性可言。</li>
 *   <li><b>实体 PDC 是「这个实体是谁」的权威源</b>。它随区块存盘，服务器重启后
 *       实体自己带着标记回来——插件不需要（也没法）在启动时去扫描全服实体做重新关联。
 *       这正是 HANDOFF.md 6.6 说「实体 PDC 才是权威源」的那一半：
 *       实体的<b>身份与归属</b>不能靠内存里的 Map 记，否则重启就丢。</li>
 * </ul>
 * <p>
 * 换句话说：<b>数据库回答「这座岛的下界商队名额被占了吗」，PDC 回答「眼前这只游商是谁家的」。</b>
 * 两个问题在不同的时刻、不同的线程上被问到，用同一份存储都答不好。
 * </p>
 *
 * <h2>关于规格里的 {@code token_trader} 这个 key</h2>
 * <p>
 * HANDOFF.md 6.6 的数据表列了一个 {@code token_trader} STRING（凭证游商 UUID，{@code ""} = 无）。
 * 本插件的数据层（见 {@code TraderDataService} 类注释）把<b>整座岛的状态序列化成一个 JSON
 * 存进单个 custom-data key</b>，所以这里没有一个物理上叫 {@code token_trader} 的键，
 * 它对应的是 JSON 里的 {@code merchants} 数组。语义完全保留，而且更强：
 * </p>
 * <ul>
 *   <li>规格要求「{@code ""} = 无，不要 remove」——本实现里对应「数组里没有该商队的记录」，
 *       同样<b>永远不会去 remove 那个 custom-data key</b>（整块 JSON 一直在），
 *       所以规格担心的「remove 之后类型/存在性变化导致读出 null 走进异常分支」不会发生；</li>
 *   <li>三种商队各要一个 UUID，用一个 STRING 存不下，拆成三个 key 又会让
 *       「占位 + 记账」跨多次写入而失去原子性——数组形式天然支持三种商队且仍是一次写入。</li>
 * </ul>
 */
public final class MerchantKeys {

    private final NamespacedKey kind;
    private final NamespacedKey island;
    private final NamespacedKey caravan;
    private final NamespacedKey origin;
    private final NamespacedKey expire;

    /** {@link #kind} 的固定值，用来一眼判断「这是本插件的游商」。 */
    public static final String KIND_MERCHANT = "merchant";

    public MerchantKeys(@NotNull Plugin plugin) {
        this.kind = new NamespacedKey(plugin, "kind");
        this.island = new NamespacedKey(plugin, "island");
        this.caravan = new NamespacedKey(plugin, "caravan");
        this.origin = new NamespacedKey(plugin, "origin");
        this.expire = new NamespacedKey(plugin, "expire");
    }

    /**
     * 给一个刚生成的游商打上全部标记。
     * <p>
     * <b>必须在实体被加入世界之前调用</b>（即 {@code World#spawn} 的 pre-spawn 回调里），
     * 否则 {@code EntityAddToWorldEvent} 会先于标记看到这只实体，把它当成「不是我们的游商」放过去，
     * 于是自然刷新的游商在下一次区块加载时就不会被过期清理了。
     * </p>
     *
     * @param expireAt 过期时间戳（epoch millis）；0 表示永不过期（凭证游商）
     */
    public void mark(@NotNull Entity entity, @NotNull UUID islandId, @NotNull CaravanType caravanType,
                     @NotNull MerchantOrigin merchantOrigin, long expireAt) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(kind, PersistentDataType.STRING, KIND_MERCHANT);
        pdc.set(island, PersistentDataType.STRING, islandId.toString());
        pdc.set(caravan, PersistentDataType.STRING, caravanType.name());
        pdc.set(origin, PersistentDataType.STRING, merchantOrigin.name());
        pdc.set(expire, PersistentDataType.LONG, expireAt);
    }

    /** 这只实体是不是本插件生成的游商。 */
    public boolean isMerchant(@NotNull Entity entity) {
        return KIND_MERCHANT.equals(entity.getPersistentDataContainer().get(kind, PersistentDataType.STRING));
    }

    /** 读取归属岛屿 id；不是本插件的游商、或标记损坏时返回 {@code null}。 */
    public @Nullable UUID readIslandId(@NotNull Entity entity) {
        String raw = entity.getPersistentDataContainer().get(island, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 读取商队类型；标记缺失或无法识别时返回 {@code null}。 */
    public @Nullable CaravanType readCaravan(@NotNull Entity entity) {
        return CaravanType.parseOrNull(entity.getPersistentDataContainer().get(caravan, PersistentDataType.STRING));
    }

    /** 读取来源；标记缺失或无法识别时返回 {@code null}。 */
    public @Nullable MerchantOrigin readOrigin(@NotNull Entity entity) {
        return MerchantOrigin.parseOrNull(entity.getPersistentDataContainer().get(origin, PersistentDataType.STRING));
    }

    /** 读取过期时间戳；0（含标记缺失）表示永不过期。 */
    public long readExpireAt(@NotNull Entity entity) {
        Long value = entity.getPersistentDataContainer().get(expire, PersistentDataType.LONG);
        return value == null ? 0L : value;
    }
}
