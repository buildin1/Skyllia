package fr.euphyllia.skyllia.hook.canvas;

import fr.euphyllia.skyllia.api.hooks.ServerHook;
import fr.euphyllia.skyllia.hook.canvas.player.PlayerRespawnHooks;
import fr.euphyllia.skyllia.hook.canvas.teleport.PlayerTeleportHooks;
import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;
import org.bukkit.plugin.Plugin;

public class CanvasHook implements ServerHook {

    @Override
    public String name() {
        return "Canvas";
    }

    @Override
    public boolean isAvailable() {
        return ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("canvasmc", "canvas"));
    }

    @Override
    public void register(Plugin skylliaPlugin) {
        var manager = skylliaPlugin.getServer().getPluginManager();
        manager.registerEvents(new PlayerTeleportHooks(), skylliaPlugin);
        manager.registerEvents(new PlayerRespawnHooks(), skylliaPlugin);
    }
}
