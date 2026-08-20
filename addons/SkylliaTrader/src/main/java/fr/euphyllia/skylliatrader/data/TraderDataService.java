package fr.euphyllia.skylliatrader.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.database.IslandCustomDataQuery;
import fr.euphyllia.skyllia.api.skyblock.Island;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 游商岛屿数据的读写入口。
 *
 * <h2>数据层选型说明（为什么不建独立数据库表）</h2>
 * <p>
 * {@link IslandCustomDataQuery} 本身就是"按岛屿存 key-value"的通用机制：每个 addon 用自己的
 * {@link NamespacedKey} 开一片命名空间，每条记录是 {@code (island_id, data_key) -> BLOB} ——
 * 看 SQLite 实现（{@code SQLiteIslandCustomData}）就知道，value 列本来就是 {@code BLOB}，
 * 不是只能存标量，一个 key 能存多大就看序列化后的字节数，没有"简单标量"的硬限制。
 * SkylliaIslandLevel 就是直接用它存 score/level 两个标量 key。
 * </p>
 * <p>
 * 但如果照搬"一个字段一个 key"的写法来存本插件需要的结构化数据（游商列表、订单槽位数组、
 * 订单完成次数表），会有两个实际问题：
 * <ol>
 *   <li>这些字段不是标量，是列表/字典，{@link PersistentDataType} 没有现成的"List/Map"类型，
 *       每个字段都要自己撸一个 {@code PersistentDataType} 适配器，代码量不小且容易出 bug；</li>
 *   <li>更新时如果拆成多个 key（比如"加一次交易次数"改一个 key、"召唤一个游商"改另一个 key），
 *       中途插件崩溃/服务器强杀会留下不一致的半更新状态（比如声望加了但订单完成次数没加）。</li>
 * </ol>
 * 所以本类的方案是：<b>把一整座岛屿的游商状态（{@link TraderIslandData}）序列化成一个 JSON
 * 字符串，整体存进一个 {@code PersistentDataType.STRING} 类型的 custom-data key</b>——
 * 读的时候整体反序列化，写的时候整体覆盖。这样：
 * </p>
 * <ul>
 *   <li>不需要新建数据库表、不需要额外的建表/迁移逻辑，复用现成机制；</li>
 *   <li>单个 key 的一次 UPDATE 在底层数据库里本身就是原子的一行，不会出现"部分字段更新成功、
 *       部分失败"的中间状态；</li>
 *   <li>结构可以随便加字段（Gson 按字段名序列化，多/少字段不影响旧数据解析），
 *       给 T2-T6 扩展留了余地。</li>
 * </ul>
 *
 * <h2>API 形状：为什么只有 load / save / mutate，没有一堆便捷 getter</h2>
 * <p>
 * 这里曾经提供过 {@code getReputation(island)} / {@code getTradeCount(island)} 之类的便捷方法，
 * 每一个内部都是 {@code load(island).xxx}。问题在于一次 {@code load()} = <b>一次同步 JDBC 查询
 * + 一次反序列化</b>，那套 API 形状会诱导调用方写出
 * {@code if (svc.getReputation(i) >= x && svc.getTradeCount(i) >= y)} 这种一行两三次全表往返的
 * 代码；而购买/订单结算是高频路径，还跑在 region tick 线程上，上线即卡服。
 * 所以现在强制"先 {@link #load} 拿到 {@link TraderIslandData}，再读它的字段"，
 * 让一次判定只对应一次读库。
 * </p>
 *
 * <h2>并发（T2 必须处理）</h2>
 * <p>
 * 整座岛屿状态全量替换的代价是：并发写（比如两个订单同时结算）如果没做互斥，后写的会
 * 覆盖先写的。T1 阶段还没有任何真正的并发写入路径（购买/订单执行是 T2-T3 的范围），
 * 这里先不引入锁。<b>T2 开始实现"扣减/增加"类操作时，必须在 island 维度做互斥</b>
 * （按 island id 分桶加锁，或者把关键计数器改成数据库层面的原子自增）——
 * 「读出来改一改再写回去」在并发下不安全。
 * </p>
 * <p>
 * 记账类的"读-改-写"都收敛在 {@link #mutate} 这一个入口上，就是为了让 T2 加锁时只需要改这一处：
 * 在 {@code mutate} 里按 {@code island.getId()} 取锁，把 load/apply/save 三步包进临界区即可，
 * 不用去挨个改散落各处的 addXxx 方法。
 * </p>
 * <p>
 * <b>但 {@code mutate} 不是唯一的读-改-写路径</b>：{@link #ensureOrderSlots} 是刻意留在外面的
 * 第二条。它需要"本来就够就直接返回、一个字节都不写库"的短路，而 {@code mutate} 的形状
 * （{@code Consumer} 改完必写）表达不了这件事——放进 mutate 就等于每次岛屿初始化都白写一次库。
 * <b>T2 加锁时必须把 {@code ensureOrderSlots} 一起纳入同一把 island 维度的锁</b>，
 * 只按本段前半句去改 {@code mutate} 会漏掉它，导致"补槽"和"记账"互相覆盖。
 * </p>
 */
public final class TraderDataService {

    private static final Logger log = LoggerFactory.getLogger(TraderDataService.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String KEY_STATE = "state";

    private final NamespacedKey namespace;

    public TraderDataService(@NotNull org.bukkit.plugin.Plugin plugin) {
        this.namespace = new NamespacedKey(plugin, "data");
    }

    private IslandCustomDataQuery query() {
        return SkylliaAPI.getIslandCustomDataQuery();
    }

    /**
     * 读取一座岛屿的完整游商状态；从未初始化过时返回一份全新的默认状态（不会写库）。
     * <p>
     * <b>这是一次同步阻塞的数据库查询</b>（底层 {@code SQLiteIslandCustomData#get} 就是同步
     * {@code SQLExecute.queryMap}，没有 Future/回调），绝不能在 region tick 线程上调用——
     * GUI 打开路径必须先在 {@code Bukkit.getAsyncScheduler()} 上把数据取完，再回玩家线程建界面。
     * </p>
     */
    @NotNull
    public TraderIslandData load(@NotNull Island island) {
        String json = query().get(namespace, island, KEY_STATE, PersistentDataType.STRING);
        if (json == null) {
            return new TraderIslandData();
        }
        try {
            TraderIslandData data = GSON.fromJson(json, TraderIslandData.class);
            return data != null ? data : new TraderIslandData();
        } catch (Exception e) {
            log.warn("岛屿 {} 的游商数据反序列化失败，已回退为默认状态：{}", island.getId(), e.getMessage());
            return new TraderIslandData();
        }
    }

    /**
     * 整体覆盖写回一座岛屿的游商状态。
     *
     * @return {@code true} 表示确实落库了；{@code false} 表示写入失败（连接池耗尽、磁盘满、
     * 建表失败……底层 {@code SQLiteIslandCustomData#set} 异常时就是返回 false）。
     * <b>调用方必须检查返回值</b>：T2 的"扣钱加声望"如果忽略它，玩家会看到交易成功、
     * 实际一个字节都没写进数据库，等于白交材料。
     */
    public boolean save(@NotNull Island island, @NotNull TraderIslandData data) {
        boolean ok = query().set(namespace, island, KEY_STATE, PersistentDataType.STRING, GSON.toJson(data));
        if (!ok) {
            log.error("岛屿 {} 的游商数据写入失败（本次改动没有落库）", island.getId());
        }
        return ok;
    }

    /**
     * 记账类操作的"读-改-写"入口：读出当前状态，交给 {@code mutator} 原地修改，再整体写回。
     * （不是唯一入口——{@link #ensureOrderSlots} 是另一条，见类注释末尾。）
     * <p>
     * T2 起的所有记账操作（加交易次数、加消费、加声望、记录订单完成、增删游商记录）都走这里，
     * 比如 {@code mutate(island, d -> { d.tradeCount++; d.reputation += 5; })}。
     * 一次 mutate = 一次读库 + 一次写库，把多项改动合并进同一个 lambda 就只有一个来回；
     * 拆成多次 mutate 则是多个来回，而且中间会被并发写插队。
     * </p>
     * <p>
     * <b>T2 的互斥点就在这里</b>：见类注释末尾。
     * </p>
     *
     * @return 写入是否成功；失败时改动只存在于内存里那份临时对象，等于没发生
     */
    public boolean mutate(@NotNull Island island, @NotNull Consumer<TraderIslandData> mutator) {
        TraderIslandData data = load(island);
        mutator.accept(data);
        return save(island, data);
    }

    /**
     * 确保订单槽位数组至少有 {@code slotCount} 个槽位（不足则补空槽，多余的保留不截断，
     * 避免管理员调小配置后误删已经绑定订单的槽位）。T1 只负责把占位结构建好，
     * 真正往槽位里塞订单是 T2 自然刷新逻辑的工作，这里绝不写入任何 orderId。
     * <p>
     * <b>这是 {@link #mutate} 之外的第二条"读-改-写"路径</b>（理由见类注释）：
     * T2 给 mutate 加 island 维度互斥锁时，这个方法必须纳入同一把锁。
     * </p>
     *
     * @return 是否处于"槽位已齐备"的状态：本来就够（没写库）或补齐后写库成功都返回 true
     */
    public boolean ensureOrderSlots(@NotNull Island island, int slotCount) {
        TraderIslandData data = load(island);
        if (data.orderSlots.size() >= slotCount) return true;
        for (int i = data.orderSlots.size(); i < slotCount; i++) {
            data.orderSlots.add(new OrderSlotState(i));
        }
        return save(island, data);
    }
}
