package fr.euphyllia.skyllia.hook;

import fr.euphyllia.skyllia.api.hooks.SchematicHook;
import fr.euphyllia.skyllia.api.skyblock.model.SchematicPlugin;
import fr.euphyllia.skyllia.hook.fastasyncworldedit.FAWESchematicHook;
import fr.euphyllia.skyllia.hook.internal.InternalSchematicHook;
import fr.euphyllia.skyllia.hook.worldedit.WorldEditSchematicHook;
import org.bukkit.plugin.java.JavaPlugin;

public class SkylliaSchematicHookResolver {

    private final FAWESchematicHook fawe;
    private final WorldEditSchematicHook we;
    private final InternalSchematicHook internal;

    public SkylliaSchematicHookResolver(JavaPlugin plugin) {
        this.fawe = new FAWESchematicHook(plugin);
        this.we = new WorldEditSchematicHook(plugin);
        this.internal = new InternalSchematicHook(plugin);
    }

    public SchematicHook resolve(SchematicPlugin requested) {
        return switch (requested) {
            case WORLD_EDIT -> fawe.isAvailable() ? fawe : we.isAvailable() ? we : internal;
            case INTERNAL -> internal;
            case UNKNOWN -> fawe.isAvailable() ? fawe : we.isAvailable() ? we : internal;
        };
    }
}
