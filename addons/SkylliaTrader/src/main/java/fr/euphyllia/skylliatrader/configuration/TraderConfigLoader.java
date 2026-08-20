package fr.euphyllia.skylliatrader.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.SkylliaAPI;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * 负责把 {@code config/config.toml} 从插件数据目录加载出来并挂到 Skyllia 的全局配置重载体系里。
 * 仿 {@code IslandLevelConfigLoader} 的写法：本类只管"文件在哪、怎么读进来、注册到哪"，
 * 真正的字段解析/默认值补全交给 {@link TraderConfigManager}。
 */
public class TraderConfigLoader {

    public static TraderConfigManager config;

    public static void init(File dataFolder) throws Exception {
        File configDir = new File(dataFolder, "config");
        //noinspection ResultOfMethodCallIgnored
        configDir.mkdirs();

        File configFile = new File(configDir, "config.toml");
        if (!configFile.exists()) {
            try (InputStream in = TraderConfigLoader.class.getResourceAsStream("/config/config.toml")) {
                if (in != null) {
                    Files.copy(in, configFile.toPath());
                }
            } catch (IOException e) {
                throw new RuntimeException("释放默认 config.toml 失败", e);
            }
        }

        // 不开 autosave：落盘一律走 TraderConfigManager#loadConfig 里显式的原子写入，
        // 否则每次 config.set 都会整表落盘一遍，最后再整表写一遍，纯属重复双写。
        CommentedFileConfig cfg = CommentedFileConfig.builder(configFile)
                .sync()
                .build();
        cfg.load();

        config = new TraderConfigManager(cfg);
        config.loadConfig();

        SkylliaAPI.getConfigRegistry().registerConfig(config);
    }

    public static void unregister() {
        if (config != null) {
            SkylliaAPI.getConfigRegistry().unregisterConfig(config);
        }
    }
}
