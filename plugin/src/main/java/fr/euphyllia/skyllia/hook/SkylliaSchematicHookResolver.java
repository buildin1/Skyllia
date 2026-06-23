package fr.euphyllia.skyllia.hook;

import fr.euphyllia.skyllia.api.hooks.SchematicHook;
import fr.euphyllia.skyllia.api.skyblock.model.SchematicPlugin;
import fr.euphyllia.skyllia.hook.internal.InternalSchematicHook;
import org.bukkit.plugin.java.JavaPlugin;

public class SkylliaSchematicHookResolver {

    private final SchematicHook fawe;
    private final SchematicHook we;
    private final InternalSchematicHook internal;

    public SkylliaSchematicHookResolver(JavaPlugin plugin) {
        this.internal = HookBootstrap.internalHook;
        this.fawe = HookBootstrap.faweHook.isAvailable() ? HookBootstrap.faweHook : null;
        this.we = HookBootstrap.worldEditHook.isAvailable() ? HookBootstrap.worldEditHook : null;
    }

    public SchematicHook resolve(SchematicPlugin requested) {
        return switch (requested) {
            case WORLD_EDIT -> fawe != null ? fawe : we != null ? we : internal;
            case INTERNAL -> internal;
            case UNKNOWN -> fawe != null ? fawe : we != null ? we : internal;
        };
    }
}
