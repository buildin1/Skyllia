package fr.euphyllia.skylliaupgrade.database.postgresql;

import fr.euphyllia.skyllia.api.database.DatabaseInitializeQuery;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.sgbd.exceptions.DatabaseException;
import fr.euphyllia.skyllia.sgbd.postgre.Postgres;
import fr.euphyllia.skyllia.sgbd.postgre.PostgresLoader;
import fr.euphyllia.skyllia.sgbd.utils.model.DatabaseLoader;
import fr.euphyllia.skyllia.sgbd.utils.sql.SQLExecute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgreSQLUpgradeInit extends DatabaseInitializeQuery {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLUpgradeInit.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS island_upgrade (
                island_id uuid PRIMARY KEY,
                level integer NOT NULL DEFAULT 0
            );
            """;

    private static DatabaseLoader database;
    private static PostgreSQLUpgradeGenerator generator;

    public PostgreSQLUpgradeInit() {
        Postgres pg = new Postgres(ConfigLoader.database.getPostgreConfig());
        database = new PostgresLoader(pg);
        generator = new PostgreSQLUpgradeGenerator(database);
    }

    public static PostgreSQLUpgradeGenerator getGenerator() {
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
        } catch (Exception e) {
            log.error("Error creating island_upgrade table: {}", e.getMessage(), e);
            return false;
        }
    }
}
