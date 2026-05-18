package fr.euphyllia.skylliabank.cache;

import fr.euphyllia.skyllia.api.utils.ExpiringValue;
import fr.euphyllia.skylliabank.api.BankAccount;
import fr.euphyllia.skylliabank.configuration.BankConfigLoader;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class BankPapiCache {

    private final ConcurrentHashMap<UUID, ExpiringValue<Double>> balanceByIsland = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Double>> inFlight = new ConcurrentHashMap<>();

    private final AtomicReference<ExpiringValue<List<BankAccount>>> topCache = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<List<BankAccount>>> topInFlight = new AtomicReference<>();

    private static <K, V> @Nullable V getIfValid(ConcurrentHashMap<K, ExpiringValue<V>> map, K key) {
        ExpiringValue<V> ev = map.get(key);
        if (ev == null) return null;
        if (ev.isExpired()) {
            map.remove(key, ev);
            return null;
        }
        return ev.get();
    }

    public @Nullable Double getBalanceIfValid(UUID islandId) {
        return getIfValid(balanceByIsland, islandId);
    }

    public void putBalance(UUID islandId, double balance) {
        balanceByIsland.put(islandId, ExpiringValue.of(balance, BankConfigLoader.config.getTtlCache(), TimeUnit.SECONDS));
    }

    public void invalidate(UUID islandId) {
        balanceByIsland.remove(islandId);
        inFlight.remove(islandId);
        topCache.set(null);
    }

    public void clear() {
        balanceByIsland.clear();
        inFlight.clear();
        topCache.set(null);
        topInFlight.set(null);
    }

    public double getBalanceOrDefaultAndRefresh(
            Plugin plugin,
            UUID islandId,
            Supplier<Double> dbLoaderSync,
            double fallback
    ) {
        Double cached = getBalanceIfValid(islandId);
        if (cached != null) return cached;

        refreshAsync(plugin, islandId, dbLoaderSync);
        return fallback;
    }

    public void refreshAsync(Plugin plugin, UUID islandId, Supplier<Double> dbLoaderSync) {
        inFlight.computeIfAbsent(islandId, id -> {
            CompletableFuture<Double> f = new CompletableFuture<>();
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try {
                    Double bal = dbLoaderSync.get();
                    if (bal != null) putBalance(islandId, bal);
                    f.complete(bal);
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                } finally {
                    inFlight.remove(id);
                }
            });
            return f;
        });
    }

    public @Nullable List<BankAccount> getTopIfValid() {
        ExpiringValue<List<BankAccount>> ev = topCache.get();
        if (ev == null) return null;
        if (ev.isExpired()) {
            topCache.compareAndSet(ev, null);
            return null;
        }
        return ev.get();
    }

    public void putTop(List<BankAccount> top) {
        topCache.set(ExpiringValue.of(top, BankConfigLoader.config.getTtlCacheTop(), TimeUnit.SECONDS));
    }

    public List<BankAccount> getTopOrDefaultAndRefresh(
            Plugin plugin,
            Supplier<List<BankAccount>> dbLoaderSync,
            List<BankAccount> fallback
    ) {
        List<BankAccount> cached = getTopIfValid();
        if (cached != null) return cached;

        refreshTopAsync(plugin, dbLoaderSync);
        return fallback;
    }

    public void refreshTopAsync(Plugin plugin, Supplier<List<BankAccount>> dbLoaderSync) {
        if (topInFlight.get() != null) return;

        CompletableFuture<List<BankAccount>> f = new CompletableFuture<>();
        if (!topInFlight.compareAndSet(null, f)) return;

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                List<BankAccount> top = dbLoaderSync.get();
                if (top != null) putTop(top);
                f.complete(top);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            } finally {
                topInFlight.compareAndSet(f, null);
            }
        });
    }
}
