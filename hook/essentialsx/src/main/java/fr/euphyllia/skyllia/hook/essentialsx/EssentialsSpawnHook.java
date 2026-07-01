package fr.euphyllia.skyllia.hook.essentialsx;

import fr.euphyllia.skyllia.api.hooks.PluginHook;
import fr.euphyllia.skyllia.api.hooks.SpawnHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

public class EssentialsSpawnHook implements SpawnHook {

    private static final boolean CLASS_AVAILABLE =
            PluginHook.hasClass("com.earth2me.essentials.spawn.EssentialsSpawn")
                    && PluginHook.hasClass("com.earth2me.essentials.Essentials");

    private EssentialsDelegate delegate;

    public EssentialsSpawnHook() {
    }

    @Override
    public String name() {
        return "Essentials";
    }

    @Override
    public boolean isAvailable() {
        return CLASS_AVAILABLE
                && Bukkit.getPluginManager().getPlugin("EssentialsSpawn") != null
                && Bukkit.getPluginManager().getPlugin("Essentials") != null;
    }

    @Override
    public void register(Plugin skylliaPlugin) {
        this.delegate = new EssentialsDelegate();
    }

    @Override
    public @Nullable Location getSpawnLocation(Player player) {
        if (delegate == null || !delegate.isEnabled()) {
            return null;
        }
        return delegate.getSpawnLocation(player);
    }
}