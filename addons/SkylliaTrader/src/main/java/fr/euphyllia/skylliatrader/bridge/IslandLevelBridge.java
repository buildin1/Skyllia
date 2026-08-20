package fr.euphyllia.skylliatrader.bridge;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "岛屿等级"轨道的数据来源：SkylliaIslandLevel（{@code addons/SkylliaIslandValue} 模块产出的插件，
 * 内部插件名是 {@code SkylliaIslandLevel}）。
 * <p>
 * 这是一个软依赖桥接，不是硬编译依赖：SkylliaTrader 的 {@code build.gradle.kts} 并没有
 * {@code compileOnly(project(":addons:SkylliaIslandValue"))}，也读不到它的 {@code LevelManager}
 * 内部类。之所以还能拿到等级数据，是因为 SkylliaIslandLevel 自己把等级存进了
 * {@link fr.euphyllia.skyllia.api.database.IslandCustomDataQuery} 的
 * {@code NamespacedKey("skylliaislandlevel", "data")} 命名空间下的 {@code "level"} 这个 key
 * （见该插件的 {@code IslandLevelListener}），而这套 custom-data 机制本来就是跨插件共享的
 * key-value 存储，所以这里直接按同样的 key 读，不需要对方开放专门的 API。
 * </p>
 * <p>
 * 风险自留：如果 SkylliaIslandLevel 以后改了它自己的命名空间/key 名/存储类型，这里会读不到
 * 数据、静默退化成"等级恒为 0"，不会抛异常也不会有明显报错。T1 先接受这个风险换取不需要
 * 对方开放专用 API 就能跑；如果这条桥接后续变得重要，应该找 SkylliaIslandLevel 要一个正式的
 * "获取岛屿等级"API（比如通过 SkylliaAPI 扩展一个可选服务注册表），而不是继续猜 key 名。
 * </p>
 */
public final class IslandLevelBridge {

    private static final Logger log = LoggerFactory.getLogger(IslandLevelBridge.class);
    private static final String ISLAND_LEVEL_PLUGIN_NAME = "SkylliaIslandLevel";
    private static final NamespacedKey NAMESPACE = new NamespacedKey("skylliaislandlevel", "data");
    private static final String KEY_LEVEL = "level";

    private IslandLevelBridge() {
    }

    /** SkylliaIslandLevel 插件是否已安装并启用；未安装时本轨道永远视为 0 级。 */
    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled(ISLAND_LEVEL_PLUGIN_NAME);
    }

    /** 读取一座岛屿当前的等级；SkylliaIslandLevel 未安装或读取失败时返回 0。 */
    public static long getIslandLevel(@NotNull Island island) {
        if (!isAvailable()) return 0L;
        try {
            return SkylliaAPI.getIslandCustomDataQuery()
                    .getOrDefault(NAMESPACE, island, KEY_LEVEL, PersistentDataType.LONG, 0L);
        } catch (Exception e) {
            log.debug("读取岛屿 {} 的等级失败，按 0 级处理：{}", island.getId(), e.getMessage());
            return 0L;
        }
    }
}
