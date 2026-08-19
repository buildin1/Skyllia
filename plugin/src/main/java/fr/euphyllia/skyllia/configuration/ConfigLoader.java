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
        permissionsV2Config = loadFile(new File(configDir, "permissions-v2.toml"));
        flagsConfig = loadFile(new File(configDir, "flags.toml"));
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

    private static CommentedFileConfig loadFile(File file) {
        CommentedFileConfig configFile = CommentedFileConfig.builder(file).sync().autosave().build();
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
