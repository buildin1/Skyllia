package fr.euphyllia.skyllia.api.hooks;

import fr.euphyllia.skyllia.api.skyblock.model.SchematicSetting;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public interface SchematicHook {

    static boolean hasClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    String name();

    boolean isAvailable();

    CompletableFuture<Boolean> paste(@NotNull Location loc, @NotNull SchematicSetting settings);
}
