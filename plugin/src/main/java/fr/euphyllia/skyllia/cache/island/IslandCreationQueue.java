package fr.euphyllia.skyllia.cache.island;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.commands.common.subcommands.CreateSubCommand;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-wide queue for island creation requests.
 * <p>
 * Up to {@code settings.island.queue.max-concurrent} (see config.toml)
 * creations are allowed to run in parallel rather than strictly one at a
 * time. The slow steps of a creation (schematic paste, teleport, world
 * border...) genuinely run concurrently; only the single database write that
 * claims a region for the new island is serialized — via a lock in
 * {@link fr.euphyllia.skyllia.managers.skyblock.SkyblockManager#createIsland} —
 * so two concurrent creations can never be assigned the same region, even on
 * database engines whose schema does not enforce that uniqueness itself.
 * <p>
 */
public class IslandCreationQueue {

    private static final Queue<IslandCreationRequest> creationQueue = new ConcurrentLinkedQueue<>();
    private static final Logger logger = LogManager.getLogger(IslandCreationQueue.class);
    private static final AtomicInteger activeCreations = new AtomicInteger(0);

    public static synchronized void queuePlayer(Player player, String[] args) {
        UUID uuid = player.getUniqueId();

        int index = 1;
        for (IslandCreationRequest req : creationQueue) {
            if (req.uuid().equals(player.getUniqueId())) {
                ConfigLoader.language.sendMessage(player, "island.create.already-in-queue", Map.of("%position%", String.valueOf(index)));
                return;
            }
            index++;
        }


        creationQueue.add(new IslandCreationRequest(uuid, args));
        ConfigLoader.language.sendMessage(player, "island.create.queued", Map.of("%position%", String.valueOf(creationQueue.size())));

        pumpQueue();
    }

    /**
     * Starts as many queued creations as the configured concurrency limit
     * still allows, then returns — it never blocks waiting for a creation to
     * finish. Safe to call redundantly (e.g. once per queued player and once
     * per finished creation): each call only starts what capacity permits,
     * and does nothing if the queue is empty or already at capacity.
     */
    private static synchronized void pumpQueue() {
        removeOfflinePlayers();

        if (creationQueue.isEmpty()) return;

        final int max = ConfigLoader.general.getIslandSettings().resolvedMaxConcurrentCreations();
        boolean startedAny = false;

        while (activeCreations.get() < max) {
            IslandCreationRequest request = creationQueue.poll();
            if (request == null) break;

            Player player = Bukkit.getPlayer(request.uuid());
            if (player == null || !player.isOnline()) continue;

            activeCreations.incrementAndGet();
            startedAny = true;
            startCreation(request, player);
        }

        if (startedAny) {
            broadcastPositions();
        }
    }

    private static void startCreation(IslandCreationRequest request, Player player) {
        new CreateSubCommand().runCreateIsland(Skyllia.getInstance(), player, request.args())
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        logger.error("Island creation failed for {}", request.uuid(), throwable);
                    }
                    activeCreations.decrementAndGet();
                    pumpQueue();
                });
    }

    public static synchronized boolean isQueued(UUID uuid) {
        return creationQueue.stream().anyMatch(req -> req.uuid().equals(uuid));
    }

    private static void removeOfflinePlayers() {
        creationQueue.removeIf(req -> Bukkit.getPlayer(req.uuid()) == null);
    }

    private static void broadcastPositions() {
        int pos = 1;
        for (IslandCreationRequest req : creationQueue) {
            Player target = Bukkit.getPlayer(req.uuid());
            if (target != null) {
                ConfigLoader.language.sendMessage(target, "island.create.position-update", Map.of("%position%", String.valueOf(pos)));
            }
            pos++;
        }
    }

    private record IslandCreationRequest(UUID uuid, String[] args) {
    }
}