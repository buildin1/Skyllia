package fr.euphyllia.skyllia.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 拦截处于 {@link GuiTextInput} 等待输入状态的玩家聊天消息。
 * <p>
 * 优先级设为 LOWEST，早于普通聊天插件（如 SkylliaChat）处理，命中后直接取消事件，
 * 避免输入内容被广播到公共聊天或被其它聊天插件处理。
 * </p>
 */
public class GuiTextInputListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(@NotNull AsyncPlayerChatEvent event) {
        if (GuiTextInput.handleChat(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        GuiTextInput.cancelPending(event.getPlayer().getUniqueId(), false);
    }
}
