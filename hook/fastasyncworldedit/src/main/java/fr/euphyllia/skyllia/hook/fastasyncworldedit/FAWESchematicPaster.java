package fr.euphyllia.skyllia.hook.fastasyncworldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.World;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.model.SchematicSetting;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class FAWESchematicPaster {

    private static final Map<File, ClipboardFormat> cache = new ConcurrentHashMap<>();
    private static final Logger logger = LogManager.getLogger(FAWESchematicPaster.class);

    private final Plugin plugin = SkylliaAPI.getPlugin();

    public static void clearCache() {
        cache.clear();
    }

    public CompletableFuture<Boolean> paste(Location loc, SchematicSetting settings) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                File file = new File(plugin.getDataFolder() + File.separator + settings.schematicFile());
                ClipboardFormat format = cache.computeIfAbsent(file, ClipboardFormats::findByFile);
                if (format == null) {
                    future.complete(false);
                    return;
                }
                Clipboard clipboard;
                try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
                    clipboard = reader.read();
                } catch (Exception e) {
                    logger.log(Level.ERROR, e.getMessage(), e);
                    future.complete(false);
                    return;
                }

                World w = BukkitAdapter.adapt(loc.getWorld());
                try (EditSession editSession = WorldEdit.getInstance().newEditSession(w)) {
                    editSession.setSideEffectApplier(SideEffectSet.defaults());
                    editSession.setReorderMode(EditSession.ReorderMode.FAST);
                    Operation operation = new ClipboardHolder(clipboard)
                            .createPaste(editSession)
                            .to(BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()))
                            .copyEntities(settings.copyEntities())
                            .ignoreAirBlocks(settings.ignoreAirBlocks())
                            .build();
                    Operations.completeLegacy(operation);
                }
                future.complete(true);
            } catch (Exception e) {
                logger.log(Level.ERROR, e.getMessage(), e);
                future.complete(false);
            } catch (Throwable t) {
                logger.log(Level.FATAL, t.getMessage(), t);
                future.complete(false);
            }
        });
        return future;
    }
}