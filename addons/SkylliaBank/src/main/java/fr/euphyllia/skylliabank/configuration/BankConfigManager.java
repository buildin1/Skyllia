package fr.euphyllia.skylliabank.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlWriter;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;

import java.util.Locale;

public class BankConfigManager implements IConfigurationProvider {

    private final CommentedFileConfig config;
    private boolean changed = false;
    private String formatBankAccount;
    private Locale localBankAccount;
    private int ttlCache = 60;
    private int ttlCacheTop = 300;

    public BankConfigManager(CommentedFileConfig commentedFileConfig) {
        this.config = commentedFileConfig;
    }

    @Override
    public void loadConfig() throws Exception {
        changed = false;
        this.formatBankAccount = getOrSetDefault("bank-account.format", "#,##0.##", String.class);
        this.localBankAccount = Locale.of(getOrSetDefault("bank-account.locale", Locale.FRANCE.toString(), String.class));
        this.ttlCache = getOrSetDefault("cache.ttl", ttlCache, Integer.class);
        this.ttlCacheTop = getOrSetDefault("cache.ttl-top", ttlCacheTop, Integer.class);

        if (changed) {
            TomlWriter tomlWriter = new TomlWriter();
            tomlWriter.setIndent(IndentStyle.NONE);
            tomlWriter.write(config, config.getFile(), WritingMode.REPLACE);
        }
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
    public void reloadFromDisk() {
        config.load();
    }

    @Override
    public boolean canReloadFromDisk() {
        return true;
    }

    public Locale getLocalBankAccount() {
        return localBankAccount;
    }

    public String getFormatBankAccount() {
        return formatBankAccount;
    }

    public int getTtlCache() {
        return ttlCache;
    }

    public int getTtlCacheTop() {
        return ttlCacheTop;
    }
}
