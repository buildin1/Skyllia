package fr.euphyllia.skylliatrader.listener;

import fr.euphyllia.skyllia.api.skyblock.Island;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每名在线玩家「最近一次已知的方块坐标」缓存。
 *
 * <h2>它解决的是什么问题</h2>
 * <p>
 * 自然刷新的巡检任务跑在 <b>async 线程</b>上，而 Folia 上从 async 线程读
 * {@code player.getLocation()} 是不安全的。原实现因此把判定口径退化成
 * <b>「岛屿有成员在线」</b>——但那意味着一个在主城挂机的玩家，就足以让插件去
 * {@code getChunkAtAsync} 他那座空岛的 spawn 区块。那个调用的第三个参数 {@code false}
 * 只表示「不生成新区块」，对<b>已经生成过、只是没加载</b>的区块照样会加载：
 * 结果是把一座没人的岛拽进内存、让 Folia 为它拉起一个 region 开始 tick、
 * 刷出一个没人看得见的商人，然后区块又卸载。69 座岛叠起来是持续的无谓开销。
 * </p>
 * <p>
 * 本类把「读玩家坐标」这件事挪到<b>玩家自己的线程</b>上做（移动/传送/加入/重生/换世界这些
 * 事件天然就在那条线程上，事件对象里直接带着目标坐标，不需要再去问实体），
 * 结果存进一张 {@link ConcurrentHashMap}。async 侧只读这张表，再做一次
 * {@code Island#isInside(World,int,int,int)} 就能判定，<b>既不读实体，也不碰区块</b>。
 * </p>
 * <p>
 * ⚠️ 但 {@code isInside} <b>不是</b>无条件的纯内存操作：核心 {@code IslandHook} 里它先查
 * {@code boundsCache}，<b>未命中</b>时要走 {@code computeBounds}，那里面的
 * {@code getCenterLocation}（可能查一次 center 表）和
 * {@code getBuildMinHeight}/{@code getBuildMaxHeight}（各查一次建筑高度表）都是
 * <b>同步阻塞 JDBC</b>；而且岛屿改尺寸/改中心点时核心会主动 {@code boundsCache.clear()}，
 * 缓存并不是一次性的。所以本类的读取侧<b>只能在 async 线程上调用</b>，
 * 详见 {@link #isKnownInside}。
 * </p>
 *
 * <h2>为什么不会出现「玩家刚回岛就判定失败」</h2>
 * <p>
 * 缓存是在<b>传送发生之前</b>就按目标坐标写好的（{@link PlayerTeleportEvent#getTo()}），
 * 不依赖区块加载完成；登录走 {@link PlayerJoinEvent}、重生走 {@link PlayerRespawnEvent}、
 * 换世界走 {@link PlayerChangedWorldEvent}。也就是说玩家一按下 {@code /is home}，
 * 这张表就已经认为他在岛上了，而不是等他落地。
 * </p>
 * <p>
 * 唯一的空窗是<b>插件热加载</b>（{@code /reload}）：此前就在线的玩家没有触发过任何事件，
 * 表里没有他们的记录，他们所在的岛在<b>第一次移动之前</b>不会自然刷新。玩家几乎不可能
 * 一动不动，而自然刷新本身就是「可有可无的路人商人」，所以这里刻意不去做启动时的
 * 全量扫描——那要在 onEnable 里遍历在线玩家读坐标，正是本类想避开的那种跨线程读。
 * </p>
 *
 * <h2>开销</h2>
 * <p>
 * {@link #onMove} 会在每名玩家每次移动时被调用，所以它必须廉价：只在<b>跨区块</b>时才写表，
 * 同区块内的移动第一行就返回。核心自己的 {@code PlayerRegionChangeListener} 在同一个事件上
 * 做的事（区域换算 + 岛屿查询 + 发消息）比这重得多。
 * </p>
 */
public final class PlayerLocationTracker implements Listener {

    /**
     * 一个已知的落脚点。存世界<b>名字</b>而不是 {@link World} 引用：世界对象可能被卸载，
     * 留着引用会把一整个世界钉在内存里，而我们只需要拿它和目标世界比一次名字。
     */
    public record Spot(String world, int x, int y, int z) {
    }

    private final Map<UUID, Spot> spots = new ConcurrentHashMap<>();

    // ── 写入侧（全部在玩家自己的线程上） ────────────────────────────────────

    /**
     * 用 {@code MONITOR} + {@code ignoreCancelled}：被别的插件取消掉的移动等于没发生，
     * 记下那个坐标会让缓存指向一个玩家其实没去成的地方。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        // 只在跨区块时更新。本方法每名玩家每次移动都会被调用，必须先做最便宜的过滤。
        // 区块级精度对「人在不在这座岛上」完全够用——岛屿边界以区域（32×32 区块）为单位。
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)
                && from.getWorld() == to.getWorld()) {
            return;
        }
        record(event.getPlayer().getUniqueId(), to);
    }

    /**
     * 传送在<b>发生之前</b>就记下目标坐标，玩家不用等区块加载完就已经算「在岛上」。
     * <p>
     * 代价是这条记录写的是<b>意图</b>而不是结果：传送随后如果没成
     * （{@code teleportAsync} 的目标区块加载失败、或者某个比 {@code MONITOR} 更晚跑的插件
     * 又改了目标），缓存会短暂指向一个玩家其实没去成的位置。这里<b>刻意不改逻辑</b>：
     * 最坏后果只是自然刷新为那座岛白拉一次区块、刷一个没人看得见的路人商人，
     * 而玩家下一次移动就会把缓存纠正回来。反过来「等传送真的落地再记」会引入一个更常见的坑
     * ——玩家 {@code /is home} 落地前的那几百毫秒里，他那座岛会被判成没人。
     * </p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        record(event.getPlayer().getUniqueId(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        record(event.getPlayer().getUniqueId(), event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        record(player.getUniqueId(), player.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        record(player.getUniqueId(), player.getLocation());
    }

    /** 玩家下线就摘掉，表的大小恒等于在线人数，不需要任何额外的清理任务。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        spots.remove(event.getPlayer().getUniqueId());
    }

    private void record(UUID playerId, @Nullable Location location) {
        if (location == null || location.getWorld() == null) return;
        spots.put(playerId, new Spot(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    // ── 读取侧（async 线程） ────────────────────────────────────────────────

    /**
     * 这名玩家最近一次已知的位置是不是落在 {@code island} 在 {@code world} 里的范围内。
     * <p>
     * <b>必须在 async 线程上调用。</b>读表那一步确实是纯内存的，但后面那次
     * {@code Island#isInside(World,int,int,int)} 只在核心的 {@code boundsCache}
     * <b>命中</b>时才是纯整数比较；<b>未命中</b>时它会走 {@code computeBounds}，
     * 里面的 {@code getCenterLocation} 和 {@code getBuildMinHeight}/{@code getBuildMaxHeight}
     * 都是<b>同步阻塞 JDBC</b>。缓存也不是一次性的：岛屿改尺寸/改中心点时核心会
     * {@code boundsCache.clear()}，之后第一次调用又会打到库上。
     * </p>
     * <p>
     * 所以<b>不要</b>把它搬到 region tick 线程或任何主 tick 路径上去（T3 注意）。
     * 目前唯一的调用点是跑在 async 上的 {@code NaturalSpawnTask}。
     * </p>
     *
     * @return 没有任何已知位置（刚热加载、还没触发过事件）时返回 {@code false}——
     * 宁可少刷一个可有可无的路人商人，也不要为一座没人的岛拉起区块
     */
    public boolean isKnownInside(@NotNull UUID playerId, @NotNull Island island, @NotNull World world) {
        Spot spot = spots.get(playerId);
        if (spot == null) return false;
        if (!world.getName().equals(spot.world())) return false;
        return island.isInside(world, spot.x(), spot.y(), spot.z());
    }
}
