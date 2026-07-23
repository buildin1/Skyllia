package fr.euphyllia.skylliabank.commands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.permissions.PermissionId;
import fr.euphyllia.skyllia.api.permissions.PermissionNode;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.cache.commands.CommandCacheExecution;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skylliabank.EconomyManager;
import fr.euphyllia.skylliabank.SkylliaBank;
import fr.euphyllia.skylliabank.api.BankAccount;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class BankCommand implements SubCommandInterface {

    private static final Logger logger = LogManager.getLogger(BankCommand.class);

    private final Plugin plugin;
    private final Economy economy;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PermissionId BANK_DEPOSIT_PERMISSION;
    private final PermissionId BANK_WITHDRAW_PERMISSION;

    public BankCommand(Plugin plugin) {
        this.plugin = plugin;
        this.economy = EconomyManager.getEconomy();

        this.BANK_DEPOSIT_PERMISSION = SkylliaAPI.getPermissionRegistry().idOrRegister(new PermissionNode(
                new NamespacedKey(plugin, "command.bank.deposit"),
                "addons.bank.permission.deposit.name",
                "addons.bank.permission.deposit.description"
        ));
        this.BANK_WITHDRAW_PERMISSION = SkylliaAPI.getPermissionRegistry().idOrRegister(new PermissionNode(
                new NamespacedKey(plugin, "command.bank.withdraw"),
                "addons.bank.permission.withdraw.name",
                "addons.bank.permission.withdraw.description"
        ));
    }

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        // Vérifier si c'est un joueur
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "addons.bank.player.player-only");
            return;
        }
        UUID playerId = player.getUniqueId();

        if (!CommandCacheExecution.tryAcquire(playerId, "bank")) {
            ConfigLoader.language.sendMessage(player, "island.generic.command-in-progress");
            return;
        }

        try {
            @Nullable Island island = SkylliaAPI.getIslandByPlayerId(playerId);
            if (island == null) {
                ConfigLoader.language.sendMessage(player, "addons.bank.player.no-island");
                return;
            }

            if (args.length == 0) {
                handleBalance(player, island);
                return;
            }

            switch (args[0].toLowerCase()) {
                case "deposit" -> handleDeposit(player, island, args);
                case "withdraw" -> handleWithdraw(player, island, args);
                case "balance" -> handleBalance(player, island);
                case "top", "baltop" -> handleTop(player, island, args);
                case "rank" -> handleRank(player, island, args);
                default -> ConfigLoader.language.sendMessage(player, "addons.bank.player.unknown-command");
            }
        } finally {
            CommandCacheExecution.removeCommandExec(playerId, "bank");
        }
    }

    /**
     * /is bank deposit <amount>
     */
    private void handleDeposit(Player player, Island island, String[] args) {
        UUID playerId = player.getUniqueId();
        if (!PlayerUtils.hasPermission(player, "skyllia.bank.deposit")) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.no-permission-deposit");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
            return;
        }

        boolean isAllowed = SkylliaAPI.getPermissionsManager()
                .hasPermission(player, island, BANK_DEPOSIT_PERMISSION, null, ConfigLoader.general.getDebugSettings().permission());
        if (!isAllowed) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.no-permission-deposit");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
            return;
        }

        if (args.length < 2) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.usage-deposit");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (!Double.isFinite(amount) || amount <= 0) {
                ConfigLoader.language.sendMessage(player, "addons.bank.player.invalid-amount-positive");
                CommandCacheExecution.removeCommandExec(playerId, "bank");
                return;
            }
        } catch (NumberFormatException e) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.invalid-amount");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
            return;
        }

        if (economy.getBalance(player) < amount) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.not-enough-money-player");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
            return;
        }

        EconomyResponse withdrawResponse = economy.withdrawPlayer(player, amount);
        if (withdrawResponse.transactionSuccess()) {
            boolean success = SkylliaBank.getBankManager().deposit(island.getId(), amount);
            if (success) {
                ConfigLoader.language.sendMessage(player, "addons.bank.player.success-deposit", Map.of("%amount%", economy.format(amount)));
            } else {
                EconomyResponse refundResponse = economy.depositPlayer(player, amount);
                if (refundResponse.transactionSuccess()) {
                    ConfigLoader.language.sendMessage(player, "addons.bank.player.error-deposit-refunded");
                } else {
                    logger.error("CRITICAL: bank deposit failed AND refund failed. " +
                                    "Player={} ({}), island={}, amount={}, balanceBeforeRefund={}. " +
                                    "Manual compensation required.",
                            player.getName(), player.getUniqueId(), island.getId(),
                            amount, economy.getBalance(player));
                    ConfigLoader.language.sendMessage(player, "addons.bank.player.critical-error-deposit");
                }
            }
            CommandCacheExecution.removeCommandExec(playerId, "bank");
        } else {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.error-withdraw-player", Map.of("%errorMessage%", withdrawResponse.errorMessage));
            CommandCacheExecution.removeCommandExec(playerId, "bank");
        }
    }

    /**
     * /is bank withdraw <amount>
     */
    private void handleWithdraw(Player player, Island island, String[] args) {
        UUID playerId = player.getUniqueId();
        if (!PlayerUtils.hasPermission(player, "skyllia.bank.withdraw")) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.no-permission-withdraw");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
            return;
        }

        boolean isAllowed = SkylliaAPI.getPermissionsManager()
                .hasPermission(player, island, BANK_WITHDRAW_PERMISSION, null, ConfigLoader.general.getDebugSettings().permission());
        if (!isAllowed) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.no-permission-withdraw");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
            return;
        }

        if (args.length < 2) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.usage-withdraw");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
            if (!Double.isFinite(amount) || amount <= 0) {
                ConfigLoader.language.sendMessage(player, "addons.bank.player.invalid-amount-positive");
                CommandCacheExecution.removeCommandExec(playerId, "bank");
                return;
            }
        } catch (NumberFormatException e) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.invalid-amount");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
            return;
        }

        // Vérifier le solde de l'île
        UUID islandId = island.getId();
        BankAccount bankAccount = SkylliaBank.getBankManager().getBankAccount(islandId);
        if (bankAccount.balance() < amount) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.not-enough-money-island");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
            return;
        }

        boolean success = SkylliaBank.getBankManager().withdraw(islandId, amount);

        if (success) {
            EconomyResponse depositResponse = economy.depositPlayer(player, amount);
            if (depositResponse.transactionSuccess()) {
                ConfigLoader.language.sendMessage(player, "addons.bank.player.success-withdraw", Map.of("%amount%", economy.format(amount)));
                CommandCacheExecution.removeCommandExec(playerId, "bank");
            } else {
                boolean refund = SkylliaBank.getBankManager().deposit(islandId, amount);
                if (refund) {
                    ConfigLoader.language.sendMessage(player, "addons.bank.player.error-deposit-player-refund-island");
                } else {
                    ConfigLoader.language.sendMessage(player, "addons.bank.player.critical-error-withdraw");
                }
                CommandCacheExecution.removeCommandExec(playerId, "bank");
            }
        } else {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.error-withdraw-island");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
        }
    }

    /**
     * /is bank balance
     */
    private void handleBalance(Player player, Island island) {
        UUID playerId = player.getUniqueId();
        if (!PlayerUtils.hasPermission(player, "skyllia.bank.balance")) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.no-permission-balance");
            CommandCacheExecution.removeCommandExec(player.getUniqueId(), "bank");
            return;
        }

        BankAccount bankAccount = SkylliaBank.getBankManager().getBankAccount(island.getId());

        if (bankAccount != null) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.balance", Map.of("%amount%", economy.format(bankAccount.balance())));
        } else {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.error-get-balance");
        }
        CommandCacheExecution.removeCommandExec(playerId, "bank");
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin,
                                               @NotNull CommandSender sender,
                                               @NotNull String[] args) {

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> commands = Arrays.asList("deposit", "withdraw", "balance", "top", "baltop", "rank");
            return commands.stream()
                    .filter(cmd -> cmd.startsWith(partial))
                    .collect(Collectors.toList());
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("deposit") || args[0].equalsIgnoreCase("withdraw"))) {
            String partial = args[1].trim().toLowerCase();
            List<String> amounts = Arrays.asList("1", "10", "100", "1000");
            return amounts.stream()
                    .filter(amount -> amount.startsWith(partial))
                    .collect(Collectors.toList());
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("top") || args[0].equalsIgnoreCase("baltop"))) {
            String partial = args[1].trim().toLowerCase();
            List<String> pages = Arrays.asList("1", "2", "3", "4", "5");
            return pages.stream()
                    .filter(page -> page.startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private void handleRank(Player player, Island island, String[] args) {
        List<BankAccount> top;
        List<BankAccount> cached = SkylliaBank.getInstance().getPapiCache().getTopIfValid();
        if (cached != null) {
            top = cached;
        } else {
            top = SkylliaBank.getBankManager().getTopBalances();
            if (top != null) SkylliaBank.getInstance().getPapiCache().putTop(top);
        }
        if (top == null) top = List.of();
        final UUID playerIslandId = island.getId();
        int myRank = 0;
        BankAccount myEntry = null;
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).islandId().equals(playerIslandId)) {
                myRank = i + 1;
                myEntry = top.get(i);
                break;
            }
        }
        if (myRank > 0) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.top-self", Map.of(
                    "%rank%", String.valueOf(myRank),
                    "%owner%", island.getOwner().getLastKnowName(),
                    "%amount%", economy.format(myEntry.balance())
            ), false);
        } else {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.top-self-unranked", Map.of(
                    "%owner%", island.getOwner().getLastKnowName()
            ));
        }
    }

    private void handleTop(Player player, Island island, String[] args) {
        UUID playerId = player.getUniqueId();

        if (!PlayerUtils.hasPermission(player, "skyllia.bank.top")) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.no-permission-top");
            CommandCacheExecution.removeCommandExec(playerId, "bank");
            return;
        }

        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
                if (page < 1) page = 1;
            } catch (NumberFormatException e) {
                ConfigLoader.language.sendMessage(player, "addons.bank.player.invalid-page", Map.of("%input%", args[1]));
                CommandCacheExecution.removeCommandExec(playerId, "bank");
                return;
            }
        }
        final int pageSize = 10;
        final int finalPage = page;


        List<BankAccount> top;
        List<BankAccount> cached = SkylliaBank.getInstance().getPapiCache().getTopIfValid();
        if (cached != null) {
            top = cached;
        } else {
            top = SkylliaBank.getBankManager().getTopBalances();
            if (top != null) SkylliaBank.getInstance().getPapiCache().putTop(top);
        }
        if (top == null) top = List.of();

        int totalEntries = top.size();
        int maxPage = Math.max(1, (int) Math.ceil(totalEntries / (double) pageSize));
        int safePage = Math.min(finalPage, maxPage);
        int start = (safePage - 1) * pageSize;
        int end = Math.min(start + pageSize, totalEntries);

        ConfigLoader.language.sendMessage(player, "addons.bank.player.top-header", Map.of(
                "%page%", String.valueOf(safePage),
                "%max_page%", String.valueOf(maxPage),
                "%total%", String.valueOf(totalEntries)
        ), false);

        if (totalEntries == 0) {
            ConfigLoader.language.sendMessage(player, "addons.bank.player.top-empty");
        } else {
            for (int i = start; i < end; i++) {
                BankAccount entry = top.get(i);
                Island entryIsland = SkylliaAPI.getIslandByIslandId(entry.islandId());
                String ownerName = "Unknown";
                if (entryIsland != null && entryIsland.getOwner() != null) {
                    String n = entryIsland.getOwner().getLastKnowName();
                    if (n != null) ownerName = n;
                }
                ConfigLoader.language.sendMessage(player, "addons.bank.player.top-line", Map.of(
                        "%rank%", String.valueOf(i + 1),
                        "%owner%", ownerName,
                        "%amount%", economy.format(entry.balance())
                ), false);
            }
        }

    }
}