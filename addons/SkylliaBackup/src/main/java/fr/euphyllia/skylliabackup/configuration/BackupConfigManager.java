package fr.euphyllia.skylliabackup.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlWriter;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackupConfigManager implements IConfigurationProvider {

    private static final Logger log = LoggerFactory.getLogger(BackupConfigManager.class);

    private final CommentedFileConfig config;
    private boolean changed = false;

    private String backupFolder;
    private int maxBackupsPerIsland;
    private boolean allowPlayerBackup;
    private long playerCooldownSeconds;

    private String uploadUrl;
    private String uploadToken;

    public BackupConfigManager(CommentedFileConfig config) {
        this.config = config;
    }

    @Override
    public void loadConfig() {
        changed = false;

        // [backup]
        this.backupFolder = getOrSetDefault("backup.folder", "plugins/SkylliaBackup/backups", String.class);
        this.maxBackupsPerIsland = getOrSetDefault("backup.max-per-island", 5, Integer.class);
        this.allowPlayerBackup = getOrSetDefault("backup.allow-player-backup", true, Boolean.class);
        this.playerCooldownSeconds = getOrSetDefault("backup.player-cooldown-seconds", 3600L, Long.class);

        // [upload]
        this.uploadUrl = getOrSetDefault("upload.url", "", String.class);
        this.uploadToken = getOrSetDefault("upload.token", "", String.class);

        if (changed) {
            TomlWriter tomlWriter = new TomlWriter();
            tomlWriter.setIndent(IndentStyle.NONE);
            tomlWriter.write(config, config.getFile(), WritingMode.REPLACE);
        }

        log.info("(Re)Loaded config SkylliaBackup");
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
            default -> throw new IllegalStateException(
                    "Cannot convert value at '" + path + "' from "
                            + value.getClass().getSimpleName() + " to " + expectedClass.getSimpleName());
        };
    }

    @Override
    public void reloadFromDisk() {
        config.load();
    }

    @Override
    public boolean canReloadFromDisk() {
        return true;
    }

    public String getBackupFolder() {
        return backupFolder;
    }

    public int getMaxBackupsPerIsland() {
        return maxBackupsPerIsland;
    }

    public boolean isAllowPlayerBackup() {
        return allowPlayerBackup;
    }

    public long getPlayerCooldownSeconds() {
        return playerCooldownSeconds;
    }

    public String getUploadUrl() {
        return uploadUrl;
    }

    public String getUploadToken() {
        return uploadToken;
    }

    public boolean isUploadEnabled() {
        return uploadUrl != null && !uploadUrl.isBlank();
    }
}