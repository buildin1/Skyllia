package fr.euphyllia.skylliaupgrade.database.mariadb;

import fr.euphyllia.skyllia.sgbd.utils.model.DatabaseLoader;
import fr.euphyllia.skyllia.sgbd.utils.sql.SQLExecute;
import fr.euphyllia.skylliaupgrade.api.UpgradeGenerator;
import fr.euphyllia.skylliaupgrade.api.UpgradeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MariaDBUpgradeGenerator implements UpgradeGenerator {

    private static final Logger log = LoggerFactory.getLogger(MariaDBUpgradeGenerator.class);

    private static final String SELECT_LEVEL = """
            SELECT `level`
            FROM `island_upgrade`
            WHERE `island_id` = ?;
            """;

    private static final String UPSERT_LEVEL = """
            INSERT INTO `island_upgrade` (`island_id`, `level`)
            VALUES(?, ?)
            ON DUPLICATE KEY UPDATE `level` = VALUES(`level`);
            """;

    private static final String SELECT_TOP_LEVELS = """
            SELECT `island_id`, `level`
            FROM `island_upgrade`
            ORDER BY `level` DESC;
            """;

    private final DatabaseLoader loader;

    public MariaDBUpgradeGenerator(DatabaseLoader loader) {
        this.loader = loader;
    }

    @Override
    public UpgradeRecord getRecord(UUID islandId) {
        UpgradeRecord result = SQLExecute.queryMap(loader, SELECT_LEVEL, List.of(islandId.toString()), rs -> {
            try {
                if (rs.next()) {
                    return new UpgradeRecord(islandId, rs.getInt("level"));
                }
                return new UpgradeRecord(islandId, 0);
            } catch (SQLException e) {
                log.error(e.getMessage(), e);
                return new UpgradeRecord(islandId, 0);
            }
        });
        return result != null ? result : new UpgradeRecord(islandId, 0);
    }

    @Override
    public Boolean setLevel(UUID islandId, int level) {
        int affected = SQLExecute.update(loader, UPSERT_LEVEL, List.of(islandId.toString(), level));
        return affected > 0;
    }

    @Override
    public List<UpgradeRecord> getTopLevels() {
        List<UpgradeRecord> result = SQLExecute.queryMap(loader, SELECT_TOP_LEVELS, List.of(), rs -> {
            List<UpgradeRecord> list = new ArrayList<>();
            try {
                while (rs.next()) {
                    UUID islandId = UUID.fromString(rs.getString("island_id"));
                    list.add(new UpgradeRecord(islandId, rs.getInt("level")));
                }
            } catch (SQLException e) {
                log.error(e.getMessage(), e);
            }
            return list;
        });
        return result != null ? result : List.of();
    }
}
