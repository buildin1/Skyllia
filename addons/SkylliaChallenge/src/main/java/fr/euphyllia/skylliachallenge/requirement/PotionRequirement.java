package fr.euphyllia.skylliachallenge.requirement;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;

import java.util.Locale;
import java.util.Map;

public record PotionRequirement(int requirementId, NamespacedKey challengeKey, PotionType potionType, int data,
                                int count) implements ChallengeRequirement {

    @Override
    public boolean isMet(Player player, Island island) {
        long counted = player.getActivePotionEffects().stream()
                .map(PotionEffect::getType)
                .filter(type -> type.getKey().value().equalsIgnoreCase(potionType.getKey().value()))
                .count();
        return counted >= count;
    }

    @Override
    public Component getDisplay(Locale locale) {
        String potionDisplay = (potionType != null)
                ? "<lang:effect.minecraft." + potionType.getKey().getKey() + ">"
                : "未知效果";
        return ConfigLoader.language.translate(locale, "addons.challenge.requirement.potion.display", Map.of(
                "%potion_name%", potionDisplay,
                "%potion_data%", String.valueOf(data),
                "%amount%", String.valueOf(count)
        ), false);
    }
}