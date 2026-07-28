package fr.euphyllia.skylliaupgrade.api;

import java.util.UUID;

/** 岛屿当前的升级等级（0 = 从未升级过） */
public record UpgradeRecord(UUID islandId, int level) {
}
