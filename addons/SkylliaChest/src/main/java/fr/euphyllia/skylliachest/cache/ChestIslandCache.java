package fr.euphyllia.skylliachest.cache;

import fr.euphyllia.skylliachest.api.ChestIsland;
import fr.euphyllia.skylliachest.inventory.IslandChestInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class ChestIslandCache {

    private final Map<UUID, ChestIsland> cachedChests = new ConcurrentHashMap<>();
    private final Map<UUID, IslandChestInventory> cachedInventories = new ConcurrentHashMap<>();

    @NotNull
    public ChestIsland getOrCreateChest(@NotNull UUID islandId, @NotNull Function<UUID, ChestIsland> loader) {
        return cachedChests.computeIfAbsent(islandId, loader);
    }

    @NotNull
    public IslandChestInventory getOrCreateInventory(@NotNull UUID islandId, @NotNull Function<UUID, IslandChestInventory> factory) {
        return cachedInventories.computeIfAbsent(islandId, factory);
    }

    @Nullable
    public IslandChestInventory getCachedInventory(@NotNull UUID islandId) {
        return cachedInventories.get(islandId);
    }

    @NotNull
    public Map<UUID, ChestIsland> getAllCachedChests() {
        return Map.copyOf(cachedChests);
    }

    public void clear() {
        cachedChests.clear();
        cachedInventories.clear();
    }
}
