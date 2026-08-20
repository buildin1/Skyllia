package fr.euphyllia.skylliatrader.listener;

import fr.euphyllia.skyllia.api.event.SkyblockCreateEvent;
import fr.euphyllia.skyllia.api.event.SkyblockDeleteEvent;
import fr.euphyllia.skyllia.api.event.SkyblockLoadEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.data.TraderDataService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 岛屿首次被本插件见到时的游商数据初始化。
 * <p>
 * 交易次数/消费/声望三个计数器不需要显式初始化——{@link TraderDataService#load}
 * 在从未写过数据时会返回一份全零的默认状态，读取端不会因为"没初始化过"而报错。
 * 这里唯一需要主动做的事是把订单槽位数组占位补齐到配置的数量，方便管理端 GUI
 * 以及 T2 的自然刷新逻辑不用每次都判断"数组是不是还没建好"。
 * </p>
 *
 * <h2>为什么需要 {@link #initialized} 这个去重集合</h2>
 * <p>
 * {@link SkyblockLoadEvent} <b>不是"岛屿首次加载"事件</b>：它是在数据库层的
 * {@code getIslandByPlayerId} / {@code getIslandByOwnerId} 里 fire 的，而岛屿缓存
 * （{@code SkyblockCache}）是 TTL + stale-while-revalidate 的，<b>每次缓存过期回源都会
 * 再 fire 一次</b>。不去重的话，{@code ensureOrderSlots} 里那次 custom-data SELECT 会按
 * "每岛每 TTL 一次"的频率一直跑下去——虽然都在 async 线程上不阻塞 tick，但对着几十上百座
 * 岛屿就是一份持续的数据库放大，纯属白烧。
 * </p>
 * <p>
 * 所以这里按 island id 记一次"本次运行已经初始化过"，重复的 load 事件直接返回。
 * 集合在 {@link SkyblockDeleteEvent} 上清理，避免"建岛-删岛"反复循环的服跑久了内存里
 * 攒下一堆早已不存在的岛屿 id。进程重启后集合清空，最多每岛多跑一次 SELECT，可以接受。
 * </p>
 *
 * <h2>并发窗口（T2 加锁时必须一起处理）</h2>
 * <p>
 * 同一座岛的两名玩家同时冷缓存登录时，两个 async 线程可能同时穿过下面的
 * {@code initialized.add(...)}（{@link Set#add} 本身是原子的，所以实际上只有一个能进去，
 * 但如果第一个线程正卡在 {@code ensureOrderSlots} 的读库上，另一条来自
 * {@link SkyblockCreateEvent} 的路径仍可能并发进入）。T1 阶段两次写入的内容完全相同，
 * 后写覆盖先写无害；<b>但 T2 给 {@code TraderDataService#mutate} 加 island 维度互斥锁时，
 * {@code ensureOrderSlots} 必须纳入同一把锁</b>——它是 mutate 之外的第二条"读-改-写"路径，
 * 只锁 mutate 会让"补槽"和"记账"互相覆盖。
 * </p>
 */
public class TraderIslandListener implements Listener {

    private final TraderDataService dataService;

    /** 本次运行中已经补过订单槽位的岛屿 id；理由见类注释。 */
    private final Set<UUID> initialized = ConcurrentHashMap.newKeySet();

    public TraderIslandListener(TraderDataService dataService) {
        this.dataService = dataService;
    }

    @EventHandler
    public void onIslandCreate(SkyblockCreateEvent event) {
        initIsland(event.getIsland());
    }

    @EventHandler
    public void onIslandLoad(SkyblockLoadEvent event) {
        initIsland(event.getIsland());
    }

    /**
     * 岛屿被删除时把去重标记摘掉：一是防止长期运行的服攒下已不存在的岛屿 id，
     * 二是万一同一个 id 被复用（理论上不会，但不必赌），下次还能正常补槽。
     * <p>
     * 用 {@link EventPriority#MONITOR} + {@code ignoreCancelled}：别的插件可能取消删除，
     * 被取消的删除不该清掉我们的标记。
     * </p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandDelete(SkyblockDeleteEvent event) {
        initialized.remove(event.getIsland().getId());
    }

    private void initIsland(Island island) {
        // add 返回 false 说明本次运行里已经补过了，直接省掉一次 custom-data SELECT。
        if (!initialized.add(island.getId())) return;

        int slots = TraderConfigLoader.config.getOrderSlotsPerIsland();
        if (!dataService.ensureOrderSlots(island, slots)) {
            // 写库失败（连接池耗尽/磁盘满）时不能留下"已初始化"的标记，
            // 否则这座岛在本次运行里再也不会重试补槽了。
            initialized.remove(island.getId());
        }
    }
}
