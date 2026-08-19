package fr.euphyllia.skylliaislandlevel.commands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliaislandlevel.SkylliaIslandLevel;
import fr.euphyllia.skylliaislandlevel.api.IslandLevelRecord;
import fr.euphyllia.skylliaislandlevel.gui.ScoreDetailGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IslandLevelCommand implements SubCommandInterface {

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NonNull @NotNull String[] args) {
        if (!sender.hasPermission(permission())) {
            ConfigLoader.language.sendMessage(sender, "addons.island-value.no-permissions");
            return;
        }

        if (args.length == 0) {
            ConfigLoader.language.sendMessage(sender, "addons.island-value.level-command-usage");
            return;
        }

        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "addons.island-value.player-only-command");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "scan" -> handleScan(player);
            case "top" -> handleTop(player, args);
            case "rank" -> handleRank(player);
            case "detail" -> ScoreDetailGui.open(player);
            default -> ConfigLoader.language.sendMessage(player, "addons.island-value.level-command-usage");
        }
    }

    private void handleScan(Player player) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            ConfigLoader.language.sendMessage(player, "addons.island-value.not-on-island");
            return;
        }

        if (SkylliaIslandLevel.getInstance().getLevelManager().isScanning(island.getId())) {
            ConfigLoader.language.sendMessage(player, "addons.island-value.already-scanning");
            return;
        }

        ConfigLoader.language.sendMessage(player, "addons.island-value.scan-started");
        boolean started = SkylliaIslandLevel.getInstance().getLevelManager().triggerScan(island, (score, level) -> {
            ConfigLoader.language.sendMessage(player, "addons.island-value.scan-completed", Map.of(
                    "%score%", String.format("%.2f", score),
                    "%level%", Long.toString(level)
            ));
        });

        if (!started) {
            ConfigLoader.language.sendMessage(player, "addons.island-value.scan-start-failed");
        }
    }

    private void handleTop(Player sender, String[] args) {
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
                if (page < 1) page = 1;
            } catch (NumberFormatException e) {
                ConfigLoader.language.sendMessage(sender, "addons.island-value.invalid-page",
                        Map.of("%input%", args[1]));
                return;
            }
        }

        final int pageSize = 10;

        List<IslandLevelRecord> top = SkylliaIslandLevel.getInstance().getLevelManager().getTopIslands();

        int totalEntries = top.size();
        int maxPage = Math.max(1, (int) Math.ceil(totalEntries / (double) pageSize));
        int safePage = Math.min(page, maxPage);
        int start = (safePage - 1) * pageSize;
        int end = Math.min(start + pageSize, totalEntries);

        ConfigLoader.language.sendMessage(sender, "addons.island-value.top-header", Map.of(
                "%page%", String.valueOf(safePage),
                "%max_page%", String.valueOf(maxPage),
                "%total%", String.valueOf(totalEntries)
        ), false);

        if (totalEntries == 0) {
            ConfigLoader.language.sendMessage(sender, "addons.island-value.top-empty");
            return;
        }

        for (int i = start; i < end; i++) {
            IslandLevelRecord entry = top.get(i);
            Island island = SkylliaAPI.getIslandByIslandId(entry.islandId());

            String ownerName = "Unknown";
            if (island != null && island.getOwner() != null) {
                String n = island.getOwner().getLastKnowName();
                if (n != null) ownerName = n;
            }

            ConfigLoader.language.sendMessage(sender, "addons.island-value.top-line", Map.of(
                    "%rank%", String.valueOf(i + 1),
                    "%owner%", ownerName,
                    "%level%", String.valueOf(entry.level()),
                    "%score%", String.format("%.0f", entry.score())
            ), false);
        }
    }

    private void handleRank(Player player) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            ConfigLoader.language.sendMessage(player, "addons.island-value.not-on-island");
            return;
        }

        List<IslandLevelRecord> top = SkylliaIslandLevel.getInstance().getLevelManager().getTopIslands();

        final UUID islandId = island.getId();
        int myRank = 0;
        IslandLevelRecord myEntry = null;

        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).islandId().equals(islandId)) {
                myRank = i + 1;
                myEntry = top.get(i);
                break;
            }
        }

        if (myRank > 0) {
            ConfigLoader.language.sendMessage(player, "addons.island-value.rank-self", Map.of(
                    "%rank%", String.valueOf(myRank),
                    "%owner%", island.getOwner() != null ? island.getOwner().getLastKnowName() : "Unknown",
                    "%level%", String.valueOf(myEntry.level()),
                    "%score%", String.format("%.0f", myEntry.score())
            ), false);
        } else {
            ConfigLoader.language.sendMessage(player, "addons.island-value.rank-self-unranked", Map.of(
                    "%owner%", island.getOwner() != null ? island.getOwner().getLastKnowName() : "Unknown"
            ));
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NonNull @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return Stream.of("scan", "top", "rank", "detail")
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            String partial = args[1];
            return Stream.of("1", "2", "3", "4", "5")
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "skyllia.island-value.command";
    }
}
