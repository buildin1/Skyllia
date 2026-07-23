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

public record BlockBreakRequirement(int requirementId, NamespacedKey challengeKey, Material material,
                                    int count, String blockName,
                                    String customNamespace, String customId) implements ChallengeRequirement {

    public BlockBreakRequirement(int requirementId, NamespacedKey challengeKey, Material material,
                                 int count, String blockName) {
        this(requirementId, challengeKey, material, count, blockName, null, null);
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
        String displayBlock;
        if (isCustom()) {
            displayBlock = customNamespace + ":" + customId;
        } else if (material != null) {
            displayBlock = "<lang:block.minecraft." + material.getKey().getKey() + ">";
        } else {
            displayBlock = blockName; // fallback
        }
        return ConfigLoader.language.translate(locale, "addons.challenge.requirement.block_break.display", Map.of(
                "%amount%", String.valueOf(count),
                "%block_name%", displayBlock,
                "%material%", material != null ? material.name() : (customNamespace + ":" + customId)
        ), false);
    }

    public Material getMaterial() {
        return material;
    }
}