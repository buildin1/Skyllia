package fr.euphyllia.skylliaupgrade;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.gui.GuiExtensionEntry;
import fr.euphyllia.skyllia.gui.GuiExtensionRegistry;
import fr.euphyllia.skylliaupgrade.api.UpgradeGenerator;
import fr.euphyllia.skylliaupgrade.commands.UpgradeAdminCommand;
import fr.euphyllia.skylliaupgrade.commands.UpgradeCommand;
import fr.euphyllia.skylliaupgrade.configuration.UpgradeConfigLoader;
import fr.euphyllia.skylliaupgrade.database.mariadb.MariaDBUpgradeInit;
import fr.euphyllia.skylliaupgrade.database.postgresql.PostgreSQLUpgradeInit;
import fr.euphyllia.skylliaupgrade.database.sqlite.SQLiteUpgradeInit;
import fr.euphyllia.skylliaupgrade.gui.UpgradeGui;
import fr.euphyllia.skylliaupgrade.manager.UpgradeManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SkylliaUpgrade extends JavaPlugin {

    private static final Logger log = LoggerFactory.getLogger(SkylliaUpgrade.class);
    private static final String EXTENSION_ID = "skylliaupgrade:menu";

    private static SkylliaUpgrade instance;
    private UpgradeManager upgradeManager;

    public static SkylliaUpgrade getInstance() {
        return instance;
    }

    public UpgradeManager getUpgradeManager() {
        return upgradeManager;
    }

    @Override
    public void onEnable() {
        instance = this;

        if (getServer().getPluginManager().getPlugin("Skyllia") == null) {
            getLogger().severe("Skyllia is not installed! The plugin will stop.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getServer().getPluginManager().getPlugin("SkylliaIslandLevel") == null) {
            getLogger().severe("SkylliaIslandLevel (SkylliaIslandValue) is not installed!");
            getLogger().severe("SkylliaUpgrade uses it to gate upgrades on island score. The plugin will stop.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            UpgradeConfigLoader.init(getDataFolder());
        } catch (Exception e) {
            log.error("Error while loading SkylliaUpgrade config", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        UpgradeGenerator generator;
        if (ConfigLoader.database.getMariaDBConfig() != null) {
            MariaDBUpgradeInit dbInit = new MariaDBUpgradeInit();
            if (!dbInit.init()) {
                getLogger().severe("Unable to initialize the MariaDB upgrade database! The plugin will stop.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            generator = MariaDBUpgradeInit.getGenerator();
        } else if (ConfigLoader.database.getPostgreConfig() != null) {
            PostgreSQLUpgradeInit dbInit = new PostgreSQLUpgradeInit();
            if (!dbInit.init()) {
                getLogger().severe("Unable to initialize the PostgreSQL upgrade database! The plugin will stop.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            generator = PostgreSQLUpgradeInit.getGenerator();
        } else if (ConfigLoader.database.getSqLiteConfig() != null) {
            SQLiteUpgradeInit dbInit = new SQLiteUpgradeInit();
            if (!dbInit.init()) {
                getLogger().severe("Unable to initialize the SQLite upgrade database! The plugin will stop.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            generator = SQLiteUpgradeInit.getGenerator();
        } else {
            getLogger().severe("No database configuration found! The plugin will stop.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.upgradeManager = new UpgradeManager(generator);

        SkylliaAPI.registerCommands(new UpgradeCommand(), "upgrade", "up");
        SkylliaAPI.registerAdminCommands(new UpgradeAdminCommand(), "upgrade");

        GuiExtensionRegistry.register(new GuiExtensionEntry(
                EXTENSION_ID,
                org.bukkit.Material.NETHER_STAR,
                "<light_purple>🏝 岛屿升级",
                java.util.List.of("<gray>花材料 + 领地拓展令", "<gray>扩大岛屿边境，提升成员上限"),
                0,
                player -> SkylliaAPI.getIslandByPlayerId(player.getUniqueId()) != null,
                UpgradeGui::open
        ));

        getLogger().info("SkylliaUpgrade has been successfully activated!");
    }

    @Override
    public void onDisable() {
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        GuiExtensionRegistry.unregister(EXTENSION_ID);
        UpgradeConfigLoader.unregister();
    }

    public void reloadConfig0() {
        UpgradeConfigLoader.config.reloadFromDisk();
    }
}
