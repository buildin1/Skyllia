package fr.euphyllia.skyllia.api.hooks;

import fr.euphyllia.skyllia.api.skyblock.model.SchematicSetting;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public interface SchematicHook {

    /**
     * Tests whether a class is present on the classpath.
     *
     * @param className fully qualified class name to look up.
     * @return {@code true} if the class can be loaded.
     */
    static boolean hasClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Human-readable name used in log messages.
     */
    String name();

    /**
     * Returns {@code true} when the required classes are present
     * and the hook can operate in the current environment.
     */
    boolean isAvailable();

    /**
     * Registers any event listeners or service bindings required by this hook.
     *
     * @param plugin the Skyllia {@link Plugin} instance to register against.
     */
    default void register(@NotNull Plugin plugin) {
    }

    /**
     * Called when Skyllia is disabled or reloaded. Override to clean up
     * resources (e.g. clear caches, cancel tasks).
     * No-op by default.
     */
    default void unregister() {
    }

    /**
     * Pastes a schematic at the given location according to the provided settings.
     *
     * @param loc      the target {@link Location} where the schematic will be pasted.
     * @param settings the {@link SchematicSetting} describing which file to paste and paste options.
     * @return a {@link CompletableFuture} completing with {@code true} on success, {@code false} otherwise.
     */
    CompletableFuture<Boolean> paste(@NotNull Location loc, @NotNull SchematicSetting settings);
}