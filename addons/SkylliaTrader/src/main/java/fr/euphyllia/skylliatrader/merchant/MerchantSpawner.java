package fr.euphyllia.skylliatrader.merchant;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.entity.WanderingTrader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 真正把游商实体放进世界，以及「重启后让已存在的游商恢复该有的样子」。
 *
 * <h2>线程约束（Folia）</h2>
 * <p>
 * {@link #spawn} 必须在<b>目标位置所属的 region 线程</b>上调用；
 * {@link #applyRuntimeSettings} 必须在<b>该实体所属的线程</b>上调用
 * （事件处理器里天然满足，别的地方走 {@code entity.getScheduler()}）。
 * </p>
 */
public final class MerchantSpawner {

    private static final Logger log = LoggerFactory.getLogger(MerchantSpawner.class);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final MerchantKeys keys;

    public MerchantSpawner(@NotNull MerchantKeys keys) {
        this.keys = keys;
    }

    /**
     * 在给定位置生成一个游商。
     *
     * <h3>为什么用 {@code SpawnReason.CUSTOM}</h3>
     * <p>
     * 核心的 {@code IslandMobSpawnPassiveFlag} 会对 {@code WANDERING_TRADER} 判定
     * {@code skyllia:island.spawn.passive.wandering_villager} 标志并取消生成，
     * 但它一开头就跳过 {@code MobsSpawnImpl#ignoredReasons()} 里的原因，而 {@code CUSTOM} 正在其中。
     * 也就是说<b>本插件生成的游商天然不受岛屿标志约束</b>——这正是规格要的：
     * 凭证召唤<b>无视</b>标志。而自然刷新<b>要</b>尊重标志，所以那条路径由
     * {@code MerchantService} 在调用本方法<b>之前</b>自己查一次标志，而不是指望核心来拦。
     * </p>
     *
     * <h3>为什么在 pre-spawn 回调里就把设置做完</h3>
     * <p>
     * {@code World#spawn} 的第四个参数是「实体加入世界<b>之前</b>」的回调。PDC 标记必须在这里打，
     * 否则 {@code EntityAddToWorldEvent} 会先于标记看到这只实体，把它当成路人游商放过去
     * （后果：自然刷新的游商在下次区块加载时不会被过期清理）。
     * </p>
     *
     * <h3>{@code preSpawn} 回调是干什么的</h3>
     * <p>
     * 调用方需要「在这只实体<b>被世界看见之前</b>」就拿到它的 UUID 做登记。原因是
     * {@code addEntityToWorld} 会<b>同步</b>触发 {@code EntityAddToWorldEvent}，
     * 监听器在那一刻就会对这只实体做孤儿检查——而此时凭证召唤的记账（占位转正）
     * 连派发都还没派发，数据库里只有一条 {@code entityUuid == null} 的占位，
     * 孤儿检查必然判它是孤儿并当场删掉。所以 {@code MerchantService} 要在这里
     * 把 UUID 写进「正在记账」名单，让孤儿检查跳过它。详见
     * {@code MerchantService#pendingFinalize}。
     * </p>
     * <p>
     * 它和 {@code keys.mark} 在同一个 pre-spawn 回调里，共享「先于事件执行」这个保证。
     * </p>
     *
     * <h3>⚠️ 返回值的真实语义：非 {@code null} <b>不等于</b>生成成功</h3>
     * <p>
     * Paper 的这条链路把「实体到底有没有进世界」这个信息<b>丢掉了</b>：
     * {@code CraftRegionAccessor#addEntity} 调完 {@code addEntityToWorld} 就无条件
     * {@code return entity.getBukkitEntity()}，而 {@code CraftWorld#addEntityToWorld} 是
     * {@code void} 的，把 {@code ServerLevel#addFreshEntity} 的返回值直接扔了。
     * 第三方插件（反挂机刷怪限制、区域保护的 deny-spawn……）取消
     * {@code CreatureSpawnEvent} 时，{@code CraftEventFactory#doEntityAddEventCalling} 会先
     * {@code entity.discard(null)} 再 {@code return false} —— 于是本方法照样返回一个
     * <b>非 {@code null}</b> 的 Bukkit 实体，只不过它<b>从未加入世界、已经被 discard</b>。
     * </p>
     * <p>
     * 所以<b>调用方必须自己判一次 {@code trader.isValid()}</b>，不能只判 {@code null}。
     * 这个判据是可靠的：{@code CraftEntity#isValid()} = {@code isAlive() && entity.valid}，
     * 而 {@code entity.valid} 只在 {@code ServerLevel$EntityCallbacks#onTrackingStart} 里被置
     * {@code true}，那个回调在 {@code addNewEntity} 内部执行、<b>晚于</b>上面那次事件判定；
     * 取消路径根本走不到那里，{@code valid} 恒为 {@code false}，{@code discard} 也让
     * {@code isAlive()} 变成 {@code false}。反过来，生成成功时
     * {@code EntityAddToWorldEvent} 正是在 {@code valid = true} <b>之后</b>才发出的
     * （Paper 的注释原话是 "fire while valid"），所以「真的进了世界 ⇒ {@code isValid()}」
     * 同样成立，不会把成功的生成误判成失败。
     * </p>
     * <p>
     * （Skyllia 核心自己不会拦：核心的被动生物标志一开头就跳过
     * {@code MobsSpawnImpl#ignoredReasons()}，{@code CUSTOM} 正在其中。风险全部来自第三方插件。）
     * </p>
     *
     * @param location   已经确认安全的落点（来自 {@link SafeSpawnFinder#find}）
     * @param islandId   归属岛屿
     * @param caravan    商队类型
     * @param origin     来源，决定停留时长与商品范围
     * @param expireAt   过期时间戳（epoch millis），0 = 永不过期
     * @param preSpawn   实体加入世界之前的额外回调（可为 {@code null}），拿得到实体 UUID
     * @return 真正进了世界的实体；世界为 {@code null}、刷怪蛋路径失败或抛异常时返回 {@code null}。
     * 调用方仍应再判一次 {@code isValid()}（见 {@code MerchantService#spawnAt}）
     */
    public @Nullable WanderingTrader spawn(@NotNull Location location, @NotNull UUID islandId,
                                           @NotNull CaravanType caravan, @NotNull MerchantOrigin origin,
                                           long expireAt, @Nullable Consumer<WanderingTrader> preSpawn) {
        if (location.getWorld() == null) return null;
        Consumer<WanderingTrader> configure = trader -> {
            // 顺序有意为之：先登记再打标记。EntityAddToWorldEvent 是靠 PDC 标记
            // 认出这只实体的，登记必须在标记之前完成，中间不能留下
            // 「已经能被认出、但还没登记」的窗口。
            if (preSpawn != null) preSpawn.accept(trader);
            keys.mark(trader, islandId, caravan, origin, expireAt);
            applyRuntimeSettings(trader, caravan, origin, expireAt);
        };
        try {
            // 优先走原版刷怪蛋那条 NMS 路径：tryMoveDown 落点校正 + finalizeSpawn，
            // 事件被取消时返回 null，不会像 World#spawn 那样交回一只已 discard 的实体。
            // 正式服「放下去原地消失」就是这条 World#spawn 路径在虚空岛上挤出碰撞、
            // 下一 tick 掉虚空的结果。
            WanderingTrader viaEgg = SkylliaAPI.getWorldNMS()
                    .spawnWanderingTraderLikeEgg(location, configure);
            if (viaEgg != null) return viaEgg;

            // 不回退 World#spawn：正式服「放下去原地消失」就是那条路径在虚空岛上
            // 挤出碰撞、下一 tick 掉虚空。刷怪蛋路径失败就当本次没生成。
            log.warn("刷怪蛋路径在 {} 没能放下游商（岛屿 {}，商队 {}）", location, islandId, caravan);
            return null;
        } catch (Exception e) {
            // 生成本身抛异常时不能让调用方以为生成成功了：那会导致占位被转正，
            // 而岛上一个商人都没有。
            log.error("在 {} 生成游商失败（岛屿 {}，商队 {}）", location, islandId, caravan, e);
            return null;
        }
    }

    /**
     * 把一只游商调成「本插件想要的样子」。生成时调一次，<b>每次实体重新加载进世界时也要再调一次</b>
     * （见 {@code MerchantLifecycleListener} 的 {@code EntityAddToWorldEvent}）。
     *
     * <h3>为什么重启后要重来一遍</h3>
     * <p>
     * PDC 会随区块存盘，但下面这些运行时属性<b>不全都会</b>：{@code despawnDelay}、
     * {@code removeWhenFarAway}、原版交易表都是实体 NBT 的一部分会存下来，
     * 而「不喝药水」这类 Paper 追加的字段在不同版本上的持久化情况并不一致。
     * 与其逐个考证，不如每次实体进世界都幂等地重设一遍——代价是几个 setter，
     * 换来的是「重启之后游商夜里喝隐身药水消失」这类只能靠玩家反馈才能发现的 bug 不会出现。
     * </p>
     */
    public void applyRuntimeSettings(@NotNull WanderingTrader trader, @NotNull CaravanType caravan,
                                     @NotNull MerchantOrigin origin, long expireAt) {
        // ① 清空原版商品。交易走本插件的自定义 GUI（HANDOFF 6.7：订单没法用 MerchantRecipe 表达、
        //    价格要用 Vault 金币、四维度的教学文案要写在 lore 里），原版商品表必须是空的。
        //    ⚠️ 空商品表 + 玩家右键 = 一个空白的交易界面，看起来像 bug，
        //    所以 MerchantLifecycleListener 会拦下右键，不让原版界面打开。
        trader.setRecipes(List.of());

        // ② 规格 6.9 点名：不设这两个，游商夜里会喝隐身药水凭空消失，玩家以为是 bug。
        trader.setCanDrinkPotion(false);
        trader.setCanDrinkMilk(false);

        // ③ 停留时长。原版 WanderingTrader 的 despawnDelay 每 tick 自减，减到 0 的那一刻消失；
        //    **字段值本身为 0 表示「不启用倒计时」**（原版判定是 despawnDelay > 0 才递减），
        //    所以凭证游商设 0 = 永久常驻，自然刷新游商设成剩余 tick 数 = 到点自己走。
        if (origin == MerchantOrigin.CREDENTIAL) {
            trader.setDespawnDelay(0);
            // 玩家离岛很远时不要被当成普通生物清掉。
            trader.setRemoveWhenFarAway(false);
            trader.setPersistent(true);
        } else {
            long remainMillis = expireAt - System.currentTimeMillis();
            int remainTicks = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, remainMillis / 50L));
            trader.setDespawnDelay(remainTicks);
            // 自然刷新的游商是临时的：区块卸载就让它跟着消失，省掉一套额外的清理逻辑。
            trader.setPersistent(false);
            trader.setRemoveWhenFarAway(true);
        }

        // ④ 名字。让玩家一眼看出这是哪种商队，也顺带和路过的原版游商区分开。
        String rawName = origin == MerchantOrigin.CREDENTIAL
                ? TraderConfigLoader.config.getCaravanDisplayName(caravan)
                : TraderConfigLoader.config.getNaturalDisplayName();
        trader.customName(MM.deserialize("<!italic>" + rawName));
        trader.setCustomNameVisible(true);

        // ⑤ 可以被杀死（规格明确要求），所以不设 invulnerable。
        //    这里显式写一次是为了防止将来有人「顺手」加上无敌——那会让「打死重召」这条规则失效。
        trader.setInvulnerable(false);
    }
}
