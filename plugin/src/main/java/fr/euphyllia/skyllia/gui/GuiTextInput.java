package fr.euphyllia.skyllia.gui;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * 「点按钮 → 在聊天栏输入一个值 → 生效」的共享 GUI 组件。
 * <p>
 * 核心/addon 都可以用它做数值或文本输入，不用各自重复写聊天监听逻辑：
 * <pre>{@code
 * GuiTextInput.promptNumber(plugin, player, "<yellow>请输入新的半径（数字），或输入 取消 退出：",
 *         value -> { ... 用 value ... },
 *         () -> UpgradeAdminGui.open(player)); // 取消/失败后重新打开的菜单
 * }</pre>
 * 调用前会自动关闭玩家当前打开的菜单；玩家在聊天栏输入的内容会被拦截（不会广播到公共聊天，
 * 优先级早于普通聊天插件），解析失败会提示重新输入而不取消，输入 "取消"/"cancel" 或超时
 * （60 秒）会触发取消回调。
 * </p>
 */
public final class GuiTextInput {

    private static final long TIMEOUT_SECONDS = 60L;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private record Pending(Plugin owner, DoubleConsumer onNumber, Consumer<String> onText,
                            @Nullable Runnable onCancel, ScheduledTask timeoutTask) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private GuiTextInput() {}

    /** 提示玩家在聊天栏输入一个数字（int/double 均可解析为 double） */
    public static void promptNumber(@NotNull Plugin owner, @NotNull Player player,
                                     @NotNull String promptMessage,
                                     @NotNull DoubleConsumer onSuccess,
                                     @Nullable Runnable onCancel) {
        start(owner, player, promptMessage, onSuccess, null, onCancel);
    }

    /** 提示玩家在聊天栏输入一段自由文本 */
    public static void promptText(@NotNull Plugin owner, @NotNull Player player,
                                   @NotNull String promptMessage,
                                   @NotNull Consumer<String> onSuccess,
                                   @Nullable Runnable onCancel) {
        start(owner, player, promptMessage, null, onSuccess, onCancel);
    }

    private static void start(@NotNull Plugin owner, @NotNull Player player, @NotNull String promptMessage,
                               @Nullable DoubleConsumer onNumber, @Nullable Consumer<String> onText,
                               @Nullable Runnable onCancel) {
        UUID id = player.getUniqueId();
        cancelPending(id, false);

        player.closeInventory();
        player.sendMessage(MM.deserialize(promptMessage));
        player.sendMessage(Component.text("§7（在聊天栏输入 §f取消§7 可退出，60 秒后自动过期）"));

        ScheduledTask timeoutTask = Bukkit.getAsyncScheduler().runDelayed(owner, task -> {
            Pending pending = PENDING.remove(id);
            if (pending == null) return;
            Player p = Bukkit.getPlayer(id);
            if (p != null && pending.onCancel() != null) {
                p.getScheduler().run(pending.owner(), t -> pending.onCancel().run(), null);
            }
        }, TIMEOUT_SECONDS, TimeUnit.SECONDS);

        PENDING.put(id, new Pending(owner, onNumber, onText, onCancel, timeoutTask));
    }

    /** 玩家离线/手动取消时调用，清理挂起状态 */
    static void cancelPending(@NotNull UUID id, boolean runCallback) {
        Pending pending = PENDING.remove(id);
        if (pending == null) return;
        pending.timeoutTask().cancel();
        if (runCallback && pending.onCancel() != null) {
            pending.onCancel().run();
        }
    }

    /**
     * 由 {@link GuiTextInputListener} 在收到聊天消息时调用。
     *
     * @return true 表示该消息已被本组件消费（调用方应取消聊天事件，不再广播/交给其它聊天插件）
     */
    static boolean handleChat(@NotNull Player player, @NotNull String rawMessage) {
        UUID id = player.getUniqueId();
        Pending pending = PENDING.get(id);
        if (pending == null) return false;

        String message = rawMessage.trim();
        if (message.equalsIgnoreCase("cancel") || message.equals("取消")) {
            PENDING.remove(id);
            pending.timeoutTask().cancel();
            if (pending.onCancel() != null) {
                player.getScheduler().run(pending.owner(), t -> pending.onCancel().run(), null);
            }
            return true;
        }

        if (pending.onNumber() != null) {
            double value;
            try {
                value = Double.parseDouble(message);
            } catch (NumberFormatException ex) {
                player.sendMessage(Component.text("§c请输入一个有效的数字，或输入 取消 退出。"));
                return true;
            }
            PENDING.remove(id);
            pending.timeoutTask().cancel();
            player.getScheduler().run(pending.owner(), t -> pending.onNumber().accept(value), null);
            return true;
        }

        PENDING.remove(id);
        pending.timeoutTask().cancel();
        player.getScheduler().run(pending.owner(), t -> pending.onText().accept(message), null);
        return true;
    }
}
