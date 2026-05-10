package fr.euphyllia.skyllia.sgbd.postgre;

import com.zaxxer.hikari.HikariDataSource;
import fr.euphyllia.skyllia.sgbd.DatabaseConfig;
import fr.euphyllia.skyllia.sgbd.exceptions.DatabaseException;
import fr.euphyllia.skyllia.sgbd.utils.model.DBConnect;
import fr.euphyllia.skyllia.sgbd.utils.model.DBInterface;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * PostgreSQL connection backend.
 * <p>
 * Uses HikariCP for pooling. The bootstrap step ({@link #ensureDatabaseExists()})
 * connects to the {@code postgres} system database, checks for the configured
 * database via parameterized query (no injection surface), then creates it with
 * a properly quoted identifier if missing.
 * </p>
 */
public class Postgres implements DBConnect, DBInterface {

    /**
     * Allowed identifier pattern. Matches the rule used by MariaDB to keep
     * configuration semantics uniform across SGBDs. PostgreSQL itself supports
     * any string between double quotes (with quote-doubling), but Skyllia
     * intentionally restricts to a safe subset.
     */
    private static final Pattern SAFE_IDENT = Pattern.compile("^[A-Za-z0-9_]+$");

    private static final String BOOTSTRAP_DB = "postgres";

    private final Logger logger = LogManager.getLogger(Postgres.class);
    private final DatabaseConfig cfg;
    private HikariDataSource pool;
    private boolean connected;

    public Postgres(final DatabaseConfig cfg) {
        this.cfg = cfg;
        this.connected = false;
    }


    /**
     * Initializes and loads the database connection.
     *
     * @return {@code true} if the connection is successfully established,
     * {@code false} otherwise
     * @throws DatabaseException if any error occurs during initialization
     */
    @Override
    public boolean onLoad() throws DatabaseException {
        if (pool != null && !pool.isClosed()) return connected;

        ensureDatabaseExists();

        this.pool = new HikariDataSource();
        this.pool.setPoolName("skyllia-pg-hikari");
        this.pool.setDriverClassName("org.postgresql.Driver");
        this.pool.setJdbcUrl(buildJdbcUrl(cfg.database()));
        this.pool.setUsername(cfg.user());
        this.pool.setPassword(cfg.pass());

        this.pool.addDataSourceProperty("reWriteBatchedInserts", "true");

        this.pool.addDataSourceProperty("stringtype", "unspecified");

        this.pool.setMaximumPoolSize(cfg.maxPool().intValue());
        this.pool.setMinimumIdle(cfg.minPool().intValue());
        this.pool.setMaxLifetime(cfg.maxLifeTime().longValue());
        this.pool.setKeepaliveTime(cfg.keepAliveTime().longValue());
        this.pool.setConnectionTimeout(cfg.timeOut().longValue());

        if (cfg.validationTimeout() != null && cfg.validationTimeout() > 0) {
            this.pool.setValidationTimeout(cfg.validationTimeout());
        }
        if (cfg.leakDetectionThreshold() != null && cfg.leakDetectionThreshold() > 0) {
            this.pool.setLeakDetectionThreshold(cfg.leakDetectionThreshold());
        }

        if (Boolean.FALSE.equals(cfg.useServerPrepStmts())) {
            this.pool.addDataSourceProperty("prepareThreshold", "0");
        }

        if (cfg.prepStmtCacheSize() != null && cfg.prepStmtCacheSize() > 0) {
            this.pool.addDataSourceProperty(
                    "preparedStatementCacheQueries",
                    String.valueOf(cfg.prepStmtCacheSize())
            );
        }


        try (Connection c = pool.getConnection()) {
            if (c.isValid(2)) {
                this.connected = true;
                this.logger.info(
                        "PostgreSQL pool initialized successfully. Min={}, Max={}, serverPrepStmts={}",
                        cfg.minPool(),
                        cfg.maxPool(),
                        !Boolean.FALSE.equals(cfg.useServerPrepStmts())
                );
                return true;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to initialize PostgreSQL pool", e);
        }
        return false;

    }

    private String buildJdbcUrl(String database) {
        StringBuilder url = new StringBuilder("jdbc:postgresql://")
                .append(cfg.hostname())
                .append(':').append(cfg.port())
                .append('/').append(database);
        if (Boolean.TRUE.equals(cfg.useSSL())) {
            url.append("?ssl=true");
        }
        return url.toString();
    }

    /**
     * Closes the database connection if it is currently open.
     */
    @Override
    public void onClose() {
        if (isConnected() && pool != null && !pool.isClosed()) {
            pool.close();
            connected = false;
            logger.info("PostgreSQL pool closed.");
        }
    }

    /**
     * Checks whether the database connection is active and valid.
     *
     * @return {@code true} if the database is connected, {@code false} otherwise
     */
    @Override
    public boolean isConnected() {
        return connected && pool != null && !pool.isClosed();
    }

    /**
     * Retrieves a valid {@link Connection} to the database.
     *
     * @return a {@link Connection}, or {@code null} if no connection could be established
     * @throws DatabaseException if an error occurs while obtaining the connection
     */
    @Override
    public @Nullable Connection getConnection() throws DatabaseException {
        if (pool == null) throw new DatabaseException("Pool is null");
        if (!isConnected()) throw new DatabaseException("Not connected");
        try {
            return pool.getConnection();
        } catch (SQLException e) {
            throw new DatabaseException("getConnection failed", e);
        }
    }

    private void ensureDatabaseExists() throws DatabaseException {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new DatabaseException("PostgreSQL JDBC driver not found in classpath", e);
        }

        final String dbName = cfg.database();
        if (dbName == null || dbName.isBlank()) {
            throw new DatabaseException("PostgreSQL database name is missing in configuration.");
        }
        if (!SAFE_IDENT.matcher(dbName).matches()) {
            throw new DatabaseException(
                    "PostgreSQL database name '" + dbName + "' contains forbidden characters. " +
                            "Only [A-Za-z0-9_] are allowed."
            );
        }

        final String bootstrapUrl = buildJdbcUrl(BOOTSTRAP_DB);

        try (Connection c = DriverManager.getConnection(bootstrapUrl, cfg.user(), cfg.pass())) {

            boolean exists;
            try (var ps = c.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
                ps.setString(1, dbName);
                try (var rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (!exists) {
                final String quotedDb = "\"" + dbName.replace("\"", "\"\"") + "\"";
                try (var stmt = c.createStatement()) {
                    stmt.execute("CREATE DATABASE " + quotedDb);
                }
                logger.info("PostgreSQL database '{}' created.", dbName);
            } else {
                logger.info("PostgreSQL database '{}' already exists.", dbName);
            }

        } catch (SQLException e) {
            throw new DatabaseException(
                    e.getMessage(),
                    e
            );
        }
    }


}