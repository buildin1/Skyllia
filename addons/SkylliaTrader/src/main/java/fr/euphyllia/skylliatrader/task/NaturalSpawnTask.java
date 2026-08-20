package fr.euphyllia.skylliatrader.task;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.listener.PlayerLocationTracker;
import fr.euphyllia.skylliatrader.merchant.MerchantService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 自然刷新的巡检任务：每隔一段时间看一遍「有成员在线的岛屿」，按概率给它们刷一个临时游商。
 *
 * <h2>为什么跑在 async scheduler 上</h2>
 * <p>
 * 这条链路上的每一步都是<b>同步阻塞</b>的：{@code getIslandByPlayerId} 在缓存未命中时要查库，
 * 岛屿标志判定要读 {@code IslandFlags}，{@code getSpawnLocation} 要查 warp 表。
 * 放在 global region scheduler 上就是拿全服的主 tick 去等数据库。
 * 真正需要 region 线程的只有最后「扫方块 + 生成实体」那一小段，由
 * {@link MerchantService#tryNaturalSpawn} 自己调度过去。
 * </p>
 * <p>
 * 遍历在线玩家 → 去重成岛屿列表的写法照抄 SkylliaIslandLevel 的 {@code LevelManager#autoScanAll}，
 * 那是本仓库里既有的 async 巡检范式。
 * </p>
 *
 * <h2>「岛上有玩家」的判定口径：必须<b>人在岛上</b>，不是「有成员在线」</h2>
 * <p>
 * 这里曾经判的是「岛屿有成员在线」，理由是「async 线程读 {@code player.getLocation()}
 * 在 Folia 上不安全」。那个理由本身成立，但它选的规避方式有一个很贵的副作用：
 * {@link MerchantService#tryNaturalSpawn} 里的 {@code getChunkAtAsync(x, z, false)}，
 * 第三个参数 {@code false} 只表示<b>「不生成新区块」</b>，对已经生成过、只是当前没加载的
 * 区块<b>照样会加载</b>。于是「玩家在主城挂机 → 巡检认为他那座岛有人 → 概率命中 →
 * 把空岛的 spawn 区块拽进内存 → Folia 为它拉起一个 region 开始 tick → 刷出一个
 * 没人看得见的商人 → 区块再卸载」，69 座岛叠起来是持续的无谓开销。
 * </p>
 * <p>
 * 现在的口径是<b>「该成员此刻确实站在自己岛上」</b>，判定数据来自
 * {@link PlayerLocationTracker}——玩家坐标由玩家自己线程上的移动/传送/加入事件写进缓存，
 * async 侧只读缓存 + 做一次带缓存的 bounds 比较，<b>既不读实体也不碰区块</b>，
 * 所以在人不在岛上的时候，我们连 {@code getChunkAtAsync} 都不会调。
 * </p>
 * <p>
 * 边缘情况：玩家传送回岛时缓存是在<b>传送之前</b>按目标坐标写好的，不等区块加载，
 * 所以不会出现「刚回岛就判定失败」；唯一空窗是插件热加载后、老玩家第一次移动之前，
 * 详见 {@link PlayerLocationTracker} 的类注释。
 * </p>
 */
public final class NaturalSpawnTask {

    private static final Logger log = LoggerFactory.getLogger(NaturalSpawnTask.class);

    private NaturalSpawnTask() {
    }

    /**
     * 挂上定时巡检。任务由 {@code Bukkit.getAsyncScheduler().cancelTasks(plugin)} 在 onDisable 里统一取消，
     * 不需要单独保存句柄。
     * <p>
     * ⚠️ 巡检间隔在<b>启动时</b>读一次就固定了：{@code runAtFixedRate} 的周期没法在运行中改。
     * 管理员改完 {@code natural-spawn.check-interval-seconds} 后 {@code /skyllia reload} 不会
     * 改变巡检频率，得重启——不过概率、停留时长、冷却这些都是每次判定时现读的，会立即生效。
     * </p>
     */
    public static void start(@NotNull SkylliaTrader plugin, @NotNull MerchantService service,
                             @NotNull PlayerLocationTracker tracker) {
        int intervalSeconds = TraderConfigLoader.config.getNaturalCheckIntervalSeconds();
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            try {
                tick(service, tracker);
            } catch (Throwable t) {
                // 巡检任务里抛出去的异常会被 asyncScheduler 吞掉，而且 runAtFixedRate 之后
                // 还会继续跑下一轮——不打日志的话，自然刷新会静默失灵。
                log.error("游商自然刷新巡检出错", t);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private static void tick(MerchantService service, PlayerLocationTracker tracker) {
        if (!TraderConfigLoader.config.isNaturalSpawnEnabled()) return;

        var onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) return;

        // 自然刷新只发生在主岛世界，判定也就只需要拿这一个世界去比。
        World world = service.resolveSummonWorld();
        if (world == null) return;

        Set<UUID> visited = new HashSet<>();
        for (Player player : onlinePlayers) {
            // getIslandByPlayerId 给出的是「这名玩家所属的岛」，所以下面这条判定天然是
            // 「成员站在自己岛上」，路过的访客不会替别人家触发刷新。
            Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
            if (island == null) continue;
            // ⚠️ 顺序不能反：先判「这名玩家在不在岛上」，再做一岛一次的去重。
            // 反过来的话，一名在主城挂机的成员会先把这座岛标记成「本轮已处理」，
            // 把真正站在岛上的另一名成员挡掉。
            if (!tracker.isKnownInside(player.getUniqueId(), island, world)) continue;
            // 一座岛一轮只掷一次骰子：五个人在岛上的岛不该比一个人在岛上的岛多五倍刷新率。
            if (!visited.add(island.getId())) continue;
            service.tryNaturalSpawn(island);
        }
    }
}
