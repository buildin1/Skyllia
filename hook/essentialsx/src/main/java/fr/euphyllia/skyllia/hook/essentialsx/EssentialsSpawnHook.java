package fr.euphyllia.skyllia.hook.essentialsx;


import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.spawn.EssentialsSpawn;
import fr.euphyllia.skyllia.api.hooks.SpawnHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public class EssentialsSpawnHook implements SpawnHook {

    private static final boolean AVAILABLE =
            SpawnHook.hasClass("com.earth2me.essentials.spawn.EssentialsSpawn")
                    && SpawnHook.hasClass("com.earth2me.essentials.Essentials");

    private final EssentialsSpawn essentialsSpawn;
    private final Essentials essentials;

    public EssentialsSpawnHook() {
        this.essentialsSpawn =
                (EssentialsSpawn) Bukkit.getPluginManager().getPlugin("EssentialsSpawn");

        this.essentials =
                (Essentials) Bukkit.getPluginManager().getPlugin("Essentials");
    }

    @Override
    public boolean isAvailable() {
        return AVAILABLE
                && essentialsSpawn != null
                && essentials != null
                && essentialsSpawn.isEnabled()
                && essentials.isEnabled();
    }

    @Override
    public @Nullable Location getSpawnLocation(Player player) {
        if (!isAvailable()) {
            return null;
        }

        return essentialsSpawn.getSpawn(
                essentials.getUser(player.getUniqueId()).getGroup()
        );
    }
}
