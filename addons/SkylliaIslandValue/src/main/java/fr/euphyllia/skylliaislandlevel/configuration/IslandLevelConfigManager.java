package fr.euphyllia.skylliaislandlevel.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlWriter;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import org.bukkit.Material;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class IslandLevelConfigManager implements IConfigurationProvider {

    private static final Logger log = LoggerFactory.getLogger(IslandLevelConfigManager.class);
    private final CommentedFileConfig config;
    private boolean changed = false;
    private Map<Material, Double> blockValues = Collections.emptyMap();
    private String levelExpression = "FLOOR(SQRT(score / 10))";
    private long minLevel = 0L;
    private int timerIntervalSeconds = 60;
    private int timerIntervalSecondsTop = 120;
    private ChunkProcessingSettings processingSettings;

    private ScanNotificationConfig scanNotification = new ScanNotificationConfig(
            ScanNotificationConfig.NotificationType.BOSS_BAR, "YELLOW"
    );

    public IslandLevelConfigManager(CommentedFileConfig cfg) {
        this.config = cfg;
    }

    @Override
    public void loadConfig() throws Exception {
        changed = false;

        blockValues = loadBlockValues();
        levelExpression = getOrSetDefault("island-level.level-expression", levelExpression, String.class);
        minLevel = getOrSetDefault("island-level.min-level", minLevel, Long.class);
        timerIntervalSeconds = getOrSetDefault("island-level.timer-interval-seconds", timerIntervalSeconds, Integer.class);
        timerIntervalSecondsTop = getOrSetDefault("island-level.timer-interval-seconds-top", timerIntervalSeconds, Integer.class);

        String notifType = getOrSetDefault("scan-notification.type", scanNotification.type().name(), String.class);
        String notifColor = getOrSetDefault("scan-notification.boss-bar-color", scanNotification.bossBarColor(), String.class);

        ScanNotificationConfig.NotificationType type;
        try {
            type = ScanNotificationConfig.NotificationType.valueOf(notifType.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid scan-notification.type '{}', falling back to BOSS_BAR.", notifType);
            type = ScanNotificationConfig.NotificationType.BOSS_BAR;
        }
        scanNotification = new ScanNotificationConfig(type, notifColor);

        processingSettings = new ChunkProcessingSettings(
                getOrSetDefault("scan-processing.threads", -1, Integer.class),
                getOrSetDefault("scan-processing.delay-ms", 50, Integer.class)
        );

        if (changed) {
            TomlWriter tomlWriter = new TomlWriter();
            tomlWriter.setIndent(IndentStyle.NONE);
            tomlWriter.write(config, config.getFile(), WritingMode.REPLACE);
        }
    }

    private Map<Material, Double> loadBlockValues() {
        EnumMap<Material, Double> values = new EnumMap<>(Material.class);
        com.electronwill.nightconfig.core.Config blockSection = config.get("blocks");
        if (blockSection == null) {
            log.warn("No [blocks] section found in config, island levels will not be calculated based on blocks.");
            return Collections.unmodifiableMap(values);
        }
        for (Map.Entry<String, Object> entry : blockSection.valueMap().entrySet()) {
            String matName = entry.getKey().toUpperCase();
            try {
                Material mat = Material.valueOf(matName);
                double val = ((Number) entry.getValue()).doubleValue();
                if (val > 0) values.put(mat, val);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid material '{}' in config blocks section, skipping.", matName);
            }
        }

        log.info("Loaded {} block values from config.", values.size());
        return Collections.unmodifiableMap(values);
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
            default -> throw new IllegalStateException("Cannot convert value at '" + path + "' from "
                    + value.getClass().getSimpleName() + " to " + expectedClass.getSimpleName());
        };
    }

    @Override
    public boolean canReloadFromDisk() {
        return true;
    }

    @Override
    public void reloadFromDisk() {
        config.load();
    }

    public Map<Material, Double> getBlockValues() {
        return blockValues;
    }

    public String getLevelExpression() {
        return levelExpression;
    }

    public long getMinLevel() {
        return minLevel;
    }

    public int getTimerIntervalSeconds() {
        return timerIntervalSeconds;
    }

    public int getTimerIntervalSecondsTop() {
        return timerIntervalSecondsTop;
    }

    public ScanNotificationConfig getScanNotification() {
        return scanNotification;
    }

    public ChunkProcessingSettings getProcessingSettings() {
        return processingSettings;
    }

    public record ChunkProcessingSettings(int scanThread, int scanDelayMs) {
        private static int resolveThreads(int configured, String context) {
            if (configured == -1) {
                return Math.max(4, Runtime.getRuntime().availableProcessors() / 8);
            }

            if (configured > 0) {
                return configured;
            }

            log.warn("Invalid chunk-processing.{}.threads value ({}), falling back to 1.", context, configured);
            return 1;
        }

        public int resolvedScanThreads() {
            return resolveThreads(scanThread, "island_scan");
        }
    }
}
