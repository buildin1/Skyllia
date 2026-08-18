package fr.euphyllia.skyllia.api.database;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.permissions.CompiledPermissions;
import fr.euphyllia.skyllia.api.permissions.FlagId;
import fr.euphyllia.skyllia.api.permissions.IslandFlagRegistry;
import fr.euphyllia.skyllia.api.permissions.IslandFlags;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.PermissionSet;
import fr.euphyllia.skyllia.api.permissions.PermissionSetCodec;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

public abstract class IslandPermissionQuery {

    /**
     * 条带锁，用于串行化「读整块位图 → 改 1 位 → 写回整块位图」这个非原子序列。
     * <p>
     * {@link #set} 与 {@link #setFlag} 都是读-改-写：先把整份位图从数据库读出来，
     * 翻转其中一位，再把<b>整份</b>写回去。GUI 的每一次点击都在
     * {@code Bukkit.getAsyncScheduler().runNow(...)} 上独立执行，因此岛主连续快速点开
     * 一排权限时，这些任务是真正并发的——后写回的整块位图会覆盖掉前一个刚翻转的位，
     * 造成部分勾选<b>静默丢失</b>。而 GUI 又是乐观刷新（直接把格子染绿并同步内存缓存），
     * 所以当场看不出问题，直到下次从数据库重新加载才暴露成「明明开了权限却不生效」。
     * </p>
     * <p>
     * 这里用固定 64 条带而不是「每个岛屿一把锁」的 Map，是为了避免锁对象随岛屿数量
     * 无限增长；不同岛屿偶尔共用一条带只会带来可忽略的竞争，不影响正确性。
     * 临界区只包含一次数据库读和一次数据库写，且调用方始终在异步线程上，
     * 不会阻塞任何 Folia region 线程。
     * </p>
     */
    private static final Object[] WRITE_LOCKS = createLocks(64);

    private static Object[] createLocks(int size) {
        Object[] locks = new Object[size];
        for (int i = 0; i < size; i++) locks[i] = new Object();
        return locks;
    }

    /**
     * 取岛屿对应的条带锁。用 {@code hash ^ (hash >>> 16)} 打散，避免 UUID 低位分布不均。
     */
    private static Object lockFor(UUID islandId) {
        int h = islandId.hashCode();
        h ^= (h >>> 16);
        return WRITE_LOCKS[Math.floorMod(h, WRITE_LOCKS.length)];
    }

    public abstract CompiledPermissions loadCompiled(UUID islandId, PermissionRegistry registry);

    /**
     * Load the island-wide flags for a given island.
     * Returns {@code null} if no flags are stored yet (caller will use an empty {@link IslandFlags}).
     */
    @Deprecated(forRemoval = true, since = "3.x")
    @ApiStatus.ScheduledForRemoval(inVersion = "4.x")
    public IslandFlags loadIslandFlags(UUID islandId, IslandFlagRegistry registry) {
        return this.loadIslandFlags(islandId, registry, SkylliaAPI.getRegisteredWorlds().getFirst().getWorldName());
    }

    public abstract IslandFlags loadIslandFlags(UUID islandId, IslandFlagRegistry registry, String worldName);

    /**
     * Persist the full flag bitset for an island.
     */
    @Deprecated(forRemoval = true, since = "3.x")
    @ApiStatus.ScheduledForRemoval(inVersion = "4.x")
    public boolean saveIslandFlags(UUID islandId, byte[] wordsBlob) {
        for (String world : SkylliaAPI.getRegisteredWorlds().stream().map(WorldConfig::getWorldName).toList()) {
            if (!saveIslandFlags(islandId, wordsBlob, world)) {
                return false;
            }
        }
        return true;
    }

    public abstract boolean saveIslandFlags(UUID islandId, byte[] wordsBlob, String worldName);

    /**
     * Convenience: flip a single flag and persist it.
     */
    public final boolean setFlag(UUID islandId, IslandFlagRegistry registry, FlagId id, boolean value) {
        for (String world : SkylliaAPI.getRegisteredWorlds().stream().map(WorldConfig::getWorldName).toList()) {
            if (!setFlag(islandId, registry, id, world, value)) {
                return false;
            }
        }
        return true;
    }

    public final boolean setFlag(UUID islandId, IslandFlagRegistry registry, FlagId id, String worldName, boolean value) {
        // 读-改-写必须整体串行，否则并发写会互相覆盖，见 WRITE_LOCKS 注释
        synchronized (lockFor(islandId)) {
            IslandFlags flags = loadIslandFlags(islandId, registry, worldName);
            if (flags == null) flags = new IslandFlags(registry);

            flags.set(registry, id, value);
            byte[] blob = PermissionSetCodec.encodeLongs(flags.snapshotWords());
            return saveIslandFlags(islandId, blob, worldName);
        }
    }

    /**
     * DB write only (impl can override if it wants, but default is fine)
     */
    public boolean set(UUID islandId, RoleType role, PermissionId id, boolean value) {
        return set(islandId, SkylliaAPI.getPermissionRegistry(), role, id, value);
    }

    public abstract boolean saveRole(UUID islandId, RoleType role, byte[] wordsBlob);

    public abstract boolean deleteRole(UUID islandId, RoleType role);

    /**
     * Shared logic: load -> flip bit -> save blob
     * (DB-only; no runtime cache update here)
     */
    public final boolean set(UUID islandId, PermissionRegistry registry, RoleType role, PermissionId id, boolean value) {
        // 读-改-写必须整体串行，否则并发写会互相覆盖，见 WRITE_LOCKS 注释
        synchronized (lockFor(islandId)) {
            CompiledPermissions compiled = loadCompiled(islandId, registry);
            if (compiled == null) return false;

            PermissionSet set = compiled.setFor(role);
            if (set == null) return false;

            set.set(id, value);
            byte[] blob = PermissionSetCodec.encodeLongs(set.snapshotWords());
            return saveRole(islandId, role, blob);
        }
    }
}
