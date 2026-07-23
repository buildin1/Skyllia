package fr.euphyllia.skyllia.cache;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.skyblock.model.WarpIsland;
import fr.euphyllia.skyllia.api.utils.ExpiringValue;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * In-memory cache for island data, implementing the
 * <em>stale-while-revalidate</em> pattern.
 * <p>
 * Each entry has two lifetimes:
 * <ul>
 *   <li><b>Freshness window</b> (the configured TTL): the entry is served as-is.</li>
 *   <li><b>Grace window</b> ({@link #STALE_GRACE_SECONDS} past the TTL): the entry
 *       is <em>stale</em> — it is still served to the caller so that tick threads
 *       (event listeners, PlaceholderAPI resolution) are never blocked on a JDBC
 *       round-trip, and a background refresh is scheduled on {@link #asyncExecutor}.
 *       Concurrent refreshes of the same entry are deduplicated.</li>
 * </ul>
 * Past the grace window the entry is treated as a miss: the caller falls back to
 * its synchronous load path, exactly like the pre-SWR behavior. With the default
 * TTL of {@code -1} (never expire) entries are never stale and this class behaves
 * identically to the previous implementation.
 */
public class SkyblockCache {

    /**
     * How long (seconds) a stale entry may still be served after its TTL while a
     * background refresh runs. Only relevant when an admin configures finite TTLs
     * in {@code config.toml}; the default TTL of -1 never becomes stale.
     */
    public static final long STALE_GRACE_SECONDS = 600;

    /*
     * Refresh-deduplication domains. Each cached data type gets its own domain
     * so that a {@link RefreshKey} of (domain, key) uniquely identifies one
     * logical cache entry: the same island UUID can be refreshing its members
     * and its warps at the same time without the two reloads deduplicating
     * against each other. These constants are public because SkyblockManager's
     * cache-only accessors build RefreshKeys with them on cold misses.
     */
    public static final String DOMAIN_ISLAND = "island";
    public static final String DOMAIN_PLAYER_LINK = "playerLink";
    public static final String DOMAIN_OWNER = "owner";
    public static final String DOMAIN_MEMBERS = "members";
    public static final String DOMAIN_BANNED = "banned";
    public static final String DOMAIN_WARPS = "warps";
    public static final String DOMAIN_WARP = "warp";
    public static final String DOMAIN_STATE = "state";
    public static final String DOMAIN_NAME = "islandName";
    public static final String DOMAIN_DESCRIPTION = "islandDescription";

    private static final Logger LOGGER = LogManager.getLogger(SkyblockCache.class);

    /**
     * Island objects by island id — the primary cache, populated by every island load path.
     */
    private final ConcurrentHashMap<UUID, ExpiringValue<Island>> islandById = new ConcurrentHashMap<>();
    /**
     * Player UUID → island UUID link. Shared by both "island the player owns"
     * and "island the player is a member of" lookups: a player belongs to at
     * most one island, so the two resolve to the same id.
     */
    private final ConcurrentHashMap<UUID, ExpiringValue<UUID>> islandIdByPlayer = new ConcurrentHashMap<>();

    /**
     * Island owner by island id. Populated alongside {@link #roleByMember} (owner role).
     */
    private final ConcurrentHashMap<UUID, ExpiringValue<Players>> ownerByIsland = new ConcurrentHashMap<>();
    /**
     * Full member list by island id. The list stored is always an immutable copy.
     */
    private final ConcurrentHashMap<UUID, ExpiringValue<List<Players>>> membersByIsland = new ConcurrentHashMap<>();
    /**
     * Banned-player list by island id. The list stored is always an immutable copy.
     */
    private final ConcurrentHashMap<UUID, ExpiringValue<List<Players>>> bannedByIsland = new ConcurrentHashMap<>();
    /**
     * Role of a single player on a single island — derived data, repopulated as
     * a side effect of {@link #putMembers}, {@link #putOwner} and
     * {@link #putBanned}. {@link RoleType#VISITOR} entries act as a negative
     * cache ("this player is not a member"), which is why lookups that miss the
     * member list still write a VISITOR role instead of writing nothing.
     */
    private final ConcurrentHashMap<MemberKey, ExpiringValue<RoleType>> roleByMember = new ConcurrentHashMap<>();
    /**
     * Same as {@link #roleByMember} but keyed by last-known player name (lowercased by callers).
     */
    private final ConcurrentHashMap<MemberNameKey, ExpiringValue<RoleType>> roleByMemberName = new ConcurrentHashMap<>();

    /**
     * Full warp list by island id. Kept in sync with {@link #warpByKey} by {@link #putWarps}.
     */
    private final ConcurrentHashMap<UUID, ExpiringValue<List<WarpIsland>>> warpsByIsland = new ConcurrentHashMap<>();
    /**
     * Single warp by (island id, lowercased warp name). Denormalized from
     * {@link #warpsByIsland} so the hot path (resolving one warp for a
     * teleport or a spawn lookup) does not scan a list.
     */
    private final ConcurrentHashMap<WarpKey, ExpiringValue<WarpIsland>> warpByKey = new ConcurrentHashMap<>();

    /**
     * Aggregated island state (disabled / private / locked / max members /
     * size) by island id. Grouped in one snapshot because the four flags are
     * always loaded together (one DB pass in
     * {@code SkyblockManager#loadStateSnapshotFromDb}) and consumed by
     * per-event permission checks that would otherwise trigger four lookups.
     */
    private final ConcurrentHashMap<UUID, ExpiringValue<IslandStateSnapshot>> stateByIsland = new ConcurrentHashMap<>();

    /**
     * Custom island display name by island id (stored as custom data in the DB).
     */
    private final ConcurrentHashMap<UUID, ExpiringValue<String>> islandNameById = new ConcurrentHashMap<>();
    /**
     * Custom island description by island id (stored as custom data in the DB).
     */
    private final ConcurrentHashMap<UUID, ExpiringValue<String>> islandDescriptionById = new ConcurrentHashMap<>();

    /**
     * Keys of cache entries currently being refreshed in the background,
     * so that a stale entry read by 200 players in the same tick triggers
     * exactly one reload instead of 200.
     */
    private final Set<RefreshKey> inFlightRefreshes = ConcurrentHashMap.newKeySet();

    /**
     * Executor used for background refreshes. Must never run tasks on a tick
     * thread; the plugin wires Paper's async scheduler here so refresh tasks
     * are cancelled with the plugin on disable.
     */
    private final Executor asyncExecutor;

    /**
     * @param asyncExecutor executor for background cache refreshes; must execute
     *                      tasks off the server tick threads.
     */
    public SkyblockCache(Executor asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * Central write path: interprets the configured TTL the same way for every
     * data type. {@code ttl < 0} → the entry never expires (default config);
     * {@code ttl == 0} → caching disabled for this type, any existing entry is
     * evicted; {@code ttl > 0} → the entry is fresh for {@code ttl} seconds,
     * then serveable-but-stale for {@link #STALE_GRACE_SECONDS} more, then a miss.
     */
    private static <K, V> void put(ConcurrentHashMap<K, ExpiringValue<V>> map, K key, V value, long ttlSec) {
        if (ttlSec < 0) {
            map.put(key, ExpiringValue.neverExpire(value));
            return;
        }

        if (ttlSec == 0) {
            map.remove(key);
            return;
        }

        map.put(key, ExpiringValue.withGrace(value, ttlSec, STALE_GRACE_SECONDS, TimeUnit.SECONDS));
    }

    /**
     * Serves an entry with stale-while-revalidate semantics.
     *
     * @return the cached value (fresh or stale), or {@code null} on a miss
     * (absent or hard-expired) — the caller then performs its synchronous load.
     */
    private <K, V> @Nullable V serve(ConcurrentHashMap<K, ExpiringValue<V>> map,
                                     K key,
                                     String domain,
                                     @Nullable Runnable reload) {
        ExpiringValue<V> ev = map.get(key);
        if (ev == null) return null;

        if (ev.isExpired()) {
            map.remove(key, ev);
            return null;
        }

        if (reload != null && ev.isStale()) {
            refreshAsync(new RefreshKey(domain, key), reload);
        }

        return ev.get();
    }

    /**
     * Schedules {@code reload} on the async executor, unless a refresh for the
     * same key is already in flight. The reload is expected to write its result
     * back into this cache via the relevant {@code put*} method; failures are
     * logged and the in-flight marker is always released.
     */
    public void refreshAsync(RefreshKey key, Runnable reload) {
        if (!inFlightRefreshes.add(key)) return;

        try {
            asyncExecutor.execute(() -> {
                try {
                    reload.run();
                } catch (Throwable t) {
                    LOGGER.warn("Background cache refresh failed for {}", key, t);
                } finally {
                    inFlightRefreshes.remove(key);
                }
            });
        } catch (Throwable t) {
            // Executor rejected the task (e.g. plugin shutting down): release the
            // marker so a later attempt can retry, and keep serving the stale value.
            inFlightRefreshes.remove(key);
            LOGGER.debug("Could not schedule background cache refresh for {}", key, t);
        }
    }

    public @Nullable Island getIsland(UUID islandId) {
        return getIsland(islandId, null);
    }

    public @Nullable Island getIsland(UUID islandId, @Nullable Runnable reload) {
        return serve(islandById, islandId, DOMAIN_ISLAND, reload);
    }

    public void putIsland(Island island) {
        put(islandById, island.getId(), island, ConfigLoader.general.getCacheTtlSettings().island());
    }

    public @Nullable UUID getIslandIdByPlayer(UUID playerId) {
        return getIslandIdByPlayer(playerId, null);
    }

    public @Nullable UUID getIslandIdByPlayer(UUID playerId, @Nullable Runnable reload) {
        return serve(islandIdByPlayer, playerId, DOMAIN_PLAYER_LINK, reload);
    }

    public void putIslandIdByPlayer(UUID playerId, UUID islandId) {
        put(islandIdByPlayer, playerId, islandId, ConfigLoader.general.getCacheTtlSettings().playerLink());
    }

    public @Nullable Players getOwner(UUID islandId) {
        return getOwner(islandId, null);
    }

    public @Nullable Players getOwner(UUID islandId, @Nullable Runnable reload) {
        return serve(ownerByIsland, islandId, DOMAIN_OWNER, reload);
    }

    public void putOwner(UUID islandId, Players owner) {
        put(ownerByIsland, islandId, owner, ConfigLoader.general.getCacheTtlSettings().members());
        putRole(islandId, owner.getMojangId(), RoleType.OWNER);
    }

    public @Nullable List<Players> getMembers(UUID islandId) {
        return getMembers(islandId, null);
    }

    public @Nullable List<Players> getMembers(UUID islandId, @Nullable Runnable reload) {
        return serve(membersByIsland, islandId, DOMAIN_MEMBERS, reload);
    }

    public void putMembers(UUID islandId, List<Players> members) {
        List<Players> copy = List.copyOf(members);
        put(membersByIsland, islandId, copy, ConfigLoader.general.getCacheTtlSettings().members());
        for (Players p : copy) {
            putRole(islandId, p.getMojangId(), p.getRoleType());
            if (p.getLastKnowName() != null) {
                putRoleByName(islandId, p.getLastKnowName(), p.getRoleType());
            }
        }
    }

    public @Nullable List<Players> getBanned(UUID islandId) {
        return getBanned(islandId, null);
    }

    public @Nullable List<Players> getBanned(UUID islandId, @Nullable Runnable reload) {
        return serve(bannedByIsland, islandId, DOMAIN_BANNED, reload);
    }

    public void putBanned(UUID islandId, List<Players> banned) {
        List<Players> copy = List.copyOf(banned);
        put(bannedByIsland, islandId, copy, ConfigLoader.general.getCacheTtlSettings().members());
        for (Players p : copy) {
            putRole(islandId, p.getMojangId(), RoleType.BAN);
        }
    }

    public @Nullable RoleType getRole(UUID islandId, UUID playerId) {
        // Derived data: repopulated by member reloads, no dedicated refresh path.
        return serve(roleByMember, new MemberKey(islandId, playerId), "role", null);
    }

    public void putRole(UUID islandId, UUID playerId, RoleType role) {
        put(roleByMember, new MemberKey(islandId, playerId), role, ConfigLoader.general.getCacheTtlSettings().role());
    }

    public @Nullable RoleType getRoleByName(UUID islandId, String nameLower) {
        return serve(roleByMemberName, new MemberNameKey(islandId, nameLower), "roleByName", null);
    }

    public void putRoleByName(UUID islandId, String nameLower, RoleType role) {
        put(roleByMemberName, new MemberNameKey(islandId, nameLower), role, ConfigLoader.general.getCacheTtlSettings().nameRole());
    }

    public @Nullable List<WarpIsland> getWarps(UUID islandId) {
        return getWarps(islandId, null);
    }

    public @Nullable List<WarpIsland> getWarps(UUID islandId, @Nullable Runnable reload) {
        return serve(warpsByIsland, islandId, DOMAIN_WARPS, reload);
    }

    public void putWarps(UUID islandId, List<WarpIsland> warps) {
        put(warpsByIsland, islandId, List.copyOf(warps), ConfigLoader.general.getCacheTtlSettings().warps());
        for (WarpIsland w : warps) {
            put(warpByKey, new WarpKey(islandId, w.warpName().toLowerCase(Locale.ROOT)), w, ConfigLoader.general.getCacheTtlSettings().warps());
        }
    }

    public @Nullable WarpIsland getWarp(UUID islandId, String name) {
        return getWarp(islandId, name, null);
    }

    public @Nullable WarpIsland getWarp(UUID islandId, String name, @Nullable Runnable reload) {
        return serve(warpByKey, new WarpKey(islandId, name.toLowerCase(Locale.ROOT)), DOMAIN_WARP, reload);
    }

    public void putWarp(UUID islandId, String name, WarpIsland warp) {
        put(warpByKey, new WarpKey(islandId, name.toLowerCase(Locale.ROOT)), warp, ConfigLoader.general.getCacheTtlSettings().warps());
    }

    public @Nullable IslandStateSnapshot getState(UUID islandId) {
        return getState(islandId, null);
    }

    public @Nullable IslandStateSnapshot getState(UUID islandId, @Nullable Runnable reload) {
        return serve(stateByIsland, islandId, DOMAIN_STATE, reload);
    }

    public void putState(UUID islandId, IslandStateSnapshot state) {
        put(stateByIsland, islandId, state, ConfigLoader.general.getCacheTtlSettings().state());
    }

    public @Nullable String getIslandName(UUID islandId) {
        return getIslandName(islandId, null);
    }

    public @Nullable String getIslandName(UUID islandId, @Nullable Runnable reload) {
        return serve(islandNameById, islandId, DOMAIN_NAME, reload);
    }

    public void putIslandName(UUID islandId, String name) {
        put(islandNameById, islandId, name, ConfigLoader.general.getCacheTtlSettings().islandName());
    }

    public @Nullable String getIslandDescription(UUID islandId) {
        return getIslandDescription(islandId, null);
    }

    public @Nullable String getIslandDescription(UUID islandId, @Nullable Runnable reload) {
        return serve(islandDescriptionById, islandId, DOMAIN_DESCRIPTION, reload);
    }

    public void putIslandDescription(UUID islandId, String description) {
        put(islandDescriptionById, islandId, description, ConfigLoader.general.getCacheTtlSettings().islandDescription());
    }

    /**
     * Evicts everything known about an island except member/role data:
     * the island object, its state, owner, member and banned lists, and all
     * its warps. Called on structural changes (creation, deletion, resize)
     * where any of these could have changed. Member roles are handled by
     * {@link #invalidateMembers(UUID)}, which the relevant write paths call
     * separately.
     */
    public void invalidateIsland(UUID islandId) {
        islandById.remove(islandId);
        stateByIsland.remove(islandId);
        ownerByIsland.remove(islandId);
        membersByIsland.remove(islandId);
        bannedByIsland.remove(islandId);
        warpsByIsland.remove(islandId);

        warpByKey.keySet().removeIf(k -> k.islandId.equals(islandId));
    }

    /**
     * Evicts all member-related data for an island: owner, member and banned
     * lists, and every derived per-player role entry (by UUID and by name).
     * Called after any membership write (add, kick, promote, demote, ban…) so
     * the next read rebuilds a consistent view in one pass.
     */
    public void invalidateMembers(UUID islandId) {
        ownerByIsland.remove(islandId);
        membersByIsland.remove(islandId);
        bannedByIsland.remove(islandId);
        roleByMember.keySet().removeIf(k -> k.islandId.equals(islandId));
        roleByMemberName.keySet().removeIf(k -> k.islandId().equals(islandId));
    }

    /**
     * Evicts an island's warp list and every denormalized per-name warp entry.
     * Called after a warp is added, moved or deleted.
     */
    public void invalidateWarps(UUID islandId) {
        warpsByIsland.remove(islandId);
        warpByKey.keySet().removeIf(k -> k.islandId.equals(islandId));
    }

    /**
     * Evicts a player's player→island link. Called when a player joins or
     * leaves an island, so the next lookup re-resolves against the database.
     */
    public void invalidatePlayerLink(UUID playerId) {
        islandIdByPlayer.remove(playerId);
    }

    /**
     * Evicts an island's state snapshot. Called after any single state flag is
     * written (disable, privacy, lock, max members, size) — the whole snapshot
     * is rebuilt on the next read since its fields are loaded together anyway.
     */
    public void invalidateState(UUID islandId) {
        stateByIsland.remove(islandId);
    }

    /**
     * Evicts an island's custom display name. Called when the name is removed;
     * name updates write the new value directly instead.
     */
    public void invalidateIslandName(UUID id) {
        islandNameById.remove(id);
    }

    /**
     * Evicts an island's custom description. Called when the description is
     * removed; description updates write the new value directly instead.
     */
    public void invalidateIslandDescription(UUID id) {
        islandDescriptionById.remove(id);
    }

    /**
     * Deduplication key for background refreshes: one refresh at a time per
     * (domain, cache key) pair.
     */
    public record RefreshKey(String domain, Object key) {
    }

    /**
     * Composite key: role of one player (by UUID) on one island.
     */
    private record MemberKey(UUID islandId, UUID playerId) {
    }

    /**
     * Composite key: role of one player (by lowercased last-known name) on one island.
     */
    private record MemberNameKey(UUID islandId, String nameLower) {
    }

    /**
     * Composite key: one warp (by lowercased name) on one island.
     */
    private record WarpKey(UUID islandId, String nameLower) {
    }

    /**
     * Immutable aggregate of the island flags that per-event checks consume
     * together. Loaded in one database pass and cached as a unit — see
     * {@code SkyblockManager#loadStateSnapshotFromDb}.
     *
     * @param disabled   whether the island is disabled (soft-deleted or frozen by an admin)
     * @param priv       whether the island is private (visitors denied)
     * @param locked     whether the island is locked (true while a deletion is in progress)
     * @param maxMembers the maximum number of members allowed
     * @param size       the island size (radius in blocks) at snapshot time
     */
    public record IslandStateSnapshot(boolean disabled, boolean priv, boolean locked, int maxMembers, double size) {
    }
}
