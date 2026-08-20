package me.earthme.luminol.api.entity;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Luminol 的异步传送事件——<b>这里的实现是编译期占位（桩），不是真实实现</b>。
 *
 * <h2>为什么会有这个文件</h2>
 * 本插件原先从 {@code me.earthme.luminol:luminol-api} 这个 Maven 依赖取得该类。
 * 2026-08-20 起，Luminol 项目的仓库域名 {@code repo.menthamc.org} 过期变成域名停放页，
 * 返回的 HTML 被 Gradle 当成 maven-metadata.xml 解析失败，导致 CI 全线构建失败；
 * 而 Luminol 上游仓库本身也已归档，不会再有新的官方 Maven 地址。
 * 我们实际用到的 API 面只有这一个类，所以改为在本仓库内提供签名一致的占位类型。
 *
 * <h2>签名从哪来</h2>
 * 用 {@code javap} 从原 {@code luminol-api} 的 jar 里逐行抄出来的，包名、类名、父类、
 * 方法名与返回类型全部一致。<b>修改本文件前务必确认与真实类仍然一致</b>——
 * 运行期加载的是服务端提供的真类，签名对不上会导致方法找不到，
 * 而调用方 {@code TeleportPermissions} 负责的是「封禁玩家遣返」和「访客传送准入」，
 * 静默失效等于岛屿准入控制被绕过。
 *
 * <h2>绝对不能被打进最终 jar</h2>
 * 本模块只允许以 {@code compileOnly} 的方式被依赖，<b>不要</b>在根项目里写成
 * {@code implementation(project(":stubs:luminol-api"))}。插件的类加载器会优先加载自己 jar
 * 里的类，一旦这个桩被打包进去，它会盖掉服务端提供的真类，监听到的将是另一个类对象，
 * 事件永远不会触发——比缺失依赖更难排查。
 *
 * <h2>运行期由谁提供</h2>
 * 实际运行的服务端（Luminol，或其闭源延续 Shiroha）自带这个类。因此本桩不需要有任何
 * 方法体逻辑，只需要让编译器看到正确的类型。
 *
 * @see fr.euphyllia.skyllia.listeners.permissions.player.TeleportPermissions
 */
public class EntityTeleportAsyncEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Entity entity;
    private final PlayerTeleportEvent.TeleportCause cause;
    private final Location destination;

    public EntityTeleportAsyncEvent(Entity entity, PlayerTeleportEvent.TeleportCause cause, Location destination) {
        this.entity = entity;
        this.cause = cause;
        this.destination = destination;
    }

    public Entity getEntity() {
        return this.entity;
    }

    public PlayerTeleportEvent.TeleportCause getTeleportCause() {
        return this.cause;
    }

    public Location getDestination() {
        return this.destination;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
