package fr.euphyllia.skyllia.database;

import fr.euphyllia.skyllia.api.InterneAPI;
import fr.euphyllia.skyllia.api.database.ActivityZoneDataQuery;
import fr.euphyllia.skyllia.api.database.DatabaseInitializeQuery;
import fr.euphyllia.skyllia.api.database.IslandBuildHeightQuery;
import fr.euphyllia.skyllia.api.database.IslandCustomDataQuery;
import fr.euphyllia.skyllia.api.database.IslandDataQuery;
import fr.euphyllia.skyllia.api.database.IslandMemberQuery;
import fr.euphyllia.skyllia.api.database.IslandPermissionQuery;
import fr.euphyllia.skyllia.api.database.IslandUpdateQuery;
import fr.euphyllia.skyllia.api.database.IslandWarpQuery;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.database.mariadb.MariaDBActivityZoneData;
import fr.euphyllia.skyllia.database.mariadb.MariaDBDatabaseInitialize;
import fr.euphyllia.skyllia.database.mariadb.MariaDBIslandBuildHeight;
import fr.euphyllia.skyllia.database.mariadb.MariaDBIslandCustomData;
import fr.euphyllia.skyllia.database.mariadb.MariaDBIslandData;
import fr.euphyllia.skyllia.database.mariadb.MariaDBIslandMember;
import fr.euphyllia.skyllia.database.mariadb.MariaDBIslandPermission;
import fr.euphyllia.skyllia.database.mariadb.MariaDBIslandUpdate;
import fr.euphyllia.skyllia.database.mariadb.MariaDBIslandWarp;
import fr.euphyllia.skyllia.database.postgresql.PostgreSQLActivityZoneData;
import fr.euphyllia.skyllia.database.postgresql.PostgreSQLDatabaseInitialize;
import fr.euphyllia.skyllia.database.postgresql.PostgreSQLIslandBuildHeight;
import fr.euphyllia.skyllia.database.postgresql.PostgreSQLIslandCustomData;
import fr.euphyllia.skyllia.database.postgresql.PostgreSQLIslandData;
import fr.euphyllia.skyllia.database.postgresql.PostgreSQLIslandMember;
import fr.euphyllia.skyllia.database.postgresql.PostgreSQLIslandPermission;
import fr.euphyllia.skyllia.database.postgresql.PostgreSQLIslandUpdate;
import fr.euphyllia.skyllia.database.postgresql.PostgreSQLIslandWarp;
import fr.euphyllia.skyllia.database.sqlite.SQLiteActivityZoneData;
import fr.euphyllia.skyllia.database.sqlite.SQLiteDatabaseInitialize;
import fr.euphyllia.skyllia.database.sqlite.SQLiteIslandBuildHeight;
import fr.euphyllia.skyllia.database.sqlite.SQLiteIslandCustomData;
import fr.euphyllia.skyllia.database.sqlite.SQLiteIslandData;
import fr.euphyllia.skyllia.database.sqlite.SQLiteIslandMember;
import fr.euphyllia.skyllia.database.sqlite.SQLiteIslandPermission;
import fr.euphyllia.skyllia.database.sqlite.SQLiteIslandUpdate;
import fr.euphyllia.skyllia.database.sqlite.SQLiteIslandWarp;
import fr.euphyllia.skyllia.sgbd.exceptions.DatabaseException;
import fr.euphyllia.skyllia.sgbd.sqlite.SQLiteDatabaseLoader;
import fr.euphyllia.skyllia.sgbd.utils.model.DatabaseLoader;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class IslandQuery {

    private final Logger logger = LogManager.getLogger(IslandQuery.class);
    private final InterneAPI api;

    private DatabaseInitializeQuery databaseInitializeQuery;
    private IslandDataQuery islandDataQuery;
    private IslandUpdateQuery islandUpdateQuery;
    private IslandWarpQuery islandWarpQuery;
    private IslandMemberQuery islandMemberQuery;
    private IslandPermissionQuery islandPermissionQuery;
    private IslandCustomDataQuery islandCustomDataQuery;
    private IslandBuildHeightQuery islandBuildHeightQuery;
    private ActivityZoneDataQuery activityZoneDataQuery;

    public IslandQuery(InterneAPI api) {
        this.api = api;
        try {
            this.init();
        } catch (DatabaseException exception) {
            logger.log(Level.FATAL, exception.getMessage(), exception);
        }
    }

    private void init() throws DatabaseException {
        final DatabaseLoader loader = this.api.getDatabaseLoader();
        if (loader == null) throw new DatabaseException("Database loader is not initialized.");

        // --- MariaDB ---
        if (ConfigLoader.database.getMariaDBConfig() != null) {

            this.databaseInitializeQuery = new MariaDBDatabaseInitialize(loader);
            this.islandDataQuery = new MariaDBIslandData(loader);
            this.islandUpdateQuery = new MariaDBIslandUpdate(loader);
            this.islandWarpQuery = new MariaDBIslandWarp(loader);
            this.islandMemberQuery = new MariaDBIslandMember(loader);
            this.islandPermissionQuery = new MariaDBIslandPermission(loader);
            this.islandCustomDataQuery = new MariaDBIslandCustomData(loader);
            this.islandBuildHeightQuery = new MariaDBIslandBuildHeight(loader);
            this.activityZoneDataQuery = new MariaDBActivityZoneData(loader);

            return;
        }

        // --- PostgreSQL ---
        if (ConfigLoader.database.getPostgreConfig() != null) {
            this.databaseInitializeQuery = new PostgreSQLDatabaseInitialize(loader);
            this.islandDataQuery = new PostgreSQLIslandData(loader);
            this.islandUpdateQuery = new PostgreSQLIslandUpdate(loader);
            this.islandWarpQuery = new PostgreSQLIslandWarp(loader);
            this.islandMemberQuery = new PostgreSQLIslandMember(loader);
            this.islandPermissionQuery = new PostgreSQLIslandPermission(loader);
            this.islandCustomDataQuery = new PostgreSQLIslandCustomData(loader);
            this.islandBuildHeightQuery = new PostgreSQLIslandBuildHeight(loader);
            this.activityZoneDataQuery = new PostgreSQLActivityZoneData(loader);

            return;
        }

        // --- SQLite ---
        if (ConfigLoader.database.getSqLiteConfig() != null) {
            if (!(loader instanceof SQLiteDatabaseLoader sqliteLoader)) {
                throw new DatabaseException(
                        "SQLite config is set but DatabaseLoader is not SQLiteDatabaseLoader (got: " + loader.getClass().getName() + ")"
                );
            }

            this.databaseInitializeQuery = new SQLiteDatabaseInitialize(sqliteLoader);
            this.islandDataQuery = new SQLiteIslandData(sqliteLoader);
            this.islandUpdateQuery = new SQLiteIslandUpdate(sqliteLoader);
            this.islandWarpQuery = new SQLiteIslandWarp(sqliteLoader);
            this.islandMemberQuery = new SQLiteIslandMember(sqliteLoader);
            this.islandPermissionQuery = new SQLiteIslandPermission(sqliteLoader);
            this.islandCustomDataQuery = new SQLiteIslandCustomData(sqliteLoader);
            this.islandBuildHeightQuery = new SQLiteIslandBuildHeight(sqliteLoader);
            this.activityZoneDataQuery = new SQLiteActivityZoneData(sqliteLoader);

            return;
        }

        throw new DatabaseException("No Database configured!");
    }

    public DatabaseInitializeQuery getDatabaseInitializeQuery() {
        return this.databaseInitializeQuery;
    }

    public IslandDataQuery getIslandDataQuery() {
        return this.islandDataQuery;
    }

    public IslandCustomDataQuery getIslandCustomDataQuery() {
        return this.islandCustomDataQuery;
    }

    public IslandUpdateQuery getIslandUpdateQuery() {
        return this.islandUpdateQuery;
    }

    public IslandWarpQuery getIslandWarpQuery() {
        return this.islandWarpQuery;
    }

    public IslandMemberQuery getIslandMemberQuery() {
        return this.islandMemberQuery;
    }

    public IslandPermissionQuery getIslandPermissionQuery() {
        return this.islandPermissionQuery;
    }

    public IslandBuildHeightQuery getIslandBuildHeightQuery() {
        return this.islandBuildHeightQuery;
    }

    public ActivityZoneDataQuery getActivityZoneDataQuery() {
        return this.activityZoneDataQuery;
    }
}
