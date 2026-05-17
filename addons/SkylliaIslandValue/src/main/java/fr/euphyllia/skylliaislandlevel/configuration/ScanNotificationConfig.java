package fr.euphyllia.skylliaislandlevel.configuration;

public record ScanNotificationConfig(NotificationType type, String bossBarColor) {

    public enum NotificationType {BOSS_BAR, TITLE, ACTION_BAR, NONE}
}
