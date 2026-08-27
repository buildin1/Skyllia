package fr.euphyllia.skyllia.database.postgresql;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.database.IslandPermissionQuery;
import fr.euphyllia.skyllia.api.permissions.CompiledPermissions;
import fr.euphyllia.skyllia.api.permissions.IslandFlagRegistry;
import fr.euphyllia.skyllia.api.permissions.IslandFlags;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionRegistry;
import fr.euphyllia.skyllia.api.permissions.PermissionSet;
import fr.euphyllia.skyllia.api.permissions.PermissionSetCodec;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.database.FlagWordsNormalizer;
import fr.euphyllia.skyllia.sgbd.utils.model.DatabaseLoader;
import fr.euphyllia.skyllia.sgbd.utils.sql.SQLExecute;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PostgreSQLIslandPermission extends IslandPermissionQuery {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLIslandPermission.class);

    private final DatabaseLoader databaseLoader;
    private final String schema;

    private final String SELECT_ALL;
    private final String UPSERT_ROLE;
    private final String DELETE_ROLE;


    private final String SELECT_FLAGS;
    private final String UPSERT_FLAGS;
    private final String UPDATE_FLAGS_NORMALIZED;

    public PostgreSQLIslandPermission(DatabaseLoader databaseLoader) {
        this(databaseLoader, "public");
    }

    public PostgreSQLIslandPermission(DatabaseLoader databaseLoader, String schema) {
        this.databaseLoader = databaseLoader;
        this.schema = sanitizeIdent(schema);

        String permTable = this.schema + ".islands_permissions_v2";
        String flagsTable = this.schema + ".islands_flags";

        this.SELECT_ALL = """
                SELECT role, words
                FROM %s
                WHERE island_id = ?;
                """.formatted(permTable);

        this.UPSERT_ROLE = """
                INSERT INTO %s (island_id, role, words)
                VALUES (?, ?, ?)
                ON CONFLICT (island_id, role)
                DO UPDATE SET words = EXCLUDED.words;
                """.formatted(permTable);

        this.DELETE_ROLE = """
                DELETE FROM %s
                WHERE island_id = ? AND role = ?;
                """.formatted(permTable);

        this.SELECT_FLAGS = """
                SELECT words, words_version
                FROM %s
                WHERE island_id = ? AND world_name = ?;
                """.formatted(flagsTable);

        this.UPSERT_FLAGS = """
                INSERT INTO %s (island_id, world_name, words, words_version)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (island_id, world_name)
                DO UPDATE SET words = EXCLUDED.words, words_version = EXCLUDED.words_version;
                """.formatted(flagsTable);

        // 惰性迁移的写回。WHERE 里带上旧版本号：并发线程已迁移同一行时这里就是 0 行无害空写，
        // 谁先写回都一样——归一化是纯函数，两边算出的位图逐位相同。
        this.UPDATE_FLAGS_NORMALIZED = """
                UPDATE %s
                SET words = ?, words_version = ?
                WHERE island_id = ? AND world_name = ? AND words_version = ?;
                """.formatted(flagsTable);
    }

    private static String sanitizeIdent(String ident) {
        if (ident == null || ident.isBlank()) return "public";
        return ident.replaceAll("[^a-zA-Z0-9_]", "");
    }

    @Override
    public @Nullable CompiledPermissions loadCompiled(UUID islandId, PermissionRegistry registry) {
        CompiledPermissions compiled = new CompiledPermissions(registry);

        List<RoleRow> rows = SQLExecute.queryMap(databaseLoader, SELECT_ALL, List.of(islandId), rs -> {
            List<RoleRow> out = new ArrayList<>();
            try {
                while (rs.next()) {
                    String roleStr = rs.getString("role");
                    byte[] words = rs.getBytes("words");
                    out.add(new RoleRow(roleStr, words));
                }
            } catch (SQLException e) {
                log.error("SQL error while loading permissions for island {}", islandId, e);
                return null;
            }
            return out;
        });

        if (rows == null) return null;

        int regSize = registry.size();
        for (RoleRow row : rows) {
            RoleType role;
            try {
                role = RoleType.valueOf(row.role());
            } catch (Exception ignored) {
                continue;
            }
            long[] wordsArr = PermissionSetCodec.decodeLongs(row.words());
            PermissionSet set = new PermissionSet(regSize);
            set.loadWords(wordsArr);
            set.ensureCapacity(regSize);
            compiled.replace(role, set);
        }

        compiled.ensureUpToDate(registry);
        return compiled;
    }

    @Override
    public boolean set(UUID islandId, RoleType role, PermissionId id, boolean value) {
        return super.set(islandId, SkylliaAPI.getPermissionRegistry(), role, id, value);
    }

    @Override
    public boolean saveRole(UUID islandId, RoleType role, byte[] wordsBlob) {
        int affected = SQLExecute.update(databaseLoader, UPSERT_ROLE,
                List.of(islandId, role.name(), wordsBlob));
        return affected != 0;
    }

    @Override
    public boolean deleteRole(UUID islandId, RoleType role) {
        int affected = SQLExecute.update(databaseLoader, DELETE_ROLE,
                List.of(islandId, role.name()));
        return affected != 0;
    }

    @Override
    public @Nullable IslandFlags loadIslandFlags(UUID islandId, IslandFlagRegistry registry, String worldName) {
        FlagsRow row = SQLExecute.queryMap(databaseLoader, SELECT_FLAGS, List.of(islandId, worldName), rs -> {
            try {
                if (rs.next()) return new FlagsRow(rs.getBytes("words"), rs.getInt("words_version"));
            } catch (SQLException e) {
                log.error("SQL error while loading flags for island {} world {}", islandId, worldName, e);
            }
            return null;
        });

        if (row == null) return null;

        long[] words = PermissionSetCodec.decodeLongs(row.words());
        // 惰性迁移：旧行做“或→与”合并，已迁移行回填此后新注册的单体位（见 FlagWordsNormalizer）
        FlagWordsNormalizer.Result normalized = FlagWordsNormalizer.normalize(words, row.version(), registry);
        if (normalized != null) {
            words = normalized.words();
            SQLExecute.update(databaseLoader, UPDATE_FLAGS_NORMALIZED, List.of(
                    PermissionSetCodec.encodeLongs(words), normalized.newCoveredCount(),
                    islandId, worldName, row.version()));
        }

        IslandFlags flags = new IslandFlags(registry);
        flags.loadWords(words);
        flags.ensureUpToDate(registry);
        return flags;
    }

    @Override
    public boolean saveIslandFlags(UUID islandId, byte[] wordsBlob, String worldName) {
        int affected = SQLExecute.update(databaseLoader, UPSERT_FLAGS,
                List.of(islandId, worldName, wordsBlob, SkylliaAPI.getFlagRegistry().size()));
        return affected != 0;
    }

    private record RoleRow(String role, byte[] words) {
    }

    private record FlagsRow(byte[] words, int version) {
    }
}
