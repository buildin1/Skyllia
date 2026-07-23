package fr.euphyllia.skylliachallenge.requirement;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import fr.euphyllia.skylliachallenge.storage.ProgressStoragePartial;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Map;

public record CraftRequirement(int requirementId, NamespacedKey challengeKey, Material material, int count,
                               String itemName,
                               int customModelData, NamespacedKey itemModel,
                               String customNamespace, String customId) implements ChallengeRequirement {

    public static final boolean HAS_ITEM_MODEL_METHOD;

    static {
        boolean hasMethod;
        try {
            ItemMeta.class.getMethod("getItemModel");
            hasMethod = true;
        } catch (NoSuchMethodException e) {
            hasMethod = false;
        }
        HAS_ITEM_MODEL_METHOD = hasMethod;
    }

    public CraftRequirement(int requirementId, NamespacedKey challengeKey, Material material, int count,
                            String itemName, int customModelData, NamespacedKey itemModel) {
        this(requirementId, challengeKey, material, count, itemName, customModelData, itemModel, null, null);
    }

    public boolean isCustom() {
        return customNamespace != null && customId != null;
    }

    @Override
    public boolean isMet(Player player, Island island) {
        long collected = ProgressStoragePartial.getPartial(island.getId(), challengeKey, requirementId);
        return collected >= count;
    }

    @Override
    public Component getDisplay(Locale locale) {
        String displayName;
        if (isCustom()) {
            displayName = itemName;
        } else if (material != null) {
            String prefix = material.isBlock() ? "block.minecraft." : "item.minecraft.";
            displayName = "<lang:" + prefix + material.getKey().getKey() + ">";
        } else {
            displayName = itemName;
        }
        return ConfigLoader.language.translate(locale, "addons.challenge.requirement.craft.display", Map.of(
                "%item_name%", displayName,
                "%amount%", String.valueOf(count)
        ), false);
    }
}