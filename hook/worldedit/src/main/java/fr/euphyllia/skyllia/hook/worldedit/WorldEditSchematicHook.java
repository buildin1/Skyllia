package fr.euphyllia.skyllia.hook.worldedit;

import fr.euphyllia.skyllia.api.hooks.SchematicHook;
import fr.euphyllia.skyllia.api.skyblock.model.SchematicSetting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public class WorldEditSchematicHook implements SchematicHook {

    private static final Logger logger = LogManager.getLogger(WorldEditSchematicHook.class);

    private WorldEditSchematicPaster paster;
    private WorldEditListener worldEditListener;

    public WorldEditSchematicHook() {
    }

    @Override
    public String name() {
        return "WorldEdit";
    }

    @Override
    public boolean isAvailable() {
        return SchematicHook.hasClass("com.sk89q.worldedit.WorldEdit")
                && Bukkit.getPluginManager().getPlugin("WorldEdit") != null
                && !SchematicHook.hasClass("com.fastasyncworldedit.core.FaweAPI");
    }

    @Override
    public CompletableFuture<Boolean> paste(@NotNull Location loc, @NotNull SchematicSetting settings) {
        return paster.paste(loc, settings);
    }

    @Override
    public void register(@NotNull Plugin plugin) {
        paster = new WorldEditSchematicPaster();
        worldEditListener = new WorldEditListener();
        Bukkit.getPluginManager().registerEvents(worldEditListener, plugin);
    }

    @Override
    public void unregister() {
        WorldEditSchematicPaster.clearCache();
        if (worldEditListener != null) HandlerList.unregisterAll(worldEditListener);
    }
}
