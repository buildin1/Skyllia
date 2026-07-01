package fr.euphyllia.skyllia.utils;

import fr.euphyllia.skyllia.Skyllia;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateCheckerTask {

    public static final String NOTIFY_PERMISSION = "skyllia.admin.update.notify";
    private static final Logger log = LogManager.getLogger(UpdateCheckerTask.class);
    private static final String MODRINTH_VERSIONS_URL =
            "https://api.modrinth.com/v2/project/skyllia/version";
    private static final String USER_AGENT =
            "Euphillya/Skyllia (modrinth.com/plugin/skyllia)";
    private static final String VERSION_PREFIX = "3.0-";
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version_number\"\\s*:\\s*\"(3\\.0-\\d+)\"");
    private static final boolean DEBUG_UPDATE_CHECKER = false;
    private static final String DEBUG_VERSION = "3.0-1";
    private static volatile UpdateInfo cachedUpdate = null;

    private UpdateCheckerTask() {
    }

    public static void start(Skyllia plugin) {
        long intervalMinutes = ConfigLoader.general.getUpdateCheckerSettings().intervalMinutes();
        Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                task -> runCheck(plugin),
                1L,
                intervalMinutes * 60,
                TimeUnit.SECONDS
        );
        log.debug("Update checker started (check interval: {} minutes).", intervalMinutes);
    }

    public static Optional<UpdateInfo> getCachedUpdate() {
        return Optional.ofNullable(cachedUpdate);
    }

    public static void notifyIfUpdateAvailable(Player player) {
        if (!player.hasPermission(NOTIFY_PERMISSION)) return;

        getCachedUpdate().ifPresent(update -> {
            String key = update.behindCount() == 1
                    ? "update-checker.update-available-one"
                    : "update-checker.update-available-many";

            ConfigLoader.language.sendMessage(player, key, Map.of(
                    "%version%", update.latestVersion(),
                    "%behind%", String.valueOf(update.behindCount()),
                    "%url%", update.pageUrl()
            ));
        });
    }

    public static void notifyConsoleIfUpdateAvailable() {
        getCachedUpdate().ifPresent(update -> {
            String key = update.behindCount() == 1
                    ? "update-checker.update-available-one"
                    : "update-checker.update-available-many";

            ConfigLoader.language.sendMessage(Bukkit.getConsoleSender(), key, Map.of(
                    "%version%", update.latestVersion(),
                    "%behind%", String.valueOf(update.behindCount()),
                    "%url%", update.pageUrl()
            ));
        });
    }

    private static void runCheck(Skyllia plugin) {
        log.debug("Checking for new Skyllia releases on Modrinth...");
        try {
            URL url = new URI(MODRINTH_VERSIONS_URL).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(15_000);

            int code = conn.getResponseCode();

            if (conn.getHeaderFieldInt("x-ratelimit-remaining", -1) == 0) {
                log.warn("Unable to check for updates because the Modrinth API rate limit has been reached.");
                return;
            }

            if (code != 200) {
                log.warn("Failed to check for updates: received HTTP status {} from Modrinth API.", code);
                return;
            }

            String responseBody;
            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                responseBody = sb.toString();
            }

            String currentVersion = DEBUG_UPDATE_CHECKER
                    ? DEBUG_VERSION
                    : plugin.getPluginMeta().getVersion();

            if (DEBUG_UPDATE_CHECKER) {
                log.warn(
                        "Update checker is running in DEBUG mode. Simulated version: {}",
                        currentVersion
                );
            }

            int currentBuild = parseBuildNumber(currentVersion);

            if (currentBuild == -1) {
                log.debug(
                        "Running a development build ({}). Update checking is skipped.",
                        currentVersion
                );
                return;
            }


            Matcher matcher = VERSION_PATTERN.matcher(responseBody);

            int latestBuild = -1;
            String latestVersionNumber = null;
            int behindCount = 0;

            while (matcher.find()) {
                String vn = matcher.group(1);
                int build = parseBuildNumber(vn);
                if (build == -1) continue;

                if (latestBuild == -1) {
                    latestBuild = build;
                    latestVersionNumber = vn;
                }

                if (build > currentBuild) {
                    behindCount++;
                }
            }

            if (latestBuild == -1 || latestVersionNumber == null) {
                log.warn("No valid Skyllia versions matching pattern '{}' were found in the Modrinth API response.", VERSION_PREFIX);
                return;
            }

            if (latestBuild > currentBuild) {
                String pageUrl = "https://modrinth.com/plugin/skyllia/version/" + latestVersionNumber;
                cachedUpdate = new UpdateInfo(latestVersionNumber, behindCount, pageUrl);

                log.info(
                        "Update available: current version={}, latest version={}, versions behind={}.",
                        currentVersion,
                        latestVersionNumber,
                        behindCount
                );

                notifyConsoleIfUpdateAvailable();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    notifyIfUpdateAvailable(player);
                }
            } else {
                cachedUpdate = null;

                log.debug(
                        "No updates found. Current version {} is up to date.",
                        currentVersion
                );
            }
        } catch (URISyntaxException | IOException e) {
            log.error("Failed to perform update check.", e);
        }
    }

    static int parseBuildNumber(String version) {
        if (version == null) return -1;
        int dash = version.lastIndexOf('-');
        if (dash == -1 || dash == version.length() - 1) return -1;
        try {
            return Integer.parseInt(version.substring(dash + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public record UpdateInfo(String latestVersion, int behindCount, String pageUrl) {
    }
}
