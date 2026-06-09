package fr.euphyllia.skylliaextra.commands.admin;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliaextra.utils.Keys;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.*;

public class AdminSetNameCommand implements SubCommandInterface {
    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NonNull @NotNull String[] args) {
        if (!sender.hasPermission(permission())) {
            ConfigLoader.language.sendMessage(sender, "addons.skylliaextra.admin.setname.no-permission");
            return;
        }

        if (args.length < 2) {
            ConfigLoader.language.sendMessage(sender, "addons.skylliaextra.admin.setname.usage");
            return;
        }

        String targetName = args[1];
        UUID targetId;
        try {
            targetId = UUID.fromString(targetName);
        } catch (IllegalArgumentException ignored) {
            targetId = Bukkit.getPlayerUniqueId(targetName);
        }

        if (targetId == null) {
            ConfigLoader.language.sendMessage(sender, "addons.skylliaextra.admin.setname.player-not-found",
                    Map.of("%player%", targetName));
            return;
        }

        Island island = SkylliaAPI.getIslandByOwner(targetId);
        if (island == null) {
            island = SkylliaAPI.getIslandByPlayerId(targetId);
        }
        if (island == null) {
            ConfigLoader.language.sendMessage(sender, "addons.skylliaextra.admin.setname.no-island");
            return;
        }

        String rawName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        if (rawName.equalsIgnoreCase("reset")) {
            SkylliaAPI.getIslandCustomDataQuery().remove(
                    Keys.NAMESPACE_KEY, island, Keys.KEY_NAME
            );
            ConfigLoader.language.sendMessage(sender, "addons.skylliaextra.admin.setname.reset",
                    Map.of("%player%", targetName));
            return;
        }

        boolean success = SkylliaAPI.getIslandCustomDataQuery().set(
                Keys.NAMESPACE_KEY,
                island,
                Keys.KEY_NAME,
                PersistentDataType.STRING,
                rawName
        );

        if (success) {
            ConfigLoader.language.sendMessage(sender, "addons.skylliaextra.admin.setname.success",
                    Map.of("%player%", targetName, "%name%", rawName));
        } else {
            ConfigLoader.language.sendMessage(sender, "addons.skylliaextra.admin.setname.failed");
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> list = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                String n = player.getName();
                if (n.toLowerCase().startsWith(partial)) {
                    list.add(n);
                }
            }
            return list;
        }
        if (args.length == 2) return List.of("reset");
        return List.of();
    }

    @Override
    public String permission() {
        return "skyllia.admin.setname";
    }
}
