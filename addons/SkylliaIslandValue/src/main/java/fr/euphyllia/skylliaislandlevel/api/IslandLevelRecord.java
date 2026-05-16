package fr.euphyllia.skylliaislandlevel.api;

import java.util.UUID;

/**
 * Holds the level snapshot of one island for top-list purposes.
 *
 * @param islandId UUID of the island
 * @param score    Raw weighted block score
 * @param level    Computed level
 */
public record IslandLevelRecord(UUID islandId, double score, long level) {
}