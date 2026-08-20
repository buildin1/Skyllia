package fr.euphyllia.skylliatrader;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.gui.GuiExtensionEntry;
import fr.euphyllia.skyllia.gui.GuiExtensionRegistry;
import fr.euphyllia.skylliatrader.commands.TraderAdminCommand;
import fr.euphyllia.skylliatrader.commands.TraderCommand;
import fr.euphyllia.skylliatrader.configuration.OrdersConfigLoader;
import fr.euphyllia.skylliatrader.configuration.TraderConfigLoader;
import fr.euphyllia.skylliatrader.data.TraderDataService;
import fr.euphyllia.skylliatrader.gui.TraderProgressGui;
import fr.euphyllia.skylliatrader.listener.TraderIslandListener;
import fr.euphyllia.skylliatrader.permission.TraderPermissions;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * SkylliaTrader 主类：增强游商系统。
 * <p>
 * T1 阶段交付范围：模块骨架、配置（四轨档位表 + 订单表）、按岛屿维度的数据层、
 * 只读的玩家进度指南 GUI、管理命令/GUI 框架的骨架。T2-T6（自然刷新、购买事务、
 * 订单执行、折扣联动、等级联动）不在本阶段实现，代码里能看到 TODO 的地方就是留给它们的口子。
 * </p>
 */
public final class SkylliaTrader extends JavaPlugin {

    private static final Logger log = LoggerFactory.getLogger(SkylliaTrader.class);
    private static final String EXTENSION_ID = "skylliatrader:progress";

    private static SkylliaTrader instance;

    private TraderDataService dataService;
    private TraderPermissions permissions;

    public static SkylliaTrader getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        log.warn("SkylliaTrader 目前是测试版本！（T1 阶段：仅骨架 + 数据结构，无购买/订单执行逻辑）");

        try {
            TraderConfigLoader.init(getDataFolder());
            OrdersConfigLoader.init(getDataFolder());
        } catch (Exception e) {
            log.error("配置加载失败，插件已自动停用。请检查 config/config.toml 与 config/orders.toml。", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.permissions = new TraderPermissions(this);

        this.dataService = new TraderDataService(this);

        getServer().getPluginManager().registerEvents(new TraderIslandListener(dataService), this);

        SkylliaAPI.registerCommands(new TraderCommand(), "trader");
        SkylliaAPI.registerAdminCommands(new TraderAdminCommand(), "trader");

        // 挂进 /is gui 的「扩展功能」列表，玩家不用记命令也能找到进度指南。
        GuiExtensionRegistry.register(new GuiExtensionEntry(
                EXTENSION_ID,
                Material.EMERALD,
                "<light_purple>🛒 游商进度",
                List.of("<gray>查看交易次数/岛屿等级/商会声望/累计消费", "<gray>四条轨道目前解锁到了哪一档</gray>"),
                0,
                player -> SkylliaAPI.getIslandByPlayerId(player.getUniqueId()) != null,
                TraderProgressGui::open
        ));
    }

    @Override
    public void onDisable() {
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);

        GuiExtensionRegistry.unregister(EXTENSION_ID);

        TraderConfigLoader.unregister();
        OrdersConfigLoader.unregister();
        instance = null;
    }

    public TraderDataService getDataService() {
        return dataService;
    }

    /** 本插件注册的岛屿角色权限点；T2 的游商交互监听器判权限时用。 */
    public TraderPermissions getPermissions() {
        return permissions;
    }
}
