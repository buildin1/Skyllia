package fr.euphyllia.skylliaislandlevel.listener;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.database.IslandCustomDataQuery;
import fr.euphyllia.skyllia.api.event.SkyblockCreateEvent;
import fr.euphyllia.skyllia.api.event.SkyblockLoadEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliaislandlevel.SkylliaIslandLevel;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;

public class IslandLevelListener implements Listener {

    private static final String KEY_SCORE = "score";
    private static final String KEY_LEVEL = "level";
    private final NamespacedKey namespaceKey;
    private final IslandCustomDataQuery customDataQuery;

    public IslandLevelListener(SkylliaIslandLevel plugin) {
        this.namespaceKey = new NamespacedKey(plugin, "data");
        this.customDataQuery = SkylliaAPI.getIslandCustomDataQuery();
    }

    @EventHandler
    public void onIslandLoad(SkyblockLoadEvent event) {
        initIsland(event.getIsland());
    }

    @EventHandler
    public void onIslandCreate(SkyblockCreateEvent event) {
        initIsland(event.getIsland());
    }

    private void initIsland(Island island) {
        if (!customDataQuery.has(namespaceKey, island, KEY_SCORE)) {
            customDataQuery.set(namespaceKey, island, KEY_SCORE, PersistentDataType.DOUBLE, 0.0);
        }
        if (!customDataQuery.has(namespaceKey, island, KEY_LEVEL)) {
            customDataQuery.set(namespaceKey, island, KEY_LEVEL, PersistentDataType.LONG, 0L);
        }
    }
}