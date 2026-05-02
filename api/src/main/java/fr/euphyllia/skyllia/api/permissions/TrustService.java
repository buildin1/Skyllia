package fr.euphyllia.skyllia.api.permissions;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TrustService {

    private static final Logger log = LoggerFactory.getLogger(TrustService.class);
    private final ConcurrentHashMap<UUID, Set<UUID>> trustedByIsland = new ConcurrentHashMap<>();

    public boolean addTrusted(UUID islandId, UUID playerId) {
        boolean added = trustedByIsland
                .computeIfAbsent(islandId, __ -> ConcurrentHashMap.newKeySet())
                .add(playerId);
//        log.info("[Trust] addTrusted called: island={}, player={}, added={}", islandId, playerId, added);
//        if (added) {
//            log.info("[Trust] Current trusted set for island {}: {}", islandId, trustedByIsland.get(islandId));
//        }
        return added;
    }

    public boolean removeTrusted(UUID islandId, UUID playerId) {
        Set<UUID> set = trustedByIsland.get(islandId);
        if (set == null) {
            //log.info("[Trust] removeTrusted: no set for island {}", islandId);
            return false;
        }
        boolean removed = set.remove(playerId);
        if (set.isEmpty()) trustedByIsland.remove(islandId, set);
        //log.info("[Trust] removeTrusted: island={}, player={}, removed={}", islandId, playerId, removed);
        return removed;
    }

    public boolean isTrusted(UUID islandId, UUID playerId) {
        Set<UUID> set = trustedByIsland.get(islandId);
        boolean trusted = set != null && set.contains(playerId);
//        log.info("[Trust] isTrusted called: island={}, player={}, trusted={}, setSize={}",
//                islandId, playerId, trusted, set == null ? 0 : set.size());
        return trusted;
    }

    public @Nullable Set<UUID> getTrusted(UUID islandId) {
        Set<UUID> set = trustedByIsland.get(islandId);
//        log.info("[Trust] getTrusted: island={}, set={}", islandId, set);
        return set != null ? Set.copyOf(set) : null;
    }

    public void clearIsland(UUID islandId) {
        trustedByIsland.remove(islandId);
//        log.info("[Trust] clearIsland: island={}", islandId);
    }

    public void clearAll() {
        trustedByIsland.clear();
//        log.info("[Trust] clearAll called");
    }
}