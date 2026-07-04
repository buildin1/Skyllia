package fr.euphyllia.skyllia.hook.insights;

import fr.euphyllia.skyllia.api.hooks.PluginHook;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InsightsHook implements PluginHook {

    private static final Logger log = LoggerFactory.getLogger(InsightsHook.class);


    private static final boolean API_AVAILABLE = checkInsightsApi();

    private static boolean checkInsightsApi() {
        try {
            Class<?> addonManagerClass = Class.forName("dev.frankheijden.insights.api.addons.AddonManager");
            Class<?> insightsAddonClass = Class.forName("dev.frankheijden.insights.api.addons.InsightsAddon");
            addonManagerClass.getMethod("registerAddon", insightsAddonClass); // 6.22.0+
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        }
    }

    @Override
    public String name() {
        return "Insights";
    }

    @Override
    public boolean isAvailable() {
        return API_AVAILABLE && Bukkit.getPluginManager().getPlugin("Insights") != null;
    }

    @Override
    public void register(Plugin skylliaPlugin) {
        try {
            if (InsightsRegistrar.register()) {
                log.debug("Hook Insights enabled");
            } else {
                log.warn("Insights refused the Skyllia addon registration.");
            }
        } catch (Throwable t) {
            log.error("Failed to register Skyllia as an Insights addon", t);
        }
    }

    @Override
    public void unregister() {
        if (!isAvailable()) return;
        try {
            InsightsRegistrar.unregister();
        } catch (Throwable t) {
            log.error("Failed to unregister Skyllia from Insights", t);
        }
    }
}