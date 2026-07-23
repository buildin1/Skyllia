package fr.euphyllia.skylliachallenge.requirement;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import fr.euphyllia.skylliachallenge.storage.ProgressStoragePartial;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

public record FishRequirement(int requirementId, NamespacedKey challengeKey, Material entityType,
                              int count) implements ChallengeRequirement {
    @Override
    public boolean isMet(Player player, Island island) {
        long collected = ProgressStoragePartial.getPartial(island.getId(), challengeKey, requirementId);
        return collected >= count;
    }

    @Override
    public Component getDisplay(Locale locale) {
        String entityDisplay;
        if (entityType != null) {
            String prefix = entityType.isBlock() ? "block.minecraft." : "item.minecraft.";
            entityDisplay = "<lang:" + prefix + entityType.getKey().getKey() + ">";
        } else {
            entityDisplay = "未知物品";
        }
        return ConfigLoader.language.translate(locale, "addons.challenge.requirement.fishing.display", Map.of(
                "%amount%", String.valueOf(count),
                "%entity_type%", entityDisplay
        ), false);
    }
}