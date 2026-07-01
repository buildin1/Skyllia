package fr.euphyllia.skyllia.api.event.skyllia;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Event fired when the Skyllia plugin is reloaded.
 *
 */
public class SkylliaReloadEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * Constructs a new {@code SkylliaReloadEvent}.
     *
     * <p>This event is always fired asynchronously.</p>
     */
    public SkylliaReloadEvent() {
        super(true);
    }

    /**
     * Gets the static handler list for this event type.
     *
     * <p>Required by Bukkit's event system to register and dispatch this event.</p>
     *
     * @return the static {@link HandlerList} for {@code SkylliaReloadEvent}
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /**
     * Gets the handler list for this event instance.
     *
     * <p>Required by Bukkit's event system. Delegates to {@link #getHandlerList()}.</p>
     *
     * @return the {@link HandlerList} for this event
     */
    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }
}