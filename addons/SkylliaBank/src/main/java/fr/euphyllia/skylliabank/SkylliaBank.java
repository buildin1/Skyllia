package fr.euphyllia.skylliabank;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliabank.api.BankGenerator;
import fr.euphyllia.skylliabank.cache.BankPapiCache;
import fr.euphyllia.skylliabank.commands.BankAdminCommand;
import fr.euphyllia.skylliabank.commands.BankCommand;
import fr.euphyllia.skylliabank.configuration.BankConfigLoader;
import fr.euphyllia.skylliabank.database.mariadb.MariaDBBankInit;
import fr.euphyllia.skylliabank.database.postgresql.PostgreSQLBankInit;
import fr.euphyllia.skylliabank.database.sqlite.SQLiteBankInit;
import fr.euphyllia.skylliabank.listeners.InfoListener;
import fr.euphyllia.skylliabank.papi.SkylliaBankExpansion;
import fr.euphyllia.skylliabank.vault.VaultIslandEconomy;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SkylliaBank extends JavaPlugin {

    private static final Logger log = LoggerFactory.getLogger(SkylliaBank.class);
    private static BankManager bankManager;
    private static SkylliaBank instance;
    private BankPapiCache papiCache;
    private boolean registeredAsVaultProvider = false;

    public static BankManager getBankManager() {
        return bankManager;
    }

    public static SkylliaBank getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        // Initialiser l'intégration avec Vault
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("Vault is not installed!");
            getLogger().severe("No economy plugin found. The plugin will stop.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getServer().getPluginManager().getPlugin("Skyllia") == null) {
            getLogger().severe("Skyllia is not installed! The plugin will stop.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            BankConfigLoader.init(getDataFolder());
        } catch (Exception e) {
            log.error("Error while loading BankConfig", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        BankGenerator generator;
        papiCache = new BankPapiCache();
        if (ConfigLoader.database.getMariaDBConfig() != null) {
            MariaDBBankInit dbInit = new MariaDBBankInit();
            if (!dbInit.init()) {
                getLogger().severe("Unable to initialize the MariaDB bank database! The plugin will stop.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            generator = MariaDBBankInit.getMariaDbBankGenerator();
        } else if (ConfigLoader.database.getPostgreConfig() != null) {
            PostgreSQLBankInit dbInit = new PostgreSQLBankInit();
            if (!dbInit.init()) {
                getLogger().severe("Unable to initialize the PostgreSQL bank database! The plugin will stop.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            generator = PostgreSQLBankInit.getPostgresBankGenerator();

        } else if (ConfigLoader.database.getSqLiteConfig() != null) {
            SQLiteBankInit dbInit = new SQLiteBankInit();
            if (!dbInit.init()) {
                getLogger().severe("Unable to initialize the SQLite bank database! The plugin will stop.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            generator = SQLiteBankInit.getGenerator();
        } else {
            getLogger().severe("No database configuration found! The plugin will stop.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        bankManager = new BankManager(generator);

        if (BankConfigLoader.config.isVaultIslandEconomyEnabled()) {
            VaultIslandEconomy vaultIslandEconomy = new VaultIslandEconomy();
            getServer().getServicesManager().register(
                    Economy.class,
                    vaultIslandEconomy,
                    this,
                    ServicePriority.Highest
            );
            registeredAsVaultProvider = true;
            getLogger().info("Island economy mode enabled: SkylliaBank is now the Vault Economy provider.");
            getLogger().info("Per-player economy calls will be routed to island banks.");
        } else {
            if (!EconomyManager.setupEconomy(this)) {
                getLogger().severe("No economy plugin found. The plugin will stop.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().info("Classic economy mode: using existing Vault Economy provider.");
        }

        SkylliaAPI.registerCommands(new BankCommand(this), "bank", "money", "bal", "balance");
        SkylliaAPI.registerAdminCommands(new BankAdminCommand(this), "bank");

        getServer().getPluginManager().registerEvents(new InfoListener(), this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            SkylliaBankExpansion expansion = new SkylliaBankExpansion();
            expansion.register();
        }

        getLogger().info("SkylliaBank has been successfully activated!");
    }

    @Override
    public void onDisable() {
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        if (papiCache != null) papiCache.clear();

        if (registeredAsVaultProvider) {
            getServer().getServicesManager().unregisterAll(this);
        }
    }

    public BankPapiCache getPapiCache() {
        return papiCache;
    }
}