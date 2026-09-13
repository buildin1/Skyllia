package fr.euphyllia.skyllia.commands.common.subcommands;

import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.listeners.bukkitevents.player.IslandFlight;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * {@code /is fly}：在自己的空岛上开关飞行，规则见 {@link IslandFlight}。
 */
public class FlySubCommand implements SubCommandInterface {

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            ConfigLoader.language.sendMessage(sender, "island.player.player-only-command");
            return;
        }
        // SkylliaCommand 在 async 线程上执行子命令，改飞行状态必须回玩家自己的线程。
        player.getScheduler().run(plugin, task -> IslandFlight.toggle(player), null);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
