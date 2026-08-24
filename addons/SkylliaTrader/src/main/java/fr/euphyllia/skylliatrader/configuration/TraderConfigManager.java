package fr.euphyllia.skylliatrader.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import fr.euphyllia.skyllia.utils.ConfigFileWriter;
import fr.euphyllia.skylliatrader.configuration.model.CredentialItemSpec;
import fr.euphyllia.skylliatrader.configuration.model.GuidebookConfig;
import fr.euphyllia.skylliatrader.configuration.model.TrackTiers;
import fr.euphyllia.skylliatrader.merchant.CaravanType;
import org.bukkit.Material;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * config.toml 的运行时读写。仿 {@code IslandFlagsConfigManager} / {@code IslandLevelConfigManager}
 * 的分工：本类只负责"读进内存 + 缺项自动补默认值并整表回写"，具体谁来调用 {@link #loadConfig()}
 * 由 {@link TraderConfigLoader} 负责（首次加载）以及 Skyllia 核心的 {@code /skyllia reload}
 * 全局重载流程负责（通过 {@link fr.euphyllia.skyllia.api.configuration.IConfigRegistry}）。
 * <p>
 * <b>坏配置一律降级不抛异常</b>：本类被挂在核心的全局重载列表里，而
 * {@code ConfigLoader#reloadAllConfigs} 的 try 块包着整个 for 循环——这里抛一个异常，
 * 排在后面的所有配置提供者都不会被重载，管理员只会在控制台看到一行 error，
 * 却以为 {@code /skyllia reload} 成功了。所以类型写错、数组顺序写错这些情况统一是
 * "警告 + 回退默认值"，绝不 throw。
 * </p>
 */
public class TraderConfigManager implements IConfigurationProvider {

    private static final Logger log = LoggerFactory.getLogger(TraderConfigManager.class);

    // ── 文档默认值 ───────────────────────────────────────────────────────────
    // 必须是常量而不是"字段当前值"：用字段当前值当默认值的话，管理员删掉某个键之后
    // reload 会把"上一次运行时的值"写回去，而不是文档里写的默认值。
    /**
     * 3 = 把 {@code merchant-spawn.max-spawn-attempts} 的默认值改成「0 = 自动按扫描半径取满」，
     * 并对停留在旧默认值 10 上的存量配置做一次迁移（见 {@link #migrateSpawnAttemptsToAuto}）。
     */
    private static final int DEFAULT_CONFIG_VERSION = 3;
    private static final int DEFAULT_MAX_PER_CARAVAN = 1;
    /**
     * 下界商队召唤权解锁等级（HANDOFF 7.1/9.3 T6 拍板："下界商队召唤权：岛屿 20 级解锁"）。
     * 岛屿等级低于这个数字时，即使玩家手持有效的下界凭证、名额也够，也直接拒绝召唤。
     */
    private static final int DEFAULT_NETHER_UNLOCK_LEVEL = 20;
    /** 末地商队召唤权解锁等级（同上，HANDOFF 9.3："末地商队召唤权：岛屿 30 级解锁"）。 */
    private static final int DEFAULT_END_UNLOCK_LEVEL = 30;
    /**
     * 凭证游商"总数天花板"默认值随岛屿等级变化的档位表（HANDOFF 7.1"凭证游商名额 1→2"，
     * 9.3 拍板为"10 级前 1 个，10 级起 2 个"）。
     * <p>
     * 语义是"每跨过一个门槛，默认总数天花板 +1，起始值 1"：默认 {@code [10, 30]} 表示
     * 等级 &lt;10 → 1，10≤等级&lt;30 → 2，等级 ≥30 → 3（等于三种商队各 1 个，
     * 和 T1 时代写死的默认值 3 效果一致）。见 {@link #defaultCredentialSlotsForLevel(long)}。
     * 只在岛屿从未被管理员单独设置过 {@code credentialSlots} 时才会用到这张表，
     * 单独设置过的岛屿完全不受它影响。
     * </p>
     */
    private static final List<Long> DEFAULT_CREDENTIAL_TOTAL_SLOT_LEVEL_TIERS = List.of(10L, 30L);
    private static final boolean DEFAULT_RESPAWN_ON_DEATH = true;
    private static final int DEFAULT_RESPAWN_COOLDOWN_SECONDS = 300;
    private static final int DEFAULT_CLAIM_TIMEOUT_SECONDS = 30;
    private static final String DEFAULT_SUMMON_WORLD = "";
    private static final int DEFAULT_SAFE_SCAN_RADIUS_BLOCKS = 3;
    private static final int DEFAULT_MIN_CLEAR_HEIGHT_BLOCKS = 3;
    /**
     * {@code 0} = 自动取 {@code (2r+1)²}（把 {@code safe-scan-radius-blocks} 圈出来的候选列
     * <b>全部</b>试一遍）。
     * <p>
     * 旧默认值是 10，而半径 3 一共有 49 个候选列——10 次尝试只够试到 (0,0) + 半径 1 的一圈
     * + 半径 2 的第一个点，<b>有效搜索半径被压回 1.x</b>，而配置里写着的 radius 是 3。
     * 玩家在岛屿 spawn 上盖了房子/停了船（非常常见）时，3×3 范围内找不到落点，召唤直接失败
     * 并提示「清理出生点周围」，服主却以为插件已经搜过 7×7 了。
     * </p>
     * <p>
     * 这个旋钮本来也挡不住「死循环」——候选列表由 {@code SafeSpawnFinder#horizontalOffsets}
     * 一次性建出来，长度天然就是 {@code (2r+1)²}，真正的上界是<b>半径</b>而不是它。
     * 所以默认让它跟着半径走，只有想在大半径下故意提前收手的服主才需要写一个正数。
     * </p>
     */
    private static final int DEFAULT_MAX_SPAWN_ATTEMPTS = 0;
    private static final int DEFAULT_VERTICAL_SCAN_RANGE_BLOCKS = 8;
    private static final int DEFAULT_ORDER_SLOTS_PER_ISLAND = 3;
    /** 每个订单槽位独立计时，到点强制换新订单——这是订单吞吐的上限，不是"完成后立刻刷新"。 */
    private static final int DEFAULT_ORDER_REFRESH_HOURS = 6;
    /** 每岛每日通过订单获得的货币总额上限，对齐 HANDOFF 9.3（日均产出约 100 的新锚点，原 400 的十分之一）。 */
    private static final double DEFAULT_DAILY_ORDER_INCOME_CAP = 40.0;
    /**
     * 每种商品每日回收收入上限（金币）。回收系统通胀防线的<b>第二道</b>，第一道是 shop.toml 的
     * {@code recyclable = false}（见 {@code DailyRecycleIncome} 类文档）。
     * 取 40 和订单上限同一个量级：两者都是"辅助收入"，不该盖过 PlayerTask 的日均 100 主线。
     * <p>
     * 2026-08-24 起这个数字是<b>按单个物品</b>计，不是全岛所有商品加总。沙子卖满 40 金币
     * 不影响今天还能回收烈焰棒。
     * </p>
     */
    private static final double DEFAULT_DAILY_RECYCLE_INCOME_CAP = 40.0;
    /** 订单看板巡检间隔（分钟）。判定口径是"是否已过期"，跑得比 refresh-hours 频繁得多也没问题，
     *  只要别频繁到对数据库造成不必要压力——15 分钟对一个 6 小时的刷新周期来说粒度足够细。 */
    private static final int DEFAULT_ORDER_BOARD_CHECK_INTERVAL_MINUTES = 15;
    /**
     * 每岛每日通过订单获得的<b>声望</b>总额上限（2026-08-21 T4 审查后补，与
     * {@link #DEFAULT_DAILY_ORDER_INCOME_CAP} 对货币做的事对称）。200 是按审查给出的"最坏情况
     * 理论产出"（3 槽位、每 6 小时刷新、每次都恰好抽到大额档 40 声望 ≈ 480/天）打了个折扣、
     * 又比 HANDOFF 6.4"到 3000 声望约需 75-200 个大订单"（对应几天到几周量级）留出余量算出来的，
     * 200/天意味着理论最快约 15 天到顶——比原设计意图略快，但不会再出现"十分钟刷穿"这种量级。
     */
    private static final long DEFAULT_DAILY_ORDER_REPUTATION_CAP = 200L;
    /**
     * 同一个订单槽位两次结算之间的最短间隔（秒，2026-08-21 T4 审查后补）。每日声望/货币上限
     * 锁的是"总量"，这个锁的是"频率"——没有这道闸门，玩家理论上可以在触达每日上限之前的
     * 短短几秒钟内就把当天的额度全部刷完，体验上和"没有上限"没有本质区别。30 秒足够让
     * "点一下、看结果、再点下一次"这种正常交互不受影响，但能挡住脚本/连点式的高频刷新。
     */
    private static final int DEFAULT_SLOT_REDEEM_COOLDOWN_SECONDS = 30;

    private static final boolean DEFAULT_NATURAL_ENABLED = true;
    private static final int DEFAULT_NATURAL_CHECK_INTERVAL_SECONDS = 60;
    private static final double DEFAULT_NATURAL_CHANCE = 0.05;
    private static final int DEFAULT_NATURAL_STAY_MINUTES = 10;
    private static final int DEFAULT_NATURAL_COOLDOWN_MINUTES = 30;
    private static final int DEFAULT_NATURAL_MAX_TRADE_COUNT_TIER = 0;
    private static final boolean DEFAULT_NATURAL_RESPECT_ISLAND_FLAG = true;

    private static final boolean DEFAULT_GUIDEBOOK_ENABLED = true;
    private static final String DEFAULT_GUIDEBOOK_MATERIAL = "WRITTEN_BOOK";
    private static final String DEFAULT_GUIDEBOOK_TITLE = "<gold>游商指南";
    private static final String DEFAULT_GUIDEBOOK_AUTHOR = "空岛商会";
    /** 2026-08-21 起日均货币产出锚点由约 1000 下调为约 100，此默认值同步除以 10，与公开百科对齐。 */
    private static final double DEFAULT_GUIDEBOOK_PRICE = 5.0;
    private static final int DEFAULT_GUIDEBOOK_PURCHASE_LIMIT = 0;
    /** 成书的页数上限（原版限制）。超出的页在 {@code CraftMetaBook#addPages} 里被静默丢弃。 */
    private static final int MAX_BOOK_PAGES = 100;
    private static final List<String> DEFAULT_GUIDEBOOK_PAGES = List.of(
            "<b>游商指南</b>\n\n路过的游商只带基础货：沙子、甘蔗、烈焰棒、下界疣、青蛙卵、种子和树苗。\n\n想买到建材、钻石、下界合金？往后翻。",
            "<b>一、常驻商队</b>\n\n完成挑战任务可以拿到<b>商队凭证</b>，一共三种：主世界、下界、末地。\n\n拿着凭证在自己岛上<b>右键</b>，就能召唤一个<b>永久常驻</b>的商队商人。",
            "<b>凭证的规矩</b>\n\n· 每座岛<b>每种商队各 1 个</b>\n· 凭证是<b>开关</b>：岛上没有这种商人时右键放出，已经有了再右键就是收回\n· <b>凭证不会被消耗</b>，可以反复使用\n· 岛上所有成员共用这些商人",
            "<b>二、四条轨道</b>\n\n商人卖什么，由四条互不替代的轨道决定：\n\n1. 交易次数\n2. 岛屿等级\n3. 商会声望\n4. 累计消费",
            "<b>1. 交易次数</b>\n开基础生活物资：树苗、染料、花草、珊瑚、苔藓……买得越多开得越全。\n\n<b>2. 岛屿等级</b>\n开建材大宗：下界岩、凝灰岩、滴水石锥、朱砂族、硫磺族……量大价低。",
            "<b>3. 商会声望</b>\n开稀有物资：钻石、远古残骸、下界之星、鞘翅。\n\n声望<b>只能</b>靠完成商队订单获得，钱和等级都换不来。\n\n<b>4. 累计消费</b>\n只给折扣和限购加成，不解锁新商品。",
            "<b>三、限购</b>\n\n所有限购都按<b>整座岛</b>计算，不是按人头。\n\n多拉几个人进岛并不能多买钻石。\n\n<gray>输入 /is trader 可以随时查看四条轨道的当前进度。</gray>"
    );

    private static final List<Long> DEFAULT_TRADE_COUNT_TIERS = List.of(0L, 10L, 25L, 50L, 100L);
    private static final List<Long> DEFAULT_ISLAND_LEVEL_TIERS = List.of(1L, 5L, 10L, 15L, 20L, 30L);
    private static final List<Long> DEFAULT_REPUTATION_TIERS = List.of(0L, 100L, 300L, 700L, 1500L, 3000L);
    // T3 对齐公开百科/HANDOFF 9.3 的折扣档位表（消费 1,000/5,000/20,000/50,000 → 折扣 -3%/-6%/-10%/-15%）：
    // 最后一档从 100000 改成 50000。注意这只影响"配置文件缺失时首次生成的默认值"——
    // 已经跑起来的服务器如果 config.toml 早就存在（T1 上线时生成的），这里改了也不会
    // 回填过去，需要服主自己去改线上的 config.toml（见项目"配置手改不用脚本"的既有规范）。
    private static final List<Double> DEFAULT_SPENDING_TIERS = List.of(0.0, 1000.0, 5000.0, 20000.0, 50000.0);

    private final CommentedFileConfig config;

    /** 只在 {@link #loadConfig()} 内部使用，不跨线程，因此不需要 volatile。 */
    private boolean changed = false;

    // ── 下面全部是「reload 线程写、各业务线程读」的配置值 ─────────────────────
    // 一律加 volatile。标量的读写在 JVM 上虽然是原子的（long/double 除外），
    // 但没有 volatile 就没有可见性保证：/skyllia reload 改完之后，别的线程可能相当长一段时间
    // 还在读旧值，而且这种「有时候生效、有时候不生效」的现象几乎不可能靠日志排查出来。
    // 同一批字段用两种写法（对象型 volatile、标量型不加）更糟：后来者会以为那是有意的区分，
    // 去猜「哪些字段 reload 安全」。所以这里统一。

    private volatile TrackTiers trackTiers;

    private volatile int configVersion = DEFAULT_CONFIG_VERSION;
    private volatile int maxPerCaravan = DEFAULT_MAX_PER_CARAVAN;
    private volatile int netherUnlockLevel = DEFAULT_NETHER_UNLOCK_LEVEL;
    private volatile int endUnlockLevel = DEFAULT_END_UNLOCK_LEVEL;
    private volatile List<Long> credentialTotalSlotLevelTiers = DEFAULT_CREDENTIAL_TOTAL_SLOT_LEVEL_TIERS;
    private volatile boolean respawnOnDeath = DEFAULT_RESPAWN_ON_DEATH;
    private volatile int respawnCooldownSeconds = DEFAULT_RESPAWN_COOLDOWN_SECONDS;
    private volatile int claimTimeoutSeconds = DEFAULT_CLAIM_TIMEOUT_SECONDS;
    private volatile String summonWorld = DEFAULT_SUMMON_WORLD;

    private volatile int safeScanRadiusBlocks = DEFAULT_SAFE_SCAN_RADIUS_BLOCKS;
    private volatile int minClearHeightBlocks = DEFAULT_MIN_CLEAR_HEIGHT_BLOCKS;
    private volatile int maxSpawnAttempts = DEFAULT_MAX_SPAWN_ATTEMPTS;
    private volatile int verticalScanRangeBlocks = DEFAULT_VERTICAL_SCAN_RANGE_BLOCKS;

    private volatile boolean naturalSpawnEnabled = DEFAULT_NATURAL_ENABLED;
    private volatile int naturalCheckIntervalSeconds = DEFAULT_NATURAL_CHECK_INTERVAL_SECONDS;
    private volatile double naturalChance = DEFAULT_NATURAL_CHANCE;
    private volatile int naturalStayMinutes = DEFAULT_NATURAL_STAY_MINUTES;
    private volatile int naturalCooldownMinutes = DEFAULT_NATURAL_COOLDOWN_MINUTES;
    private volatile int naturalMaxTradeCountTier = DEFAULT_NATURAL_MAX_TRADE_COUNT_TIER;
    private volatile boolean naturalRespectIslandFlag = DEFAULT_NATURAL_RESPECT_ISLAND_FLAG;

    /** 三种商队各自的凭证识别规则；键一定齐全（缺项会补默认值再回写）。 */
    private volatile Map<CaravanType, CredentialItemSpec> credentialItems = new EnumMap<>(CaravanType.class);

    /** 三种商队 + 自然刷新游商的实体显示名（MiniMessage）。 */
    private volatile Map<CaravanType, String> caravanDisplayNames = new EnumMap<>(CaravanType.class);
    private volatile String naturalDisplayName = "<gray>流浪游商";

    private volatile GuidebookConfig guidebook;

    private volatile int orderSlotsPerIsland = DEFAULT_ORDER_SLOTS_PER_ISLAND;
    private volatile int orderRefreshHours = DEFAULT_ORDER_REFRESH_HOURS;
    private volatile double dailyOrderIncomeCap = DEFAULT_DAILY_ORDER_INCOME_CAP;
    private volatile double dailyRecycleIncomeCap = DEFAULT_DAILY_RECYCLE_INCOME_CAP;
    private volatile long dailyOrderReputationCap = DEFAULT_DAILY_ORDER_REPUTATION_CAP;
    private volatile int slotRedeemCooldownSeconds = DEFAULT_SLOT_REDEEM_COOLDOWN_SECONDS;
    private volatile int orderBoardCheckIntervalMinutes = DEFAULT_ORDER_BOARD_CHECK_INTERVAL_MINUTES;

    public TraderConfigManager(CommentedFileConfig config) {
        this.config = config;
    }

    @Override
    public void loadConfig() {
        changed = false;

        int storedVersion = getOrSetDefault("config-version", DEFAULT_CONFIG_VERSION, Integer.class);
        if (storedVersion < DEFAULT_CONFIG_VERSION) {
            migrateSpawnAttemptsToAuto(storedVersion);
            config.set("config-version", DEFAULT_CONFIG_VERSION);
            changed = true;
            storedVersion = DEFAULT_CONFIG_VERSION;
        }
        this.configVersion = storedVersion;

        List<Long> tradeCountTiers = getOrSetLongList("track.trade-count.tiers", DEFAULT_TRADE_COUNT_TIERS);
        List<Long> islandLevelTiers = getOrSetLongList("track.island-level.tiers", DEFAULT_ISLAND_LEVEL_TIERS);
        List<Long> reputationTiers = getOrSetLongList("track.reputation.tiers", DEFAULT_REPUTATION_TIERS);
        List<Double> spendingTiers = getOrSetDoubleList("track.spending.tiers", DEFAULT_SPENDING_TIERS);
        this.trackTiers = new TrackTiers(tradeCountTiers, islandLevelTiers, reputationTiers, spendingTiers);

        // 主判定：每岛「每种」商队最多几个。规格是 1，做成配置项只是为了服主将来能开活动。
        this.maxPerCaravan = Math.max(0, getOrSetDefault("credential.max-per-caravan",
                DEFAULT_MAX_PER_CARAVAN, Integer.class));
        // T6 等级门槛回填（HANDOFF 7.1/9.3）：下界/末地商队的召唤权解锁等级，
        // 以及"总名额天花板"默认值随等级变化的档位表。三者都只影响
        // TraderIslandData#credentialSlots == -1（未被管理员单独设置过）的岛屿。
        this.netherUnlockLevel = Math.max(0, getOrSetDefault("credential.level-gate.nether-unlock-level",
                DEFAULT_NETHER_UNLOCK_LEVEL, Integer.class));
        this.endUnlockLevel = Math.max(0, getOrSetDefault("credential.level-gate.end-unlock-level",
                DEFAULT_END_UNLOCK_LEVEL, Integer.class));
        this.credentialTotalSlotLevelTiers = getOrSetLongList("credential.level-gate.total-slot-level-tiers",
                DEFAULT_CREDENTIAL_TOTAL_SLOT_LEVEL_TIERS);
        this.respawnOnDeath = getOrSetDefault("credential.respawn-on-death",
                DEFAULT_RESPAWN_ON_DEATH, Boolean.class);
        this.respawnCooldownSeconds = Math.max(0, getOrSetDefault("credential.respawn-cooldown-seconds",
                DEFAULT_RESPAWN_COOLDOWN_SECONDS, Integer.class));
        // 占位超时下限夹到 5 秒：写成 0 的话占位一写进去就立刻算过期，CAS 形同虚设，
        // 两名成员同时用凭证就能各自召出一个同种商人。
        this.claimTimeoutSeconds = Math.max(5, getOrSetDefault("credential.claim-timeout-seconds",
                DEFAULT_CLAIM_TIMEOUT_SECONDS, Integer.class));
        this.summonWorld = getOrSetDefault("credential.summon-world",
                DEFAULT_SUMMON_WORLD, String.class);

        loadCredentialItems();

        this.safeScanRadiusBlocks = Math.max(0, getOrSetDefault("merchant-spawn.safe-scan-radius-blocks",
                DEFAULT_SAFE_SCAN_RADIUS_BLOCKS, Integer.class));
        // 净空至少 2 格：游商是 1.95 格高的生物，只留 1 格净空必然卡进方块里。
        this.minClearHeightBlocks = Math.max(2, getOrSetDefault("merchant-spawn.min-clear-height-blocks",
                DEFAULT_MIN_CLEAR_HEIGHT_BLOCKS, Integer.class));
        // 0 或负数 = 自动取满 (2r+1)²，即把 safe-scan-radius-blocks 圈出来的候选列全试一遍。
        int rawSpawnAttempts = getOrSetDefault("merchant-spawn.max-spawn-attempts",
                DEFAULT_MAX_SPAWN_ATTEMPTS, Integer.class);
        int fullColumns = fullColumnCount(this.safeScanRadiusBlocks);
        this.maxSpawnAttempts = rawSpawnAttempts <= 0 ? fullColumns : rawSpawnAttempts;
        if (rawSpawnAttempts > 0 && rawSpawnAttempts < fullColumns) {
            log.warn("merchant-spawn.max-spawn-attempts = {} 小于 safe-scan-radius-blocks = {} "
                            + "对应的候选列总数 {}，实际有效扫描半径达不到配置写明的 {} 格。"
                            + "想搜满整个半径请把它改成 0（自动）。",
                    rawSpawnAttempts, this.safeScanRadiusBlocks, fullColumns, this.safeScanRadiusBlocks);
        }
        this.verticalScanRangeBlocks = Math.max(0, getOrSetDefault("merchant-spawn.vertical-scan-range-blocks",
                DEFAULT_VERTICAL_SCAN_RANGE_BLOCKS, Integer.class));

        this.naturalSpawnEnabled = getOrSetDefault("natural-spawn.enabled",
                DEFAULT_NATURAL_ENABLED, Boolean.class);
        // 巡检间隔下限 5 秒：写成 0/1 会让「遍历在线玩家 + 每岛一次读旗标」变成每秒都跑一遍。
        this.naturalCheckIntervalSeconds = Math.max(5, getOrSetDefault("natural-spawn.check-interval-seconds",
                DEFAULT_NATURAL_CHECK_INTERVAL_SECONDS, Integer.class));
        this.naturalChance = clamp01(getOrSetDefault("natural-spawn.chance",
                DEFAULT_NATURAL_CHANCE, Double.class));
        this.naturalStayMinutes = Math.max(1, getOrSetDefault("natural-spawn.stay-minutes",
                DEFAULT_NATURAL_STAY_MINUTES, Integer.class));
        this.naturalCooldownMinutes = Math.max(0, getOrSetDefault("natural-spawn.cooldown-minutes",
                DEFAULT_NATURAL_COOLDOWN_MINUTES, Integer.class));
        this.naturalMaxTradeCountTier = Math.max(0, getOrSetDefault("natural-spawn.max-trade-count-tier",
                DEFAULT_NATURAL_MAX_TRADE_COUNT_TIER, Integer.class));
        this.naturalRespectIslandFlag = getOrSetDefault("natural-spawn.respect-island-flag",
                DEFAULT_NATURAL_RESPECT_ISLAND_FLAG, Boolean.class);

        loadDisplayNames();
        loadGuidebook();

        this.orderSlotsPerIsland = getOrSetDefault("order-board.slots-per-island",
                DEFAULT_ORDER_SLOTS_PER_ISLAND, Integer.class);
        // 每个槽位独立计时，到点强制换新订单，不管上一单有没有完成——这是订单吞吐的上限。
        // 下限夹到 1：写成 0 会让每一轮巡检都判定"已过期"，槽位变成每次巡检都换一条订单。
        this.orderRefreshHours = Math.max(1, getOrSetDefault("order-board.refresh-hours",
                DEFAULT_ORDER_REFRESH_HOURS, Integer.class));
        this.dailyOrderIncomeCap = Math.max(0.0, getOrSetDefault("order-board.daily-order-income-cap",
                DEFAULT_DAILY_ORDER_INCOME_CAP, Double.class));
        // 旧键 recycle.daily-income-cap 是「全岛所有商品加总」；2026-08-24 改成按单个物品计。
        // 存量配置如果还没新键，把旧值搬过去，避免 reload 时静默掉回默认 40。
        if (config.get("recycle.daily-income-cap-per-item") == null) {
            Object legacy = config.get("recycle.daily-income-cap");
            if (legacy instanceof Number number) {
                config.set("recycle.daily-income-cap-per-item", number.doubleValue());
                changed = true;
            }
        }
        this.dailyRecycleIncomeCap = Math.max(0.0, getOrSetDefault("recycle.daily-income-cap-per-item",
                DEFAULT_DAILY_RECYCLE_INCOME_CAP, Double.class));
        this.dailyOrderReputationCap = Math.max(0L, getOrSetDefault("order-board.daily-reputation-cap",
                DEFAULT_DAILY_ORDER_REPUTATION_CAP, Long.class));
        // 下限夹到 0：写成负数没有意义，0 表示"完全不能重复结算同一个槽位直到它自然过期"
        // （不是"不冷却"——不冷却应该配一个很小的正数，比如 1 秒，而不是 0）。
        this.slotRedeemCooldownSeconds = Math.max(0, getOrSetDefault("order-board.slot-redeem-cooldown-seconds",
                DEFAULT_SLOT_REDEEM_COOLDOWN_SECONDS, Integer.class));
        // 巡检间隔和 natural-spawn.check-interval-seconds 一样，只在【启动时】读一次就固定了，
        // /skyllia reload 改不了巡检频率，需要重启。
        this.orderBoardCheckIntervalMinutes = Math.max(1, getOrSetDefault("order-board.check-interval-minutes",
                DEFAULT_ORDER_BOARD_CHECK_INTERVAL_MINUTES, Integer.class));

        if (changed) {
            writeAtomically();
        }
    }

    /** 半径 r 的切比雪夫方框里一共有多少个候选列，等于 {@code SafeSpawnFinder} 会生成的偏移条数。 */
    private static int fullColumnCount(int radius) {
        int side = 2 * Math.max(0, radius) + 1;
        return side * side;
    }

    /**
     * 旧版本（{@code <= 2}）→ v3：把停留在旧默认值上的 {@code max-spawn-attempts} 改成 0（自动）。
     * <p>
     * 别被参数名骗了：本仓库<b>从来没有发布过 config-version 2</b>——上一个发布过的版本是 1，
     * 线上生产服现在也是 1，所以实际走的是 <b>1 → 3</b>。判定用的是
     * {@code storedVersion < DEFAULT_CONFIG_VERSION}（见 {@link #loadConfig}），
     * 任何小于 3 的存量值都会被这条迁移覆盖到，中间缺一个版本号不影响正确性。
     * </p>
     * <p>
     * 光把代码里的默认常量改掉是不够的：{@link #getOrSetDefault} 只在<b>键缺失</b>时写默认值，
     * 而线上那份 config.toml 里明明白白写着 {@code max-spawn-attempts = 10}，
     * 不迁移的话这个 bug 在已经跑起来的服上一行都不会被修好。
     * </p>
     * <p>
     * 只在「配置值确实小于该半径的候选列总数」时才改——服主如果是<b>有意</b>写了一个足够大的
     * 数字，就不该被我们悄悄改掉。
     * </p>
     */
    private void migrateSpawnAttemptsToAuto(int fromVersion) {
        Object rawAttempts = config.get("merchant-spawn.max-spawn-attempts");
        if (!(rawAttempts instanceof Number attempts)) return;

        Object rawRadius = config.get("merchant-spawn.safe-scan-radius-blocks");
        int radius = rawRadius instanceof Number number
                ? number.intValue() : DEFAULT_SAFE_SCAN_RADIUS_BLOCKS;
        int fullColumns = fullColumnCount(radius);

        if (attempts.intValue() <= 0 || attempts.intValue() >= fullColumns) return;

        config.set("merchant-spawn.max-spawn-attempts", 0);
        // 值改了，说明这个键的注释也必须跟着改：存量 config.toml 里那段注释写的还是
        // 「连续多少个候选点找不到就放弃 / 防死循环」，和 0 的新语义直接矛盾。
        // 服主看文件的时候只会看到注释，不会看到几天前控制台里那条 warn。
        config.setComment("merchant-spawn.max-spawn-attempts",
                " 最多尝试多少个候选【列】就放弃本次召唤，并提示操作的玩家稍后重试。\n"
                        + "\n"
                        + " 【0 = 自动】按上面的 safe-scan-radius-blocks 取满 (2r+1)² —— 半径 3 就是 49 列。\n"
                        + " 推荐保持 0，让它跟着半径走。\n"
                        + "\n"
                        + " ⚠️ 写一个小于 (2r+1)² 的正数，等于【偷偷把有效扫描半径改小】：\n"
                        + "    候选列是按「由近到远」的顺序试的，写 10 就只够试到 (0,0) + 半径 1 的一圈\n"
                        + "    + 半径 2 的第一个点，有效半径不到 2，而 safe-scan-radius-blocks 写着 3。\n"
                        + "    玩家在岛屿出生点盖了房子 / 停了船（很常见）时会直接召唤失败。\n"
                        + "    检测到这种情况时控制台会打一条 warn。\n"
                        + "\n"
                        + " 另外：这一项挡不住「死循环」—— 候选列表本来就是一次性建出来的、\n"
                        + "      长度天然等于 (2r+1)²，真正的上界是【半径】而不是它。\n"
                        + "\n"
                        + " ⚠️ 全部尝试都失败时的行为是【放弃】：\n"
                        + "    · 凭证召唤 —— 不消耗凭证，回滚占位，给玩家一句中文提示\n"
                        + "    · 自然刷新 —— 不消耗冷却，下一轮巡检还会再试\n"
                        + "    绝不会「退而求其次」在不安全的位置生成 —— 虚空世界里那等于生成即坠出世界。");
        changed = true;
        log.warn("配置迁移（config-version {} → {}）：merchant-spawn.max-spawn-attempts 原值 {} "
                        + "小于扫描半径 {} 对应的候选列总数 {}，实际有效扫描半径被压缩到不足 {} 格"
                        + "（玩家在岛屿出生点盖了东西就召唤不出商人）。已改为 0 = 自动搜满整个半径。",
                fromVersion, DEFAULT_CONFIG_VERSION, attempts.intValue(), radius, fullColumns, radius);
    }

    /** 概率类配置夹到 [0,1]：写成 5（想表达 5%）时至少变成「必刷」而不是负数/NaN 引发的怪异行为。 */
    private static double clamp01(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * 读三种商队的凭证识别规则。
     * <p>
     * 三种凭证的默认 CMD 故意错开成 90001 / 90002 / 90003：<b>两种商队用同一个 CMD 是灾难性的</b>——
     * 玩家拿主世界凭证会被识别成下界凭证（取决于遍历顺序），所以这里检测到重复 CMD 会直接
     * 停用冲突的那几种并 error，宁可召不出来也不能召错。
     * </p>
     */
    private void loadCredentialItems() {
        Map<CaravanType, CredentialItemSpec> specs = new EnumMap<>(CaravanType.class);
        Map<Integer, CaravanType> seenCmd = new java.util.HashMap<>();

        int defaultCmd = 90001;
        for (CaravanType type : CaravanType.values()) {
            String base = "credential.item." + type.configKey() + ".";
            String rawMaterial = getOrSetDefault(base + "material", "PAPER", String.class);
            int cmd = getOrSetDefault(base + "custom-model-data", defaultCmd, Integer.class);
            String name = getOrSetDefault(base + "display-name",
                    "<gold>" + type.defaultDisplayName() + "凭证", String.class);
            defaultCmd++;

            Material material = null;
            if (rawMaterial != null && !rawMaterial.isBlank()) {
                try {
                    material = Material.valueOf(rawMaterial.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    log.warn("配置项 '{}material' 引用了未知材料 '{}'，本次改为「只按 CustomModelData 识别」",
                            base, rawMaterial);
                }
            }

            if (cmd >= 0) {
                CaravanType conflict = seenCmd.putIfAbsent(cmd, type);
                if (conflict != null) {
                    log.error("凭证配置错误：{} 与 {} 用了同一个 custom-model-data={}，"
                                    + "两种凭证会互相认错，已停用 {} 的凭证；请给它们分配不同的 CMD 后 /skyllia reload",
                            conflict.configKey(), type.configKey(), cmd, type.configKey());
                    cmd = -1;
                }
            }

            specs.put(type, new CredentialItemSpec(material, cmd, name));
        }
        this.credentialItems = specs;
    }

    /** 读四种游商的实体显示名。 */
    private void loadDisplayNames() {
        Map<CaravanType, String> names = new EnumMap<>(CaravanType.class);
        for (CaravanType type : CaravanType.values()) {
            names.put(type, getOrSetDefault("merchant.display-name." + type.configKey(),
                    "<gold>" + type.defaultDisplayName(), String.class));
        }
        this.caravanDisplayNames = names;
        this.naturalDisplayName = getOrSetDefault("merchant.display-name.natural",
                "<gray>流浪游商", String.class);
    }

    /** 读说明书配置。材料解析失败一律回退成书，不停用整本书（文案还在，换个材质照样能看）。 */
    private void loadGuidebook() {
        boolean enabled = getOrSetDefault("guidebook.enabled", DEFAULT_GUIDEBOOK_ENABLED, Boolean.class);
        String rawMaterial = getOrSetDefault("guidebook.material", DEFAULT_GUIDEBOOK_MATERIAL, String.class);
        String title = getOrSetDefault("guidebook.title", DEFAULT_GUIDEBOOK_TITLE, String.class);
        String author = getOrSetDefault("guidebook.author", DEFAULT_GUIDEBOOK_AUTHOR, String.class);
        double price = Math.max(0.0, getOrSetDefault("guidebook.price", DEFAULT_GUIDEBOOK_PRICE, Double.class));
        int limit = Math.max(0, getOrSetDefault("guidebook.purchase-limit",
                DEFAULT_GUIDEBOOK_PURCHASE_LIMIT, Integer.class));
        List<String> pages = getOrSetStringList("guidebook.pages", DEFAULT_GUIDEBOOK_PAGES);
        // 成书最多 100 页。CraftMetaBook#addPages 超出部分是<b>静默 return</b>——不抛异常、
        // 不打日志，服主写了 120 页只会得到一本 100 页的书，而且完全不知道后面 20 页去哪了。
        if (pages.size() > MAX_BOOK_PAGES) {
            log.warn("配置项 'guidebook.pages' 写了 {} 页，而成书最多只能有 {} 页；"
                            + "超出的 {} 页会被客户端/服务端静默丢弃，请自行精简。",
                    pages.size(), MAX_BOOK_PAGES, pages.size() - MAX_BOOK_PAGES);
        }

        Material material = Material.WRITTEN_BOOK;
        try {
            material = Material.valueOf(rawMaterial.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("配置项 'guidebook.material' 引用了未知材料 '{}'，本次使用 WRITTEN_BOOK", rawMaterial);
        }

        this.guidebook = new GuidebookConfig(enabled, material, title, author, price, limit, pages);
    }

    /**
     * 整表落盘。走核心的 {@link ConfigFileWriter#writeAtomically}：先写 {@code .tmp} 再原子改名，
     * 不直接 truncate 目标文件——{@code WritingMode.REPLACE} 是"截断 + 写入"两步，
     * 写到一半崩溃/掉电会留下半截 config.toml，下次启动直接解析失败，整个插件起不来。
     */
    private void writeAtomically() {
        ConfigFileWriter.writeAtomically(config);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOrSetDefault(String path, T defaultValue, Class<T> expectedClass) {
        Object value = config.get(path);
        if (value == null) {
            config.set(path, defaultValue);
            changed = true;
            return defaultValue;
        }
        if (expectedClass.isInstance(value)) return (T) value;
        return switch (value) {
            case Integer i when expectedClass == Long.class -> (T) Long.valueOf(i);
            case Double d when expectedClass == Float.class -> (T) Float.valueOf(d.floatValue());
            case Integer i when expectedClass == Double.class -> (T) Double.valueOf(i);
            case Long l when expectedClass == Integer.class -> (T) Integer.valueOf(l.intValue());
            // 类型写错（比如 respawn-on-death = 1 而不是 true）时警告 + 回退默认值，
            // 与下面两个数组方法的策略保持一致。绝不抛异常，理由见类注释。
            default -> {
                log.warn("配置项 '{}' 类型不匹配：期望 {}，实际是 {}，本次使用默认值 {}",
                        path, expectedClass.getSimpleName(), value.getClass().getSimpleName(), defaultValue);
                yield defaultValue;
            }
        };
    }

    /**
     * 读一个数值数组配置项；缺失时写回默认值并标记 {@link #changed}。
     * TOML 里的整数在 night-config 里可能被解析成 {@code Integer} 或 {@code Long}，
     * 这里统一按 {@link Number} 处理再转换，不假设具体的装箱类型。
     * <p>
     * 档位表的语义要求"从小到大排列"（{@code TrackTiers#currentTierIndex} 遇到第一个大于
     * 当前值的元素就 break），乱序会让面板显示的门槛和实际解锁关系全乱且全程无日志，
     * 所以这里检查并自动排序 + 警告；空数组直接回退默认值（否则进度面板会显示"还差 ? 达到第 1 档"）。
     * </p>
     * <p>
     * <b>混入非数字元素时整条回退默认值，而不是跳过那一个元素</b>：档位表是按<b>下标</b>
     * 决定"第几档"的，跳过一个元素会让它后面所有档位的下标整体前移——{@code [0, "abc", 100]}
     * 变成 {@code [0, 100]}，第 2 档的门槛就从管理员写的值悄悄变成了原本第 3 档的值，
     * 而日志里只有一条"已跳过"，没人会意识到全服的解锁门槛已经错位了。
     * 宁可整条不要，也不要一份下标被悄悄改过的档位表。
     * </p>
     */
    private List<Long> getOrSetLongList(String path, List<Long> defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            config.set(path, defaultValue);
            changed = true;
            return defaultValue;
        }
        if (!(value instanceof List<?> rawList)) {
            log.warn("配置项 '{}' 不是数组，使用默认值 {}", path, defaultValue);
            return defaultValue;
        }
        List<Long> result = new ArrayList<>(rawList.size());
        for (Object o : rawList) {
            if (!(o instanceof Number number)) {
                log.error("配置项 '{}' 里的元素 '{}' 不是数字。档位表按下标决定第几档，跳过它会让后面所有档位错位，"
                        + "因此整条档位表回退默认值 {}；请修正配置文件后 /skyllia reload。", path, o, defaultValue);
                return defaultValue;
            }
            result.add(number.longValue());
        }
        if (result.isEmpty()) {
            log.warn("配置项 '{}' 是空数组（档位表不能为空，否则解锁进度无从判定），使用默认值 {}", path, defaultValue);
            return defaultValue;
        }
        if (!isAscending(result)) {
            log.warn("配置项 '{}' 没有从小到大排列（实际是 {}），已自动排序后使用；请修正配置文件", path, result);
            result.sort(null);
        }
        return List.copyOf(result);
    }

    private List<Double> getOrSetDoubleList(String path, List<Double> defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            config.set(path, defaultValue);
            changed = true;
            return defaultValue;
        }
        if (!(value instanceof List<?> rawList)) {
            log.warn("配置项 '{}' 不是数组，使用默认值 {}", path, defaultValue);
            return defaultValue;
        }
        List<Double> result = new ArrayList<>(rawList.size());
        for (Object o : rawList) {
            // 与 getOrSetLongList 同样的理由：混入非数字元素整条回退，绝不跳过单个元素。
            if (!(o instanceof Number number)) {
                log.error("配置项 '{}' 里的元素 '{}' 不是数字。档位表按下标决定第几档，跳过它会让后面所有档位错位，"
                        + "因此整条档位表回退默认值 {}；请修正配置文件后 /skyllia reload。", path, o, defaultValue);
                return defaultValue;
            }
            result.add(number.doubleValue());
        }
        if (result.isEmpty()) {
            log.warn("配置项 '{}' 是空数组（档位表不能为空，否则解锁进度无从判定），使用默认值 {}", path, defaultValue);
            return defaultValue;
        }
        if (!isAscending(result)) {
            log.warn("配置项 '{}' 没有从小到大排列（实际是 {}），已自动排序后使用；请修正配置文件", path, result);
            result.sort(null);
        }
        return List.copyOf(result);
    }

    /**
     * 读一个字符串数组配置项（目前只有说明书的 {@code pages}）。
     * <p>
     * 和档位表不一样，这里<b>不做「混入坏元素就整条回退」</b>：书页数组的下标没有语义
     * （第几页只是第几页，跳过一页不会让后面的页"错位"成别的含义），
     * 而整条回退会让服主辛苦写的七页文案因为一个手滑全部消失。所以非字符串元素调用
     * {@code toString()} 硬转 + 警告，尽量保住内容。空数组则回退默认值——
     * 一本零页的成书在客户端会显示成空白书，比默认文案更糟。
     * </p>
     */
    private List<String> getOrSetStringList(String path, List<String> defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            config.set(path, defaultValue);
            changed = true;
            return defaultValue;
        }
        if (!(value instanceof List<?> rawList)) {
            log.warn("配置项 '{}' 不是数组，使用默认值", path);
            return defaultValue;
        }
        List<String> result = new ArrayList<>(rawList.size());
        for (Object o : rawList) {
            if (o == null) continue;
            if (!(o instanceof String)) {
                log.warn("配置项 '{}' 里有一项不是字符串（{}），已按文本处理", path, o.getClass().getSimpleName());
            }
            result.add(String.valueOf(o));
        }
        if (result.isEmpty()) {
            log.warn("配置项 '{}' 是空数组，使用默认值", path);
            return defaultValue;
        }
        return List.copyOf(result);
    }

    private static <T extends Comparable<T>> boolean isAscending(List<T> values) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i - 1).compareTo(values.get(i)) > 0) return false;
        }
        return true;
    }

    @Override
    public boolean canReloadFromDisk() {
        return true;
    }

    @Override
    public void reloadFromDisk() {
        config.load();
    }

    public TrackTiers getTrackTiers() {
        return trackTiers;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    /** 主判定：每岛「每种」商队的常驻商人上限，规格值为 1。 */
    public int getMaxPerCaravan() {
        return maxPerCaravan;
    }

    /** 下界商队召唤权解锁等级（HANDOFF 9.3 T6，默认 20）。仅用于失败提示文案，判定走 {@link #isCaravanUnlockedAtLevel}。 */
    public int getNetherUnlockLevel() {
        return netherUnlockLevel;
    }

    /** 末地商队召唤权解锁等级（HANDOFF 9.3 T6，默认 30）。仅用于失败提示文案，判定走 {@link #isCaravanUnlockedAtLevel}。 */
    public int getEndUnlockLevel() {
        return endUnlockLevel;
    }

    /**
     * 判断某种商队的召唤权当前是否已被这座岛屿的等级解锁（HANDOFF 7.1/9.3 T6）。
     * 主世界商队没有等级门槛，永远为 {@code true}；下界/末地各自对应一条可配置的等级门槛。
     * <p>
     * <b>这条判定和 {@link #defaultCredentialSlotsForLevel(long)} 是两条完全独立的判定</b>：
     * 前者管"这种商队现在能不能召唤"，后者管"这座岛总共能留几个常驻商人"，任一条不满足都要
     * 拒绝召唤，调用方（{@code MerchantService}）在 CAS 占位阶段和转正阶段都要把两条各查一遍。
     * </p>
     */
    public boolean isCaravanUnlockedAtLevel(CaravanType caravan, long islandLevel) {
        return switch (caravan) {
            case OVERWORLD -> true;
            case NETHER -> islandLevel >= netherUnlockLevel;
            case END -> islandLevel >= endUnlockLevel;
        };
    }

    /** 某种商队召唤权所需的最低岛屿等级，只用于拼失败提示文案；主世界没有门槛，返回 0。 */
    public int requiredUnlockLevel(CaravanType caravan) {
        return switch (caravan) {
            case OVERWORLD -> 0;
            case NETHER -> netherUnlockLevel;
            case END -> endUnlockLevel;
        };
    }

    /**
     * 按岛屿等级算出"总数天花板"的默认值（HANDOFF 7.1/9.3 T6："10 级前 1 个，10 级起 2 个"，
     * 30 级起 3 个）。<b>只应该在 {@code TraderIslandData#credentialSlots == -1}
     * （未被管理员单独设置过）时调用</b>——已经被单独设置过的岛屿走
     * {@code TraderIslandData#effectiveCredentialSlots(int)} 的另一条分支，这张表对它们不生效。
     */
    public int defaultCredentialSlotsForLevel(long islandLevel) {
        int slots = 1;
        for (long threshold : credentialTotalSlotLevelTiers) {
            if (islandLevel >= threshold) {
                slots++;
            }
        }
        return slots;
    }

    /** CAS 占位的存活时长（秒）；超时的占位视为崩溃残留，可被新召唤顶掉。 */
    public int getClaimTimeoutSeconds() {
        return claimTimeoutSeconds;
    }

    /** 凭证召唤的目标世界名；空串表示自动取第一个 NORMAL 环境的空岛世界。 */
    public String getSummonWorld() {
        return summonWorld;
    }

    /** 某种商队的凭证识别规则。 */
    public CredentialItemSpec getCredentialItem(CaravanType type) {
        return credentialItems.get(type);
    }

    /** 某种商队的游商实体显示名（MiniMessage）。 */
    public String getCaravanDisplayName(CaravanType type) {
        String name = caravanDisplayNames.get(type);
        return name != null ? name : type.defaultDisplayName();
    }

    /** 自然刷新游商的实体显示名（MiniMessage）。 */
    public String getNaturalDisplayName() {
        return naturalDisplayName;
    }

    public GuidebookConfig getGuidebook() {
        return guidebook;
    }

    public boolean isNaturalSpawnEnabled() {
        return naturalSpawnEnabled;
    }

    public int getNaturalCheckIntervalSeconds() {
        return naturalCheckIntervalSeconds;
    }

    public double getNaturalChance() {
        return naturalChance;
    }

    public int getNaturalStayMinutes() {
        return naturalStayMinutes;
    }

    public int getNaturalCooldownMinutes() {
        return naturalCooldownMinutes;
    }

    /** 自然刷新游商最多开放到交易次数轨的第几档（0-based）。 */
    public int getNaturalMaxTradeCountTier() {
        return naturalMaxTradeCountTier;
    }

    /** 自然刷新是否尊重岛主的 {@code skyllia:island.spawn.passive.wandering_villager} 标志。 */
    public boolean isNaturalRespectIslandFlag() {
        return naturalRespectIslandFlag;
    }

    /** 安全落点扫描时向上/向下各探多少格。 */
    public int getVerticalScanRangeBlocks() {
        return verticalScanRangeBlocks;
    }

    public boolean isRespawnOnDeath() {
        return respawnOnDeath;
    }

    public int getRespawnCooldownSeconds() {
        return respawnCooldownSeconds;
    }

    public int getSafeScanRadiusBlocks() {
        return safeScanRadiusBlocks;
    }

    public int getMinClearHeightBlocks() {
        return minClearHeightBlocks;
    }

    public int getMaxSpawnAttempts() {
        return maxSpawnAttempts;
    }

    public int getOrderSlotsPerIsland() {
        return orderSlotsPerIsland;
    }

    /** 每个订单槽位独立计时的刷新周期（小时）；到点强制换新订单，不管上一单是否已完成。 */
    public int getOrderRefreshHours() {
        return orderRefreshHours;
    }

    /** 每岛每日通过订单获得的货币总额上限，超过部分只给声望不给钱。 */
    public double getDailyOrderIncomeCap() {
        return dailyOrderIncomeCap;
    }

    /** 每种商品每日回收收入上限（金币）；0 表示完全禁止回收换钱。 */
    public double getDailyRecycleIncomeCap() {
        return dailyRecycleIncomeCap;
    }

    /** 每岛每日通过订单获得的声望总额上限，超过部分订单依然算完成、材料依然被扣，只是不再加声望。 */
    public long getDailyOrderReputationCap() {
        return dailyOrderReputationCap;
    }

    /** 同一个订单槽位两次结算之间的最短间隔（秒），防止对着同一个未变化的槽位反复点击刷声望。 */
    public int getSlotRedeemCooldownSeconds() {
        return slotRedeemCooldownSeconds;
    }

    /** 订单看板巡检间隔（分钟）；启动时读一次就固定，reload 不生效，需要重启。 */
    public int getOrderBoardCheckIntervalMinutes() {
        return orderBoardCheckIntervalMinutes;
    }
}
