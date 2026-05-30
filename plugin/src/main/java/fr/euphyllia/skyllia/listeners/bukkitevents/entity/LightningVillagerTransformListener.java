package fr.euphyllia.skyllia.listeners.bukkitevents.entity;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.EntityTransformEvent.TransformReason;

public class LightningVillagerTransformListener implements Listener {

    @EventHandler
    public void onEntityTransform(EntityTransformEvent event) {
        // 只处理雷击转化
        if (event.getTransformReason() != TransformReason.LIGHTNING) return;

        Entity original = event.getEntity();
        Entity transformed = event.getTransformedEntity();

        // 确认是村民 → 女巫
        if (original.getType() != EntityType.VILLAGER || transformed.getType() != EntityType.WITCH) return;

        Location loc = original.getLocation();
        // 获取岛屿主人名
        Island island = SkylliaAPI.getIslandByChunk(
                loc.getBlockX() >> 4,
                loc.getBlockZ() >> 4
        );
        String ownerName;
        if (island != null) {
            Players owner = island.getOwner();
            ownerName = owner != null ? owner.getLastKnowName() : "未知玩家";
        } else {
            ownerName = "无人";
        }

        // 坐标取整数
        String pos = String.format("%d %d %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        // 构建消息并转换颜色代码
        String rawMessage = String.format(
                "&f[&6喜报&f] &c%s岛上的村民&7(%s)&c被雷劈成了&5女巫&c喵！",
                ownerName, pos
        );
        String coloredMessage = ChatColor.translateAlternateColorCodes('&', rawMessage);

        // 向全服在线玩家广播
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(coloredMessage);
        }
    }
}