package fr.euphyllia.skylliaislandlevel;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skylliaislandlevel.cache.LevelPapiCache;
import fr.euphyllia.skylliaislandlevel.commands.IslandLevelCommand;
import fr.euphyllia.skylliaislandlevel.configuration.IslandLevelConfigLoader;
import fr.euphyllia.skylliaislandlevel.listener.IslandLevelListener;
import fr.euphyllia.skylliaislandlevel.manager.LevelManager;
import fr.euphyllia.skylliaislandlevel.papi.IslandLevelExpansion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SkylliaIslandLevel extends JavaPlugin {

    private static final Logger log = LoggerFactory.getLogger(SkylliaIslandLevel.class);
    private static SkylliaIslandLevel instance;
    private LevelPapiCache papiCache;
    private LevelManager levelManager;

    public static SkylliaIslandLevel getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        log.warn("SkylliaIslandLevel is in beta version!");

        try {
            SkylliaAPI.getWorldNMS().getClass()
                    .getMethod("getCountAllBlocksInChunk", World.class, int.class, int.class);
        } catch (NoSuchMethodException e) {
            log.error("Your version of Skyllia does not support getCountAllBlocksInChunk().");
            log.error("Please update Skyllia to use SkylliaIslandLevel.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            IslandLevelConfigLoader.init(getDataFolder());
        } catch (Exception e) {
            log.error("Failed to load configuration!", e);
            getServer().getPluginManager().disablePlugin(this);
        }

        papiCache = new LevelPapiCache();

        levelManager = new LevelManager(this, papiCache);

        SkylliaAPI.registerCommands(new IslandLevelCommand(), "level", "niveau");

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new IslandLevelExpansion().register();
            log.debug("PlaceholderAPI expansion registered.");
        }

        getServer().getPluginManager().registerEvents(new IslandLevelListener(this), this);
    }

    @Override
    public void onDisable() {
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);

        IslandLevelConfigLoader.unregister();
        instance = null;
    }

    public LevelManager getLevelManager() {
        return levelManager;
    }

    public LevelPapiCache getPapiCache() {
        return papiCache;
    }
}
