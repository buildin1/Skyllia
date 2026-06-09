package fr.euphyllia.skylliabackup;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skylliabackup.commands.BackupCommand;
import fr.euphyllia.skylliabackup.commands.admin.BackupAdminCommand;
import fr.euphyllia.skylliabackup.configuration.BackupConfigLoader;
import fr.euphyllia.skylliabackup.manager.BackupManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkylliaBackup extends JavaPlugin {

    private static final Logger log = LoggerFactory.getLogger(SkylliaBackup.class);
    private static SkylliaBackup instance;
    private BackupManager backupManager;

    public static SkylliaBackup getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        if (getServer().getPluginManager().getPlugin("Skyllia") == null) {
            log.error("Skyllia is not installed! SkylliaBackup will stop.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            BackupConfigLoader.init(getDataFolder());
        } catch (Exception e) {
            log.error("Failed to load SkylliaBackup config", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.backupManager = new BackupManager(this, BackupConfigLoader.config);

        SkylliaAPI.registerCommands(new BackupCommand(this), "backup");
        SkylliaAPI.registerAdminCommands(new BackupAdminCommand(this), "backup");

        log.info("SkylliaBackup enabled.");
    }

    @Override
    public void onDisable() {
        BackupConfigLoader.unregister();
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        log.info("SkylliaBackup disabled.");
    }

    public BackupManager getBackupManager() {
        return backupManager;
    }
}