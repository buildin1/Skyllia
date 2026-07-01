package fr.euphyllia.skylliabackup.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.SkylliaAPI;

import java.io.File;

public class BackupConfigLoader {
    public static BackupConfigManager config;

    public static void init(File dataFolder) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        dataFolder.mkdirs();

        config = new BackupConfigManager(loadFile(new File(dataFolder, "config.toml")));
        config.loadConfig();

        SkylliaAPI.getConfigRegistry().registerConfig(config);
    }

    public static void unregister() {
        if (config != null) {
            SkylliaAPI.getConfigRegistry().unregisterConfig(config);
        }
    }

    private static CommentedFileConfig loadFile(File file) {
        CommentedFileConfig cfg = CommentedFileConfig.builder(file).sync().autosave().build();
        cfg.load();
        return cfg;
    }
}
