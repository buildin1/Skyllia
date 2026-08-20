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
import java.util.function.Function;

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
 * <h2>并发：island 维度的分桶互斥（T2 已实现）</h2>
 * <p>
 * 整座岛屿状态全量替换的代价是：并发写（两名成员同时用凭证、订单同时结算）如果没做互斥，
 * 后写的会覆盖先写的——「读出来改一改再写回去」在并发下不安全。所以本类持有一组
 * {@value #LOCK_STRIPES} 把锁，按 {@code island.getId()} 的哈希分桶，
 * <b>把 load / apply / save 三步整体包进临界区</b>。
 * </p>
 * <p>
 * <b>三个"读-改-写"入口全部在锁内</b>：{@link #mutate(Island, Consumer)}、
 * {@link #compute(Island, Function)}、{@link #ensureOrderSlots(Island, int)}。
 * 其中 {@code ensureOrderSlots} 是刻意留在 {@code mutate} 之外的第二条路径——它需要
 * "本来就够就直接返回、一个字节都不写库"的短路，而 {@code Consumer} 改完必写的形状表达不了
 * （放进 mutate 就等于每次岛屿初始化都白写一次库）。它<b>用的是同一把锁</b>，
 * 所以"补槽"和"记账"不会互相覆盖。
 * </p>
 * <p>
 * <b>{@link #load} 与 {@link #save} 这两个单步方法不加锁</b>，因为它们各自就是一次数据库操作，
 * 加锁也挡不住"别人在你 load 和 save 之间插了一脚"。<b>需要读-改-写语义的地方一律走上面三个入口，
 * 不要自己 load 完改完再 save</b>——那是本类唯一真正危险的用法。
 * </p>
 *
 * <h3>死锁分析</h3>
 * <p>
 * 这套锁不会死锁，依据是：<b>任何时刻一个线程最多只持有一把本类的锁</b>。
 * 临界区里做的事只有「反序列化 → 跑 mutator → 序列化 → 一次 SQL」，
 * mutator 是纯内存操作（调用方约定，见 {@link #mutate} 的文档），不会回头再调本类的其它方法，
 * 也不会去抓别的锁。用 {@code synchronized} 而不是 {@code ReentrantLock} 还额外提供了
 * 可重入性作为兜底：万一将来谁在 mutator 里嵌套调了<b>同一座岛</b>的 mutate，
 * 结果只是多一次读写，不会自锁。
 * </p>
 * <p>
 * <b>唯一的风险是临界区里做了阻塞 IO（那一次 SQL）</b>：同一个桶里的两座岛会互相排队。
 * 分 {@value #LOCK_STRIPES} 个桶就是为了把这种"无关岛屿互相等"的概率压下去；
 * 而调用方本来就必须在 async 线程上调本类（见 {@link #load} 的告诫），
 * 排队排在 async 线程上，不会卡住任何 region tick。
 * </p>
 *
 * <h3>⚠️ 适用前提：这套互斥只在<b>单个 JVM 内</b>成立</h3>
 * <p>
 * 上面所有关于"CAS"、"原子性"的说法，前提都是<b>只有一个进程在写这套岛屿数据</b>。
 * 锁是本类实例持有的 Java 对象，跨不出 JVM——一旦哪天上 BungeeCord / Velocity 多子服、
 * 让两个 Skyllia 实例共享同一个岛屿数据库，{@link #compute} 就<b>不再是</b> CAS：
 * 两个子服会各自在自己的锁里读到同一份旧状态、各自判定"名额还空着"、各自写回，
 * 后写的那份把先写的整块 JSON 覆盖掉。表现就是规格明令禁止的
 * 「同一座岛同一种商队出现两个常驻商人，两张凭证都被扣」。
 * </p>
 * <p>
 * 真要走到那一步，必须把并发控制下沉到数据库层，两条路子：
 * </p>
 * <ul>
 *   <li><b>乐观锁</b>：给状态加一个版本号列，{@code UPDATE ... WHERE version = ?}，
 *       受影响行数为 0 就重读重试。改动最小，和现在"整块 JSON 覆盖写"的形状最贴合；</li>
 *   <li><b>行锁</b>：{@code SELECT ... FOR UPDATE} 包住读-改-写。要求底层是 MySQL/PostgreSQL
 *       这类支持行锁的库，SQLite 做不到。</li>
 * </ul>
 * <p>
 * 这是<b>部署前提</b>而不是待办：本服当前是单实例，现有实现完全正确。写在这里是为了让将来
 * 动多子服的人在改架构之前就看见这一条，而不是上线之后靠玩家报"我的凭证白扣了"来发现。
 * </p>
 */
public final class TraderDataService {

    private static final Logger log = LoggerFactory.getLogger(TraderDataService.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String KEY_STATE = "state";

    /**
     * island 维度互斥锁的分桶数。必须是 2 的幂以便用位与取桶（这里仍然用 floorMod 写，
     * 意图更直白，JIT 会处理掉这点开销）。
     * <p>
     * 取 64 的依据：线上大约几十座岛，64 个桶让"两座无关岛屿撞进同一把锁"成为小概率事件，
     * 同时静态数组的内存开销可以忽略。桶数只影响吞吐，不影响正确性。
     * </p>
     */
    private static final int LOCK_STRIPES = 64;

    private final NamespacedKey namespace;

    /** 按 island id 分桶的互斥锁；理由与死锁分析见类注释。 */
    private final Object[] locks = new Object[LOCK_STRIPES];

    public TraderDataService(@NotNull org.bukkit.plugin.Plugin plugin) {
        this.namespace = new NamespacedKey(plugin, "data");
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    /**
     * 取这座岛对应的锁对象。
     * <p>
     * 用 {@code UUID#hashCode()}（= 高低 64 位异或再折叠成 int）而不是 {@code toString().hashCode()}：
     * 前者本身分布就足够均匀，后者还要多构造一个字符串。再套一层 {@code floorMod} 是因为
     * hashCode 可能是负数，{@code %} 会给出负下标。
     * </p>
     */
    private Object lockFor(@NotNull Island island) {
        return locks[Math.floorMod(island.getId().hashCode(), LOCK_STRIPES)];
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
     * <b>互斥点就在这里</b>：整个 load/apply/save 都在 island 维度的锁里，见类注释。
     * 因此 {@code mutator} 必须是<b>纯内存操作</b>：不要在里面读库、发消息、调度任务或抓别的锁，
     * 那会把阻塞操作拖进临界区，让同一个桶的其它岛屿一起等。
     * </p>
     *
     * @return 写入是否成功；失败时改动只存在于内存里那份临时对象，等于没发生
     */
    public boolean mutate(@NotNull Island island, @NotNull Consumer<TraderIslandData> mutator) {
        synchronized (lockFor(island)) {
            TraderIslandData data = load(island);
            mutator.accept(data);
            return save(island, data);
        }
    }

    /**
     * {@link #mutate} 的带返回值版本，用于「先判断能不能做，再决定改不改、并把判定结果告诉调用方」
     * 这类操作——凭证的 CAS 占位就是典型场景：读出状态 → 判断名额/冷却 → 满足才写入占位记录 →
     * 无论如何都要把「为什么失败」返回给调用方去提示玩家。
     * <p>
     * 判定和写入必须在<b>同一个临界区</b>里完成，这正是「CAS」的含义：分成
     * "先 load 判一次、再 mutate 写一次" 两步的话，两名成员同时用凭证时两边都会判定通过，
     * 于是召唤出两个同种商人 —— 规格明令禁止的情况。
     * </p>
     *
     * @param mutator 拿到当前状态，返回一个 {@link Mutation} 说明「结果是什么」「要不要落库」
     * @return {@code mutator} 给出的结果；若需要落库但写库失败，返回 {@link Mutation#onSaveFailed()}
     */
    public <R> R compute(@NotNull Island island, @NotNull Function<TraderIslandData, Mutation<R>> mutator) {
        synchronized (lockFor(island)) {
            TraderIslandData data = load(island);
            Mutation<R> mutation = mutator.apply(data);
            if (!mutation.write()) return mutation.value();
            if (!save(island, data)) return mutation.onSaveFailed();
            return mutation.value();
        }
    }

    /**
     * {@link #compute} 的返回值：把「给调用方的结果」「要不要落库」「落库失败时改返回什么」
     * 三件事绑在一起。
     * <p>
     * 之所以要显式带上 {@link #onSaveFailed}：写库失败时内存里那份改动等于没发生，
     * 如果还照常返回「成功」，凭证就会被白白消耗掉而商人的名额并没有真正记上——
     * 下次重启玩家会发现商人没了、凭证也没了。让调用方在构造 Mutation 的时候就想清楚
     * 「写不进去要告诉玩家什么」，比在外面写一堆 if 判断更难漏。
     * </p>
     *
     * @param value        正常情况下返回给调用方的结果
     * @param write        是否需要把 {@code TraderIslandData} 的改动落库
     * @param onSaveFailed {@code write} 为 true 但落库失败时返回的结果
     */
    public record Mutation<R>(R value, boolean write, R onSaveFailed) {

        /** 只读判定，不改任何东西、不写库。 */
        public static <R> Mutation<R> readOnly(R value) {
            return new Mutation<>(value, false, value);
        }

        /** 改了状态、需要落库；落库失败时返回 {@code failure}。 */
        public static <R> Mutation<R> commit(R value, R failure) {
            return new Mutation<>(value, true, failure);
        }
    }

    /**
     * 确保订单槽位数组至少有 {@code slotCount} 个槽位（不足则补空槽，多余的保留不截断，
     * 避免管理员调小配置后误删已经绑定订单的槽位）。T1 只负责把占位结构建好，
     * 真正往槽位里塞订单是 T2 自然刷新逻辑的工作，这里绝不写入任何 orderId。
     * <p>
     * <b>这是 {@link #mutate} 之外的第二条"读-改-写"路径</b>（理由见类注释），
     * 已经和 {@code mutate} / {@code compute} 纳入<b>同一把</b> island 维度的锁——
     * 否则「补槽」和「记账」会互相覆盖：补槽读到的旧状态里没有刚记上的凭证占位，
     * 写回去就把占位抹掉了，等于一个无限召唤漏洞。
     * </p>
     *
     * @return 是否处于"槽位已齐备"的状态：本来就够（没写库）或补齐后写库成功都返回 true
     */
    public boolean ensureOrderSlots(@NotNull Island island, int slotCount) {
        synchronized (lockFor(island)) {
            TraderIslandData data = load(island);
            if (data.orderSlots.size() >= slotCount) return true;
            for (int i = data.orderSlots.size(); i < slotCount; i++) {
                data.orderSlots.add(new OrderSlotState(i));
            }
            return save(island, data);
        }
    }
}
