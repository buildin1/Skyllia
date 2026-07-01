package fr.euphyllia.skyllia.hook.fastasyncworldedit;

import fr.euphyllia.skyllia.api.event.skyllia.SkylliaReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class FAWEListener implements Listener {

    @EventHandler
    public void onSkylliaReloaded(final SkylliaReloadEvent event) {
        FAWESchematicPaster.clearCache();
    }
}