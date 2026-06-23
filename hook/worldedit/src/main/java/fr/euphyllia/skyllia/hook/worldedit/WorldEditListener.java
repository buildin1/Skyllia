package fr.euphyllia.skyllia.hook.worldedit;

import fr.euphyllia.skyllia.api.event.skyllia.SkylliaReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class WorldEditListener implements Listener {

    @EventHandler
    public void onSkylliaReloaded(final SkylliaReloadEvent event) {
        WorldEditSchematicPaster.clearCache();
    }
}
