package fr.euphyllia.skylliaacidrain.season;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.UUID;

/**
 * 把酸雨季的世界级状态落盘，使其能跨服务器重启存活。
 *
 * <p>不落盘会有两个后果，其中第二个是玩家实际报上来的 bug：</p>
 * <ol>
 *   <li>重启时若正处于酸雨季，季节会静默消失 —— 没有结束事件，也没有结束广播；</li>
 *   <li>{@code lastTriggerDayIndex} 归零后，<b>满月当天重启会让同一轮酸雨季反复触发</b>。</li>
 * </ol>
 *
 * <p>存储位置 {@code plugins/SkylliaAcidRain/seasons.toml}，按世界 UUID 分组。
 * 世界 UUID 不含 {@code .}，因此可以安全地作为 night-config 的路径段使用。</p>
 */
public final class SeasonStateStore {

    private static final Logger log = LoggerFactory.getLogger(SeasonStateStore.class);

    private final CommentedFileConfig cfg;

    public SeasonStateStore(File dataFolder) {
        //noinspection ResultOfMethodCallIgnored
        dataFolder.mkdirs();
        File file = new File(dataFolder, "seasons.toml");
        this.cfg = CommentedFileConfig.builder(file).sync().autosave().build();
        try {
            this.cfg.load();
        } catch (Exception e) {
            // 文件损坏时不能让附属起不来：清空重来，代价只是丢失一轮季节状态。
            log.warn("读取 seasons.toml 失败，将以空状态继续（本轮酸雨季状态丢失）", e);
            this.cfg.clear();
        }
        this.cfg.setComment("worlds",
                " 酸雨季的持久化状态，按世界 UUID 分组。由插件自动维护，正常情况下无需手动编辑。");
    }

    /**
     * 读取某个世界已落盘的状态；从未落盘过则返回 {@code null}。
     */
    public @Nullable Snapshot load(UUID worldId) {
        String base = "worlds." + worldId;
        if (cfg.get(base) == null) return null;
        return new Snapshot(
                cfg.getOrElse(base + ".active", false),
                getLong(base + ".end-full-time", 0L),
                getLong(base + ".last-trigger-day-index", -1L)
        );
    }

    /**
     * night-config 读 TOML 整数时具体装箱成 {@code Integer} 还是 {@code Long} 并不总是
     * 可预期的（2026-08-21 生产日志实测命中：{@code cfg.getOrElse(path, 0L)} 的泛型类型
     * 推断在运行时撞上 {@code ClassCastException: Integer cannot be cast to Long}）。
     * 统一走 {@code Number} 装箱再取 {@code longValue()}，不依赖具体是哪个装箱类型，
     * 和 {@code GuiLayoutConfigManager#getOrSetDefault} 里已经验证过的做法一致。
     */
    private long getLong(String path, long defaultValue) {
        Object raw = cfg.get(path);
        return raw instanceof Number n ? n.longValue() : defaultValue;
    }

    /**
     * 写入某个世界的状态。每次季节开始 / 结束时调用即可 —— 这两处是仅有的状态变更点，
     * 因此不依赖 {@code onDisable}（Folia 关服阶段无法可靠调度）。
     */
    public void save(UUID worldId, boolean active, long endFullTime, long lastTriggerDayIndex) {
        String base = "worlds." + worldId;
        try {
            cfg.set(base + ".active", active);
            cfg.set(base + ".end-full-time", endFullTime);
            cfg.set(base + ".last-trigger-day-index", lastTriggerDayIndex);
        } catch (Exception e) {
            log.warn("写入酸雨季状态失败（世界 {}）", worldId, e);
        }
    }

    /**
     * @param active             重启时是否正处于酸雨季
     * @param endFullTime        季节结束时刻，单位是世界的 {@code getFullTime()}
     * @param lastTriggerDayIndex 上一次触发季节的天数索引，用于防止同一天重复触发
     */
    public record Snapshot(boolean active, long endFullTime, long lastTriggerDayIndex) {
    }
}
