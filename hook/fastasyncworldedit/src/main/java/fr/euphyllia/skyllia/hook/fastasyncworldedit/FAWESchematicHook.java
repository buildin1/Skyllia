package fr.euphyllia.skyllia.hook.fastasyncworldedit;

import fr.euphyllia.skyllia.api.hooks.SchematicHook;
import fr.euphyllia.skyllia.api.skyblock.model.SchematicSetting;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public class FAWESchematicHook implements SchematicHook {

    private FAWESchematicPaster paster;
    private FAWEListener faweListener;

    @Override
    public String name() {
        return "FastAsyncWorldEdit";
    }

    @Override
    public boolean isAvailable() {
        return SchematicHook.hasClass("com.fastasyncworldedit.core.FaweAPI")
                && Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
    }

    @Override
    public void register(@NotNull Plugin plugin) {
        paster = new FAWESchematicPaster();
        faweListener = new FAWEListener();
        Bukkit.getPluginManager().registerEvents(faweListener, plugin);
    }

    @Override
    public void unregister() {
        FAWESchematicPaster.clearCache();
        if (faweListener != null) HandlerList.unregisterAll(faweListener);
    }

    @Override
    public CompletableFuture<Boolean> paste(@NotNull Location loc, @NotNull SchematicSetting settings) {
        return paster.paste(loc, settings);
    }
}