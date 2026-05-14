package fr.euphyllia.skylliabank.papi;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skylliabank.SkylliaBank;
import fr.euphyllia.skylliabank.api.BankAccount;
import fr.euphyllia.skylliabank.configuration.BankConfigLoader;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class SkylliaBankExpansion extends PlaceholderExpansion {

    private static final Logger log = LoggerFactory.getLogger(SkylliaBankExpansion.class);

    @Override
    public @NotNull String getIdentifier() {
        return "skybank";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Euphyllia";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return List.of(
                "balance",
                "balance_formatted",
                "rank",
                "top_<n>_name",
                "top_<n>_balance",
                "top_<n>_balance_formatted"
        );
    }


    @Override
    public @NotNull String getVersion() {
        return SkylliaBank.getInstance().getPluginMeta().getVersion();
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || !player.hasPlayedBefore()) return "";

        String key = params.toLowerCase(Locale.ROOT);

        if (key.startsWith("top_")) {
            return resolveTopPlaceholder(key);
        }

        UUID playerId = player.getUniqueId();
        Island island = SkylliaAPI.getIslandByPlayerId(playerId);
        if (island == null) return "";

        UUID islandId = island.getId();

        return switch (key) {
            case "balance" -> String.valueOf(getCachedBalance(islandId));
            case "balance_formatted" -> format(getCachedBalance(islandId));
            case "rank" -> {
                int rank = computeRank(islandId);
                yield rank > 0 ? String.valueOf(rank) : "";
            }
            default -> null;
        };
    }

    private @Nullable String resolveTopPlaceholder(String key) {
        String remainder = key.substring("top_".length());
        int underscore = remainder.indexOf('_');
        if (underscore <= 0) return null;

        int position;
        try {
            position = Integer.parseInt(remainder.substring(0, underscore));
        } catch (NumberFormatException e) {
            return null;
        }
        if (position <= 0) return "";

        String field = remainder.substring(underscore + 1);

        List<BankAccount> top = getCachedTop();
        if (position > top.size()) return "";

        BankAccount entry = top.get(position - 1);

        return switch (field) {
            case "name" -> resolveIslandOwnerName(entry.islandId());
            case "balance" -> String.valueOf(entry.balance());
            case "balance_formatted" -> format(entry.balance());
            default -> null;
        };
    }

    private int computeRank(UUID islandId) {
        List<BankAccount> top = getCachedTop();
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).islandId().equals(islandId)) {
                return i + 1;
            }
        }
        return 0;
    }

    private String resolveIslandOwnerName(UUID islandId) {
        Island island = SkylliaAPI.getIslandByIslandId(islandId);
        if (island == null) return "";
        Players owner = island.getOwner();
        if (owner == null) return "";
        String name = owner.getLastKnowName();
        return name != null ? name : "";
    }

    private double getCachedBalance(UUID islandId) {
        return SkylliaBank.getInstance().getPapiCache().getBalanceOrDefaultAndRefresh(
                SkylliaBank.getInstance(),
                islandId,
                () -> SkylliaBank.getBankManager().getBankAccount(islandId).balance(),
                0.0
        );
    }

    private List<BankAccount> getCachedTop() {
        return SkylliaBank.getInstance().getPapiCache().getTopOrDefaultAndRefresh(
                SkylliaBank.getInstance(),
                () -> SkylliaBank.getBankManager().getTopBalances(),
                List.of()
        );
    }

    private String format(double amount) {
        DecimalFormat df = new DecimalFormat(BankConfigLoader.config.getFormatBankAccount());
        df.setDecimalFormatSymbols(
                DecimalFormatSymbols.getInstance(BankConfigLoader.config.getLocalBankAccount())
        );
        return df.format(amount);
    }
}