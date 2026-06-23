package fr.euphyllia.skyllia.hook;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.hooks.PluginHook;
import fr.euphyllia.skyllia.api.hooks.SchematicHook;
import fr.euphyllia.skyllia.api.hooks.ServerHook;
import fr.euphyllia.skyllia.hook.canvas.CanvasHook;
import fr.euphyllia.skyllia.hook.fastasyncworldedit.FAWESchematicHook;
import fr.euphyllia.skyllia.hook.internal.InternalSchematicHook;
import fr.euphyllia.skyllia.hook.luminol.LuminolHook;
import fr.euphyllia.skyllia.hook.quickshop.QuickShopHook;
import fr.euphyllia.skyllia.hook.worldedit.WorldEditSchematicHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class HookBootstrap {

    static final List<ServerHook> serverHooks = List.of(
            new CanvasHook(),
            new LuminolHook()
    );
    static final List<PluginHook> pluginHooks = List.of(
            new QuickShopHook()
    );
    static final FAWESchematicHook faweHook = new FAWESchematicHook();
    static final WorldEditSchematicHook worldEditHook = new WorldEditSchematicHook();
    static final InternalSchematicHook internalHook = new InternalSchematicHook();
    static final List<SchematicHook> schematicHooks = List.of(
            faweHook,
            worldEditHook,
            internalHook
    );
    private static final Logger log = LoggerFactory.getLogger(HookBootstrap.class);

    private HookBootstrap() {
    }

    public static void registerAll() {
        for (ServerHook hook : serverHooks) {
            if (!hook.isAvailable()) continue;
            hook.register(Skyllia.getInstance());
            log.debug("Registered server hook: {}", hook.name());
        }

        for (SchematicHook schematicHook : schematicHooks) {
            if (!schematicHook.isAvailable()) continue;
            schematicHook.register(Skyllia.getInstance());
            log.debug("Active schematic hook: {}", schematicHook.name());
            break;
        }

        for (PluginHook pluginHook : pluginHooks) {
            if (!pluginHook.isAvailable()) continue;
            pluginHook.register(Skyllia.getInstance());
        }
    }

    public static void unregisterAll() {
        for (PluginHook pluginHook : pluginHooks) {
            if (pluginHook.isAvailable()) pluginHook.unregister();
        }

        for (SchematicHook schematicHook : schematicHooks) {
            if (schematicHook.isAvailable()) schematicHook.unregister();
        }
    }
}
