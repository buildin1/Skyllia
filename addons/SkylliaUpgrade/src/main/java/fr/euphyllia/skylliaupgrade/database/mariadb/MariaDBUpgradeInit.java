package fr.euphyllia.skylliaupgrade.database.mariadb;

import fr.euphyllia.skyllia.api.database.DatabaseInitializeQuery;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.sgbd.exceptions.DatabaseException;
import fr.euphyllia.skyllia.sgbd.mariadb.MariaDB;
import fr.euphyllia.skyllia.sgbd.mariadb.MariaDBLoader;
import fr.euphyllia.skyllia.sgbd.utils.model.DatabaseLoader;
import fr.euphyllia.skyllia.sgbd.utils.sql.SQLExecute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MariaDBUpgradeInit extends DatabaseInitializeQuery {

    private static final Logger log = LoggerFactory.getLogger(MariaDBUpgradeInit.class);
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS `island_upgrade` (
                `island_id` CHAR(36) NOT NULL,
                `level` INT NOT NULL DEFAULT 0,
                PRIMARY KEY (`island_id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
            """;
    private static DatabaseLoader database;
    private static MariaDBUpgradeGenerator generator;

    public MariaDBUpgradeInit() {
        MariaDB mariaDB = new MariaDB(ConfigLoader.database.getMariaDBConfig());
        database = new MariaDBLoader(mariaDB);
        generator = new MariaDBUpgradeGenerator(database);
    }

    public static MariaDBUpgradeGenerator getGenerator() {
        return generator;
    }

    @Override
    public Boolean init() {
        try {
            if (!database.loadDatabase()) {
                return false;
            }
            SQLExecute.update(database, CREATE_TABLE, null);
            return true;
        } catch (DatabaseException e) {
            log.error("Database initialization error: {}", e.getMessage(), e);
            return false;
        }
    }
}
