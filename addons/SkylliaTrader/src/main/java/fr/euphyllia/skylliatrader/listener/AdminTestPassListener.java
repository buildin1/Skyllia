package fr.euphyllia.skylliatrader.listener;

import fr.euphyllia.skylliatrader.credential.AdminTestPass;
import fr.euphyllia.skylliatrader.gui.shop.MerchantShopGui;
import fr.euphyllia.skylliatrader.shop.ShopSession;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 「手持游商测试凭证右键」= 直接打开测试货架。
 * <p>
 * 手序处理照抄 {@link CredentialUseListener}：主手、副手两次事件<b>各只认自己那只手</b>，
 * 一次右键不会开两次界面，「主手空 + 副手拿凭证 + 右键空气」也能用。
 * 和商队凭证不同，这里对着空气也能用——不需要找地方放商人。
 * </p>
 */
public class AdminTestPassListener implements Listener {

    private final AdminTestPass pass;

    public AdminTestPassListener(AdminTestPass pass) {
        this.pass = pass;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerInteract(PlayerInteractEvent event) {
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        ShopSession session = pass.read(item);
        if (session == null) return;

        event.setCancelled(true);

        if (!player.hasPermission(AdminTestPass.PERMISSION)) {
            player.sendMessage(Component.text("§c游商测试凭证仅限管理员使用。"));
            return;
        }
        MerchantShopGui.openAdminTest(player, session);
    }
}
