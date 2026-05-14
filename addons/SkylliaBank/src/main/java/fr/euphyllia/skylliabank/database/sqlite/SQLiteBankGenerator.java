package fr.euphyllia.skylliabank.database.sqlite;

import fr.euphyllia.skyllia.sgbd.utils.model.DatabaseLoader;
import fr.euphyllia.skyllia.sgbd.utils.sql.SQLExecute;
import fr.euphyllia.skylliabank.api.BankAccount;
import fr.euphyllia.skylliabank.api.BankGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SQLiteBankGenerator implements BankGenerator {

    private static final Logger log = LogManager.getLogger(SQLiteBankGenerator.class);

    private static final String SELECT_BALANCE = """
            SELECT balance FROM island_bank WHERE island_id = ?;
            """;

    private static final String UPSERT_BALANCE = """
            INSERT INTO island_bank (island_id, balance)
            VALUES (?, ?)
            ON CONFLICT(island_id) DO UPDATE SET balance = excluded.balance;
            """;

    private static final String DEPOSIT = """
            INSERT INTO island_bank (island_id, balance)
            VALUES (?, ?)
            ON CONFLICT(island_id) DO UPDATE SET balance = island_bank.balance + excluded.balance;
            """;

    private static final String WITHDRAW = """
            UPDATE island_bank
            SET balance = balance - ?
            WHERE island_id = ?
              AND balance >= ?;
            """;

    private static final String SELECT_TOP_BALANCES = """
            SELECT island_id, balance
            FROM island_bank
            ORDER BY balance DESC;
            """;

    private final DatabaseLoader loader;

    public SQLiteBankGenerator(DatabaseLoader loader) {
        this.loader = loader;
    }

    @Override
    public BankAccount getBankAccount(UUID islandId) {
        return SQLExecute.queryMap(loader, SELECT_BALANCE, List.of(islandId.toString()), rs -> {
            try {
                if (rs.next()) {
                    return new BankAccount(islandId, rs.getDouble("balance"));
                }
                return new BankAccount(islandId, 0.0);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public Boolean setBalance(UUID islandId, double balance) {
        int affected = SQLExecute.update(loader, UPSERT_BALANCE, List.of(islandId.toString(), balance));
        return affected > 0;
    }

    @Override
    public List<BankAccount> getTopBalances() {
        List<BankAccount> result = SQLExecute.queryMap(loader, SELECT_TOP_BALANCES, List.of(), rs -> {
            List<BankAccount> list = new ArrayList<>();
            try {
                while (rs.next()) {
                    UUID islandId = UUID.fromString(rs.getString("island_id"));
                    double balance = rs.getDouble("balance");
                    list.add(new BankAccount(islandId, balance));
                }
            } catch (SQLException e) {
                log.error(e.getMessage(), e);
            }
            return list;
        });
        return result != null ? result : List.of();
    }

    @Override
    public Boolean deposit(UUID islandId, double amount) {
        if (amount <= 0) return false;
        int affected = SQLExecute.update(loader, DEPOSIT, List.of(islandId.toString(), amount));
        return affected > 0;
    }

    @Override
    public Boolean withdraw(UUID islandId, double amount) {
        if (amount <= 0) return false;

        int affected = SQLExecute.update(loader, WITHDRAW, List.of(amount, islandId.toString(), amount));
        return affected > 0;
    }
}