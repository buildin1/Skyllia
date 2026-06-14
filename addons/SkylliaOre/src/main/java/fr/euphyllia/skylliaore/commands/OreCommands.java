package fr.euphyllia.skylliaore.commands;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.utils.PlayerUtils;
import fr.euphyllia.skylliaore.SkylliaOre;
import fr.euphyllia.skylliaore.api.Generator;
import fr.euphyllia.skylliaore.config.DefaultConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OreCommands implements SubCommandInterface {

    private SkylliaOre plugin;

    public OreCommands(SkylliaOre plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!PlayerUtils.hasPermission(sender, "skylliaore.use")) return;

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!PlayerUtils.hasPermission(sender, "skylliaore.reload")) {
                sender.sendMessage(Component.text("You don't have permission to reload SkylliaOre.").color(NamedTextColor.RED));
                return;
            }
            try {
                this.plugin.reloadPlugin();
                sender.sendMessage(Component.text("SkylliaOre configuration reloaded successfully.").color(NamedTextColor.GREEN));
            } catch (Exception exception) {
                sender.sendMessage(Component.text("An error occurred while reloading the configuration: " + exception.getMessage()).color(NamedTextColor.RED));
            }
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /skylliaadmin generator <player> <generator>").color(NamedTextColor.RED));
            sender.sendMessage(Component.text("       /skylliaadmin generator reload").color(NamedTextColor.RED));
            return;
        }

        OfflinePlayer offPlayer = Bukkit.getOfflinePlayer(args[0]);
        Island island = SkylliaAPI.getIslandByPlayerId(offPlayer.getUniqueId());
        if (island == null) {
            sender.sendMessage(Component.text("No island found.").color(NamedTextColor.RED));
            return;
        }

        final String nameGenerator = args[1];
        Generator generator = SkylliaOre.getDefaultConfig().getGenerators().get(nameGenerator);

        if (generator == null) {
            sender.sendMessage(Component.text("The generator '" + nameGenerator + "' does not exist.").color(NamedTextColor.RED));
            return;
        }

        boolean success = SkylliaOre.getGeneratorManager().updateGenerator(island.getId(), generator.name());
        if (success) {
            sender.sendMessage(Component.text("Generator changed to '" + generator.name() + "'.").color(NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("An error occurred while changing the generator.").color(NamedTextColor.RED));
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].trim().toLowerCase();

            List<String> suggestions = new ArrayList<>(Bukkit.getOnlinePlayers()).stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
            suggestions.add("reload");
            return suggestions.stream()
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .sorted()
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            String partial = args[1].trim().toLowerCase();
            DefaultConfig config = SkylliaOre.getDefaultConfig();
            Map<String, Generator> generators = config.getGenerators();
            return generators.keySet().stream()
                    .filter(genName -> genName.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}