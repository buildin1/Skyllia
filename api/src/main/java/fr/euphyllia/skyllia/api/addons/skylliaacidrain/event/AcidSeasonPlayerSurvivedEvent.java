package fr.euphyllia.skyllia.api.addons.skylliaacidrain.event;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Called once per player, at the exact moment {@link AcidSeasonEndEvent} fires
 * for a world, for every player who was online in that world when the acid
 * season started and who remained online, in that same world, and alive for
 * the season's entire duration.
 *
 * <p>Players who joined the world after the season started, left the world
 * (or the server) before it ended, or died — of any cause, not just acid
 * damage — at any point between the season's start and end are excluded and
 * will not receive this event.</p>
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
