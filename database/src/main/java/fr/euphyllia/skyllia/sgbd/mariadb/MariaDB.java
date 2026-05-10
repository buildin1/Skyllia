package fr.euphyllia.skyllia.sgbd.mariadb;

import com.zaxxer.hikari.HikariDataSource;
import fr.euphyllia.skyllia.sgbd.DatabaseConfig;
import fr.euphyllia.skyllia.sgbd.exceptions.DatabaseException;
import fr.euphyllia.skyllia.sgbd.utils.model.DBConnect;
import fr.euphyllia.skyllia.sgbd.utils.model.DBInterface;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * MariaDB connection backend.
 * <p>
 * Uses HikariCP for pooling with prepared-statement caching enabled by default
 * (via {@link DatabaseConfig}). The bootstrap step ({@link #ensureDatabaseExists()})
 * creates the database if missing, with a strict identifier check to prevent any
 * SQL injection through the configured database name.
 * </p>
 */
public class MariaDB implements DBConnect, DBInterface {

    /**
     * Allowed identifier pattern for database names: alphanumerics and underscores
     * only. This is intentionally stricter than what MariaDB itself supports (which
     * allows almost anything when backtick-quoted), because we want to fail fast on
     * suspicious config values rather than try to escape them.
     */
    private static final Pattern SAFE_IDENT = Pattern.compile("^[A-Za-z0-9_]+$");

    private final Logger logger = LogManager.getLogger(MariaDB.class);
    private final DatabaseConfig mariaDBConfig;
    private HikariDataSource pool;
    private boolean connected;

    /**
     * Constructs a new {@code MariaDB} instance with the specified configuration.
     *
     * @param configMariaDB the configuration object for connecting to the MariaDB database.
     */
    public MariaDB(final DatabaseConfig configMariaDB) {
        this.mariaDBConfig = configMariaDB;
        this.connected = false;
    }

    /**
     * Initializes the HikariCP connection pool for MariaDB using the provided configuration.
     *
     * @return {@code true} if the connection pool was successfully initialized, {@code false} otherwise.
     * @throws DatabaseException if any error occurs during the pool initialization.
     */
    @Override
    public boolean onLoad() throws DatabaseException {
        if (pool != null && !pool.isClosed()) {
            logger.warn("The connection pool is already initialized.");
            return connected;
        }

        ensureDatabaseExists();

        this.pool = new HikariDataSource();
        this.pool.setPoolName("skyllia-mariadb-hikari");
        this.pool.setDriverClassName("org.mariadb.jdbc.Driver");
        this.pool.setJdbcUrl(buildJdbcUrl(mariaDBConfig.database()));
        this.pool.setUsername(mariaDBConfig.user());
        this.pool.setPassword(mariaDBConfig.pass());

        // Pool sizing
        this.pool.setMaximumPoolSize(mariaDBConfig.maxPool());
        this.pool.setMinimumIdle(mariaDBConfig.minPool());
        this.pool.setMaxLifetime(mariaDBConfig.maxLifeTime());
        this.pool.setKeepaliveTime(mariaDBConfig.keepAliveTime());
        this.pool.setConnectionTimeout(mariaDBConfig.timeOut());

        // Validation & leak detection
        if (mariaDBConfig.validationTimeout() != null && mariaDBConfig.validationTimeout() > 0) {
            this.pool.setValidationTimeout(mariaDBConfig.validationTimeout());
        }
        if (mariaDBConfig.leakDetectionThreshold() != null && mariaDBConfig.leakDetectionThreshold() > 0) {
            this.pool.setLeakDetectionThreshold(mariaDBConfig.leakDetectionThreshold());
        }

        // Prepared-statement caching.
        if (Boolean.TRUE.equals(mariaDBConfig.cachePrepStmts())) {
            applyDataSourceProperty("cachePrepStmts", mariaDBConfig.cachePrepStmts());
            applyDataSourceProperty("prepStmtCacheSize", mariaDBConfig.prepStmtCacheSize());
            applyDataSourceProperty("prepStmtCacheSqlLimit", mariaDBConfig.prepStmtCacheSqlLimit());
            applyDataSourceProperty("useServerPrepStmts", mariaDBConfig.useServerPrepStmts());
        }

        try (Connection connection = pool.getConnection()) {
            if (connection.isValid(2)) {
                this.connected = true;
                this.logger.info(
                        "MariaDB pool initialized successfully. Min={}, Max={}, prepStmtCache={}",
                        mariaDBConfig.minPool(),
                        mariaDBConfig.maxPool(),
                        Boolean.TRUE.equals(mariaDBConfig.cachePrepStmts())
                );
                return true;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to initialize the MariaDB pool", e);
        }
        return false;
    }

    private String buildJdbcUrl(@Nullable String database) {
        StringBuilder url = new StringBuilder("jdbc:mariadb://")
                .append(mariaDBConfig.hostname())
                .append(':').append(mariaDBConfig.port())
                .append('/');
        if (database != null) {
            url.append(database);
        }
        if (Boolean.TRUE.equals(mariaDBConfig.useSSL())) {
            url.append("?useSSL=true");
        }
        return url.toString();
    }

    private void applyDataSourceProperty(String key, @Nullable Object value) {
        if (value == null) return;
        this.pool.addDataSourceProperty(key, String.valueOf(value));
    }

    /**
     * Closes the HikariCP connection pool if it is currently open and connected.
     */
    @Override
    public void onClose() {
        if (isConnected() && this.pool != null && !this.pool.isClosed()) {
            this.pool.close();
            connected = false;
            logger.info("MariaDB pool has been closed.");
        }
    }

    /**
     * Indicates whether a valid connection to the database is currently active.
     *
     * @return {@code true} if the connection is active, otherwise {@code false}.
     */
    @Override
    public boolean isConnected() {
        return connected && this.pool != null && !this.pool.isClosed();
    }

    /**
     * Retrieves a {@link Connection} from the HikariCP connection pool.
     *
     * @return a valid {@link Connection} to the MariaDB database.
     * @throws DatabaseException if the pool is not initialized or if retrieving the connection fails.
     */
    @Override
    public @Nullable Connection getConnection() throws DatabaseException {
        if (this.pool == null) {
            throw new DatabaseException("Unable to get a connection from the pool (pool is null).");
        }

        if (!isConnected()) {
            throw new DatabaseException("Not connected to the database.");
        }

        try {
            return this.pool.getConnection();
        } catch (SQLException e) {
            throw new DatabaseException("Unable to get a connection from the pool (getConnection returned null).", e);
        }
    }

    private void ensureDatabaseExists() throws DatabaseException {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new DatabaseException("MariaDB JDBC driver not found in classpath", e);
        }

        final String dbName = mariaDBConfig.database();
        if (dbName == null || dbName.isBlank()) {
            throw new DatabaseException("MariaDB database name is missing in configuration.");
        }
        if (!SAFE_IDENT.matcher(dbName).matches()) {
            throw new DatabaseException(
                    "MariaDB database name '" + dbName + "' contains forbidden characters. " +
                            "Only [A-Za-z0-9_] are allowed."
            );
        }
        String bootstrapUrl = buildJdbcUrl(null);

        try (Connection connection = java.sql.DriverManager.getConnection(
                bootstrapUrl,
                mariaDBConfig.user(),
                mariaDBConfig.pass()
        );
             var statement = connection.createStatement()) {

            statement.execute("CREATE DATABASE IF NOT EXISTS `" + dbName + "`");

            logger.info("MariaDB database '{}' ensured.", dbName);

        } catch (SQLException e) {
            throw new DatabaseException("Failed to create database if not exists", e);
        }
    }

}
