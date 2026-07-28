package fr.euphyllia.skylliachallenge.requirement;

import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import fr.euphyllia.skylliachallenge.storage.ProgressStoragePartial;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

/**
 * Requirement satisfied once the island's player has survived {@code count} acid seasons
 * (fired by the optional SkylliaAcidRain addon via {@code AcidSeasonPlayerSurvivedEvent}).
 */
public record AcidSeasonSurviveRequirement(int requirementId, NamespacedKey challengeKey,
                                           int count) implements ChallengeRequirement {
    @Override
    public boolean isMet(Player player, Island island) {
        long collected = ProgressStoragePartial.getPartial(island.getId(), challengeKey, requirementId);
        return collected >= count;
    }

    @Override
    public Component getDisplay(Locale locale) {
        return ConfigLoader.language.translate(locale, "addons.challenge.requirement.acid_season.display", Map.of(
                "%amount%", String.valueOf(count)
        ), false);
    }
}
