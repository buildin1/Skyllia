package fr.euphyllia.skyllia.cache.commands;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CommandCacheExecution {

    private static final Map<UUID, Set<String>> COMMAND_CACHE = new ConcurrentHashMap<>();

    public static boolean tryAcquire(UUID uuid, String command) {
        Set<String> set = COMMAND_CACHE.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        return set.add(command);
    }

    public static boolean isAlreadyExecute(UUID uuid, String command) {
        Set<String> commands = COMMAND_CACHE.get(uuid);
        return commands != null && commands.contains(command);
    }

    public static void addCommandExecute(UUID uuid, String command) {
        COMMAND_CACHE.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(command);
    }

    public static void removeCommandExec(UUID uuid, String command) {
        COMMAND_CACHE.computeIfPresent(uuid, (key, set) -> {
            set.remove(command);
            return set.isEmpty() ? null : set;
        });
    }

    public static void purgePlayer(UUID uuid) {
        COMMAND_CACHE.remove(uuid);
    }
}