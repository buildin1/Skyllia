package fr.euphyllia.skyllia.api.addons.skylliaacidrain.event;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when an acid season (酸雨季) ends in a Skyblock world.
 *
 * <p>The <b>SkylliaAcidRain</b> addon fires this event once the configured
 * season duration elapses for a world that was in an active acid season
 * (see {@link AcidSeasonStartEvent}). After this event, the acid damage
 * mechanic (see {@link EntityDamageAcidEvent}) is inactive in
 * {@link #getWorld()} until the next season starts.</p>
 *
 * <p>This is a notification-only event: it is not cancellable, since no
 * single entity is involved.</p>
 */
public class AcidSeasonEndEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final World world;

    public AcidSeasonEndEvent(@NotNull World world) {
        this.world = world;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    /**
     * Returns the world in which the acid season has ended.
     */
    public @NotNull World getWorld() {
        return world;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }
}
