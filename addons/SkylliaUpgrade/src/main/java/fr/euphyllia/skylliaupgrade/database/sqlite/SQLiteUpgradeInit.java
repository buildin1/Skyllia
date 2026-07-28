package fr.euphyllia.skylliaupgrade.database.sqlite;

import fr.euphyllia.skyllia.api.database.DatabaseInitializeQuery;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.sgbd.exceptions.DatabaseException;
import fr.euphyllia.skyllia.sgbd.sqlite.SQLite;
import fr.euphyllia.skyllia.sgbd.sqlite.SQLiteDatabaseLoader;
import fr.euphyllia.skyllia.sgbd.utils.sql.SQLExecute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SQLiteUpgradeInit extends DatabaseInitializeQuery {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS island_upgrade (
                island_id TEXT PRIMARY KEY,
                level INTEGER NOT NULL DEFAULT 0
            );
            """;
    private static final Logger log = LoggerFactory.getLogger(SQLiteUpgradeInit.class);
    private static SQLiteDatabaseLoader database;
    private static SQLiteUpgradeGenerator generator;

    public SQLiteUpgradeInit() {
        SQLite sqlite = new SQLite(ConfigLoader.database.getSqLiteConfig());
        database = new SQLiteDatabaseLoader(sqlite);
        generator = new SQLiteUpgradeGenerator(database);
    }

    public static SQLiteUpgradeGenerator getGenerator() {
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
            log.error("Failed to initialize SQLite database for SkylliaUpgrade", e);
            return false;
        }
    }
}
