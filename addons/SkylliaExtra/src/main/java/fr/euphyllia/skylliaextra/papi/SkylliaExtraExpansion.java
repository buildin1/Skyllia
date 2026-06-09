package fr.euphyllia.skylliaextra.papi;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliaextra.SkylliaExtra;
import fr.euphyllia.skylliaextra.utils.Keys;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public class SkylliaExtraExpansion extends PlaceholderExpansion {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private final SkylliaExtra plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public SkylliaExtraExpansion(SkylliaExtra plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return this.plugin.getName().toLowerCase();
    }

    @Override
    public @NotNull String getAuthor() {
        return this.plugin.getPluginMeta().getAuthors().getFirst();
    }

    @Override
    public @NotNull String getVersion() {
        return this.plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return List.of(
                "name",
                "name_raw",
                "description",
                "description_raw"
        );
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || !player.hasPlayedBefore()) return "";

        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) return "";

        String key = params.toLowerCase(Locale.ROOT);

        return switch (key) {
            case "name" -> {
                String raw = getRaw(island, Keys.KEY_NAME);
                yield raw != null ? render(raw) : player.getName() + "'s Island";
            }
            case "name_raw" -> {
                String raw = getRaw(island, Keys.KEY_NAME);
                yield raw != null ? raw : player.getName() + "'s Island";
            }
            case "description" -> {
                String raw = getRaw(island, Keys.KEY_DESCRIPTION);
                yield raw != null ? render(raw) : "";
            }
            case "description_raw" -> {
                String raw = getRaw(island, Keys.KEY_DESCRIPTION);
                yield raw != null ? raw : "";
            }
            default -> null;
        };
    }


    @Nullable
    private String getRaw(Island island, String dataKey) {
        return SkylliaAPI.getIslandCustomDataQuery().get(
                Keys.NAMESPACE_KEY,
                island,
                dataKey,
                PersistentDataType.STRING
        );
    }

    private String render(String miniMessageString) {
        Component component = miniMessage.deserialize(miniMessageString);
        return LEGACY.serialize(component);
    }
}
