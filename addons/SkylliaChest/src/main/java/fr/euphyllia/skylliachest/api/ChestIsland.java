package fr.euphyllia.skylliachest.api;

import fr.euphyllia.skyllia.api.skyblock.Island;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChestIsland {

    private final int size;
    private final Component title;
    private final Island island;
    private final Map<Integer, ItemStack> itemsIndexed;
    private boolean dirty;

    public ChestIsland(Island island, int size, Component title, Map<Integer, ItemStack> itemsContent) {
        this.island = island;
        this.size = size;
        this.title = title;
        this.itemsIndexed = itemsContent;
        this.dirty = false;
    }

    @NotNull
    public Island getIsland() {
        return island;
    }

    public int getSize() {
        return size;
    }

    @NotNull
    public Component getTitle() {
        return title;
    }

    @NotNull
    public Map<Integer, ItemStack> getItemsIndexed() {
        return new ConcurrentHashMap<>(itemsIndexed);
    }

    @NotNull
    public ItemStack[] toItemStackArray() {
        ItemStack[] array = new ItemStack[size];
        for (Map.Entry<Integer, ItemStack> entry : itemsIndexed.entrySet()) {
            Integer index = entry.getKey();
            ItemStack item = entry.getValue();
            if (index < size) {
                array[index] = item.clone();
            }
        }
        return array;
    }


    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }
}
