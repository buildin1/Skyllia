package fr.euphyllia.skyllia.papi.handlers;

import fr.euphyllia.skyllia.api.skyblock.Island;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Contract for a single-responsibility placeholder handler.
 * <p>
 * Each implementation handles one prefix group (e.g. {@code island_},
 * {@code banned_}) and is registered in {@link fr.euphyllia.skyllia.papi.SkylliaExpansion}.
 * <p>
 * The {@code key} passed to {@link #handle} is the portion of the placeholder
 * that comes <em>after</em> the prefix has been stripped by the router.
 */
public interface PlaceholderHandler {

    /**
     * Returns the prefix this handler is responsible for (without trailing underscore).
     * <p>
     * Example: {@code "island"} matches placeholders starting with {@code island_}.
     */
    @NotNull String prefix();

    /**
     * Indicates whether this handler requires the requesting player to have
     * an island.
     * <p>
     * If {@code true}, the router will not invoke this handler when the player
     * has no island and will return an empty string instead. If {@code false},
     * the handler is invoked regardless and must accept a {@code null} island.
     *
     * @return {@code true} by default — override to allow handling without an island
     */
    default boolean requiresIsland() {
        return true;
    }

    /**
     * Processes the placeholder and returns the resolved value.
     *
     * @param player the requesting player (maybe offline)
     * @param island the island associated with the player; never {@code null}
     *               when {@link #requiresIsland()} returns {@code true},
     *               otherwise may be {@code null} when the player has no island
     * @param key    the placeholder key with the prefix already stripped
     * @return the resolved string value, or {@code null} if the key is unrecognized
     */
    @Nullable String handle(@NotNull OfflinePlayer player, @Nullable Island island, @NotNull String key);
}
