package fr.euphyllia.skylliaacidrain.season;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.database.IslandCustomDataQuery;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 酸雨季世界级状态，走主插件数据库的插件级 KV。
 * 以前写在 {@code plugins/SkylliaAcidRain/seasons.toml}，重启/文件损坏会丢状态。
 */
public final class SeasonStateStore {

    private static final Logger log = LoggerFactory.getLogger(SeasonStateStore.class);
    private static final NamespacedKey NS = new NamespacedKey("skylliaacidrain", "season");
    private static final String KEY_PREFIX = "world.";

    public SeasonStateStore() {
    }

    private IslandCustomDataQuery db() {
        return SkylliaAPI.getIslandCustomDataQuery();
    }

    public @Nullable Snapshot load(UUID worldId) {
        try {
            String raw = db().getPluginData(NS, KEY_PREFIX + worldId);
            if (raw == null || raw.isBlank()) return null;
            String[] parts = raw.split("\\|", 3);
            if (parts.length < 3) return null;
            boolean active = "1".equals(parts[0]) || "true".equalsIgnoreCase(parts[0]);
            long end = Long.parseLong(parts[1]);
            long last = Long.parseLong(parts[2]);
            return new Snapshot(active, end, last);
        } catch (Exception e) {
            log.warn("读取酸雨季状态失败（世界 {}）", worldId, e);
            return null;
        }
    }

    public void save(UUID worldId, boolean active, long endFullTime, long lastTriggerDayIndex) {
        try {
            String value = (active ? "1" : "0") + "|" + endFullTime + "|" + lastTriggerDayIndex;
            if (!db().setPluginData(NS, KEY_PREFIX + worldId, value)) {
                log.warn("写入酸雨季状态失败（世界 {}）", worldId);
            }
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
