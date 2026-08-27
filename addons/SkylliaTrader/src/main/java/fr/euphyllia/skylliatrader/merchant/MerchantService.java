package fr.euphyllia.skylliatrader.merchant;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.permissions.FlagId;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.bridge.IslandLevelBridge;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.configuration.TraderConfigManager;
import fr.euphyllia.skylliatrader.configuration.model.MerchantOfferScope;
import fr.euphyllia.skylliatrader.data.MerchantRecord;
import fr.euphyllia.skylliatrader.data.TraderIslandData;
import fr.euphyllia.skylliatrader.data.TraderDataService;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 游商的生成与名额管理：凭证召唤、自然刷新、死亡/移除后的名额回收。
 *
 * <h2>两条生成路径的异同</h2>
 * <p>
 * 凭证召唤和自然刷新<b>共用同一段「找安全落点 → 生成实体 → 打标记」的代码</b>
 * （{@link #spawnAt}），区别全部集中在它前后：
 * </p>
 * <ul>
 *   <li><b>之前</b>：凭证要做 CAS 占位（写数据库）；自然刷新只查一次岛屿标志 + 内存冷却，
 *       一个字节都不写库；</li>
 *   <li><b>之后</b>：凭证要把占位转正、然后才扣凭证；自然刷新只在内存里记一下「这座岛现在有一个」。</li>
 * </ul>（<b>不扣凭证</b>，凭证是放出/收回开关）
 *
 * <h2>线程模型</h2>
 * <p>
 * 一次凭证召唤要跨<b>三种线程</b>，顺序不能变：
 * </p>
 * <ol>
 *   <li><b>玩家线程</b>：读手上的物品、读玩家坐标（快照下来往后传）；</li>
 *   <li><b>async 线程</b>：查岛屿、查权限、CAS 占位（全是同步阻塞 JDBC，绝不能上 region tick）；</li>
 *   <li><b>region 线程</b>：扫方块、生成实体；</li>
 *   <li>再回 <b>async</b> 把占位转正。凭证不扣，再右键同种凭证就是收回。</li>
 * </ol>
 * <p>
 * 每一次跳线程都是一次「世界可能已经变了」的机会，所以每一步都重新校验，失败就按
 * {@link #rollback} 回滚。
 * </p>
 *
 * <h2>不变量：{@code pendingFinalize} 的登记<b>永远比</b>它对应的占位<b>晚过期</b></h2>
 * <p>
 * 这条性质此前没写在任何地方，但「{@code pendingFinalize} 用 TTL 惰性过期不会开出新漏洞」
 * 完全依赖它。动 TTL、动占位有效期、动这两者的取值时刻之前，必须先读这里：
 * </p>
 * <ol>
 *   <li>占位的 {@code claimExpiresAt = now_A + timeout}，{@code now_A} 取在 <b>async 阶段</b>
 *       （{@link #summonAsyncStage} 里 CAS 之前）；</li>
 *   <li>登记的失效时刻 {@code = now_B + timeout}，{@code now_B} 取在 <b>region 阶段</b>
 *       （{@link MerchantSpawner#spawn} 的 pre-spawn 回调里），中间还隔着一次区块加载；</li>
 *   <li>两处用的 {@code timeout} 是<b>同一个快照值</b>——{@code claimTimeoutMillis} 从
 *       {@link #summonAsyncStage} 一路传到 {@link #beginPending}，而不是各自现读一次配置。
 *       现读的话，召唤进行中管理员执行一次 {@code /skyllia reload} 把
 *       {@code claim-timeout-seconds} 调小，就能让登记比占位<b>先</b>过期；</li>
 *   <li>{@code now_B >= now_A}（同一次召唤里 region 阶段必然晚于 async 阶段）
 *       ⇒ <b>登记必定晚于占位过期</b>。</li>
 * </ol>
 * <p>
 * 于是「登记已失效」蕴含「占位早已失效」。这正是 TTL 惰性过期安全的原因：登记一旦失效、
 * 孤儿检查放行去删那只实体，随后的转正也必然撞上 {@code PromoteResult.CLAIM_EXPIRED}
 * → 回滚 → <b>凭证不扣</b>，不会出现「实体被自己人删掉了、凭证却照扣」这种最难解释的组合。
 * </p>
 * <p>
 * （{@code claim-timeout-seconds} 在配置侧已经被夹到 {@code >= 5}，所以
 * {@link #beginPending} 里那个 5 秒下限恒不生效，TTL 恒等于 claim-timeout 本身。）
 * </p>
 */
public final class MerchantService {

    private static final Logger log = LoggerFactory.getLogger(MerchantService.class);

    /** 核心注册的「允许自然生成游商」岛屿标志。 */
    private static final NamespacedKey FLAG_WANDERING_VILLAGER =
            new NamespacedKey("skyllia", "island.spawn.passive.wandering_villager");
    /** 核心注册的「允许自然生成全部被动生物」岛屿标志，作为上面那条的兜底。 */
    private static final NamespacedKey FLAG_PASSIVE_ALL =
            new NamespacedKey("skyllia", "island.spawn.passive.all");

    private final SkylliaTrader plugin;
    private final TraderDataService dataService;
    private final MerchantKeys keys;
    private final MerchantSpawner spawner;

    /**
     * 正在走召唤流程的玩家，防止连点右键把同一张凭证用两次。
     * <p>
     * 这只挡「同一个玩家」；两名<b>不同</b>成员同时用凭证靠数据库里的 CAS 占位来挡，
     * 内存集合做不到跨玩家的原子性（也不该做——那属于数据一致性，不是 UI 防抖）。
     * </p>
     */
    private final Map<UUID, Boolean> summonInFlight = new ConcurrentHashMap<>();

    /**
     * 每座岛当前存活的自然刷新游商（岛屿 id → 实体 id）。
     * <p>
     * <b>纯内存、不落库</b>：自然游商不占凭证名额、活不过一次区块卸载，把它写进数据库
     * 只会带来一堆「重启后残留」的清理工作，收益是零。重启后这张表清空，
     * 最坏情况是一座岛在重启后立刻又刷一个——无所谓。
     * </p>
     */
    private final Map<UUID, UUID> naturalMerchants = new ConcurrentHashMap<>();

    /** 每座岛下一次允许自然刷新的时间戳（epoch millis）。同样是纯内存。 */
    private final Map<UUID, Long> naturalNextEligibleAt = new ConcurrentHashMap<>();

    /**
     * 「刚生成出来、记账还没做完」的凭证游商实体 id → 这条登记的失效时间戳（epoch millis）。
     *
     * <h3>为什么必须有这张表</h3>
     * <p>
     * 生成实体那一步的真实时序是：{@code World#spawn} 的 pre-spawn 回调打完 PDC →
     * {@code addEntityToWorld} <b>同步</b>触发 {@code EntityAddToWorldEvent} →
     * 监听器当场把孤儿检查派发到 async 队列 → {@code spawnAt} 才返回 →
     * {@code summonRegionStage} 这才把「占位转正」派发到 async 队列。
     * <b>孤儿检查比转正先入队</b>，而它只做一次 SELECT，转正要抢 island 锁 + SELECT + UPDATE，
     * 几乎必然是孤儿检查先跑完。那一刻数据库里只有一条 {@code entityUuid == null} 的占位，
     * {@code isOrphan} 按 entityId 匹配必然落空 → 判为孤儿 → 当场 {@code remove()}。
     * 后果是玩家凭证被扣、提示「召唤成功」，而商人已经被自己人删掉了。
     * </p>
     * <p>
     * 所以生成之前（pre-spawn 回调里，见 {@link MerchantSpawner#spawn}）就把 UUID 登记进来，
     * 孤儿检查看到登记就跳过，等记账走完（成功或回滚）再摘掉。
     * </p>
     *
     * <h3>为什么存的是时间戳而不是一个 Set</h3>
     * <p>
     * 一条永远摘不掉的登记 = 一只<b>永远不会被孤儿检查看一眼</b>的实体，那正是孤儿检查存在的理由。
     * 所有退出路径都显式摘除（见 {@link #finishPending}），但「所有路径都覆盖到了」是一个
     * 靠人眼保证的性质；加上失效时间戳之后，即使漏了一条，它也会在超时后自动失效，
     * 最坏结果退化成「这只实体本次运行少查一次孤儿」，而不是永久豁免。
     * </p>
     */
    private final Map<UUID, Long> pendingFinalize = new ConcurrentHashMap<>();

    public MerchantService(@NotNull SkylliaTrader plugin, @NotNull TraderDataService dataService,
                           @NotNull MerchantKeys keys, @NotNull MerchantSpawner spawner) {
        this.plugin = plugin;
        this.dataService = dataService;
        this.keys = keys;
        this.spawner = spawner;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 凭证召唤
    // ══════════════════════════════════════════════════════════════════════

    /** CAS 占位的判定结果。 */
    private enum ClaimResult {
        /** 占位成功。 */
        OK,
        /** 这个商队类型还没被岛屿等级解锁（HANDOFF 9.3 T6：下界 20 级 / 末地 30 级）。 */
        CARAVAN_LOCKED,
        /** 这座岛已经有同种商队的商人（或另一名成员的占位正在进行中）。 */
        ALREADY_HAVE,
        /** 分类型上限没满，但总名额满了。 */
        TOTAL_FULL,
        /** 上一个商人刚死，还在重召冷却里。 */
        COOLDOWN,
        /** 写库失败。 */
        STORAGE_ERROR
    }

    /**
     * 玩家用凭证召唤一个常驻商队商人。<b>必须在玩家线程上调用</b>（要读手上的物品和坐标）。
     *
     * @param player  使用凭证的玩家
     * @param caravan 凭证对应的商队
     * @param hand    凭证所在的手（识别用；召唤成功不扣凭证）
     * @param spawnAt 右键方块中心 + Y+2，必须在玩家线程上拍好快照
     */
    public void summonWithCredential(@NotNull Player player, @NotNull CaravanType caravan,
                                     @NotNull EquipmentSlot hand, @NotNull Location spawnAt) {
        UUID playerId = player.getUniqueId();
        if (summonInFlight.putIfAbsent(playerId, Boolean.TRUE) != null) {
            player.sendMessage(Component.text("§e上一次召唤还在处理中，请稍等一下。"));
            return;
        }

        // 玩家坐标必须在玩家线程上读，后面几步都在别的线程上跑。
        Location playerLoc = player.getLocation();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                summonAsyncStage(player, caravan, hand, playerLoc, spawnAt);
            } catch (Throwable t) {
                // asyncScheduler 会把异常吞成一段控制台堆栈，玩家侧「点了没反应」。
                log.error("玩家 {} 使用 {} 凭证时发生未预期的异常", player.getName(), caravan, t);
                player.sendMessage(Component.text("§c召唤失败：服务器内部错误，请联系管理员。"));
                summonInFlight.remove(playerId);
            }
        });
    }

    /** 第 2 步（async）：查岛屿 / 校验位置与权限 / CAS 占位。 */
    private void summonAsyncStage(Player player, CaravanType caravan, EquipmentSlot hand,
                                  Location playerLoc, Location spawnAt) {
        UUID playerId = player.getUniqueId();
        TraderConfigManager cfg = TraderConfigLoader.config;

        Island island = SkylliaAPI.getIslandByPlayerId(playerId);
        if (island == null) {
            fail(player, "§c你还没有空岛，先创建一个空岛再使用凭证。");
            return;
        }

        World summonWorld = resolveSummonWorld();
        if (summonWorld == null) {
            log.error("凭证召唤失败：找不到可用的召唤世界（credential.summon-world='{}'）", cfg.getSummonWorld());
            fail(player, "§c召唤失败：服务器没有配置可用的主岛世界，请联系管理员。");
            return;
        }

        // 三种商队都在主岛召唤（规格 6.2.5）：下界/末地都是虚空，玩家几乎不会去，
        // 让他们跑过去召唤等于这两种商队永远用不上。
        if (playerLoc.getWorld() == null || !summonWorld.getName().equals(playerLoc.getWorld().getName())) {
            fail(player, "§c三种商队都要在§l主岛§c召唤。请回到主岛世界（§f"
                    + summonWorld.getName() + "§c）再使用凭证。");
            return;
        }
        if (!island.isInside(playerLoc)) {
            fail(player, "§c请站在自己的岛屿范围内使用凭证。");
            return;
        }
        if (!SkylliaAPI.getPermissionsManager().hasPermission(player, island, plugin.getPermissions().merchantSummon())) {
            fail(player, "§c你在这座岛上没有召唤商队的权限。");
            return;
        }

        // ── 凭证是「开关」：已经有这种商队就改为撤离 ──────────────────────────
        // 2026-08-24 服主反馈的两个真实问题，根子是同一个：
        //   ① 生产上出现过「凭证游商放下去就原地消失，实体找不到、也杀不掉」，名额却被记录
        //      占着 —— 玩家从此再也召不出这种商队，只能求管理员执行 release；
        //   ② 凭证是一次性消耗品且挑战只能做一次，一旦卡住就是永久卡进度，全服都堵在这儿。
        // 把凭证做成开关之后，这两条同时解开：名额被占着的时候右键 = 撤离并释放名额，
        // 凭证<b>不消耗</b>，玩家随时能自救；想换个位置放商人也只要右键两下。
        long nowCheck = System.currentTimeMillis();
        TraderIslandData existing = dataService.load(island);
        boolean occupied = existing.merchants.stream()
                .anyMatch(r -> caravan.name().equals(r.caravan) && r.occupiesSlot(nowCheck));
        if (occupied) {
            summonInFlight.remove(playerId);
            recallCaravan(player, island, caravan);
            return;
        }

        if (spawnAt.getWorld() == null || !summonWorld.getName().equals(spawnAt.getWorld().getName())) {
            fail(player, "§c请对着主岛上的方块右键使用凭证。");
            return;
        }
        if (!island.isInside(spawnAt)) {
            fail(player, "§c请对着自己岛屿范围内的方块右键使用凭证。");
            return;
        }
        Location base = spawnAt.clone();

        // T6（HANDOFF 7.1/9.3）：岛屿等级门槛。IslandLevelBridge 是同步阻塞读取
        // （见该类文档），必须在临界区外读好、作为快照传进 compute 的 lambda，
        // 不能在 lambda 内部现读——临界区不允许做任何阻塞 IO。
        long islandLevel = IslandLevelBridge.isAvailable() ? IslandLevelBridge.getIslandLevel(island) : 0L;

        // ── CAS 占位 ──────────────────────────────────────────────────────
        // 判定与写入在同一个 island 锁里完成，两名成员同时用凭证只有一个能拿到 OK。
        String claimId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long claimTimeoutMillis = cfg.getClaimTimeoutSeconds() * 1000L;
        long cooldownMillis = cfg.getRespawnCooldownSeconds() * 1000L;

        // 冷却剩余秒数在临界区里顺手算出来带回来，省掉一次「为了拼提示文案再读一遍库」。
        long[] cooldownRemainSeconds = {0L};

        ClaimResult result = dataService.compute(island, data -> {
            // 先把「超时的占位」这类垃圾记录清掉再判定。它们本来就已经不占名额
            // （occupiesSlot 判 false），删掉不改变任何判定结果，纯粹是回收空间：
            // 崩溃/超时留下的孤儿占位没有任何一条路径会删它——回滚按 claimId、
            // 死亡按 entityUuid、release 按商队，全都命中不了一条已超时的占位，
            // 于是 JSON 里的 merchants 数组只增不减。放在这里做是因为这里已经持有 island 锁，
            // 不用为了清垃圾额外开一条读写路径。
            // ⚠️ 但这次 removeIf 只有在本次 compute 最终走 Mutation.commit（也就是占位成功）
            // 时才落库；下面几条 readOnly 分支会把整个 data 对象连同这次清理一起丢掉
            // （load() 每次都从 PDC 重新反序列化，没有缓存）。
            // 这不影响正确性：过期占位在所有判定里本来就不占名额，而「确实需要靠清理才腾得出
            // 名额」的那一次召唤必然走到 OK 并 commit，垃圾就在那一次被顺手带走。
            data.merchants.removeIf(r -> r.isClaim() && r.claimExpiresAt > 0 && r.claimExpiresAt <= now);

            // T6 判定一：这个商队类型有没有被岛屿等级解锁。和下面的总名额天花板是两条独立判定
            // （HANDOFF 9.3 硬规则），谁不满足都直接拒绝，不占位、不消耗凭证。
            if (!cfg.isCaravanUnlockedAtLevel(caravan, islandLevel)) {
                return TraderDataService.Mutation.readOnly(ClaimResult.CARAVAN_LOCKED);
            }
            if (data.occupiedSlotsFor(caravan.name(), now) >= cfg.getMaxPerCaravan()) {
                return TraderDataService.Mutation.readOnly(ClaimResult.ALREADY_HAVE);
            }
            // T6 判定二：总名额天花板。未被管理员单独设置过（-1）的岛屿，默认值不再是
            // config.toml 里写死的数字，改成按 islandLevel 动态算（见方法文档）。
            if (data.occupiedSlots(now) >= data.effectiveCredentialSlots(cfg.defaultCredentialSlotsForLevel(islandLevel))) {
                return TraderDataService.Mutation.readOnly(ClaimResult.TOTAL_FULL);
            }
            if (cooldownMillis > 0 && data.lastMerchantDeathAt > 0
                    && now - data.lastMerchantDeathAt < cooldownMillis) {
                cooldownRemainSeconds[0] = Math.max(1L,
                        (cooldownMillis - (now - data.lastMerchantDeathAt) + 999L) / 1000L);
                return TraderDataService.Mutation.readOnly(ClaimResult.COOLDOWN);
            }
            data.merchants.add(MerchantRecord.claim(caravan, claimId, base.getWorld().getName(),
                    base.getX(), base.getY(), base.getZ(), player.getUniqueId().toString(),
                    now, claimTimeoutMillis));
            return TraderDataService.Mutation.commit(ClaimResult.OK, ClaimResult.STORAGE_ERROR);
        });

        switch (result) {
            case CARAVAN_LOCKED -> {
                fail(player, "§e" + caravan.defaultDisplayName() + "§e需要岛屿达到 §f"
                        + cfg.requiredUnlockLevel(caravan) + " §e级才能召唤（当前 §f" + islandLevel
                        + " §e级），凭证没有被消耗。");
                return;
            }
            case ALREADY_HAVE -> {
                fail(player, "§e你的岛上已经有一个§f" + caravan.defaultDisplayName()
                        + "§e了，凭证没有被消耗。");
                return;
            }
            case TOTAL_FULL -> {
                fail(player, "§e你的岛屿常驻商人数量已达上限，凭证没有被消耗。");
                return;
            }
            case COOLDOWN -> {
                fail(player, "§e岛上刚有商人死亡，还要等 §f" + cooldownRemainSeconds[0]
                        + "§e 秒才能重新召唤，凭证没有被消耗。");
                return;
            }
            case STORAGE_ERROR -> {
                fail(player, "§c召唤失败：数据保存出错，凭证没有被消耗，请稍后重试。");
                return;
            }
            case OK -> {
                // 继续往下走
            }
        }

        // ── 占位成功，去 region 线程生成实体 ──────────────────────────────
        // 先把区块拉起来再调度：岛屿 spawn 所在的区块未必加载着（玩家可能站在岛的另一头），
        // 而 RegionScheduler 对着一个不存在的区域排队的任务可能一直不执行，占位就悬在那里。
        int chunkX = base.getBlockX() >> 4;
        int chunkZ = base.getBlockZ() >> 4;
        base.getWorld().getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                log.warn("加载岛屿 {} 的出生点区块失败，本次召唤放弃", island.getId(), error);
                // ⚠️ getChunkAtAsync 的 whenComplete 一般跑在<b>区域线程</b>上，
                // 而 rollback 要写库（同步阻塞 JDBC），必须先跳回 async 再写。
                Bukkit.getAsyncScheduler().runNow(plugin, at -> rollback(island, claimId, null));
                // 这里没有任何超时机制（getChunkAtAsync 只会给出异常或 null），别写成「加载超时」。
                fail(player, "§c召唤失败：岛屿区块加载失败，凭证没有被消耗，请稍后重试。");
                return;
            }
            Bukkit.getRegionScheduler().execute(plugin, base.getWorld(), chunkX, chunkZ, () -> {
                // region 线程上抛出去的异常会被 Folia 吞成一段控制台堆栈，
                // 而 in-flight 标记会永远留着 —— 那名玩家在本次运行里再也召唤不了任何东西。
                try {
                    summonRegionStage(player, island, caravan, hand, base, claimId, claimTimeoutMillis);
                } catch (Throwable t) {
                    log.error("玩家 {} 召唤 {} 时在生成阶段出错", player.getName(), caravan, t);
                    Bukkit.getAsyncScheduler().runNow(plugin, at -> rollback(island, claimId, null));
                    fail(player, "§c召唤失败：服务器内部错误，凭证没有被消耗。");
                }
            });
        });
    }

    /**
     * 第 3 步（region 线程）：在右键方块的 Y+2 原样生成实体。
     */
    private void summonRegionStage(Player player, Island island, CaravanType caravan,
                                   EquipmentSlot hand, Location base, String claimId,
                                   long claimTimeoutMillis) {
        WanderingTrader trader = spawnExact(island, base, caravan, MerchantOrigin.CREDENTIAL, 0L,
                claimTimeoutMillis);
        if (trader == null) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> rollback(island, claimId, null));
            fail(player, "§c召唤失败：这个位置放不下商人，请换一块空地再对着方块右键。");
            return;
        }

        UUID entityId = trader.getUniqueId();
        Location spawnedAt = trader.getLocation();

        // 第 4 步：回 async 把占位转正。
        Bukkit.getAsyncScheduler().runNow(plugin, t -> {
            try {
                summonFinalizeStage(player, island, caravan, hand, claimId, entityId, spawnedAt, trader);
            } catch (Throwable error) {
                log.error("玩家 {} 召唤 {} 时在记账阶段出错，已撤销本次召唤", player.getName(), caravan, error);
                finishPending(entityId);
                rollback(island, claimId, trader);
                fail(player, "§c召唤失败：服务器内部错误，凭证没有被消耗。");
            }
        });
    }

    /** 占位转正的判定结果。失败的每一种都要能变成一句「玩家看得懂为什么」的中文提示。 */
    private enum PromoteResult {
        /** 转正成功，可以去扣凭证了。 */
        OK,
        /** 占位记录已经不在了（管理员 release、或者被后来的召唤当成超时垃圾清掉了）。 */
        CLAIM_GONE,
        /** 占位还在，但已经超过 claim-timeout —— 它此刻已经不占名额，不能再转正。 */
        CLAIM_EXPIRED,
        /** 占位期间岛屿等级掉到了这个商队类型的解锁门槛以下（T6，HANDOFF 9.3）。 */
        CARAVAN_LOCKED,
        /** 占位超时期间名额被别人（合法地）占走了，转正会造出第二个同种商人。 */
        SLOT_TAKEN,
        /** 写库失败。 */
        STORAGE_ERROR
    }

    /**
     * 第 4 步（async）：占位转正。凭证是开关，成功后不扣。
     *
     * <h3>为什么转正时要把名额判定<b>重做一遍</b></h3>
     * <p>
     * 占位和转正之间隔着「加载区块 + 扫方块 + 生成实体」，服务器卡顿时这一段完全可能超过
     * {@code claim-timeout}。一旦超时，本条占位就不再占名额，另一名成员的 CAS 会正常通过并
     * 召唤成功；此时如果本条只按 claimId 找到记录就无条件 {@code promote()}，结果就是
     * <b>同一座岛、同一种商队出现两个常驻商人，两张凭证都被扣掉</b>，直接违反
     * 「每岛每种商队最多 1 个」这条硬规格，而且残留的双商人没有任何自动清理路径。
     * </p>
     * <p>
     * 所以这里必须在<b>同一个 island 锁的临界区</b>里复核三件事：
     * ①这条占位此刻还占着名额（没超时）；②这个商队类型此刻仍然被岛屿等级解锁（T6，HANDOFF 9.3，
     * 占位期间岛屿完全可能掉级）；③把自己这条排除掉之后，该商队的上限和该岛的总上限（总上限的
     * 默认值同样要按<b>此刻</b>的岛屿等级重新算，不能沿用占位阶段的旧快照）都还有空位。
     * 任一条不满足就走回滚，<b>凭证不扣</b>——这也正是规格
     * 「CAS 占位 → 生成成功 → 才消耗凭证」在失败分支上要求的行为。
     * </p>
     */
    private void summonFinalizeStage(Player player, Island island, CaravanType caravan, EquipmentSlot hand,
                                     String claimId, UUID entityId, Location spawnedAt, WanderingTrader trader) {
        TraderConfigManager cfg = TraderConfigLoader.config;
        long now = System.currentTimeMillis();
        // T6：和占位阶段一样，等级要在临界区外读好再传进去（IslandLevelBridge 是同步阻塞读取）。
        // 占位与转正之间隔着「加载区块 + 扫方块 + 生成实体」，理论上够长到让岛屿等级发生变化
        // （比如恰好在这几十毫秒内掉级），所以这里必须重新读一次，不能沿用占位阶段的旧值。
        long islandLevel = IslandLevelBridge.isAvailable() ? IslandLevelBridge.getIslandLevel(island) : 0L;

        PromoteResult promoted = dataService.compute(island, data -> {
            MerchantRecord record = data.findByClaimId(claimId);
            if (record == null) {
                // 占位在这几十毫秒里被别人清掉了（管理员 release、占位超时被后来的召唤清理……）。
                // 不能凭空补一条：那会绕过名额判定。放弃并回收实体。
                return TraderDataService.Mutation.readOnly(PromoteResult.CLAIM_GONE);
            }
            if (!record.occupiesSlot(now)) {
                // 占位已经超时。它此刻在所有名额判定里都被当成空位，转正等于凭空多出一个名额。
                return TraderDataService.Mutation.readOnly(PromoteResult.CLAIM_EXPIRED);
            }
            // 排除自己这条之后复核两条上限：两个计数各减 1 就是「除我以外」的占用数。
            // 「减 1」成立需要两件事，上面只证了第二件，第一件此前是隐式的，这里补上并断言：
            //   ① record.caravan == caravan：claimId 是本次召唤现场生成的 UUID、全局唯一，
            //      而 caravan 在 MerchantRecord.claim(...) 里创建时就被钉死，此后没有任何路径
            //      会改这个字段 —— 所以 findByClaimId 找回来的必定是本商队这条；
            //   ② record.occupiesSlot(now) 为真（上一步刚判过），它确实被计进了这两个计数里。
            // ① 靠的是「唯一 + 不可变」这两条外部性质，将来有人给 claimId 加复用、或给 caravan
            // 加可变路径时，不断言就没人会发现，所以这里宁可多花一次字符串比较。
            if (!caravan.name().equals(record.caravan)) {
                log.error("岛屿 {} 的占位 {} 记录的商队是 {}，与本次召唤的 {} 对不上，放弃转正",
                        island.getId(), claimId, record.caravan, caravan.name());
                return TraderDataService.Mutation.readOnly(PromoteResult.CLAIM_GONE);
            }
            // T6 权威复核（HANDOFF 9.3 硬规则：占位阶段查一遍，转正临界区权威复核一遍，
            // 不信任占位阶段的结果）：占位期间岛屿等级完全可能掉到解锁线以下。
            if (!cfg.isCaravanUnlockedAtLevel(caravan, islandLevel)) {
                return TraderDataService.Mutation.readOnly(PromoteResult.CARAVAN_LOCKED);
            }
            int othersSameCaravan = data.occupiedSlotsFor(caravan.name(), now) - 1;
            int othersTotal = data.occupiedSlots(now) - 1;
            if (othersSameCaravan >= cfg.getMaxPerCaravan()
                    || othersTotal >= data.effectiveCredentialSlots(cfg.defaultCredentialSlotsForLevel(islandLevel))) {
                return TraderDataService.Mutation.readOnly(PromoteResult.SLOT_TAKEN);
            }
            record.promote(entityId, spawnedAt.getWorld().getName(),
                    spawnedAt.getX(), spawnedAt.getY(), spawnedAt.getZ());
            return TraderDataService.Mutation.commit(PromoteResult.OK, PromoteResult.STORAGE_ERROR);
        });

        if (promoted != PromoteResult.OK) {
            // 五种失败的处理动作完全一样：摘掉「正在记账」登记 → 回收实体 + 删占位 → 不扣凭证。
            // 区别只在给玩家的那句话，所以先统一做动作，再按原因分支写提示。
            finishPending(entityId);
            rollback(island, claimId, trader);
            switch (promoted) {
                case CLAIM_GONE -> {
                    log.warn("岛屿 {} 的凭证游商转正失败：占位 {} 已不存在，已回收实体", island.getId(), claimId);
                    fail(player, "§e召唤已取消：你的名额登记在生成过程中被清除了"
                            + "（可能是管理员释放了名额），凭证没有被消耗。");
                }
                case CLAIM_EXPIRED -> {
                    log.warn("岛屿 {} 的凭证游商转正失败：占位 {} 已超过 claim-timeout，已回收实体",
                            island.getId(), claimId);
                    fail(player, "§e召唤超时：生成商人花的时间超过了名额占位的有效期（"
                            + cfg.getClaimTimeoutSeconds() + " 秒），凭证没有被消耗，请稍后重试。");
                }
                case CARAVAN_LOCKED -> {
                    log.warn("岛屿 {} 的凭证游商转正失败：{} 在生成期间掉出了等级解锁线（当前 {} 级），已回收实体",
                            island.getId(), caravan, islandLevel);
                    fail(player, "§e召唤已取消：" + caravan.defaultDisplayName() + "§e需要岛屿达到 §f"
                            + cfg.requiredUnlockLevel(caravan) + " §e级才能召唤，你的岛屿在生成过程中变成了 §f"
                            + islandLevel + " §e级，没有达到要求，凭证没有被消耗。");
                }
                case SLOT_TAKEN -> {
                    log.warn("岛屿 {} 的凭证游商转正失败：占位 {} 超时期间名额已被其他成员占用，已回收实体",
                            island.getId(), claimId);
                    fail(player, "§e召唤超时，名额已被其他成员占用，凭证没有被消耗。");
                }
                default -> {
                    log.error("岛屿 {} 的凭证游商转正写库失败（占位 {}），已回收实体", island.getId(), claimId);
                    fail(player, "§c召唤失败：数据保存出错，凭证没有被消耗，请稍后重试。");
                }
            }
            return;
        }

        // 第 5 步：凭证是开关，召唤成功<b>不扣</b>。再右键同种凭证 = 撤离。
        // 2026-08-24 服主拍板：挑战凭证不能重复领，扣掉就等于永久卡进度。
        finishPending(entityId);
        player.sendMessage(Component.text("§a成功召唤了§f" + caravan.defaultDisplayName()
                + "§a！它会永久驻留在你的岛上。再右键这张凭证可以把它收回。"));
        summonInFlight.remove(player.getUniqueId());
    }

    /**
     * 回滚一次召唤：删掉占位/记录，并回收已经生成出来的实体。
     * <b>必须在 async 线程上调用</b>（要写库）。
     * <p>
     * 本方法<b>不</b>负责摘 {@link #pendingFinalize}：它有一条 {@code trader == null} 的调用路径
     * （实体还没生成就失败了），那时根本拿不到实体 id。摘除统一由调用方在调 rollback 之前调
     * {@link #finishPending}，让「谁登记、谁摘除」这对动作在同一段代码里看得见。
     * </p>
     */
    private void rollback(Island island, String claimId, @Nullable WanderingTrader trader) {
        if (trader != null) {
            // 实体只能在它自己的调度器上操作。retired 回调传 null：实体如果已经没了，
            // 那正是我们想要的结果，不需要补偿动作。
            trader.getScheduler().execute(plugin, trader::remove, null, 1L);
        }
        boolean ok = dataService.mutate(island, data -> data.merchants.removeIf(r -> claimId.equals(r.claimId)));
        if (!ok) {
            // 回滚都写不进去，说明数据库确实挂了。占位会在 claim-timeout 之后自动失效，
            // 所以不会永久锁死名额；但要留一条 error 让管理员知道发生过什么。
            log.error("岛屿 {} 的占位 {} 回滚写库失败；该占位会在超时后自动失效", island.getId(), claimId);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 「正在记账」登记表（pendingFinalize）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 登记一只刚生成、记账未完成的凭证游商。
     * <b>只在 {@code World#spawn} 的 pre-spawn 回调里调用</b>——必须早于
     * {@code EntityAddToWorldEvent}，理由见 {@link #pendingFinalize}。
     *
     * @param claimTimeoutMillis 本次召唤 CAS 占位时用的 claim-timeout <b>快照</b>（毫秒）。
     *                           <b>不能在这里现读配置</b>：召唤进行中一次 {@code /skyllia reload}
     *                           把 {@code claim-timeout-seconds} 调小，就会让这条登记比它对应的
     *                           占位<b>先</b>过期，破坏类注释里那条不变量。
     */
    private void beginPending(UUID entityId, long claimTimeoutMillis) {
        // 兜底夹一个 5 秒下限：配置侧 getClaimTimeoutSeconds() 已经夹过 >= 5，这一步恒不生效，
        // 纯粹是防止将来有别的调用方传进来一个 0。
        long ttlMillis = Math.max(5_000L, claimTimeoutMillis);
        pendingFinalize.put(entityId, System.currentTimeMillis() + ttlMillis);
        // 顺手清一遍过期项。正常情况下这张表里最多同时有几条（= 正在召唤的玩家数），
        // 只有某条退出路径漏摘时才会攒东西，所以阈值放得很松，平时一次都不会触发。
        if (pendingFinalize.size() > 32) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, Long>> it = pendingFinalize.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() <= now) it.remove();
            }
        }
    }

    /** 记账走完（成功或回滚）时摘掉登记。任何线程都能调，重复调用无害。 */
    private void finishPending(UUID entityId) {
        pendingFinalize.remove(entityId);
    }

    /**
     * 这只实体是不是「刚生成、记账还没做完」的凭证游商。
     * {@code MerchantLifecycleListener} 的孤儿检查靠它跳过尚未转正的商人。
     * <p>
     * 顺带做惰性过期：登记超时说明记账那条路早就走完（或彻底失败）了，再豁免下去
     * 就等于让这只实体永远逃过孤儿检查——那正是孤儿检查存在的理由。
     * </p>
     */
    public boolean isFinalizePending(@NotNull UUID entityId) {
        Long until = pendingFinalize.get(entityId);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            pendingFinalize.remove(entityId, until);
            return false;
        }
        return true;
    }

    /** 统一的失败出口：发提示 + 解除 in-flight 标记。可以在任何线程上调用。 */
    private void fail(Player player, String message) {
        player.sendMessage(Component.text(message));
        summonInFlight.remove(player.getUniqueId());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 自然刷新
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 尝试给一座岛做一次自然刷新。<b>必须在 async 线程上调用</b>（要读岛屿标志，可能查库）。
     * <p>
     * 全流程<b>不写任何数据库</b>：自然游商不占名额、不记账，状态只在内存 + 实体 PDC 上。
     * </p>
     *
     * <h3>⚠️ 调用方的义务：先确认「确实有成员站在这座岛上」</h3>
     * <p>
     * 本方法会调 {@code getChunkAtAsync(x, z, false)}。那个 {@code false} 只表示
     * <b>「不生成新区块」</b>，对已经生成过、只是当前没加载的区块<b>照样会加载</b>。
     * 也就是说：只要判定口径是「岛屿有成员在线」，一个在主城挂机的玩家就足以让他那座空岛的
     * spawn 区块被拽进内存、被 Folia 拉起一个 region 开始 tick、刷出一个没人看得见的商人，
     * 随后区块又卸载。69 座岛叠起来就是持续的无谓开销。
     * </p>
     * <p>
     * 所以「人是否真的在岛上」的判定放在<b>调用方</b>（{@code NaturalSpawnTask}）做，
     * 用的是玩家位置缓存（{@code PlayerLocationTracker}）而不是从 async 线程读
     * {@code player.getLocation()}——后者在 Folia 上确实不安全。把判定放在这里面、
     * 放到 region 任务里再判也不行：那时区块已经被拉起来了，代价已经付掉了。
     * </p>
     */
    public void tryNaturalSpawn(@NotNull Island island) {
        TraderConfigManager cfg = TraderConfigLoader.config;
        if (!cfg.isNaturalSpawnEnabled()) return;

        UUID islandId = island.getId();
        long now = System.currentTimeMillis();

        // 这座岛已经有一个自然游商了，不重复刷。
        if (naturalMerchants.containsKey(islandId)) return;

        Long nextAt = naturalNextEligibleAt.get(islandId);
        if (nextAt != null && now < nextAt) return;

        if (ThreadLocalRandom.current().nextDouble() >= cfg.getNaturalChance()) {
            // 没中概率也要走一次冷却，否则巡检间隔一到就又来一次，
            // 「间隔 + 概率」两个旋钮里的间隔那个就等于没有了。
            markNaturalCooldown(islandId, now, cfg);
            return;
        }

        World world = resolveSummonWorld();
        if (world == null) return;

        // 规格 6.1 第 8 条：自然刷新尊重岛主的标志，凭证召唤无视。
        if (cfg.isNaturalRespectIslandFlag() && !isWanderingTraderAllowed(island, world.getName())) return;

        Location base = island.getSpawnLocation(world);
        if (base == null || base.getWorld() == null) return;

        int chunkX = base.getBlockX() >> 4;
        int chunkZ = base.getBlockZ() >> 4;
        // 自然刷新不强行加载区块（第三个参数 false）：岛上有人在线时出生点区块通常是加载着的，
        // 为了刷一个可有可无的路人商人去把区块拽起来不值当。
        base.getWorld().getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) return;
            Bukkit.getRegionScheduler().execute(plugin, base.getWorld(), chunkX, chunkZ, () -> {
                // 和凭证路径（summonAsyncStage 里那个 try/catch）同一个理由：region 线程上抛
                // 出去的异常会被 Folia 吞成一段没有上下文的控制台堆栈。这里如果在
                // naturalMerchants.put 之后、markNaturalCooldown 之前炸掉，就会留下
                // 「key 记了、冷却没记」的半截状态，而日志里看不出是谁干的。
                // （那个 key 不会永久卡死：实体确实存在，区块卸载/过期时 forgetNatural 会摘掉它。）
                try {
                    long expireAt = System.currentTimeMillis() + cfg.getNaturalStayMinutes() * 60_000L;
                    // 自然刷新的游商在主岛刷，商队归属固定记成 OVERWORLD。
                    // 它其实用不到商队这个维度（只卖基础池），但 PDC 里存一个确定值总比存 null 好：
                    // 读侧就不用为「商队可能为空」写第二套分支，T3 的商品筛选也能一视同仁地拿到值。
                    // 最后那个 0 是 pendingFinalize 的 TTL：自然游商不登记，用不到。
                    WanderingTrader trader = spawnAt(island, base, CaravanType.OVERWORLD,
                            MerchantOrigin.NATURAL, expireAt, 0L);
                    if (trader == null) {
                        // 找不到落点，或者生成被其它插件取消（spawnAt 已经把「返回了一只已作废的
                        // 实体」这种情况归一化成 null）：放弃本次刷新，**不消耗冷却**（规格 6.9），
                        // 下一轮巡检还会再试。这一步很重要——不判的话 naturalMerchants 会记下一个
                        // 根本不在世界里的 UUID，而它永远等不到 EntityRemoveEvent 来摘，
                        // 这座岛在本次运行期内就再也不会自然刷新了。
                        return;
                    }
                    naturalMerchants.put(islandId, trader.getUniqueId());
                    markNaturalCooldown(islandId, System.currentTimeMillis(), cfg);
                    scheduleNaturalDespawn(trader, expireAt);
                } catch (Throwable t) {
                    log.error("岛屿 {} 的自然刷新生成阶段出错", islandId, t);
                }
            });
        });
    }

    /** 记一次自然刷新冷却。 */
    private void markNaturalCooldown(UUID islandId, long now, TraderConfigManager cfg) {
        naturalNextEligibleAt.put(islandId, now + cfg.getNaturalCooldownMinutes() * 60_000L);
    }

    /**
     * 给自然刷新的游商挂一个「到点自己走」的任务。
     * <p>
     * 这只是<b>第二道保险</b>：第一道是 {@code despawnDelay}（原版每 tick 自减，服务器重启后
     * 也随 NBT 恢复），第三道是实体 PDC 上的 {@code EXPIRE} + {@code EntityAddToWorldEvent}
     * （区块重新加载时补一刀）。三道都很便宜，而「玩家岛上多了一个永远不走的路人商人」
     * 是那种没人报 bug、但会慢慢积累成一群的问题。
     * </p>
     */
    private void scheduleNaturalDespawn(WanderingTrader trader, long expireAt) {
        long delayTicks = Math.max(1L, (expireAt - System.currentTimeMillis()) / 50L);
        trader.getScheduler().runDelayed(plugin, t -> trader.remove(), null, delayTicks);
    }

    /**
     * 查岛屿的「允许自然生成游商」标志。判定方式和核心的 {@code IslandMobSpawnPassiveFlag}
     * 完全一致：<b>单体标志 与 {@code .all} 总开关都为真</b>才允许（核心已改「与」语义，
     * 见 {@code ImplPermissionsManagers#hasFlag}）。
     * <p>
     * 用 {@code getIfPresent} 而不是 {@code id(...)}：核心万一改了标志名，
     * {@code id(...)} 会抛 {@code IllegalArgumentException} 把整条自然刷新链路炸掉；
     * 拿不到就<b>按「不允许」处理</b>——宁可不刷，也不要在岛主明确关掉的岛上刷出商人。
     * </p>
     */
    private boolean isWanderingTraderAllowed(Island island, String worldName) {
        FlagId specific = SkylliaAPI.getFlagRegistry().getIfPresent(FLAG_WANDERING_VILLAGER);
        FlagId fallback = SkylliaAPI.getFlagRegistry().getIfPresent(FLAG_PASSIVE_ALL);
        if (specific == null) {
            log.warn("找不到岛屿标志 {}，自然刷新本轮跳过（核心是不是改了标志名？）", FLAG_WANDERING_VILLAGER);
            return false;
        }
        return SkylliaAPI.getPermissionsManager().hasFlag(island, specific, fallback, worldName);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 共用的生成动作
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 找安全落点并生成实体。<b>必须在 {@code base} 所属的 region 线程上调用</b>。
     * 这是两条生成路径唯一共用的一段。
     *
     * @param claimTimeoutMillis 凭证路径的 claim-timeout <b>快照</b>（毫秒），用来给
     *                           {@link #beginPending} 定 TTL。自然刷新路径不登记，传 {@code 0}
     * @return <b>真正进了世界</b>的实体；找不到安全落点、生成抛异常、或生成被其它插件取消
     * （拿回来的是一只已作废的实体）时统一返回 {@code null}
     */
    private @Nullable WanderingTrader spawnAt(Island island, Location base, CaravanType caravan,
                                              MerchantOrigin origin, long expireAt,
                                              long claimTimeoutMillis) {
        TraderConfigManager cfg = TraderConfigLoader.config;
        Location safe = SafeSpawnFinder.find(island, base,
                cfg.getSafeScanRadiusBlocks(), cfg.getMinClearHeightBlocks(),
                cfg.getVerticalScanRangeBlocks(), cfg.getMaxSpawnAttempts());
        if (safe == null) return null;
        return spawnExact(island, safe, caravan, origin, expireAt, claimTimeoutMillis);
    }

    /**
     * 在给定坐标原样生成，不再扫安全落点。<b>必须在该坐标所属 region 线程上调用</b>。
     * 凭证召唤用这条：落点就是玩家右键方块的 Y+2。
     */
    private @Nullable WanderingTrader spawnExact(Island island, Location exact, CaravanType caravan,
                                                 MerchantOrigin origin, long expireAt,
                                                 long claimTimeoutMillis) {
        UUID[] marked = {null};
        Consumer<WanderingTrader> preSpawn = null;
        if (origin == MerchantOrigin.CREDENTIAL) {
            preSpawn = spawned -> {
                marked[0] = spawned.getUniqueId();
                beginPending(marked[0], claimTimeoutMillis);
            };
        }

        WanderingTrader trader = spawner.spawn(exact, island.getId(), caravan, origin, expireAt, preSpawn,
                origin != MerchantOrigin.CREDENTIAL);

        // ⚠️ spawn() 返回非 null **不代表**实体真的进了世界。第三方插件（反挂机刷怪限制、
        // 区域保护的 deny-spawn……）取消 CreatureSpawnEvent(CUSTOM) 时，Paper 会先把实体
        // discard 掉再 return false，而这个 false 在 CraftWorld#addEntityToWorld（void）
        // 和 CraftRegionAccessor#addEntity（无条件 return getBukkitEntity()）两处被丢掉了，
        // 调用方拿到的是一只「已作废」的实体。
        //
        // 不在这里拦住的话，凭证路径的后果是一条没有自愈出口的死链：
        //   转正只查数据库、不问实体在不在世界里 → 占位转正成功 → 凭证被扣 →
        //   玩家收到「召唤成功」而岛上空无一人 → 这只实体从未触发过 EntityAddToWorldEvent，
        //   孤儿检查永远跑不到它（就算跑到，isOrphan 也会读到匹配的 entityUuid 判它不是孤儿），
        //   handleDeath / handleRemoval 同样永远不会来 →
        //   这座岛这种商队的名额被**永久**锁死，玩家再用凭证只会得到「你的岛上已经有一个了」，
        //   只能管理员 /skylliadmin trader release 手动救。
        //
        // isValid() 在这里是可靠的判据（去 Paper 源码复核过）：
        //   isValid() == isAlive() && entity.valid；
        //   entity.valid 只在 ServerLevel$EntityCallbacks#onTrackingStart 里被置 true，
        //   而那个回调在 addNewEntity 内部执行、**晚于** doEntityAddEventCalling 那次事件判定，
        //   取消路径压根走不到；discard() 又让 isAlive() 也变成 false，两个条件同时不成立。
        //   反过来，生成成功时 EntityAddToWorldEvent 正是在 valid = true **之后**才发出的
        //   （Paper 注释原话 "fire while valid"），而本插件整套记账都建立在那个事件确实发出的
        //   前提上——所以「成功 ⇒ isValid()」也成立，不会把成功的生成误判成失败。
        if (trader != null && !trader.isValid()) {
            log.warn("岛屿 {} 的游商生成被其它插件取消（拿到的是一只已作废的实体），按生成失败处理",
                    island.getId());
            trader = null;
        }

        if (trader == null && marked[0] != null) {
            // pre-spawn 回调跑过了，但实体最终没能加入世界（被取消、World#spawn 抛异常……）。
            // 这条登记再没有别的路径会摘，必须就地摘掉，
            // 否则这个 UUID 会一直留在表里直到超时。
            finishPending(marked[0]);
        }
        return trader;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 死亡 / 移除
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 一个游商死了。<b>必须在 async 线程上调用</b>。
     * <p>
     * 自然游商只需要从内存表里摘掉；凭证游商要释放名额并记录死亡时间戳
     * （{@code lastMerchantDeathAt} 驱动 {@code respawn-cooldown-seconds}，
     * 防止玩家靠「打死自家商人再重召」刷新订单/规避限购）。
     * </p>
     * <p>
     * {@code respawn-on-death = false} 时<b>刻意不摘掉记录</b>：那个开关的语义就是
     * 「死掉的槽位一直空着，等管理员手动处理」（{@code /skylliadmin trader release}）。
     * </p>
     */
    public void handleDeath(@NotNull UUID islandId, @NotNull UUID entityId, @NotNull MerchantOrigin origin) {
        if (origin == MerchantOrigin.NATURAL) {
            naturalMerchants.remove(islandId, entityId);
            return;
        }
        Island island = SkylliaAPI.getIslandByIslandId(islandId);
        if (island == null) return;

        boolean freeSlot = TraderConfigLoader.config.isRespawnOnDeath();
        boolean ok = dataService.mutate(island, data -> {
            data.lastMerchantDeathAt = System.currentTimeMillis();
            if (freeSlot) {
                data.merchants.removeIf(r -> entityId.toString().equals(r.entityUuid));
            }
        });
        if (!ok) {
            // save() 内部已经打过一条 error，但那条只说「写入失败」，看不出是哪条业务路径。
            // 这里补一条：名额没被释放，玩家会以为「商人死了却召不出新的」。
            log.warn("岛屿 {} 的游商 {} 死亡记账写库失败：名额没有被释放，"
                    + "必要时用 /skylliadmin trader release 手动释放", islandId, entityId);
        }
    }

    /**
     * 一个游商被移除了（不是死亡）。<b>必须在 async 线程上调用</b>。
     * <p>
     * <b>调用方必须已经过滤掉 {@code Cause.UNLOAD}</b>——区块卸载是最常见的移除原因，
     * 把它当成「商人没了」会让玩家一走远名额就被释放，等于无限召唤漏洞。
     * 过滤在 {@code MerchantLifecycleListener} 里做，那里离事件最近、最不容易被漏改。
     * </p>
     * <p>
     * 这里<b>不</b>写 {@code lastMerchantDeathAt}：重召冷却是用来惩罚「故意打死自家商人」的，
     * 而被插件/管理员移除不是玩家的行为，不该让玩家陪着等冷却。
     * </p>
     */
    public void handleRemoval(@NotNull UUID islandId, @NotNull UUID entityId, @NotNull MerchantOrigin origin) {
        if (origin == MerchantOrigin.NATURAL) {
            naturalMerchants.remove(islandId, entityId);
            return;
        }
        Island island = SkylliaAPI.getIslandByIslandId(islandId);
        if (island == null) return;
        boolean ok = dataService.mutate(island, data ->
                data.merchants.removeIf(r -> entityId.toString().equals(r.entityUuid)));
        if (!ok) {
            log.warn("岛屿 {} 的游商 {} 移除记账写库失败：名额没有被释放，"
                    + "必要时用 /skylliadmin trader release 手动释放", islandId, entityId);
        }
    }

    /**
     * 把一只<b>自然刷新</b>游商从内存表里摘掉。<b>只动内存，一个字节都不写库</b>，
     * 因此可以在任何线程上调用（含 region tick 线程）。
     *
     * <h3>为什么必须单独有这个方法</h3>
     * <p>
     * {@code EntityRemoveEvent} 对<b>凭证</b>游商必须继续过滤 {@code Cause.UNLOAD}——
     * 区块卸载不是「商人没了」，把它当成移除会让玩家一走远名额就被释放 = 无限召唤漏洞。
     * 但自然游商是 {@code persistent = false} 的：区块一卸载实体就<b>永久</b>没了，
     * 而 {@link #naturalMerchants} 里那个 key 却会一直留着，于是
     * {@link #tryNaturalSpawn} 开头的 {@code containsKey} 永久短路——
     * <b>这座岛在本次服务器运行期内再也不会自然刷新</b>。
     * 「玩家在岛上刷出商人 → 回主城 → 区块卸载」是最常见的玩家行为，跑一小时大半岛都会中招。
     * </p>
     * <p>
     * 所以监听器对 UNLOAD 的处理是<b>按来源分流</b>而不是一律 return：自然游商走这里清内存，
     * 凭证游商仍然原样 return，一个字节都不动。
     * </p>
     */
    public void forgetNatural(@NotNull UUID islandId, @NotNull UUID entityId) {
        naturalMerchants.remove(islandId, entityId);
    }

    /**
     * 判断一只带标记的凭证游商是不是「孤儿」——岛屿数据里已经没有它的记录了。
     * <b>必须在 async 线程上调用</b>。
     * <p>
     * 孤儿的来源：管理员 {@code release} 了名额但实体所在区块当时没加载、
     * 崩溃时占位丢了而实体已经存盘、数据库被手动改过……不清理的话，玩家岛上会同时存在
     * 「一个不受管理的商人」和「一个空的名额」，等于凭空多一个商人。
     * </p>
     *
     * @return true 表示这只实体应该被移除
     */
    public boolean isOrphan(@NotNull UUID islandId, @NotNull UUID entityId) {
        Island island = SkylliaAPI.getIslandByIslandId(islandId);
        if (island == null) {
            // 岛没了（被删除），实体自然是孤儿。
            return true;
        }
        for (MerchantRecord record : dataService.load(island).merchants) {
            if (entityId.equals(record.entityId())) return false;
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 杂项
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 解析「主岛世界」：配置里写了就用配置的，留空则取第一个 {@code NORMAL} 环境的空岛世界。
     * <p>
     * 之所以默认自动推断而不是硬编码世界名：世界名是每个服自己在核心配置里定的
     * （本服是 {@code sky-overworld} 之类），写死会让插件换个服就用不了。
     * </p>
     */
    public @Nullable World resolveSummonWorld() {
        String configured = TraderConfigLoader.config.getSummonWorld();
        if (configured != null && !configured.isBlank()) {
            World world = Bukkit.getWorld(configured);
            if (world == null) {
                log.warn("配置的召唤世界 '{}' 不存在，回退到自动推断", configured);
            } else {
                return world;
            }
        }
        for (WorldConfig worldConfig : SkylliaAPI.getRegisteredWorlds()) {
            if (worldConfig.getEnvironment() != World.Environment.NORMAL) continue;
            World world = Bukkit.getWorld(worldConfig.getWorldName());
            if (world != null) return world;
        }
        return null;
    }

    /**
     * 一个游商能卖什么范围的货。T3 的商店 GUI 拿它来筛商品池。
     * <p>
     * 自然刷新的游商只开交易次数轨的头几档 + 一本说明书；凭证游商四轨全开。
     * 详见 {@link MerchantOfferScope}。
     * </p>
     */
    public MerchantOfferScope offerScopeFor(@NotNull MerchantOrigin origin) {
        TraderConfigManager cfg = TraderConfigLoader.config;
        return MerchantOfferScope.of(origin, cfg.getNaturalMaxTradeCountTier(),
                cfg.getGuidebook() != null && cfg.getGuidebook().enabled());
    }

    /** 管理端用：这座岛当前登记的凭证游商记录。<b>async 线程调用</b>。 */
    public java.util.List<MerchantRecord> listMerchants(@NotNull Island island) {
        return dataService.load(island).merchants;
    }

    /**
     * {@link #releaseSlot} 的结果。
     * <p>
     * 拆成三项而不是只返回一个 {@code int}，是因为管理员看到的那句话必须能区分
     * 「真的释放了」和「内存里删掉了但一个字节都没写进数据库」——后者的名额其实还占着，
     * 下次召唤照样失败，而旧实现会照样回一句「已释放 N 条」。
     * </p>
     *
     * @param removed         被移除的记录条数
     * @param cooldownCleared 是否顺带清掉了这座岛的重召冷却
     * @param saved           改动是否真的落库了（{@code false} = 数据库写失败，等于什么都没发生）
     */
    public record ReleaseResult(int removed, boolean cooldownCleared, boolean saved) {
    }

    /**
     * 管理端用：强制释放某种商队的名额（不动实体——实体可能在没加载的区块里）。
     * <b>async 线程调用</b>。
     * <p>
     * 被释放之后如果那只实体还活着，它会在下一次区块加载时被
     * {@code MerchantLifecycleListener} 的孤儿检查清掉。
     * </p>
     * <p>
     * <b>顺带把 {@code lastMerchantDeathAt} 归零</b>：管理员执行 release 的典型场景恰恰是
     * 「商人刚死、记录已经自动删掉了、玩家却召不出来」——此时挡着玩家的根本不是名额，
     * 而是最长 5 分钟的重召冷却。只删记录不清冷却的话，管理员看到「已释放」，
     * 玩家却依然被挡住，而且没有任何提示告诉他为什么。
     * </p>
     */
    /**
     * 「撤离」某种商队：把还活着的实体收掉、释放名额、清掉重召冷却。<b>async 线程调用</b>。
     * <p>
     * 这是玩家手持凭证右键、而岛上已经有同种商队时走的分支（见 {@code summonAsyncStage}），
     * 也是「商人卡住了、找不到也杀不掉」时玩家能自救的唯一出口。<b>不消耗凭证</b>。
     * </p>
     * <p>
     * 先按记录里的 entityUuid 尝试把实体收掉：实体可能在没加载的区块里、也可能早就没了
     * （生产上出现过的「原地消失」就是这种），收不到不影响释放名额——记录一删，
     * 万一那只实体还活着，下次区块加载时孤儿检查会把它清掉。
     * </p>
     */
    public void recallCaravan(@NotNull Player player, @NotNull Island island, @NotNull CaravanType caravan) {
        long now = System.currentTimeMillis();

        List<MerchantRecord> occupied = collectOccupiedRecords(island, caravan, now);
        if (occupied.isEmpty()) {
            player.sendMessage(Component.text("§e岛上现在没有§f" + caravan.defaultDisplayName() + "§e。"));
            return;
        }

        ReleaseResult result = releaseSlot(island, caravan);
        if (!result.saved()) {
            fail(player, "§c撤离失败：数据保存出错，请稍后重试或联系管理员。");
            return;
        }

        removeRecordedEntities(occupied);

        player.sendMessage(Component.text("§a已撤离§f" + caravan.defaultDisplayName()
                + "§a，名额已释放，凭证没有被消耗。再次右键凭证即可重新召唤。"));
    }

    /**
     * 把本岛三种常驻商队全部收回。菜单「强制撤离」用这条，避免连发三条「岛上没有 xxx」。
     * <b>async 线程调用</b>。
     */
    public void recallAllCaravans(@NotNull Player player, @NotNull Island island) {
        int recalled = 0;
        for (CaravanType caravan : CaravanType.values()) {
            if (recallCaravanQuiet(player, island, caravan)) recalled++;
        }
        if (recalled == 0) {
            player.sendMessage(Component.text("§e岛上没有需要撤离的常驻商队。"));
        } else {
            player.sendMessage(Component.text("§a已撤离 §f" + recalled
                    + " §a支常驻商队，名额已释放。再右键凭证即可重新召唤。"));
        }
    }

    /** @return 是否真的释放了这种商队的名额 */
    private boolean recallCaravanQuiet(Player player, Island island, CaravanType caravan) {
        long now = System.currentTimeMillis();
        List<MerchantRecord> occupied = collectOccupiedRecords(island, caravan, now);
        if (occupied.isEmpty()) return false;

        ReleaseResult result = releaseSlot(island, caravan);
        if (!result.saved()) {
            fail(player, "§c撤离§f" + caravan.defaultDisplayName() + "§c失败：数据保存出错。");
            return false;
        }
        removeRecordedEntities(occupied);
        return result.removed() > 0 || result.cooldownCleared() || !occupied.isEmpty();
    }

    private List<MerchantRecord> collectOccupiedRecords(Island island, CaravanType caravan, long now) {
        List<MerchantRecord> records = new ArrayList<>();
        for (MerchantRecord r : dataService.load(island).merchants) {
            if (caravan.name().equals(r.caravan) && r.occupiesSlot(now)) {
                records.add(r);
            }
        }
        return records;
    }

    /**
     * Folia：{@code Bukkit.getEntity} 会读区块实体表，不能在 async 上调。
     * 按记录里的世界/坐标跳到 region 线程再查、再 remove。
     * 区块没加载或实体早就没了是正常情况，名额已经释放，不当失败。
     */
    private void removeRecordedEntities(List<MerchantRecord> records) {
        for (MerchantRecord r : records) {
            if (r.entityUuid == null || r.entityUuid.isBlank()) continue;
            final UUID entityId;
            try {
                entityId = UUID.fromString(r.entityUuid);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            World world = r.world == null || r.world.isBlank() ? null : Bukkit.getWorld(r.world);
            if (world == null) continue;
            int chunkX = (int) Math.floor(r.x) >> 4;
            int chunkZ = (int) Math.floor(r.z) >> 4;
            Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) entity.remove();
            });
        }
    }

    public ReleaseResult releaseSlot(@NotNull Island island, @NotNull CaravanType caravan) {
        return dataService.compute(island, data -> {
            int before = data.merchants.size();
            data.merchants.removeIf(r -> caravan.name().equals(r.caravan));
            int removed = before - data.merchants.size();
            boolean cooldownCleared = data.lastMerchantDeathAt != 0L;
            data.lastMerchantDeathAt = 0L;

            if (removed == 0 && !cooldownCleared) {
                // 什么都没变，不用白写一次库。
                return TraderDataService.Mutation.readOnly(new ReleaseResult(0, false, true));
            }
            return TraderDataService.Mutation.commit(
                    new ReleaseResult(removed, cooldownCleared, true),
                    new ReleaseResult(removed, cooldownCleared, false));
        });
    }
}
