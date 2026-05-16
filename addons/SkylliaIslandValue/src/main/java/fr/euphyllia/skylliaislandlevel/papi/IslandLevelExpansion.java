package fr.euphyllia.skylliaislandlevel.papi;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skylliaislandlevel.SkylliaIslandLevel;
import fr.euphyllia.skylliaislandlevel.api.IslandLevelRecord;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class IslandLevelExpansion extends PlaceholderExpansion {

    @Override
    public @NotNull String getIdentifier() {
        return "islandlevel";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Euphyllia";
    }

    @Override
    public @NotNull String getVersion() {
        return SkylliaIslandLevel.getInstance().getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return List.of(
                "level",
                "score",
                "rank",
                "top_<n>_name",
                "top_<n>_level",
                "top_<n>_score"
        );
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || !player.hasPlayedBefore()) return "";

        String key = params.toLowerCase(Locale.ROOT);

        // --- Top-list placeholders ---
        if (key.startsWith("top_")) {
            return resolveTopPlaceholder(key);
        }

        UUID playerId = player.getUniqueId();
        Island island = SkylliaAPI.getIslandByPlayerId(playerId);
        if (island == null) return "";

        UUID islandId = island.getId();

        return switch (key) {
            case "level" -> String.valueOf(getCachedLevel(island, islandId));
            case "score" -> String.valueOf(getCachedScore(island, islandId));
            case "rank" -> {
                int rank = computeRank(islandId);
                yield rank > 0 ? String.valueOf(rank) : "";
            }
            default -> null;
        };
    }

    private long getCachedLevel(Island island, UUID islandId) {
        return SkylliaIslandLevel.getInstance().getPapiCache()
                .getLevelOrDefaultAndRefresh(
                        SkylliaIslandLevel.getInstance(),
                        islandId,
                        () -> loadFromDb(island),
                        SkylliaIslandLevel.getInstance().getLevelManager().getStoredLevel(island)
                );
    }

    private double getCachedScore(Island island, UUID islandId) {
        return SkylliaIslandLevel.getInstance().getPapiCache()
                .getScoreOrDefaultAndRefresh(
                        SkylliaIslandLevel.getInstance(),
                        islandId,
                        () -> loadFromDb(island),
                        SkylliaIslandLevel.getInstance().getLevelManager().getStoredScore(island)
                );
    }

    private double[] loadFromDb(Island island) {
        double score = SkylliaIslandLevel.getInstance().getLevelManager().getStoredScore(island);
        long level = SkylliaIslandLevel.getInstance().getLevelManager().getStoredLevel(island);
        return new double[]{score, (double) level};
    }

    private int computeRank(UUID islandId) {
        List<IslandLevelRecord> top = getCachedTop();
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).islandId().equals(islandId)) return i + 1;
        }
        return 0;
    }

    private @Nullable String resolveTopPlaceholder(String key) {
        String remainder = key.substring("top_".length());
        int underscore = remainder.indexOf('_');
        if (underscore <= 0) return null;

        int position;
        try {
            position = Integer.parseInt(remainder.substring(0, underscore));
        } catch (NumberFormatException e) {
            return null;
        }
        if (position <= 0) return "";

        String field = remainder.substring(underscore + 1);

        List<IslandLevelRecord> top = getCachedTop();
        if (position > top.size()) return "";

        IslandLevelRecord entry = top.get(position - 1);
        return switch (field) {
            case "name" -> resolveOwnerName(entry.islandId());
            case "level" -> String.valueOf(entry.level());
            case "score" -> String.valueOf(entry.score());
            default -> null;
        };
    }

    private String resolveOwnerName(UUID islandId) {
        Island island = SkylliaAPI.getIslandByIslandId(islandId);
        if (island == null) return "";
        Players owner = island.getOwner();
        if (owner == null) return "";
        String name = owner.getLastKnowName();
        return name != null ? name : "";
    }

    private List<IslandLevelRecord> getCachedTop() {
        return SkylliaIslandLevel.getInstance().getPapiCache()
                .getTopOrDefaultAndRefresh(
                        SkylliaIslandLevel.getInstance(),
                        () -> SkylliaIslandLevel.getInstance().getLevelManager().getTopIslands(),
                        List.of()
                );
    }

}
