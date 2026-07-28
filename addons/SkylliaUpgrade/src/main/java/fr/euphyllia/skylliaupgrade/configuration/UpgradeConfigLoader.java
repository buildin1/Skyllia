package fr.euphyllia.skylliaupgrade.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.SkylliaAPI;

import java.io.File;

public class UpgradeConfigLoader {

    public static UpgradeConfigManager config;

    public static void init(File dataFolder) throws Exception {
        File configDir = new File(dataFolder, "config");
        //noinspection ResultOfMethodCallIgnored
        configDir.mkdirs();
        config = new UpgradeConfigManager(loadFile(new File(configDir, "upgrades.toml")));

        config.loadConfig();

        SkylliaAPI.getConfigRegistry().registerConfig(config);
    }

    public static void unregister() {
        if (config != null) SkylliaAPI.getConfigRegistry().unregisterConfig(config);
    }

    private static CommentedFileConfig loadFile(File file) {
        CommentedFileConfig cfg = CommentedFileConfig.builder(file).sync().autosave().build();
        cfg.load();
        return cfg;
    }
}
