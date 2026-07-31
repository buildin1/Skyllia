package fr.euphyllia.skyllia.database.postgresql;

import fr.euphyllia.skyllia.api.database.ActivityZoneDataQuery;
import fr.euphyllia.skyllia.api.zone.ActivityZone;
import fr.euphyllia.skyllia.sgbd.utils.model.DatabaseLoader;
import fr.euphyllia.skyllia.sgbd.utils.sql.SQLExecute;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PostgreSQLActivityZoneData extends ActivityZoneDataQuery {

    private static final String INSERT = """
            INSERT INTO activity_zones
                (name, center_x, center_z, content_radius, buffer_radius, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, NOW());
            """;

    private static final String SELECT_BY_NAME = """
            SELECT * FROM activity_zones WHERE name = ?;
            """;

    private static final String SELECT_ALL = """
            SELECT * FROM activity_zones ORDER BY name;
            """;

    private static final String UPDATE_RADII = """
            UPDATE activity_zones SET content_radius = ?, buffer_radius = ? WHERE name = ?;
            """;

    private static final String UPDATE_FLAGS = """
            UPDATE activity_zones
            SET allow_break = ?, allow_place = ?, allow_pvp = ?, allow_mob_attack = ?
            WHERE name = ?;
            """;

    private static final String DELETE = """
            DELETE FROM activity_zones WHERE name = ?;
            """;

    private final DatabaseLoader loader;

    public PostgreSQLActivityZoneData(DatabaseLoader loader) {
        this.loader = loader;
    }

    @Override
    public boolean insert(String name, int centerX, int centerZ, double contentRadius, double bufferRadius, @Nullable UUID createdBy) {
        int affected = SQLExecute.update(loader, INSERT, List.of(
                name, centerX, centerZ, contentRadius, bufferRadius,
                createdBy != null ? createdBy.toString() : null
        ));
        return affected > 0;
    }

    @Override
    public @Nullable ActivityZone getByName(String name) {
        return SQLExecute.queryMap(loader, SELECT_BY_NAME, List.of(name), rs -> {
            try {
                if (rs.next()) return map(rs);
                return null;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public List<ActivityZone> getAll() {
        List<ActivityZone> result = SQLExecute.queryMap(loader, SELECT_ALL, List.of(), rs -> {
            List<ActivityZone> list = new ArrayList<>();
            try {
                while (rs.next()) list.add(map(rs));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return list;
        });
        return result != null ? result : List.of();
    }

    @Override
    public boolean updateRadii(String name, double contentRadius, double bufferRadius) {
        int affected = SQLExecute.update(loader, UPDATE_RADII, List.of(contentRadius, bufferRadius, name));
        return affected > 0;
    }

    @Override
    public boolean updateFlags(String name, boolean allowBreak, boolean allowPlace, boolean allowPvp, boolean allowMobAttack) {
        int affected = SQLExecute.update(loader, UPDATE_FLAGS, List.of(
                allowBreak, allowPlace, allowPvp, allowMobAttack, name
        ));
        return affected > 0;
    }

    @Override
    public boolean delete(String name) {
        int affected = SQLExecute.update(loader, DELETE, List.of(name));
        return affected > 0;
    }

    private static ActivityZone map(ResultSet rs) throws SQLException {
        String createdByStr = rs.getString("created_by");
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        Instant createdAt = createdAtTs != null ? createdAtTs.toInstant() : null;
        return new ActivityZone(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getInt("center_x"),
                rs.getInt("center_z"),
                rs.getDouble("content_radius"),
                rs.getDouble("buffer_radius"),
                rs.getBoolean("allow_break"),
                rs.getBoolean("allow_place"),
                rs.getBoolean("allow_pvp"),
                rs.getBoolean("allow_mob_attack"),
                createdByStr != null ? UUID.fromString(createdByStr) : null,
                createdAt
        );
    }
}
