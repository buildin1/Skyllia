package fr.euphyllia.skylliaislandlevel.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.SkylliaAPI;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public class IslandLevelConfigLoader {

    public static IslandLevelConfigManager config;

    public static void init(File dataFolder) throws Exception {
        File configDir = new File(dataFolder, "config");
        //noinspection ResultOfMethodCallIgnored
        configDir.mkdirs();

        File configFile = new File(configDir, "config.toml");
        if (!configFile.exists()) {
            try (InputStream in = IslandLevelConfigLoader.class
                    .getResourceAsStream("/config/config.toml")) {
                if (in != null) {
                    Files.copy(in, configFile.toPath());
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy default config", e);
            }
        }

        CommentedFileConfig cfg = CommentedFileConfig.builder(configFile)
                .sync()
                .autosave()
                .build();
        cfg.load();

        config = new IslandLevelConfigManager(cfg);
        config.loadConfig();

        SkylliaAPI.getConfigRegistry().registerConfig(config);
    }

    public static void unregister() {
        if (config != null) {
            SkylliaAPI.getConfigRegistry().unregisterConfig(config);
        }
    }
}
