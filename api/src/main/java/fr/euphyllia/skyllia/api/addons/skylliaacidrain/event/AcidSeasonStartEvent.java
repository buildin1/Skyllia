package fr.euphyllia.skyllia.api.addons.skylliaacidrain.event;

import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when an acid season (酸雨季) starts in a Skyblock world.
 *
 * <p>The <b>SkylliaAcidRain</b> addon fires this event once its season
 * scheduler determines, based on the configured vanilla moon-phase cycle,
 * that a new acid season begins in {@link #getWorld()}. From this moment
 * until the matching {@link AcidSeasonEndEvent} is fired, the acid damage
 * mechanic (see {@link EntityDamageAcidEvent}) is active in that world.</p>
 *
 * <p>This is a notification-only event: it is not cancellable, since no
 * single entity is involved.</p>
 */
public class AcidSeasonStartEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final World world;

    public AcidSeasonStartEvent(@NotNull World world) {
        this.world = world;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    /**
     * Returns the world in which the acid season has started.
     */
    public @NotNull World getWorld() {
        return world;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }
}
