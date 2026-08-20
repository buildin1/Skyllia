package fr.euphyllia.skylliatrader.listener;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skylliatrader.SkylliaTrader;
import fr.euphyllia.skylliatrader.configuration.model.MerchantOfferScope;
import fr.euphyllia.skylliatrader.merchant.CaravanType;
import fr.euphyllia.skylliatrader.merchant.MerchantKeys;
import fr.euphyllia.skylliatrader.merchant.MerchantOrigin;
import fr.euphyllia.skylliatrader.merchant.MerchantService;
import fr.euphyllia.skylliatrader.merchant.MerchantSpawner;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游商实体的生命周期：死亡、被移除、重新加载进世界、被玩家右键。
 *
 * <h2>⚠️ 这个类里最要命的一行</h2>
 * <p>
 * {@link #onEntityRemove} 里的 {@code if (event.getCause() == Cause.UNLOAD) return;}。
 * 少了它，玩家一走远导致区块卸载 → 我们以为商人没了 → 释放凭证名额 → 玩家回来发现商人还在，
 * 而且还能再用一张凭证召唤一个 = <b>无限召唤漏洞</b>。规格 6.9 专门点了这一条。
 * </p>
 * <p>
 * 同理还有 {@code Cause.DEATH}：它会和 {@link EntityDeathEvent} 一起触发，两边都处理就会
 * 写两次库，而且第二次写的时候记录已经被删了、{@code lastMerchantDeathAt} 会被刷第二遍。
 * 死亡统一交给 {@code EntityDeathEvent}，这里直接放行。
 * </p>
 * <p>
 * <b>但 UNLOAD 不能写成「一律 return」</b>：自然刷新的游商是 {@code persistent = false} 的，
 * 区块一卸载实体就永久没了，而 {@code MerchantService} 内存表里那个 key 会一直留着，
 * 让那座岛在本次运行里再也不会自然刷新。所以要按<b>来源</b>分流：
 * 自然游商调 {@code forgetNatural} 清一下内存（不写库），
 * 凭证游商仍然原样 return。详见 {@link MerchantService#forgetNatural}。
 * </p>
 */
public class MerchantLifecycleListener implements Listener {

    private static final Logger log = LoggerFactory.getLogger(MerchantLifecycleListener.class);

    private final SkylliaTrader plugin;
    private final MerchantKeys keys;
    private final MerchantSpawner spawner;
    private final MerchantService service;

    /**
     * 本次运行中已经做过孤儿检查的实体 id。
     * <p>
     * {@code EntityAddToWorldEvent} 会在<b>每一次</b>区块加载时触发，而孤儿检查要读一次数据库。
     * 不去重的话，一个玩家在自己岛上来回跑就能把这条路径变成持续的读库放大。
     * 每只商人一次进程内只查一次就够了——它在这次运行里是不是孤儿，
     * 不会因为区块反复加载而改变（真被 release 掉的话，{@code releaseSlot} 那边有自己的路径）。
     * </p>
     * <p>
     * 集合只增不减，但元素是几十个游商的 UUID 量级，可以忽略。
     * </p>
     */
    private final Set<UUID> orphanChecked = ConcurrentHashMap.newKeySet();

    public MerchantLifecycleListener(SkylliaTrader plugin, MerchantKeys keys,
                                     MerchantSpawner spawner, MerchantService service) {
        this.plugin = plugin;
        this.keys = keys;
        this.spawner = spawner;
        this.service = service;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 死亡
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 游商被杀死。规格允许玩家杀死商人，之后可以用新凭证重召——所以这里要做的是
     * <b>释放名额</b>并<b>记下死亡时间</b>（后者驱动重召冷却，防止「打死自家商人刷新订单」）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof WanderingTrader)) return;
        if (!keys.isMerchant(entity)) return;

        UUID islandId = keys.readIslandId(entity);
        MerchantOrigin origin = keys.readOrigin(entity);
        if (islandId == null || origin == null) return;

        UUID entityId = entity.getUniqueId();
        // 写库是同步阻塞 JDBC，绝不能在这条 region tick 线程上跑。
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                service.handleDeath(islandId, entityId, origin);
            } catch (Throwable t) {
                log.error("处理游商 {}（岛屿 {}）死亡时出错", entityId, islandId, t);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // 移除
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 游商被移除。<b>必须过滤 {@code UNLOAD}</b>，理由见类注释。
     *
     * <h3>关于 {@code @SuppressWarnings("removal")}</h3>
     * <p>
     * 本模块<b>编译</b>用的是 paper-api 1.20.6，那一版把 {@code EntityRemoveEvent} 标成了
     * {@code @Deprecated(forRemoval = true)}。但服务端<b>运行</b>的是 26.x
     * （见 {@code nms/v26_2}），在那一版的 paper-api 里这个事件<b>不是</b>废弃的，
     * 它就是带 {@code Cause} 的那个正式 API——反倒是 Paper 自家的
     * {@code EntityRemoveFromWorldEvent} 没有 {@code Cause}，做不了「过滤 UNLOAD」这件事。
     * 所以这里的废弃警告是编译期 API 版本落后带来的假警报，压掉它，别换事件。
     * </p>
     */
    @SuppressWarnings("removal")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        EntityRemoveEvent.Cause cause = event.getCause();

        // 死亡交给 EntityDeathEvent 处理，两边都做会重复写库。这一条和来源无关，先挡掉。
        if (cause == EntityRemoveEvent.Cause.DEATH) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof WanderingTrader)) return;
        if (!keys.isMerchant(entity)) return;

        UUID islandId = keys.readIslandId(entity);
        MerchantOrigin origin = keys.readOrigin(entity);
        if (islandId == null || origin == null) return;

        UUID entityId = entity.getUniqueId();

        // ⚠️ 区块卸载 / 玩家退出<b>不是</b>「商人没了」。对凭证游商放行这两条 cause 是硬要求：
        // 当成移除去释放名额的话，玩家一走远名额就回来了 = 无限召唤漏洞（规格 6.9 点名）。
        //
        // 但自然游商必须分流处理：它 persistent = false，区块一卸载实体就永久没了，
        // 内存表里的 key 却会留着，导致这座岛本次运行内再也不会自然刷新。
        // forgetNatural 只动内存 Map，一个字节都不写库，可以直接在这条 region 线程上调。
        if (cause == EntityRemoveEvent.Cause.UNLOAD
                || cause == EntityRemoveEvent.Cause.PLAYER_QUIT) {
            if (origin == MerchantOrigin.NATURAL) {
                service.forgetNatural(islandId, entityId);
            }
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                service.handleRemoval(islandId, entityId, origin);
            } catch (Throwable t) {
                log.error("处理游商 {}（岛屿 {}）移除时出错", entityId, islandId, t);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // 重新加载进世界（重启 / 区块加载）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 带标记的游商进入世界。这是<b>重启后重新接管已有商人</b>的入口。
     *
     * <h3>为什么不需要在 onEnable 里扫全服实体</h3>
     * <p>
     * 凭证游商的记录一直躺在岛屿数据里（重启不会清），实体也随区块存盘。两边都活着，
     * 「重新关联」这件事本身不需要任何动作——需要的只是在实体回到世界时：
     * </p>
     * <ol>
     *   <li>把运行时属性再设一遍（清空原版商品、不喝药水、不自然消失），
     *       见 {@code MerchantSpawner#applyRuntimeSettings} 的说明；</li>
     *   <li>自然刷新的游商检查 PDC 上的过期时间，过期就当场清掉；</li>
     *   <li>凭证游商做一次孤儿检查：岛屿数据里已经没有它的记录了（管理员 release 过、
     *       或者崩溃时占位没写成而实体存下来了），就把这只多出来的实体收掉。</li>
     * </ol>
     * <p>
     * 反过来说：<b>只要这里不误删，重启就不会「丢失」商人</b>——名额记录没被动过，
     * 玩家也就不可能再召唤出第二个。
     * </p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof WanderingTrader trader)) return;
        if (!keys.isMerchant(entity)) return;

        UUID islandId = keys.readIslandId(entity);
        CaravanType caravan = keys.readCaravan(entity);
        MerchantOrigin origin = keys.readOrigin(entity);
        if (islandId == null || caravan == null || origin == null) {
            // 标记残缺（版本升级、别的插件复制了实体……）。留着它只会变成一只谁都管不了的商人，
            // 直接收掉；玩家的名额记录不受影响，凭证游商会在孤儿检查那条路上被重新发现。
            log.warn("发现一只标记残缺的游商 {}，已移除", entity.getUniqueId());
            trader.getScheduler().execute(plugin, trader::remove, null, 1L);
            return;
        }

        long expireAt = keys.readExpireAt(entity);
        if (expireAt > 0 && System.currentTimeMillis() >= expireAt) {
            // 自然刷新的游商过期了（区块卸载期间到点，despawnDelay 没能跑完）。
            // 延迟 1 tick 再 remove：实体刚进世界，当场 remove 在部分版本上会踩到内部状态。
            trader.getScheduler().execute(plugin, trader::remove, null, 1L);
            return;
        }

        spawner.applyRuntimeSettings(trader, caravan, origin, expireAt);

        if (origin != MerchantOrigin.CREDENTIAL) return;

        UUID entityId = entity.getUniqueId();

        // ⚠️ 这只商人可能是「刚刚在几行代码之前被生成出来、记账还没做完」的那一只。
        // World#spawn 的 pre-spawn 回调打完 PDC 之后，addEntityToWorld 会<b>同步</b>触发
        // 本事件——此时数据库里只有一条 entityUuid == null 的占位，孤儿检查必然判它是孤儿
        // 并当场删掉，而召唤流程随后照样扣掉玩家的凭证。所以先问一句「它还在记账中吗」。
        // 这个判定必须<b>在去重集合之前</b>：放在后面的话，第一次事件会把它记进
        // orphanChecked 然后跳过，本次运行里再也不会真正查一次它是不是孤儿。
        if (service.isFinalizePending(entityId)) return;

        if (!orphanChecked.add(entityId)) return;

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                if (!service.isOrphan(islandId, entityId)) return;
                log.info("游商 {} 在岛屿 {} 的数据里已无记录（孤儿），移除该实体", entityId, islandId);
                trader.getScheduler().execute(plugin, trader::remove, null, 1L);
            } catch (Throwable t) {
                // 检查失败（数据库暂时不可用）时什么都不做：宁可留着一只可能是孤儿的商人，
                // 也不能因为一次读库超时就删掉玩家花凭证换来的常驻商人。
                // 去重集合已经把它标记过了，本次运行不会再查——下次重启会再查一遍。
                log.warn("游商 {}（岛屿 {}）的孤儿检查失败，本次跳过：{}", entityId, islandId, t.toString());
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // 玩家右键
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 玩家右键游商。
     *
     * <h3>为什么要拦下来</h3>
     * <p>
     * 本插件把游商的原版商品表清空了（交易走自定义 GUI，理由见 HANDOFF 6.7），
     * 不拦的话玩家右键会打开一个<b>空白的原版交易界面</b>，看起来像插件坏了。
     * </p>
     * <p>
     * 顺带补上规格 6.7 点出的<b>权限缺口</b>：核心的 {@code EntityTradePermissions} 只认
     * {@code VILLAGER}，而 {@code EntityInteractPermissions} 又主动放行 {@code WANDERING_TRADER}
     * ——也就是说右键游商目前不受任何岛屿权限约束。这里自己判一次
     * {@code skylliatrader:merchant.interact}。
     * </p>
     * <p>
     * ⚠️ {@code getHand() != HAND} 必须直接 return：副手会再触发一次同样的事件，
     * 不过滤的话 T3 的商店 GUI 会被开两次。
     * </p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractMerchant(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity entity = event.getRightClicked();
        if (!(entity instanceof WanderingTrader)) return;
        if (!keys.isMerchant(entity)) return;

        // 无论如何都不让原版交易界面开出来。
        event.setCancelled(true);

        Player player = event.getPlayer();
        UUID islandId = keys.readIslandId(entity);
        MerchantOrigin origin = keys.readOrigin(entity);
        if (islandId == null || origin == null) return;

        // 权限判定要查 island（可能读库），丢到 async 上；反正界面已经被 cancel 了，
        // 玩家这一帧不会看到任何原版界面。
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                Island island = SkylliaAPI.getIslandByIslandId(islandId);
                if (island == null) return;
                if (!SkylliaAPI.getPermissionsManager()
                        .hasPermission(player, island, plugin.getPermissions().merchantInteract())) {
                    player.sendMessage(Component.text("§c你在这座岛上没有和游商交易的权限。"));
                    return;
                }
                // TODO(T3) 这里打开自定义商店 GUI：商品池按下面这个 scope 筛（自然刷新的只开
                //  交易次数轨的基础档 + 说明书，凭证游商四轨全开），购买事务的
                //  「先扣钱后发货 + 发货失败退款 + inFlight 防双击」见 HANDOFF 6.8。
                MerchantOfferScope scope = service.offerScopeFor(origin);
                player.sendMessage(Component.text(scope.guidebook()
                        ? "§7这位路过的商人只带了些基础货，还有一本《游商指南》。§8（商店界面将在下一阶段开放）"
                        : "§7商队正在清点货物……§8（商店界面将在下一阶段开放）"));
            } catch (Throwable t) {
                log.error("处理玩家 {} 与游商的交互时出错", player.getName(), t);
            }
        });
    }
}
