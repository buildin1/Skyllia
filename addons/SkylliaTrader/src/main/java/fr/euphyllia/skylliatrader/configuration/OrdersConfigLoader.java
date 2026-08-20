package fr.euphyllia.skylliatrader.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.SkylliaAPI;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * 负责把 {@code config/orders.toml} 加载出来并挂到 Skyllia 的全局配置重载体系里。
 * 拆成独立的 Loader/Manager 而不是塞进 {@link TraderConfigLoader}，
 * 是因为订单表的字段结构（数组套表）和读写语义（显式条目、不自动补默认值）
 * 跟主配置的"标量键值 + 缺项自动补全"完全不同，混在一起会让两边都变得难改。
 */
public class OrdersConfigLoader {

    public static OrdersConfigManager config;

    public static void init(File dataFolder) throws Exception {
        File configDir = new File(dataFolder, "config");
        //noinspection ResultOfMethodCallIgnored
        configDir.mkdirs();

        File ordersFile = new File(configDir, "orders.toml");
        if (!ordersFile.exists()) {
            try (InputStream in = OrdersConfigLoader.class.getResourceAsStream("/config/orders.toml")) {
                if (in != null) {
                    Files.copy(in, ordersFile.toPath());
                }
            } catch (IOException e) {
                throw new RuntimeException("释放默认 orders.toml 失败", e);
            }
        }

        // 不开 autosave：订单表对本插件是只读的，管理员改完走 /skyllia reload 重新读，
        // 运行时不存在任何回写路径，开着 autosave 只会平白多出一条能写坏 orders.toml 的路子。
        CommentedFileConfig cfg = CommentedFileConfig.builder(ordersFile)
                .sync()
                .build();
        cfg.load();

        config = new OrdersConfigManager(cfg);
        config.loadConfig();

        SkylliaAPI.getConfigRegistry().registerConfig(config);
    }

    public static void unregister() {
        if (config != null) {
            SkylliaAPI.getConfigRegistry().unregisterConfig(config);
        }
    }
}
