package fr.euphyllia.skyllia.papi.handlers;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.model.Position;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

import static fr.euphyllia.skyllia.api.commands.SubCommandInterface.log;

/**
 * Handles location-aware placeholders that depend on the island the player
 * is <em>currently standing on</em>, rather than the island they own or
 * are a member of.
 *
 * <p>Unlike other handlers, these placeholders do not use the requesting
 * player's home island as their context — they resolve the island at the
 * player's current {@link Location}. They are typically used by other
 * plugins to perform conditional checks based on the player's whereabouts.
 *
 * <table>
 *   <caption>Available placeholders</caption>
 *   <tr><th>Placeholder</th><th>Returns</th></tr>
 *   <tr><td>is_on_island</td>
 *       <td>{@code true} if the player is currently standing on an island
 *           they are part of (member or trusted), {@code false} otherwise</td></tr>
 *   <tr><td>visited_id</td>
 *       <td>UUID of the island the player is currently visiting,
 *           or an empty string if not on an island</td></tr>
 *   <tr><td>visited_owner_name</td>
 *       <td>Last known name of the owner of the island the player is currently
 *           visiting, or an empty string if not on an island or if the owner
 *           cannot be resolved</td></tr>
 * </table>
 *
 * <p>This handler uses an empty prefix and is invoked by the router as a
 * fallback when no prefixed handler matches. It does not require the
 * requesting player to own an island, since visiting an island doesn't
 * imply ownership.
 *
 * <p>If the player is offline, all placeholders return an empty string
 * (or {@code "false"} for boolean ones).
 */
public class VisitedHandler implements PlaceholderHandler {

    /**
     * Resolves the island the given player is currently standing on,
     * regardless of any membership relation.
     *
     * @param player the online player
     * @return the island at the player's current location, or {@code null}
     * if the player is not on any Skyblock island
     */
    private static @Nullable Island visitedIsland(@NotNull Player player) {
        Location loc = player.getLocation();
        if (loc.getWorld() == null) return null;
        if (!SkylliaAPI.isWorldSkyblock(loc.getWorld())) return null;
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        return SkylliaAPI.getIslandByChunk(chunkX, chunkZ);
    }

    /**
     * Checks whether the given player is part of the visited island,
     * either as an official member (owner, co-owner, moderator, member)
     * or as a trusted player.
     *
     * @param visited  the island the player is currently on
     * @param playerId the UUID of the player
     * @return {@code true} if the player is a member or trusted on the island
     */
    private static boolean isPartOfIsland(@NotNull Island visited, @NotNull UUID playerId) {
        if (visited.getMember(playerId) != null) return true;
        TrustService trustService = SkylliaAPI.getTrustService();
        return trustService != null && trustService.isTrusted(visited.getId(), playerId);
    }
    private final Logger logger = LogManager.getLogger(this);

    @Override
    public @NotNull String prefix() {
        return "visited";
    }

    @Override
    public boolean requiresIsland() {
        return false;
    }

    @Override
    public @Nullable String handle(@NotNull OfflinePlayer player,
                                   @Nullable Island island,
                                   @NotNull String key) {
        if (!player.isOnline() || player.getPlayer() == null) {
            return switch (key) {
                case "is_on_island" -> "false";
                case "visited_id", "visited_owner_name" -> "";
                default -> null;
            };
        }

        Player online = player.getPlayer();
        Island visited = visitedIsland(online);

        return switch (key) {
            case "is_on_island" -> {
                if (visited == null) yield "false";
                yield String.valueOf(isPartOfIsland(visited, online.getUniqueId()));
            }
            case "visited_id" -> visited != null ? visited.getId().toString() : "";
            case "visited_owner_name" -> {
                if (visited == null) yield "";
                Players owner = visited.getOwner();
                yield owner != null ? owner.getLastKnowName() : "";
            }
            default -> null;
        };
    }
}