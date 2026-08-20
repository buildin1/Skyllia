package fr.euphyllia.skylliatrader.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import fr.euphyllia.skyllia.utils.ConfigFileWriter;
import fr.euphyllia.skylliatrader.configuration.model.TrackTiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
    private static final int DEFAULT_CONFIG_VERSION = 1;
    private static final int DEFAULT_MAX_MERCHANTS_PER_ISLAND = 3;
    private static final boolean DEFAULT_RESPAWN_ON_DEATH = true;
    private static final int DEFAULT_RESPAWN_COOLDOWN_SECONDS = 300;
    private static final int DEFAULT_SAFE_SCAN_RADIUS_BLOCKS = 3;
    private static final int DEFAULT_MIN_CLEAR_HEIGHT_BLOCKS = 3;
    private static final int DEFAULT_MAX_SPAWN_ATTEMPTS = 10;
    private static final int DEFAULT_ORDER_SLOTS_PER_ISLAND = 3;

    private static final List<Long> DEFAULT_TRADE_COUNT_TIERS = List.of(0L, 10L, 25L, 50L, 100L);
    private static final List<Long> DEFAULT_ISLAND_LEVEL_TIERS = List.of(1L, 5L, 10L, 15L, 20L, 30L);
    private static final List<Long> DEFAULT_REPUTATION_TIERS = List.of(0L, 100L, 300L, 700L, 1500L, 3000L);
    private static final List<Double> DEFAULT_SPENDING_TIERS = List.of(0.0, 1000.0, 5000.0, 20000.0, 100000.0);

    private final CommentedFileConfig config;
    private boolean changed = false;

    private volatile TrackTiers trackTiers;

    private int configVersion = DEFAULT_CONFIG_VERSION;
    private int maxMerchantsPerIsland = DEFAULT_MAX_MERCHANTS_PER_ISLAND;
    private boolean respawnOnDeath = DEFAULT_RESPAWN_ON_DEATH;
    private int respawnCooldownSeconds = DEFAULT_RESPAWN_COOLDOWN_SECONDS;

    private int safeScanRadiusBlocks = DEFAULT_SAFE_SCAN_RADIUS_BLOCKS;
    private int minClearHeightBlocks = DEFAULT_MIN_CLEAR_HEIGHT_BLOCKS;
    private int maxSpawnAttempts = DEFAULT_MAX_SPAWN_ATTEMPTS;

    private int orderSlotsPerIsland = DEFAULT_ORDER_SLOTS_PER_ISLAND;

    public TraderConfigManager(CommentedFileConfig config) {
        this.config = config;
    }

    @Override
    public void loadConfig() {
        changed = false;

        this.configVersion = getOrSetDefault("config-version", DEFAULT_CONFIG_VERSION, Integer.class);

        List<Long> tradeCountTiers = getOrSetLongList("track.trade-count.tiers", DEFAULT_TRADE_COUNT_TIERS);
        List<Long> islandLevelTiers = getOrSetLongList("track.island-level.tiers", DEFAULT_ISLAND_LEVEL_TIERS);
        List<Long> reputationTiers = getOrSetLongList("track.reputation.tiers", DEFAULT_REPUTATION_TIERS);
        List<Double> spendingTiers = getOrSetDoubleList("track.spending.tiers", DEFAULT_SPENDING_TIERS);
        this.trackTiers = new TrackTiers(tradeCountTiers, islandLevelTiers, reputationTiers, spendingTiers);

        this.maxMerchantsPerIsland = getOrSetDefault("credential.max-merchants-per-island",
                DEFAULT_MAX_MERCHANTS_PER_ISLAND, Integer.class);
        this.respawnOnDeath = getOrSetDefault("credential.respawn-on-death",
                DEFAULT_RESPAWN_ON_DEATH, Boolean.class);
        this.respawnCooldownSeconds = getOrSetDefault("credential.respawn-cooldown-seconds",
                DEFAULT_RESPAWN_COOLDOWN_SECONDS, Integer.class);

        this.safeScanRadiusBlocks = getOrSetDefault("merchant-spawn.safe-scan-radius-blocks",
                DEFAULT_SAFE_SCAN_RADIUS_BLOCKS, Integer.class);
        this.minClearHeightBlocks = getOrSetDefault("merchant-spawn.min-clear-height-blocks",
                DEFAULT_MIN_CLEAR_HEIGHT_BLOCKS, Integer.class);
        this.maxSpawnAttempts = getOrSetDefault("merchant-spawn.max-spawn-attempts",
                DEFAULT_MAX_SPAWN_ATTEMPTS, Integer.class);

        this.orderSlotsPerIsland = getOrSetDefault("order-board.slots-per-island",
                DEFAULT_ORDER_SLOTS_PER_ISLAND, Integer.class);

        if (changed) {
            writeAtomically();
        }
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

    public int getMaxMerchantsPerIsland() {
        return maxMerchantsPerIsland;
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
}
