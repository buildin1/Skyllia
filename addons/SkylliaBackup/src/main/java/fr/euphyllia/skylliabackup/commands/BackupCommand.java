package fr.euphyllia.skylliabackup.commands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliabackup.SkylliaBackup;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.UUID;

public class BackupCommand implements SubCommandInterface {

    private static final Logger log = LoggerFactory.getLogger(BackupCommand.class);
    private final SkylliaBackup plugin;

    public BackupCommand(SkylliaBackup plugin) {
        this.plugin = plugin;
    }


    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NonNull @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            log.error("You are not a player");
            return;
        }

        UUID playerId = player.getUniqueId();

        Island island = SkylliaAPI.getIslandByPlayerId(playerId);
        if (island == null) {
            log.error("Not island");
            return;
        }

        File result = this.plugin.getBackupManager().backupIsland(island, player.getName());
        if (result != null) {
            log.info("Backup created !");
        } else {
            log.error("ERROR");
        }

    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NonNull @NotNull String[] args) {
        return List.of();
    }

    @Override
    public String permission() {
        return "skyllia.island.backup";
    }
}
