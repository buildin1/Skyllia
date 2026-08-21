package fr.euphyllia.skylliaupgrade.listener;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliaupgrade.SkylliaUpgrade;
import fr.euphyllia.skylliaupgrade.configuration.UpgradeConfigLoader;
import fr.euphyllia.skylliaupgrade.configuration.UpgradeLevelDefinition;
import fr.euphyllia.skylliaupgrade.manager.UpgradeManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家登录时校正岛屿边境半径。
 *
 * <h2>为什么需要这个</h2>
 * <p>
 * 2026-08-22 生产事故：{@code islands.toml} 的建岛初始半径是 100，而 {@code upgrades.toml}
 * 的 Lv.1~Lv.4 分别是 60/70/80/90 —— 玩家升到 Lv.2 时 {@code setSize(70)} 把边境从 100
 * <b>缩小</b>到 70，岛外已经建好的东西瞬间落到边境之外。
 * {@code UpgradeManager#performUpgrade} 已经补上了"升级永不缩小"的防线，但那只对
 * <b>今后</b>的升级生效；<b>已经被缩小过的存量岛屿救不回来</b>——它们的 size 已经写进数据库了。
 * </p>
 * <p>
 * 所以按服主要求补这条：玩家登录时检查一次自己岛屿的边境，不是应有的数值就自动改回去。
 * </p>
 *
 * <h2>"应有的数值"怎么算</h2>
 * <p>
 * {@code max(该岛当前升级等级对应的半径, 建岛初始半径)}：
 * </p>
 * <ul>
 *   <li>取升级表的值，是因为升级本来就该把边境放大到那一档；</li>
 *   <li>再和建岛初始半径取大，是为了兜住"升级表低档位比初始值还小"这种配置错误——
 *       否则这个校正逻辑自己就会把边境改小，变成事故的帮凶；</li>
 *   <li><b>只放大、不缩小</b>：算出来的目标值小于当前实际值时什么都不做。管理员可能出于
 *       活动/补偿手动放大过某座岛，登录校正不该把这类人为调整悄悄抹掉。</li>
 * </ul>
 *
 * <h2>线程与频率</h2>
 * <p>
 * 读升级等级要查库，必须在 async 上做；{@code island.setSize} 走的是核心的岛屿数据接口，
 * 同样不该在登录事件的 region 线程上同步做。所以整段逻辑丢到
 * {@code Bukkit.getAsyncScheduler()}。
 * </p>
 * <p>
 * 每座岛屿本次运行只校正一次（{@link #corrected}）：同一座岛的多名成员轮流上线时没必要
 * 重复查库，而"边境被改小"这件事在校正之后本次运行内不会再次发生（升级路径已经堵死了）。
 * </p>
 */
public class BorderCorrectionListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(BorderCorrectionListener.class);

    /** 本次运行已经校正过的岛屿，避免同岛多名成员上线时重复查库。 */
    private final Set<UUID> corrected = ConcurrentHashMap.newKeySet();

    private final UpgradeManager upgradeManager;

    public BorderCorrectionListener(@NotNull UpgradeManager upgradeManager) {
        this.upgradeManager = upgradeManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getAsyncScheduler().runNow(SkylliaUpgrade.getInstance(), task -> {
            try {
                correct(player);
            } catch (Throwable t) {
                // 校正失败绝不能影响玩家正常进服，只记日志。
                log.error("[SkylliaUpgrade] 校正玩家 {} 的岛屿边境时出错", player.getName(), t);
            }
        });
    }

    private void correct(Player player) {
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) return;
        if (!corrected.add(island.getId())) return;

        int level = upgradeManager.getCurrentLevel(island.getId());
        UpgradeLevelDefinition def = UpgradeConfigLoader.config.getLevel(level);

        // 升级表里查不到当前等级（等级为 0、或者配置被删过）时，退化成只保证不低于建岛初始半径。
        double fromUpgradeTable = def != null ? def.size() : 0.0;
        double expected = Math.max(fromUpgradeTable, initialIslandSize());

        double actual = island.getSize();
        if (expected <= actual) return;   // 只放大不缩小，见类文档

        try {
            island.setSize(expected);
            log.warn("[SkylliaUpgrade] 岛屿 {} 的边境半径为 {}，低于等级 {} 应有的 {}，已自动校正"
                            + "（多半是历史上被『升级表低档位比建岛初始值还小』这个配置错误缩过）",
                    island.getId(), actual, level, expected);
        } catch (Exception e) {
            log.error("[SkylliaUpgrade] 岛屿 {} 的边境校正失败（{} → {}）",
                    island.getId(), actual, expected, e);
        }
    }

    /**
     * 建岛初始半径。核心把它配在 {@code islands.toml} 里、按岛屿类型分别设置，这里取
     * <b>所有类型里最大的那个</b>作为下限：不同类型的初始半径可能不同，而我们只想确保
     * "任何岛屿都不会比它当初被创建时更小"，取最大值在极端情况下最多是把某个小类型的岛
     * 放大到大类型的初始尺寸——比继续让玩家被缩地要安全得多。
     * <p>
     * 读不到配置时返回 0，让上层退化成"只按升级表校正"。
     * </p>
     */
    private double initialIslandSize() {
        try {
            return fr.euphyllia.skyllia.configuration.ConfigLoader.islandManager
                    .getIslandSettingsMap().values().stream()
                    .mapToDouble(fr.euphyllia.skyllia.api.skyblock.model.IslandSettings::rayon)
                    .max()
                    .orElse(0.0);
        } catch (Throwable t) {
            log.warn("[SkylliaUpgrade] 读取建岛初始半径失败，本次只按升级表校正：{}", t.toString());
            return 0.0;
        }
    }
}
