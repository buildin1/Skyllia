package fr.euphyllia.skylliachallenge.requirement;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import fr.euphyllia.skylliachallenge.storage.ProgressStoragePartial;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

public record EnchantRequirement(int requirementId, NamespacedKey challengeKey, Enchantment enchantment, int level,
                                 int count,
                                 boolean strict) implements ChallengeRequirement {
    @Override
    public boolean isMet(Player player, Island island) {
        long collected = ProgressStoragePartial.getPartial(island.getId(), challengeKey, requirementId);
        return collected >= count;
    }

    @Override
    public Component getDisplay(Locale locale) {
        String enchantName = (enchantment != null)
                ? "<lang:enchantment.minecraft." + enchantment.getKey().getKey() + ">"
                : "未知附魔";
        return ConfigLoader.language.translate(locale, "addons.challenge.requirement.enchant.display", Map.of(
                "%enchantment_name%", enchantName,
                "%enchantment_level%", String.valueOf(level),
                "%amount%", String.valueOf(count)
        ), false);
    }

    public int getLevel() {
        return level;
    }

    public boolean isStrict() {
        return strict;
    }
}