package fr.euphyllia.skylliachallenge.requirement;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import fr.euphyllia.skylliachallenge.storage.ProgressStoragePartial;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

public record PlayerConsumeRequirement(int requirementId, NamespacedKey challengeKey, String material,
                                       int count,
                                       String customNamespace, String customId) implements ChallengeRequirement {
    private static final Logger log = LoggerFactory.getLogger(PlayerConsumeRequirement.class);

    public PlayerConsumeRequirement(int requirementId, NamespacedKey challengeKey, String material, int count) {
        this(requirementId, challengeKey, material, count, null, null);
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
        String displayMaterial;
        if (isCustom()) {
            displayMaterial = customNamespace + ":" + customId;
        } else if (material.startsWith("potion[")) {
            displayMaterial = parsePotion(); // potion special handling
        } else {
            Material mat = Material.matchMaterial(material);
            if (mat != null) {
                String prefix = mat.isBlock() ? "block.minecraft." : "item.minecraft.";
                displayMaterial = "<lang:" + prefix + mat.getKey().getKey() + ">";
            } else {
                displayMaterial = material;
            }
        }
        return ConfigLoader.language.translate(locale, "addons.challenge.requirement.player_consume.display", Map.of(
                "%amount%", String.valueOf(count),
                "%material%", displayMaterial
        ), false);
    }

    public String getMaterial() {
        return material;
    }

    public boolean isPotionRequirement() {
        return material.startsWith("potion[");
    }

    public String parsePotion() {
        if (material.startsWith("potion[")) {
            String content = material.substring(7, material.length() - 1);
            String[] parts = content.split(",");
            String type = "";
            String level = "1";
            for (String part : parts) {
                String[] kv = part.split("=");
                if (kv.length != 2) continue;
                String key = kv[0].trim();
                String val = kv[1].trim();

                if (key.equalsIgnoreCase("type")) {
                    type = val.toUpperCase(Locale.ROOT);
                } else if (key.equalsIgnoreCase("level")) {
                    level = val;
                }
            }
            return "potion[type=" + type + ",level=" + level + "]";
        }
        return "";
    }

    public boolean isPotion(String potionConfig, String potionConsume) {
        String normalizedConfig = potionConfig.startsWith("potion[") ? potionConfig : parsePotion();
        return normalizedConfig.equalsIgnoreCase(potionConsume);
    }
}