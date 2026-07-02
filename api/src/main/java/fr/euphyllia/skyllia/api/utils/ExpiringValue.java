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
    /**
     * Absolute time (nanos) after which the value is considered <em>stale</em>:
     * still safe to serve, but a background refresh should be triggered.
     * Equal to {@link #expiresAtNanos} for values created via {@link #of},
     * so the legacy behavior (stale == expired) is preserved for existing callers.
     */
    private final long staleAtNanos;
    private final long expiresAtNanos;

    /**
     * Creates a new expiring value.
     *
     * @param value          the value to store
     * @param staleAtNanos   the absolute staleness time in nanoseconds
     * @param expiresAtNanos the absolute expiration time in nanoseconds
     */
    private ExpiringValue(T value, long staleAtNanos, long expiresAtNanos) {
        this.value = value;
        this.staleAtNanos = staleAtNanos;
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
        long now = System.nanoTime();
        long expires = saturatedAdd(now, unit.toNanos(ttl));
        // Legacy semantics: no grace period, stale and expired coincide.
        return new ExpiringValue<>(value, expires, expires);
    }

    /**
     * Creates a new {@link ExpiringValue} with a soft TTL and a grace period,
     * enabling the <em>stale-while-revalidate</em> pattern.
     * <p>
     * After {@code ttl}, the value becomes {@linkplain #isStale() stale}: it can
     * still be served to callers, but a background refresh should be scheduled.
     * After {@code ttl + grace}, the value is {@linkplain #isExpired() expired}
     * and must be treated as a cache miss.
     * </p>
     *
     * @param value the value to store
     * @param ttl   the soft time-to-live (freshness window)
     * @param grace additional window during which the stale value may still be served
     * @param unit  the time unit of both {@code ttl} and {@code grace}
     * @param <T>   the value type
     * @return a new expiring value instance
     */
    public static <T> ExpiringValue<T> withGrace(T value, long ttl, long grace, TimeUnit unit) {
        long now = System.nanoTime();
        long staleAt = saturatedAdd(now, unit.toNanos(ttl));
        long expires = saturatedAdd(staleAt, unit.toNanos(grace));
        return new ExpiringValue<>(value, staleAt, expires);
    }

    private static long saturatedAdd(long base, long delta) {
        // Prevent overflow by capping to "never expire".
        if (delta >= Long.MAX_VALUE - base) {
            return NEVER_EXPIRE;
        }
        return base + delta;
    }

    /**
     * Creates a value that never expires.
     *
     * @param value the value to store
     * @param <T>   the value type
     * @return a non-expiring value
     */
    public static <T> ExpiringValue<T> neverExpire(T value) {
        return new ExpiringValue<>(value, NEVER_EXPIRE, NEVER_EXPIRE);
    }

    /**
     * Checks whether the value has expired (hard expiry: must be treated as a miss).
     *
     * @return {@code true} if expired, {@code false} otherwise
     */
    public boolean isExpired() {
        return expiresAtNanos != NEVER_EXPIRE && System.nanoTime() >= expiresAtNanos;
    }

    /**
     * Checks whether the value is stale (soft expiry: still serveable, but a
     * background refresh should be triggered). A value created without a grace
     * period ({@link #of}) reports stale exactly when it reports expired.
     *
     * @return {@code true} if stale, {@code false} otherwise
     */
    public boolean isStale() {
        return staleAtNanos != NEVER_EXPIRE && System.nanoTime() >= staleAtNanos;
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