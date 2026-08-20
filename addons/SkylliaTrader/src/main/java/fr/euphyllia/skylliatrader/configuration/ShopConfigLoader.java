package fr.euphyllia.skylliatrader.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.SkylliaAPI;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * 负责把 {@code config/shop.toml} 加载出来并挂到 Skyllia 的全局配置重载体系里。
 * 拆成独立的 Loader/Manager 而不是塞进 {@link TraderConfigLoader}，理由和
 * {@link OrdersConfigLoader} 完全一样：商品表是"显式条目数组"，跟主配置的
 * "标量键值 + 缺项自动补全"结构不同。
 */
public class ShopConfigLoader {

    public static ShopConfigManager config;

    public static void init(File dataFolder) throws Exception {
        File configDir = new File(dataFolder, "config");
        //noinspection ResultOfMethodCallIgnored
        configDir.mkdirs();

        File shopFile = new File(configDir, "shop.toml");
        if (!shopFile.exists()) {
            try (InputStream in = ShopConfigLoader.class.getResourceAsStream("/config/shop.toml")) {
                if (in != null) {
                    Files.copy(in, shopFile.toPath());
                }
            } catch (IOException e) {
                throw new RuntimeException("释放默认 shop.toml 失败", e);
            }
        }

        // 不开 autosave：理由同 OrdersConfigLoader——商品表对本插件是只读的，
        // 运行时不存在任何回写路径。
        CommentedFileConfig cfg = CommentedFileConfig.builder(shopFile)
                .sync()
                .build();
        cfg.load();

        config = new ShopConfigManager(cfg);
        config.loadConfig();

        SkylliaAPI.getConfigRegistry().registerConfig(config);
    }

    public static void unregister() {
        if (config != null) {
            SkylliaAPI.getConfigRegistry().unregisterConfig(config);
        }
    }
}
