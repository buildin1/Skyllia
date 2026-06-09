package fr.euphyllia.skylliaextra.utils;

import fr.euphyllia.skylliaextra.SkylliaExtra;
import org.bukkit.NamespacedKey;

public class Keys {
    public static final String KEY_NAME = "island_name";
    public static final String KEY_DESCRIPTION = "island_description";
    public static NamespacedKey NAMESPACE_KEY;

    public static void init(SkylliaExtra plugin) {
        NAMESPACE_KEY = new NamespacedKey(plugin, "data");
    }
}