package fr.euphyllia.skyllia.join;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.database.IslandCustomDataQuery;
import fr.euphyllia.skyllia.api.skyblock.Island;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 入岛申请的持久化。走主插件 {@link IslandCustomDataQuery}：
 * 岛上待处理申请挂在该岛的 custom-data 上；申请人「已有一笔待处理」挂在插件级 KV 上。
 */
public final class JoinRequestStore {

    private static final NamespacedKey NS = new NamespacedKey("skyllia", "join");
    private static final String REQ_PREFIX = "req.";
    private static final String OUT_PREFIX = "out.";

    private JoinRequestStore() {
    }

    public record Request(UUID applicantId, String applicantName, long createdAt) {
    }

    private static IslandCustomDataQuery db() {
        return SkylliaAPI.getIslandCustomDataQuery();
    }

    public static boolean put(@NotNull Island island, @NotNull UUID applicantId,
                              @NotNull String applicantName) {
        long now = System.currentTimeMillis();
        boolean islandOk = db().set(NS, island, REQ_PREFIX + applicantId, PersistentDataType.STRING,
                applicantName + "|" + now);
        boolean outOk = db().setPluginData(NS, OUT_PREFIX + applicantId, island.getId().toString());
        return islandOk && outOk;
    }

    public static boolean remove(@NotNull Island island, @NotNull UUID applicantId) {
        db().remove(NS, island, REQ_PREFIX + applicantId);
        String outgoing = db().getPluginData(NS, OUT_PREFIX + applicantId);
        if (outgoing != null && island.getId().toString().equals(outgoing)) {
            db().removePluginData(NS, OUT_PREFIX + applicantId);
        }
        return true;
    }

    public static @Nullable UUID outgoingIslandId(@NotNull UUID applicantId) {
        String raw = db().getPluginData(NS, OUT_PREFIX + applicantId);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static @Nullable Request get(@NotNull Island island, @NotNull UUID applicantId) {
        String raw = db().get(NS, island, REQ_PREFIX + applicantId, PersistentDataType.STRING);
        return parse(applicantId, raw);
    }

    public static boolean approveInvite(@NotNull UUID islandId, @NotNull UUID applicantId) {
        return db().setPluginData(NS, "inv." + islandId + "." + applicantId, "1");
    }

    public static boolean hasApprovedInvite(@NotNull UUID islandId, @NotNull UUID applicantId) {
        String raw = db().getPluginData(NS, "inv." + islandId + "." + applicantId);
        return raw != null && !raw.isBlank();
    }

    public static void clearApprovedInvite(@NotNull UUID islandId, @NotNull UUID applicantId) {
        db().removePluginData(NS, "inv." + islandId + "." + applicantId);
    }

    public static @NotNull List<Request> list(@NotNull Island island) {
        List<Request> result = new ArrayList<>();
        for (String key : db().getKeys(NS, island)) {
            if (!key.startsWith(REQ_PREFIX)) continue;
            try {
                UUID applicantId = UUID.fromString(key.substring(REQ_PREFIX.length()));
                Request parsed = parse(applicantId,
                        db().get(NS, island, key, PersistentDataType.STRING));
                if (parsed != null) result.add(parsed);
            } catch (IllegalArgumentException ignored) {
                // 坏 key 跳过
            }
        }
        return result;
    }

    private static @Nullable Request parse(UUID applicantId, @Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        int split = raw.lastIndexOf('|');
        String name = split < 0 ? raw : raw.substring(0, split);
        long at = 0L;
        if (split >= 0) {
            try {
                at = Long.parseLong(raw.substring(split + 1));
            } catch (NumberFormatException ignored) {
                at = 0L;
            }
        }
        return new Request(applicantId, name, at);
    }
}
