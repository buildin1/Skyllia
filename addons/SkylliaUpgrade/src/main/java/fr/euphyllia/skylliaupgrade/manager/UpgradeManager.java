package fr.euphyllia.skylliaupgrade.manager;

import fr.euphyllia.skyllia.api.exceptions.MaxIslandSizeExceedException;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliaislandlevel.SkylliaIslandLevel;
import fr.euphyllia.skylliaupgrade.api.UpgradeGenerator;
import fr.euphyllia.skylliaupgrade.configuration.MaterialCost;
import fr.euphyllia.skylliaupgrade.configuration.UpgradeConfigLoader;
import fr.euphyllia.skylliaupgrade.configuration.UpgradeLevelDefinition;
import fr.euphyllia.skylliaupgrade.token.UpgradeTokenItem;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 岛屿升级的核心逻辑：三轴门槛（岛屿 score/材料上交/领地拓展令）+ 扩大边境与成员上限。
 * <p>
 * {@link #check(Player, Island)} 只读校验，不产生任何副作用，供 GUI 实时展示是否满足条件；
 * {@link #performUpgrade(Player, Island)} 在校验通过的基础上真正执行扣除与生效。
 * </p>
 */
public class UpgradeManager {

    private static final Logger log = LoggerFactory.getLogger(UpgradeManager.class);

    public enum FailReason {
        NO_NEXT_LEVEL,
        SCORE_TOO_LOW,
        REQUIREMENTS_NOT_MET,
        SIZE_REJECTED
    }

    public record UpgradeCheck(
            boolean ok,
            @Nullable FailReason reason,
            @Nullable UpgradeLevelDefinition next,
            double currentScore,
            List<MaterialCost> missingMaterials,
            int missingTokens
    ) {
        static UpgradeCheck fail(FailReason reason, UpgradeLevelDefinition next, double score,
                                  List<MaterialCost> missing, int missingTokens) {
            return new UpgradeCheck(false, reason, next, score, missing, missingTokens);
        }

        static UpgradeCheck ok(UpgradeLevelDefinition next, double score) {
            return new UpgradeCheck(true, null, next, score, List.of(), 0);
        }
    }

    private final UpgradeGenerator generator;

    public UpgradeManager(@NotNull UpgradeGenerator generator) {
        this.generator = generator;
    }

    public int getCurrentLevel(@NotNull UUID islandId) {
        return generator.getRecord(islandId).level();
    }

    public @Nullable UpgradeLevelDefinition getNextLevelDefinition(@NotNull UUID islandId) {
        int current = getCurrentLevel(islandId);
        return UpgradeConfigLoader.config.getLevel(current + 1);
    }

    public @NotNull UpgradeCheck check(@NotNull Player player, @NotNull Island island) {
        UpgradeLevelDefinition next = getNextLevelDefinition(island.getId());
        if (next == null) {
            return UpgradeCheck.fail(FailReason.NO_NEXT_LEVEL, null, 0, List.of(), 0);
        }

        double score = SkylliaIslandLevel.getInstance().getLevelManager().getStoredScore(island);
        if (score < next.scoreThreshold()) {
            return UpgradeCheck.fail(FailReason.SCORE_TOO_LOW, next, score, List.of(), 0);
        }

        List<MaterialCost> missing = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        for (MaterialCost cost : next.materials()) {
            int have = countMaterial(inventory, cost.material());
            if (have < cost.amount()) {
                missing.add(new MaterialCost(cost.material(), cost.amount() - have));
            }
        }

        int haveTokens = UpgradeTokenItem.countInInventory(inventory);
        int missingTokens = Math.max(0, next.tokenCount() - haveTokens);

        if (!missing.isEmpty() || missingTokens > 0) {
            return UpgradeCheck.fail(FailReason.REQUIREMENTS_NOT_MET, next, score, missing, missingTokens);
        }

        return UpgradeCheck.ok(next, score);
    }

    /**
     * 执行升级。会重新调用一次 {@link #check} 校验（防止 GUI 打开后背包变化/条件不再满足），
     * 通过后：先尝试 {@code island.setSize}（可能因触发核心侧的上限校验而失败，此时不会扣除任何资源），
     * 成功后再依次生效成员上限、扣材料、扣令牌、落库新等级。
     */
    public @NotNull UpgradeCheck performUpgrade(@NotNull Player player, @NotNull Island island) {
        UpgradeCheck result = check(player, island);
        if (!result.ok()) return result;

        UpgradeLevelDefinition next = result.next();
        assert next != null;

        // ⚠️ 升级<b>永远不能把边境缩小</b>。
        // 2026-08-22 生产事故：islands.toml 的建岛初始半径是 100，而 upgrades.toml 的
        // Lv.1~Lv.4 分别是 60/70/80/90 —— 玩家升到 Lv.2 时 setSize(70) 直接把地从 100 缩到 70，
        // 岛外的建筑瞬间落到边境之外。这是配置写错了（升级表起点低于建岛初始值），
        // 但代码这边必须有防线：一次"升级"在任何情况下都不该让玩家损失已有的领地。
        // 取 max(当前半径, 目标半径)，并在真的发生这种情况时打 warn 让服主看得见配置问题。
        double targetSize = Math.max(island.getSize(), next.size());
        if (targetSize > next.size()) {
            log.warn("[SkylliaUpgrade] 岛屿 {} 升级到等级 {} 时，配置里的目标半径 {} 小于当前半径 {}，"
                            + "已按当前半径保持不变（请检查 upgrades.toml：升级表的半径不该低于"
                            + " islands.toml 的建岛初始半径，也不该随等级下降）",
                    island.getId(), next.level(), next.size(), island.getSize());
        }

        try {
            island.setSize(targetSize);
        } catch (MaxIslandSizeExceedException e) {
            log.warn("[SkylliaUpgrade] 岛屿 {} 升级到等级 {} 时 setSize 被核心拒绝: {}",
                    island.getId(), next.level(), e.getMessage());
            return UpgradeCheck.fail(FailReason.SIZE_REJECTED, next, result.currentScore(), List.of(), 0);
        }

        boolean membersUpdated = island.setMaxMembers(next.maxMembers());
        if (!membersUpdated) {
            log.warn("[SkylliaUpgrade] 岛屿 {} 升级到等级 {} 时 setMaxMembers 失败，边境已生效但成员上限未更新",
                    island.getId(), next.level());
        }

        PlayerInventory inventory = player.getInventory();
        for (MaterialCost cost : next.materials()) {
            consumeMaterial(inventory, cost.material(), cost.amount());
        }
        UpgradeTokenItem.consume(inventory, next.tokenCount());
        player.updateInventory();

        generator.setLevel(island.getId(), next.level());

        return result;
    }

    private int countMaterial(@NotNull PlayerInventory inventory, @NotNull Material material) {
        int count = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) count += stack.getAmount();
        }
        return count;
    }

    private void consumeMaterial(@NotNull PlayerInventory inventory, @NotNull Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) contents[i] = null;
            remaining -= take;
        }
        inventory.setContents(contents);
    }
}
