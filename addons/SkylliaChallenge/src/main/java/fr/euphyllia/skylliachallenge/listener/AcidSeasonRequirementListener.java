package fr.euphyllia.skylliachallenge.listener;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.addons.skylliaacidrain.event.AcidSeasonPlayerSurvivedEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliachallenge.SkylliaChallenge;
import fr.euphyllia.skylliachallenge.api.requirement.ChallengeRequirement;
import fr.euphyllia.skylliachallenge.challenge.Challenge;
import fr.euphyllia.skylliachallenge.requirement.AcidSeasonSurviveRequirement;
import fr.euphyllia.skylliachallenge.storage.ProgressStoragePartial;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * 仅在 SkylliaAcidRain 安装时才会被注册（见 SkylliaChallenge#onEnable）。
 * 每次玩家挺过一次酸雨季节（未死亡且全程在场）时触发一次，为 ACIDSEASON 需求累加进度。
 * 该事件本身即代表"存活判定"，因此不再套用 mustBeOnPlayerIsland 的区块归属校验。
 */
public class AcidSeasonRequirementListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAcidSeasonPlayerSurvived(final AcidSeasonPlayerSurvivedEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

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
