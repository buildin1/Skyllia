package fr.euphyllia.skyllia.commands.common;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.api.commands.SkylliaCommandInterface;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.commands.SubCommandRegistry;
import fr.euphyllia.skyllia.commands.common.subcommands.AccessSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.BanListCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.BanSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.CreateSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.DelWarpSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.DeleteSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.DemoteSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.ExpelSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.FlagSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.HomeSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.InfoSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.InviteSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.KickSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.LeaveSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.PermissionSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.PromoteSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.SetBiomeSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.SetDescriptionCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.SetHomeSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.SetNameCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.SetSpawnSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.SetVisitSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.SetWarpSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.TPSSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.TransferSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.TrustSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.UnbanSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.UntrustSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.VisitSubCommand;
import fr.euphyllia.skyllia.commands.common.subcommands.WarpSubCommand;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public class SkylliaCommand implements SkylliaCommandInterface {

    private final Skyllia plugin;
    private final SubCommandRegistry registry;

    public SkylliaCommand(Skyllia Skyllia) {
        this.plugin = Skyllia;
        this.registry = this.plugin.getCommandRegistry();
        registerDefaultCommands();
    }

    private void registerDefaultCommands() {
        registry.registerSubCommand(new AccessSubCommand(), "access");
        registry.registerSubCommand(new BanListCommand(), "banlist", "banned");
        registry.registerSubCommand(new BanSubCommand(), "ban");
        registry.registerSubCommand(new SetBiomeSubCommand(), "biome");
        registry.registerSubCommand(new CreateSubCommand(), "create");
        registry.registerSubCommand(new DeleteSubCommand(), "delete");
        registry.registerSubCommand(new DelWarpSubCommand(), "delwarp");
        registry.registerSubCommand(new DemoteSubCommand(), "demote");
        registry.registerSubCommand(new ExpelSubCommand(), "expel");
        registry.registerSubCommand(new HomeSubCommand(), "home", "go", "tp");
        registry.registerSubCommand(new InfoSubCommand(), "info");
        registry.registerSubCommand(new InviteSubCommand(), "invite", "add");
        registry.registerSubCommand(new KickSubCommand(), "kick");
        registry.registerSubCommand(new LeaveSubCommand(), "leave");
        registry.registerSubCommand(new PermissionSubCommand(), "permission");
        registry.registerSubCommand(new FlagSubCommand(), "flag", "gamerule");
        registry.registerSubCommand(new PromoteSubCommand(), "promote");
        registry.registerSubCommand(new TPSSubCommand(), "tps", "lag", "mspt");
        registry.registerSubCommand(new TransferSubCommand(), "transfer");
        registry.registerSubCommand(new TrustSubCommand(), "trust");
        registry.registerSubCommand(new SetHomeSubCommand(), "sethome");
        registry.registerSubCommand(new SetSpawnSubCommand(), "setspawn");
        registry.registerSubCommand(new SetVisitSubCommand(), "setvisit");
        registry.registerSubCommand(new SetWarpSubCommand(), "setwarp");
        registry.registerSubCommand(new UnbanSubCommand(), "unban");
        registry.registerSubCommand(new UntrustSubCommand(), "untrust");
        registry.registerSubCommand(new VisitSubCommand(), "visit");
        registry.registerSubCommand(new WarpSubCommand(), "warp");

        // extra
        registry.registerSubCommand(new SetNameCommand(plugin), "set_name", "setname");
        registry.registerSubCommand(new SetDescriptionCommand(plugin), "set_description", "setdescription");
    }

    @Override
    public void execute(@NonNull CommandSourceStack sender, String[] args) {
        if (args.length != 0) {
            String subCommand = args[0].trim().toLowerCase();
            String[] listArgs = Arrays.copyOfRange(args, 1, args.length);
            SubCommandInterface subCommandInterface = registry.getSubCommandByName(subCommand);
            if (subCommandInterface == null) {
                ConfigLoader.language.sendMessage(sender.getSender(), "misc.unknown-command");
                return;
            }
            Bukkit.getAsyncScheduler().runNow(this.plugin, task ->
                    subCommandInterface.onExecute(this.plugin, sender.getSender(), listArgs));
        } else {
            // If no subcommand is provided, we can default to the "create" command
            Bukkit.getAsyncScheduler().runNow(this.plugin, task ->
                    registry.getSubCommandByName("create").onExecute(this.plugin, sender.getSender(), args));
        }
    }

    @Override
    public @NonNull Collection<String> suggest(@NonNull CommandSourceStack sender, String[] args) {
        Set<String> commands = registry.getCommandMap().keySet();
        if (args.length == 0) {
            return commands;
        } else if (args.length == 1) {
            String partial = args[0].trim().toLowerCase();
            return commands.stream().filter(command -> command.toLowerCase().startsWith(partial)).toList();
        } else {
            String subCommand = args[0].trim().toLowerCase();
            String[] listArgs = Arrays.copyOfRange(args, 1, args.length);
            SubCommandInterface subCommandInterface = registry.getSubCommandByName(subCommand);
            if (subCommandInterface != null) {
                return subCommandInterface.onTabComplete(this.plugin, sender.getSender(), listArgs);
            }
        }
        return Collections.emptyList();
    }

    @Override
    public @Nullable String permission() {
        return null;
    }
}