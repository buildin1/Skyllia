package fr.euphyllia.skyllia.api.addons.skylliaacidrain.event;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Called once per player, at the exact moment {@link AcidSeasonEndEvent} fires
 * for a world, for every player who accumulated at least half of the season's
 * duration in that world and did not die during it.
 *
 * <p>Players who join mid-season still count, as long as they stay long enough.
 * Players who die — of any cause, not just acid damage — are disqualified
 * for that season and will not receive this event.</p>
 *
 * <p>Fired by the <b>SkylliaAcidRain</b> addon. This is a notification-only
 * event.</p>
 */
public class AcidSeasonPlayerSurvivedEvent extends PlayerEvent {

    private static final HandlerList handlers = new HandlerList();
    private final World world;

    public AcidSeasonPlayerSurvivedEvent(@NotNull Player player, @NotNull World world) {
        super(player);
        this.world = world;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    /**
     * Returns the world the acid season took place in. This is usually the
     * same as {@code getPlayer().getWorld()} at the moment this event fires,
     * but is passed explicitly since the player could theoretically have
     * left and rejoined the world during the season.
     */
    public @NotNull World getWorld() {
        return world;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }
}
