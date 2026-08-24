package fr.euphyllia.skylliachallenge.listener;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.addons.skylliaacidrain.event.AcidSeasonPlayerSurvivedEvent;
import fr.euphyllia.skyllia.api.addons.skylliaacidrain.event.AcidSeasonPlayerSurvivedOfflineEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliachallenge.SkylliaChallenge;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import fr.euphyllia.skylliachallenge.challenge.Challenge;
import fr.euphyllia.skylliachallenge.requirement.AcidSeasonSurviveRequirement;
import fr.euphyllia.skylliachallenge.storage.ProgressStoragePartial;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * 仅在 SkylliaAcidRain 安装时才会被注册（见 SkylliaChallenge#onEnable）。
 * 每次玩家挺过一次酸雨季节（未死亡且在场过半）时触发一次，为 ACIDSEASON 需求累加进度。
 * 结算时不在线走 {@link AcidSeasonPlayerSurvivedOfflineEvent}，进度按岛屿记，一样加。
 */
public class AcidSeasonRequirementListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAcidSeasonPlayerSurvived(final AcidSeasonPlayerSurvivedEvent event) {
        credit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAcidSeasonPlayerSurvivedOffline(final AcidSeasonPlayerSurvivedOfflineEvent event) {
        credit(event.getPlayerId());
    }

    private void credit(UUID uuid) {
        Bukkit.getAsyncScheduler().runNow(SkylliaChallenge.getInstance(), task -> {
            Island playerIsland = SkylliaAPI.getIslandByPlayerId(uuid);
            if (playerIsland == null) return;

            for (Challenge challenge : SkylliaChallenge.getInstance().getChallengeManager().getChallenges()) {
                if (challenge.getRequirements() == null) continue;
                for (ChallengeRequirement req : challenge.getRequirements()) {
                    if (req instanceof AcidSeasonSurviveRequirement asr) {
                        ProgressStoragePartial.addPartial(
                                playerIsland.getId(),
                                challenge.getId(),
                                asr.requirementId(),
                                1
                        );
                    }
                }
            }
        });
    }
}
