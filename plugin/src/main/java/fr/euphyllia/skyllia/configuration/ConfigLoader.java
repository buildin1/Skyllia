package fr.euphyllia.skyllia.configuration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import fr.euphyllia.skyllia.api.configuration.IConfigRegistry;
import fr.euphyllia.skyllia.api.configuration.IConfigurationProvider;
import fr.euphyllia.skyllia.configuration.manager.DatabaseConfigManager;
import fr.euphyllia.skyllia.configuration.manager.GeneralConfigManager;
import fr.euphyllia.skyllia.configuration.manager.IslandConfigManager;
import fr.euphyllia.skyllia.configuration.manager.IslandFlagsConfigManager;
import fr.euphyllia.skyllia.configuration.manager.IslandTypeGuiConfigManager;
import fr.euphyllia.skyllia.gui.layout.GuiLayoutConfigManager;
import fr.euphyllia.skyllia.gui.layout.GuiLayoutDefaults;
import fr.euphyllia.skyllia.configuration.manager.LanguageConfigManager;
import fr.euphyllia.skyllia.configuration.manager.PermissionsV2ConfigManager;
import fr.euphyllia.skyllia.configuration.manager.PlayerConfigManager;
import fr.euphyllia.skyllia.configuration.manager.SchematicConfigManager;
import fr.euphyllia.skyllia.configuration.manager.WorldConfigManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ConfigLoader implements IConfigRegistry {

    public static final ConfigLoader INSTANCE = new ConfigLoader();
    private static final Logger logger = LogManager.getLogger(ConfigLoader.class);
    private static final List<IConfigurationProvider> configManagers = new ArrayList<>();
    public static GeneralConfigManager general;
    public static DatabaseConfigManager database;
    public static WorldConfigManager worldManager;
    public static IslandConfigManager islandManager;
    public static PlayerConfigManager playerManager;
    public static SchematicConfigManager schematicManager;
    public static LanguageConfigManager language;
    public static PermissionsV2ConfigManager permissionsV2;
    public static IslandFlagsConfigManager islandFlags;
    public static IslandTypeGuiConfigManager islandTypeGui;
    /** 主菜单外观（gui/main.toml）。 */
    public static GuiLayoutConfigManager mainGuiLayout;

    private static CommentedFileConfig generalConfig;
    private static CommentedFileConfig databaseConfig;
    private static CommentedFileConfig worldConfig;
    private static CommentedFileConfig islandConfig;
    private static CommentedFileConfig playerConfig;
    private static CommentedFileConfig schematicConfig;
    private static CommentedFileConfig permissionsV2Config;
    private static CommentedFileConfig flagsConfig;
    private static CommentedFileConfig islandTypeGuiConfig;
    private static CommentedFileConfig mainGuiLayoutConfig;

    public static void init(File allConfig) {

        File configDir = new File(allConfig, "config");

        generalConfig = loadFile(new File(configDir, "config.toml"));
        databaseConfig = loadFile(new File(configDir, "database.toml"));
        worldConfig = loadFile(new File(configDir, "worlds.toml"));
        islandConfig = loadFile(new File(configDir, "islands.toml"));
        playerConfig = loadFile(new File(configDir, "players.toml"));
        schematicConfig = loadFile(new File(configDir, "schematics.toml"));
        // permissions-v2.toml / flags.toml 走「不带 autosave」的加载方式，原因见 loadFileNoAutosave。
        permissionsV2Config = loadFileNoAutosave(new File(configDir, "permissions-v2.toml"));
        flagsConfig = loadFileNoAutosave(new File(configDir, "flags.toml"));
        islandTypeGuiConfig = loadFile(new File(configDir, "island-types.toml"));

        general = new GeneralConfigManager(generalConfig);
        database = new DatabaseConfigManager(databaseConfig);
        worldManager = new WorldConfigManager(worldConfig);
        islandManager = new IslandConfigManager(islandConfig);
        playerManager = new PlayerConfigManager(playerConfig);
        schematicManager = new SchematicConfigManager(schematicConfig);
        language = new LanguageConfigManager();
        permissionsV2 = new PermissionsV2ConfigManager(permissionsV2Config);
        islandFlags = new IslandFlagsConfigManager(flagsConfig);
        // GUI 外观配置放在独立的 gui/ 目录，与功能性配置分开 —— 服主改外观时
        // 不必在一堆功能配置里翻找，也降低误改数据库/世界配置的风险。
        File guiDir = new File(allConfig, "gui");
        //noinspection ResultOfMethodCallIgnored
        guiDir.mkdirs();
        mainGuiLayoutConfig = loadFile(new File(guiDir, "main.toml"));

        islandTypeGui = new IslandTypeGuiConfigManager(islandTypeGuiConfig);
        mainGuiLayout = new GuiLayoutConfigManager(mainGuiLayoutConfig, GuiLayoutDefaults.main());
        // 首次启动时按现有岛屿类型补全建岛菜单条目，服主无需手写。
        islandTypeGui.seedFrom(schematicManager.getIslandTypes());

        configManagers.add(general);
        configManagers.add(database);
        configManagers.add(worldManager);
        configManagers.add(islandManager);
        configManagers.add(playerManager);
        configManagers.add(schematicManager);
        configManagers.add(language);
        configManagers.add(permissionsV2);
        configManagers.add(islandFlags);
        configManagers.add(islandTypeGui);
        configManagers.add(mainGuiLayout);

        //reloadConfigs();

        logger.log(Level.INFO, "[Config] Configurations loaded successfully.");
    }

    /**
     * 默认加载方式：带 {@code autosave}。
     * <p>
     * {@code island-types.toml} 与 {@code gui/*.toml} 的管理器（{@link IslandTypeGuiConfigManager}、
     * {@link GuiLayoutConfigManager}）补默认值之后<b>没有任何显式落盘</b>，全靠 autosave 兜底，
     * 所以这里的 autosave 不能一刀切去掉——去掉会让这两份配置的默认值补全静默丢失。
     * </p>
     */
    private static CommentedFileConfig loadFile(File file) {
        CommentedFileConfig configFile = CommentedFileConfig.builder(file).sync().autosave().build();
        configFile.load();
        return configFile;
    }

    /**
     * 不带 autosave 的加载方式，供「自己负责显式整表落盘」的配置使用
     * （目前是 {@link PermissionsV2ConfigManager} 与 {@link IslandFlagsConfigManager}）。
     * <p>
     * 这两份配置在启动时可能一次性补上几百个缺失的权限/标志键（附属插件注册的权限位越多补得越多）。
     * 开着 autosave 的话，<b>每一次 {@code set} 都会把整份配置同步落盘一遍</b>，几百次 set
     * 就是几百次整表写盘，最后管理器末尾那次显式写入还要再写一遍，纯属重复双写；而且 autosave
     * 走的是 {@code WritingMode.REPLACE}（截断 + 写入两步，非原子），在启动那一 tick 里反复
     * 截断线上的 permissions-v2.toml，中途崩溃就会留下半截文件。
     * </p>
     * <p>
     * 去掉 autosave 之后，这两份配置的落盘完全由各自管理器里的
     * {@link fr.euphyllia.skyllia.utils.ConfigFileWriter#writeAtomically} 负责：
     * {@code loadConfig()} 末尾补全后写一次，{@code persist()}（{@code /skylliadmin perm set}
     * 之类的运行时改动）每次改完写一次，一次都不会漏。<b>今后给这两个管理器新增任何
     * {@code config.set} 路径时，必须记得在末尾调 persist()。</b>
     * </p>
     */
    private static CommentedFileConfig loadFileNoAutosave(File file) {
        CommentedFileConfig configFile = CommentedFileConfig.builder(file).sync().build();
        configFile.load();
        return configFile;
    }

    public static void reloadConfigs() {
        logger.log(Level.INFO, "[Config] Reloading configurations...");
        try {
            for (IConfigurationProvider manager : configManagers) {
                if (!manager.canReloadFromDisk()) continue;
                manager.reloadFromDisk();
                manager.loadConfig();
            }
            logger.log(Level.INFO, "[Config] Reload complete.");

            if (general.getDebugSettings().permission()) {
                logger.log(Level.WARN, "!!! Warning !!!\n" +
                        "Verbose permission debugging is active.\n" +
                        "Although single hasPermission checks are very fast,\n" +
                        "too many checks will cause massive permission log spamming in the console,\n" +
                        "leading to high I/O load and potential server slowdown \n" +
                        "during heavy Skyllia command usage.");
            }
        } catch (Exception exception) {
            logger.error(exception);
        }
    }

    @Override
    public void registerConfig(IConfigurationProvider provider) {
        if (!configManagers.contains(provider)) {
            configManagers.add(provider);
        }
    }

    @Override
    public void unregisterConfig(IConfigurationProvider provider) {
        configManagers.remove(provider);
    }
}
