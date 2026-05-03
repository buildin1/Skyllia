package fr.euphyllia.skyllia.cache;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.skyblock.model.WarpIsland;
import fr.euphyllia.skyllia.api.utils.ExpiringValue;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class SkyblockCache {

    private final ConcurrentHashMap<UUID, ExpiringValue<Island>> islandById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ExpiringValue<UUID>> islandIdByPlayer = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, ExpiringValue<Players>> ownerByIsland = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ExpiringValue<List<Players>>> membersByIsland = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ExpiringValue<List<Players>>> bannedByIsland = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MemberKey, ExpiringValue<RoleType>> roleByMember = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MemberNameKey, ExpiringValue<RoleType>> roleByMemberName = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, ExpiringValue<List<WarpIsland>>> warpsByIsland = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WarpKey, ExpiringValue<WarpIsland>> warpByKey = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, ExpiringValue<IslandStateSnapshot>> stateByIsland = new ConcurrentHashMap<>();

    private static <K, V> @Nullable V getIfValid(ConcurrentHashMap<K, ExpiringValue<V>> map, K key) {
        ExpiringValue<V> ev = map.get(key);
        if (ev == null) return null;

        if (ev.isExpired()) {
            map.remove(key, ev);
            return null;
        }

        return ev.get();
    }

    private static <K, V> void put(ConcurrentHashMap<K, ExpiringValue<V>> map, K key, V value, long ttlSec) {
        if (ttlSec < 0) {
            map.put(key, ExpiringValue.neverExpire(value));
            return;
        }

        if (ttlSec == 0) {
            map.remove(key);
            return;
        }

        map.put(key, ExpiringValue.of(value, ttlSec, TimeUnit.SECONDS));
    }

    public @Nullable Island getIsland(UUID islandId) {
        return getIfValid(islandById, islandId);
    }

    public void putIsland(Island island) {
        put(islandById, island.getId(), island, ConfigLoader.general.getCacheTtlIsland());
    }

    public @Nullable UUID getIslandIdByPlayer(UUID playerId) {
        return getIfValid(islandIdByPlayer, playerId);
    }

    public void putIslandIdByPlayer(UUID playerId, UUID islandId) {
        put(islandIdByPlayer, playerId, islandId, ConfigLoader.general.getCacheTtlPlayerLink());
    }

    public @Nullable Players getOwner(UUID islandId) {
        return getIfValid(ownerByIsland, islandId);
    }

    public void putOwner(UUID islandId, Players owner) {
        put(ownerByIsland, islandId, owner,  ConfigLoader.general.getCacheTtlMembers());
        putRole(islandId, owner.getMojangId(), RoleType.OWNER);
    }

    public @Nullable List<Players> getMembers(UUID islandId) {
        return getIfValid(membersByIsland, islandId);
    }

    public void putMembers(UUID islandId, List<Players> members) {
        List<Players> copy = List.copyOf(members);
        put(membersByIsland, islandId, copy,  ConfigLoader.general.getCacheTtlMembers());
        for (Players p : copy) {
            putRole(islandId, p.getMojangId(), p.getRoleType());
            if (p.getLastKnowName() != null) {
                putRoleByName(islandId, p.getLastKnowName(), p.getRoleType());
            }
        }
    }

    public @Nullable List<Players> getBanned(UUID islandId) {
        return getIfValid(bannedByIsland, islandId);
    }

    public void putBanned(UUID islandId, List<Players> banned) {
        List<Players> copy = List.copyOf(banned);
        put(bannedByIsland, islandId, copy,  ConfigLoader.general.getCacheTtlMembers());
        for (Players p : copy) {
            putRole(islandId, p.getMojangId(), RoleType.BAN);
        }
    }

    public @Nullable RoleType getRole(UUID islandId, UUID playerId) {
        return getIfValid(roleByMember, new MemberKey(islandId, playerId));
    }

    public void putRole(UUID islandId, UUID playerId, RoleType role) {
        put(roleByMember, new MemberKey(islandId, playerId), role, ConfigLoader.general.getCacheTtlRole());
    }

    public @Nullable RoleType getRoleByName(UUID islandId, String nameLower) {
        return getIfValid(roleByMemberName, new MemberNameKey(islandId, nameLower));
    }

    public void putRoleByName(UUID islandId, String nameLower, RoleType role) {
        put(roleByMemberName, new MemberNameKey(islandId, nameLower), role, ConfigLoader.general.getCacheTtlNameRole());
    }

    public @Nullable List<WarpIsland> getWarps(UUID islandId) {
        return getIfValid(warpsByIsland, islandId);
    }

    public void putWarps(UUID islandId, List<WarpIsland> warps) {
        put(warpsByIsland, islandId, List.copyOf(warps), ConfigLoader.general.getCacheTtlWarps());
        for (WarpIsland w : warps) {
            put(warpByKey, new WarpKey(islandId, w.warpName().toLowerCase(Locale.ROOT)), w, ConfigLoader.general.getCacheTtlWarps());
        }
    }

    public @Nullable WarpIsland getWarp(UUID islandId, String name) {
        return getIfValid(warpByKey, new WarpKey(islandId, name.toLowerCase(Locale.ROOT)));
    }

    public void putWarp(UUID islandId, String name, WarpIsland warp) {
        put(warpByKey, new WarpKey(islandId, name.toLowerCase(Locale.ROOT)), warp, ConfigLoader.general.getCacheTtlWarps());
    }

    public @Nullable IslandStateSnapshot getState(UUID islandId) {
        return getIfValid(stateByIsland, islandId);
    }

    public void putState(UUID islandId, IslandStateSnapshot state) {
        put(stateByIsland, islandId, state, ConfigLoader.general.getCacheTtlState());
    }

    public void invalidateIsland(UUID islandId) {
        islandById.remove(islandId);
        stateByIsland.remove(islandId);
        ownerByIsland.remove(islandId);
        membersByIsland.remove(islandId);
        bannedByIsland.remove(islandId);
        warpsByIsland.remove(islandId);

        warpByKey.keySet().removeIf(k -> k.islandId.equals(islandId));
    }

    public void invalidateMembers(UUID islandId) {
        ownerByIsland.remove(islandId);
        membersByIsland.remove(islandId);
        bannedByIsland.remove(islandId);
        roleByMember.keySet().removeIf(k -> k.islandId.equals(islandId));
        roleByMemberName.keySet().removeIf(k -> k.islandId().equals(islandId));
    }

    public void invalidateWarps(UUID islandId) {
        warpsByIsland.remove(islandId);
        warpByKey.keySet().removeIf(k -> k.islandId.equals(islandId));
    }

    public void invalidatePlayerLink(UUID playerId) {
        islandIdByPlayer.remove(playerId);
    }

    public void invalidateState(UUID islandId) {
        stateByIsland.remove(islandId);
    }

    private record MemberKey(UUID islandId, UUID playerId) {
    }

    private record MemberNameKey(UUID islandId, String nameLower) {
    }

    private record WarpKey(UUID islandId, String nameLower) {
    }

    public record IslandStateSnapshot(boolean disabled, boolean priv, boolean locked, int maxMembers, double size) {
    }
}
