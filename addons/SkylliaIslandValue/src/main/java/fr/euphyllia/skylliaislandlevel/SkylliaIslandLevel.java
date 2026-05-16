package fr.euphyllia.skylliaislandlevel;

import fr.euphyllia.skylliaislandlevel.configuration.IslandLevelConfigLoader;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SkylliaIslandLevel extends JavaPlugin {

    private static final Logger log = LoggerFactory.getLogger(SkylliaIslandLevel.class);
    private static SkylliaIslandLevel instance;

    @Override
    public void onEnable() {
        instance = this;

        try {
            IslandLevelConfigLoader.init(getDataFolder());
        } catch (Exception e) {
            log.error("Failed to load configuration!", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

    }
}
