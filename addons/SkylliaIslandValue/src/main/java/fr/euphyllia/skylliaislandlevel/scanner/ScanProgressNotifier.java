package fr.euphyllia.skylliaislandlevel.scanner;

import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliaislandlevel.SkylliaIslandLevel;
import fr.euphyllia.skylliaislandlevel.configuration.IslandLevelConfigLoader;
import fr.euphyllia.skylliaislandlevel.configuration.ScanNotificationConfig;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ScanProgressNotifier {

    private final int updateEveryNChunks;
    private final List<Player> onlineMembers;
    private final ScanNotificationConfig config;
    private final AtomicInteger updateCounter = new AtomicInteger(0);
    private BossBar bossBar;
    private volatile int lastPercent = -1;

    public ScanProgressNotifier(List<Player> onlineMembers) {
        this.onlineMembers = onlineMembers;
        var cfg = IslandLevelConfigLoader.config;
        this.config = cfg.getScanNotification();
        this.updateEveryNChunks = IslandLevelConfigLoader.config.getProcessingSettings().resolvedNotificationUpdateEvery();
        if (config.type() == ScanNotificationConfig.NotificationType.BOSS_BAR) {
            BossBar.Color color;
            try {
                color = BossBar.Color.valueOf(config.bossBarColor().toUpperCase());
            } catch (IllegalArgumentException e) {
                color = BossBar.Color.YELLOW;
            }
            bossBar = BossBar.bossBar(Component.empty(), 0f, color, BossBar.Overlay.PROGRESS);
            onlineMembers.forEach(p ->
                    p.getScheduler().run(SkylliaIslandLevel.getInstance(),
                            t -> {
                                if (p.isOnline()) p.showBossBar(bossBar);
                            },
                            null)
            );
        }
    }

    public void update(int done, int total) {
        if (updateCounter.incrementAndGet() % updateEveryNChunks != 0) return;

        float progress = total > 0 ? (float) done / total : 0f;
        int percent = (int) (progress * 100);

        if (percent == lastPercent) return;
        lastPercent = percent;

        Map<String, String> placeholders = Map.of(
                "%done%", String.valueOf(done),
                "%total%", String.valueOf(total),
                "%percent%", String.valueOf(percent)
        );

        switch (config.type()) {
            case BOSS_BAR -> {
                Component title = ConfigLoader.language.translate(
                        onlineMembers.getFirst().locale(),
                        "addons.island-value.notifier.title",
                        placeholders, false
                );
                bossBar.name(title);
                bossBar.progress(Math.clamp(progress, 0f, 1f));
            }
            case TITLE -> {
                for (Player p : onlineMembers) {
                    if (p.isOnline()) {
                        Component title = ConfigLoader.language.translate(
                                p.locale(), "addons.island-value.notifier.title", placeholders, false
                        );
                        Component sub = ConfigLoader.language.translate(
                                p.locale(), "addons.island-value.notifier.sub-title", placeholders, false
                        );
                        p.showTitle(Title.title(title, sub, Title.Times.times(
                                Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(500)
                        )));
                    }
                }
            }
            case ACTION_BAR -> {
                for (Player p : onlineMembers) {
                    if (p.isOnline()) {
                        Component title = ConfigLoader.language.translate(
                                p.locale(), "addons.island-value.notifier.title", placeholders, false
                        );
                        p.sendActionBar(title);
                    }
                }
            }
            case NONE -> { /* NOTHING */ }
        }
    }

    public void finish() {
        if (bossBar != null) {
            for (Player p : onlineMembers) {
                if (p.isOnline()) {
                    p.hideBossBar(bossBar);
                }
            }
        }
    }
}