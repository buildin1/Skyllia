package fr.euphyllia.skylliaacidrain;

import fr.euphyllia.skylliaacidrain.commands.AcidRainAdminCommand;
import fr.euphyllia.skylliaacidrain.configuration.AcidConfigLoader;
import fr.euphyllia.skylliaacidrain.listener.AcidListener;
import fr.euphyllia.skylliaacidrain.season.AcidSeasonManager;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SkylliaAcidRain extends JavaPlugin {


    private static final Logger log = LoggerFactory.getLogger(SkylliaAcidRain.class);

    private AcidSeasonManager acidSeasonManager;

    @Override
    public void onEnable() {

        AcidListener acidListener = new AcidListener(this);
        this.acidSeasonManager = new AcidSeasonManager(this);

        try {
            AcidConfigLoader.init(getDataFolder(), acidListener, acidSeasonManager);
        } catch (Exception e) {
            log.error("Error while loading AcidConfig", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(acidListener, this);
        getServer().getPluginManager().registerEvents(acidSeasonManager, this);
        // AcidConfigLoader.init() 已通过 AcidConfigManager.loadConfig() 启动周期检查任务

        SkylliaAPI.registerAdminCommands(new AcidRainAdminCommand(acidSeasonManager), "acidrain");
    }

    public AcidSeasonManager getAcidSeasonManager() {
        return acidSeasonManager;
    }

    @Override
    public void onDisable() {
        if (acidSeasonManager != null) acidSeasonManager.stop();
        AcidConfigLoader.unregister();
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
    }
}
