package fr.euphyllia.skyllia.api.database;

import fr.euphyllia.skyllia.api.zone.ActivityZone;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 活动区（{@link ActivityZone}）的持久化接口，实现类对应不同数据库后端。
 */
public abstract class ActivityZoneDataQuery {

    public abstract boolean insert(String name, int centerX, int centerZ,
                                    double contentRadius, double bufferRadius, @Nullable UUID createdBy);

    public abstract @Nullable ActivityZone getByName(String name);

    public abstract List<ActivityZone> getAll();

    public abstract boolean updateRadii(String name, double contentRadius, double bufferRadius);

    public abstract boolean updateFlags(String name, boolean allowBreak, boolean allowPlace,
                                         boolean allowPvp, boolean allowMobAttack);

    public abstract boolean delete(String name);
}
