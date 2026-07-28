package fr.euphyllia.skylliaupgrade.configuration;

import java.util.List;

/**
 * 一个升级等级的完整定义：达到该等级后岛屿的边境半径/成员上限，
 * 以及升到该等级所需的门槛（岛屿 score）和成本（材料 + 领地拓展令）。
 */
public record UpgradeLevelDefinition(
        int level,
        double size,
        int maxMembers,
        double scoreThreshold,
        int tokenCount,
        List<MaterialCost> materials
) {
}
