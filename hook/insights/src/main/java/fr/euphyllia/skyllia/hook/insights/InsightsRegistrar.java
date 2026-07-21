package fr.euphyllia.skyllia.hook.insights;

import dev.frankheijden.insights.api.InsightsPlugin;
import fr.euphyllia.skyllia.api.SkylliaAPI;

final class InsightsRegistrar {

    private InsightsRegistrar() {
    }

    static boolean register() {
        return InsightsPlugin.getInstance()
                .getAddonManager()
                .registerAddon(new InsightsDelegate());
    }

    static void unregister() {
        InsightsPlugin.getInstance()
                .getAddonManager()
                .unregisterAddon(SkylliaAPI.getPlugin().getName());
    }
}