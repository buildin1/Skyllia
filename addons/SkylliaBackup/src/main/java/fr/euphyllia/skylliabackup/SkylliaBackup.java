package fr.euphyllia.skylliabackup;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skylliabackup.commands.BackupCommand;
import fr.euphyllia.skylliabackup.manager.BackupManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkylliaBackup extends JavaPlugin {

    private static final Logger log = LoggerFactory.getLogger(SkylliaBackup.class);
    private BackupManager backupManager;

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("Skyllia") == null) {
            log.error("Skyllia is not installed! SkylliaBackup will stop.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.backupManager = new BackupManager(this);

        SkylliaAPI.registerCommands(new BackupCommand(this), "backup");
    }

    @Override
    public void onDisable() {
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
    }

    public BackupManager getBackupManager() {
        return backupManager;
    }
}
