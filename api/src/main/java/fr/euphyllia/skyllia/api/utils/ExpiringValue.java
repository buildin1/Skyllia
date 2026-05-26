package fr.euphyllia.skyllia.api.utils;

import java.util.concurrent.TimeUnit;

/**
 * Represents a value associated with an expiration time.
 * <p>
 * This class is used for caching purposes, allowing values to automatically
 * become invalid after a specified time-to-live (TTL).
 * </p>
 *
 * <p>
 * Special behaviors:
 * <ul>
 *     <li>A TTL &lt; 0 should be handled externally to create a non-expiring value via {@link #neverExpire(Object)}.</li>
 *     <li>A TTL of 0 is typically treated as "no caching" and should be handled by the caller.</li>
 * </ul>
 * </p>
 *
 * <p>
 * Internally, expiration is tracked using {@link System#nanoTime()} to ensure
 * monotonic and high-resolution time measurement.
 * </p>
 *
 * @param <T> the type of the stored value
 */
public final class ExpiringValue<T> {

    /**
     * Special constant representing a value that never expires.
     */
    private static final long NEVER_EXPIRE = Long.MAX_VALUE;

    private final T value;
    private final long expiresAtNanos;

    /**
     * Creates a new expiring value.
     *
     * @param value          the value to store
     * @param expiresAtNanos the absolute expiration time in nanoseconds
     */
    private ExpiringValue(T value, long expiresAtNanos) {
        this.value = value;
        this.expiresAtNanos = expiresAtNanos;
    }

    /**
     * Creates a new {@link ExpiringValue} with a given time-to-live (TTL).
     *
     * @param value the value to store
     * @param ttl   the time-to-live
     * @param unit  the time unit of the TTL
     * @param <T>   the value type
     * @return a new expiring value instance
     */
    public static <T> ExpiringValue<T> of(T value, long ttl, TimeUnit unit) {
        long ttlNanos = unit.toNanos(ttl);
        long now = System.nanoTime();

        long expires;
        if (ttlNanos >= Long.MAX_VALUE - now) {
            // Prevent overflow by capping to "never expire"
            expires = Long.MAX_VALUE;
        } else {
            expires = now + ttlNanos;
        }

        return new ExpiringValue<>(value, expires);
    }

    /**
     * Creates a value that never expires.
     *
     * @param value the value to store
     * @param <T>   the value type
     * @return a non-expiring value
     */
    public static <T> ExpiringValue<T> neverExpire(T value) {
        return new ExpiringValue<>(value, NEVER_EXPIRE);
    }

    /**
     * Checks whether the value has expired.
     *
     * @return {@code true} if expired, {@code false} otherwise
     */
    public boolean isExpired() {
        return expiresAtNanos != NEVER_EXPIRE && System.nanoTime() >= expiresAtNanos;
    }

    /**
     * Returns the stored value.
     *
     * @return the value
     */
    public T get() {
        return value;
    }
}