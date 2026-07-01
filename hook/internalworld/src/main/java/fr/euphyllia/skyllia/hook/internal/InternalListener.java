package fr.euphyllia.skyllia.hook.internal;

import fr.euphyllia.skyllia.api.event.skyllia.SkylliaReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class InternalListener implements Listener {

    @EventHandler
    public void onSkylliaReloaded(final SkylliaReloadEvent event) {
        InternalSchematicHook.clearCache();
    }
}