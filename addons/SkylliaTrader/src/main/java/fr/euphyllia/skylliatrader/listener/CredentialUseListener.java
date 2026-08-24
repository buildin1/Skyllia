package fr.euphyllia.skylliatrader.listener;

import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.configuration.model.CredentialItemSpec;
import fr.euphyllia.skylliatrader.merchant.CaravanType;
import fr.euphyllia.skylliatrader.merchant.MerchantService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 「手持商队凭证右键」= 召唤常驻商人。
 *
 * <h2>为什么是右键物品，而不是一条命令</h2>
 * <p>
 * 凭证是挑战任务发到玩家背包里的一件物品，玩家拿到之后的第一反应就是右键试试。
 * 做成 {@code /is trader summon <type>} 的话，玩家得先知道这条命令存在、还得知道自己手上
 * 这张纸对应哪个 {@code <type>} ——而这个系统本来就已经有四条轨道要解释了，
 * 能少一层认知负担就少一层。（说明书里也是这么写的：「拿着凭证在自己岛上右键」。）
 * </p>
 *
 * <h2>必须处理的几个细节</h2>
 * <ul>
 *   <li><b>两次事件，两条互不重叠的匹配规则</b>：一次右键最多会为主手和副手各触发一次
 *       {@code PlayerInteractEvent}。这里<b>两次都处理</b>，但每一次<b>只认它自己那只手</b>上
 *       的物品：主手事件只匹配主手，副手事件只匹配副手。这样既不会一次右键跑两遍召唤，
 *       也不会「拿错手处理错物品」。</li>
 *   <li><b>为什么副手那一次事件必须单独处理</b>：曾经的写法是「副手事件直接 return，
 *       改在主手事件里顺便看一眼副手」，代价是<b>「主手空 + 副手拿凭证 + 右键空气」完全没反应</b>。
 *       原因在服务端 {@code ServerGamePacketListenerImpl#handleUseItem} 的第一行判定
 *       {@code if (!itemStack.isEmpty() && ...)}：主手是空的那一次<b>根本不会</b>发出任何
 *       {@code PlayerInteractEvent}，这个姿势下<b>只存在</b> {@code OFF_HAND} 那一次事件，
 *       被过滤掉就等于什么都没发生。而说明书和凭证 lore 写的都是「在自己岛上右键」，
 *       没说要对着地面，玩家照着做只会得到「点了没反应」。</li>
 *   <li><b>为什么主手事件<u>不</u>回退去认副手</b>：本监听器会 {@code setCancelled(true)}。
 *       「主手拿方块 + 副手插着凭证 + 对地面右键」时，如果主手那次事件去认副手的凭证，
 *       玩家的方块放不下去，还白白烧掉一张<b>一次性</b>凭证。交给原版的手序天然就是对的：
 *       主手那次交互只要被消费掉（方块放下去了），客户端就<b>不会</b>再为副手发第二个包，
 *       副手事件根本不存在，凭证安然无恙。</li>
 *   <li><b>取消事件</b>：不取消的话，右键的原有行为还会继续——凭证如果配成了可放置的方块
 *       就会被放下去，配成食物就会被吃掉。
 *       <br>
 *       ⚠️ 取消主手这一次<b>并不</b>能保证副手那次事件不来：服务端是按包处理的，
 *       一个包一只手，发不发第二个包是客户端根据自己的预测决定的
 *       （见 {@code handleUseItemOn}，那里没有任何手序循环）。所以「一次右键召唤两次」
 *       这件事不能靠取消来防，只能靠上面那条「每次事件只认自己那只手」。真要两只手都插着
 *       凭证，最坏结果是第二次被 {@code MerchantService} 的 in-flight 集合挡下、
 *       玩家看到一句「上一次召唤还在处理中」，凭证只扣一张。</li>
 * </ul>
 */
public class CredentialUseListener implements Listener {

    private final MerchantService service;

    public CredentialUseListener(MerchantService service) {
        this.service = service;
    }

    /**
     * 用 {@code LOW} 优先级 + <b>不</b>加 {@code ignoreCancelled}：
     * 领地/保护类插件常常在默认优先级上把「对着方块右键」取消掉，
     * 而玩家在自己岛上用凭证是完全正当的操作，不该因为脚下踩着一块受保护的方块就用不了。
     * 我们自己会判「必须在自己岛上」，安全性由那一步保证。
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 只认这两只手。getHand() 还可能是 null（踩压力板之类的物理交互）。
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();

        // 刻意不用 event.getItem()：它给的就是「本次事件那只手上的物品」，语义没错，
        // 但读代码的人很容易把它当成「玩家用的那件物品」。这里直接按 hand 取，一一对应。
        //
        // ⚠️ 每一次事件**只**匹配它自己那只手，不做任何跨手回退。两个方向都踩过坑：
        //   · 主手事件回退去认副手 → 「主手拿方块 + 副手凭证 + 对地面右键」会既放不下方块、
        //     又烧掉一张一次性凭证（上一轮修的就是这个）；
        //   · 副手事件直接 return  → 「主手空 + 副手凭证 + 右键空气」完全没反应，因为那个姿势下
        //     服务端压根不会为空的主手发出事件，只有副手这一次。
        // 各管各的手，两个坑同时避开。详见类注释。
        ItemStack item = hand == EquipmentSlot.HAND
                ? inventory.getItemInMainHand()
                : inventory.getItemInOffHand();
        CaravanType caravan = matchCredential(item);
        if (caravan == null) return;

        // 取消原有的右键行为（放置 / 使用 / 打开容器）。
        event.setCancelled(true);

        service.summonWithCredential(player, caravan, hand);
    }

    /**
     * 判断手上这件物品是哪一种商队凭证。
     * <p>
     * 遍历三种依次匹配。配置加载时已经保证了三种的 CustomModelData 互不相同
     * （撞了的会被停用并 error），所以这里不会出现「一件物品同时匹配两种」的歧义。
     * </p>
     */
    private CaravanType matchCredential(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        for (CaravanType type : CaravanType.values()) {
            CredentialItemSpec spec = TraderConfigLoader.config.getCredentialItem(type);
            if (spec != null && spec.matches(item)) return type;
        }
        return null;
    }
}
