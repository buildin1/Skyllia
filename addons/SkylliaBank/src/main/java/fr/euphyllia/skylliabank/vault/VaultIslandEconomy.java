package fr.euphyllia.skylliabank.vault;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliabank.SkylliaBank;
import fr.euphyllia.skylliabank.api.BankAccount;
import fr.euphyllia.skylliabank.configuration.BankConfigLoader;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.UUID;

public class VaultIslandEconomy implements Economy {

    private static final EconomyResponse NOT_IMPLEMENTED =
            new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                    "SkylliaBank does not support Vault bank accounts.");

    @Override
    public boolean isEnabled() {
        return BankConfigLoader.config.isVaultIslandEconomyEnabled();
    }

    @Override
    public String getName() {
        return "SkylliaBank";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return BankConfigLoader.config.getVaultFractionalDigits();
    }

    @Override
    public String format(double amount) {
        DecimalFormat df = new DecimalFormat(BankConfigLoader.config.getFormatBankAccount());
        df.setDecimalFormatSymbols(
                DecimalFormatSymbols.getInstance(BankConfigLoader.config.getLocalBankAccount())
        );
        return df.format(amount) + " " + currencyNameSingular();
    }

    @Override
    public String currencyNamePlural() {
        return BankConfigLoader.config.getVaultCurrencyNamePlural();
    }

    @Override
    public String currencyNameSingular() {
        return BankConfigLoader.config.getVaultCurrencyNameSingular();
    }

    @Override
    public boolean hasAccount(String playerName) {
        return hasAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        if (player == null) return false;
        Island island = getIsland(player.getUniqueId());
        return island != null;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        return getBalance(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null) return 0;
        Island island = getIsland(player.getUniqueId());
        if (island == null) return 0;
        BankAccount account = SkylliaBank.getBankManager().getBankAccount(island.getId());
        if (account == null) return 0;
        return account.balance();
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (!Double.isFinite(amount) || amount < 0) {
            return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE,
                    "Cannot withdraw a negative or non-finite amount.");
        }
        if (player == null) {
            return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE,
                    "Player is null.");
        }
        Island island = getIsland(player.getUniqueId());
        if (island == null) {
            return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE,
                    "Player has no island.");
        }
        UUID islandId = island.getId();
        BankAccount account = SkylliaBank.getBankManager().getBankAccount(islandId);
        double currentBalance = (account != null) ? account.balance() : 0;
        if (currentBalance < amount) {
            return new EconomyResponse(amount, currentBalance, EconomyResponse.ResponseType.FAILURE,
                    "Insufficient island bank balance.");
        }
        boolean success = SkylliaBank.getBankManager().withdraw(islandId, amount);
        if (success) {
            double newBalance = currentBalance - amount;
            return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, null);
        } else {
            return new EconomyResponse(amount, currentBalance, EconomyResponse.ResponseType.FAILURE,
                    "Database error during withdrawal.");
        }
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (!Double.isFinite(amount) || amount < 0) {
            return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE,
                    "Cannot deposit a negative or non-finite amount.");
        }
        if (player == null) {
            return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE,
                    "Player is null.");
        }
        Island island = getIsland(player.getUniqueId());
        if (island == null) {
            return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE,
                    "Player has no island.");
        }
        UUID islandId = island.getId();
        boolean success = SkylliaBank.getBankManager().deposit(islandId, amount);
        if (success) {
            BankAccount updated = SkylliaBank.getBankManager().getBankAccount(islandId);
            double newBalance = (updated != null) ? updated.balance() : amount;
            return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, null);
        } else {
            BankAccount current = SkylliaBank.getBankManager().getBankAccount(islandId);
            double currentBalance = (current != null) ? current.balance() : 0;
            return new EconomyResponse(amount, currentBalance, EconomyResponse.ResponseType.FAILURE,
                    "Database error during deposit.");
        }
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return NOT_IMPLEMENTED;
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return NOT_IMPLEMENTED;
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return NOT_IMPLEMENTED;
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return NOT_IMPLEMENTED;
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return NOT_IMPLEMENTED;
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return NOT_IMPLEMENTED;
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return NOT_IMPLEMENTED;
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return NOT_IMPLEMENTED;
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return NOT_IMPLEMENTED;
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return NOT_IMPLEMENTED;
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return NOT_IMPLEMENTED;
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return createPlayerAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    private Island getIsland(UUID playerId) {
        if (playerId == null) return null;
        return SkylliaAPI.getIslandByPlayerId(playerId);
    }

}