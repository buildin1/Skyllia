package fr.euphyllia.skylliaextra;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skylliaextra.commands.SetDescriptionCommand;
import fr.euphyllia.skylliaextra.commands.SetNameCommand;
import fr.euphyllia.skylliaextra.commands.admin.AdminSetDescriptionCommand;
import fr.euphyllia.skylliaextra.commands.admin.AdminSetNameCommand;
import fr.euphyllia.skylliaextra.listeners.InfoListener;
import fr.euphyllia.skylliaextra.papi.SkylliaExtraExpansion;
import fr.euphyllia.skylliaextra.utils.Keys;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class SkylliaExtra extends JavaPlugin {


    @Override
    public void onEnable() {
        Keys.init(this);

        SkylliaAPI.registerCommands(new SetNameCommand(this), "setname");
        SkylliaAPI.registerCommands(new SetDescriptionCommand(this), "setdescription");

        SkylliaAPI.registerAdminCommands(new AdminSetNameCommand(), "setname");
        SkylliaAPI.registerAdminCommands(new AdminSetDescriptionCommand(), "setdescription");

        Bukkit.getPluginManager().registerEvents(new InfoListener(), this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SkylliaExtraExpansion(this).register();
        }
    }

    @Override
    public void onDisable() {
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
    }
}
