package fr.euphyllia.skyllia.managers.zone;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.zone.ActivityZone;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 管理活动区（{@link ActivityZone}）：商店、PVP 场地等管理员划定的区域。
 * <p>
 * 全量缓存在内存里（{@link #cache}），因为 {@link #findZoneAt} 会被方块破坏/放置/
 * 攻击这类高频事件调用，不能每次都查数据库。缓存只在管理员增删改活动区时才会
 * 重新整体加载一次，读多写少，足够简单可靠。
 * </p>
 */
public class ActivityZoneManager {

    private static final Logger log = LoggerFactory.getLogger(ActivityZoneManager.class);

    private final Skyllia plugin;
    private final List<ActivityZone> cache = new CopyOnWriteArrayList<>();

    public ActivityZoneManager(Skyllia plugin) {
        this.plugin = plugin;
    }

    /** 从数据库整体重新加载缓存。启动时、以及每次增删改后调用。 */
    public void loadAll() {
        List<ActivityZone> all = plugin.getInterneAPI().getIslandQuery().getActivityZoneDataQuery().getAll();
        cache.clear();
        cache.addAll(all);
        log.info("[SkylliaZone] 已加载 {} 个活动区", all.size());
    }

    public List<ActivityZone> getAll() {
        return List.copyOf(cache);
    }

    public Optional<ActivityZone> getByName(String name) {
        return cache.stream().filter(z -> z.name().equalsIgnoreCase(name)).findFirst();
    }

    /** 给执行层监听器用：某个方块坐标是否落在某个活动区的内容范围内（正方形）。 */
    public Optional<ActivityZone> findZoneAt(int blockX, int blockZ) {
        for (ActivityZone z : cache) {
            if (z.containsContent(blockX, blockZ)) return Optional.of(z);
        }
        return Optional.empty();
    }

    public boolean createZone(String name, int centerX, int centerZ,
                               double contentRadius, double bufferRadius, @Nullable UUID createdBy) {
        if (getByName(name).isPresent()) return false;
        boolean ok = plugin.getInterneAPI().getIslandQuery().getActivityZoneDataQuery()
                .insert(name, centerX, centerZ, contentRadius, bufferRadius, createdBy);
        if (ok) loadAll();
        return ok;
    }

    public boolean updateRadii(String name, double contentRadius, double bufferRadius) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getActivityZoneDataQuery()
                .updateRadii(name, contentRadius, bufferRadius);
        if (ok) loadAll();
        return ok;
    }

    public boolean updateFlags(String name, boolean allowBreak, boolean allowPlace,
                                boolean allowPvp, boolean allowMobAttack) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getActivityZoneDataQuery()
                .updateFlags(name, allowBreak, allowPlace, allowPvp, allowMobAttack);
        if (ok) loadAll();
        return ok;
    }

    public boolean deleteZone(String name) {
        boolean ok = plugin.getInterneAPI().getIslandQuery().getActivityZoneDataQuery().delete(name);
        if (ok) loadAll();
        return ok;
    }
}
