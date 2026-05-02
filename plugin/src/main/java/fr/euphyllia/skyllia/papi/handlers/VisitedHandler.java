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
 * Handles {@code %skyllia_visited_*%} placeholders based on player's current location.
 *
 * <p>Requires the player to be online; returns empty string for offline players.
 *
 * <p>Available placeholders:
 * <ul>
 *   <li>{@code visited_id} – UUID of the island at player's current location</li>
 *   <li>{@code visited_owner_name} – Owner name of that island</li>
 *   <li>{@code on_island} – {@code true} if player is considered part of that island
 *       (role is OWNER/CO_OWNER/MODERATOR/MEMBER or player is trusted), otherwise {@code false}</li>
 * </ul>
 */
public class VisitedHandler implements PlaceholderHandler {

    private final Logger logger = LogManager.getLogger(this);

    @Override
    public @NotNull String prefix() {
        return "visited";
    }

    @Override
    public @Nullable String handle(@NotNull OfflinePlayer offlinePlayer, @NotNull Island ignored, @NotNull String key) {
        // Must be online to get location
        //logger.info("Handle PAPI: player={}, key={}", offlinePlayer.getName(), key);
        if (!offlinePlayer.isOnline()) return "false";
        //logger.info("player={} is online", offlinePlayer.getName());
        Player player = offlinePlayer.getPlayer();
        if (player == null) return "";
        //logger.info("player={} is not null", offlinePlayer.getName());

        int chunkX = (int)player.getX()>>4;
        int chunkZ = (int)player.getZ()>>4;
        Position position = RegionHelper.getRegionFromChunk(chunkX, chunkZ);
        //logger.info("player={}, ChunkXZ={} {}", offlinePlayer.getName(), chunkX, chunkZ);
        Island islandAtLoc = SkylliaAPI.getIslandByPosition(position);
        //logger.info("player={} visited island found! Owner={}", offlinePlayer.getName(), islandAtLoc.getOwner());
        switch (key) {
            case "id": {
                if (islandAtLoc == null) return "";
                return islandAtLoc.getId().toString();
            }
            case "owner_name": {
                if (islandAtLoc == null) return "";
                var owner = islandAtLoc.getOwner();
                return owner != null ? owner.getLastKnowName() : "";
            }
            case "on_island" :{
                if (islandAtLoc == null) return "false";
                return String.valueOf(isPlayerPartOfIsland(offlinePlayer, islandAtLoc));
            }
            default: return null;
        }
    }

    /**
     * Checks whether the player is considered "on their own island" for the given island.
     * True if the player's role on that island is OWNER, CO_OWNER, MODERATOR, MEMBER,
     * OR the player is trusted on that island.
     */
    private boolean isPlayerPartOfIsland(OfflinePlayer player, Island island) {
        UUID playerId = player.getUniqueId();

        // Check member role
        var member = island.getMember(playerId);
        RoleType role = member != null ? member.getRoleType() : RoleType.VISITOR;

        if (role == RoleType.OWNER ||
                role == RoleType.CO_OWNER ||
                role == RoleType.MODERATOR ||
                role == RoleType.MEMBER) {
            return true;
        }

        // Check trust
        return Skyllia.getInstance().getInterneAPI().getTrustService().isTrusted(island.getId(), playerId);
    }
}