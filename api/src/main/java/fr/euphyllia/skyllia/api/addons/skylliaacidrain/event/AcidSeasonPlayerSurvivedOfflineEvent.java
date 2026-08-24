package fr.euphyllia.skyllia.api.addons.skylliaacidrain.event;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 酸雨季结算时，该玩家已经攒够在场时长且本季未死亡，但此刻不在线。
 * <p>
 * 挑战进度按岛屿记，不依赖在线玩家实体，所以离线也要发事件。
 * 在线玩家走 {@link AcidSeasonPlayerSurvivedEvent}。
 * </p>
 */
public class AcidSeasonPlayerSurvivedOfflineEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final UUID playerId;
    private final World world;

    public AcidSeasonPlayerSurvivedOfflineEvent(@NotNull UUID playerId, @NotNull World world) {
        this.playerId = playerId;
        this.world = world;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    public @NotNull UUID getPlayerId() {
        return playerId;
    }

    public @NotNull World getWorld() {
        return world;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }
}
