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
import fr.euphyllia.skylliatrader.listener.CredentialUseListener;
import fr.euphyllia.skylliatrader.listener.MerchantLifecycleListener;
import fr.euphyllia.skylliatrader.listener.PlayerLocationTracker;
import fr.euphyllia.skylliatrader.listener.TraderIslandListener;
import fr.euphyllia.skylliatrader.merchant.MerchantKeys;
import fr.euphyllia.skylliatrader.merchant.MerchantService;
import fr.euphyllia.skylliatrader.merchant.MerchantSpawner;
import fr.euphyllia.skylliatrader.permission.TraderPermissions;
import fr.euphyllia.skylliatrader.task.NaturalSpawnTask;
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
 * 只读的玩家进度指南 GUI、管理命令/GUI 框架的骨架。
 * </p>
 * <p>
 * T2 阶段交付范围：<b>自然刷新 + 凭证召唤</b>——三种商队、凭证的 CAS 占位与消耗、
 * 虚空世界安全落点扫描、实体 PDC 标记与重启后重新接管、死亡/移除的名额回收、
 * 自然刷新的临时游商（只开基础池 + 说明书）。
 * </p>
 * <p>
 * T3-T6（商店购买 GUI 与扣款发货事务、商队订单与声望、折扣与动态限购、等级联动）
 * 仍未实现，代码里能看到 {@code TODO(T3)} 之类标注的地方就是留给它们的口子。
 * </p>
 */
public final class SkylliaTrader extends JavaPlugin {

    private static final Logger log = LoggerFactory.getLogger(SkylliaTrader.class);
    private static final String EXTENSION_ID = "skylliatrader:progress";

    private static SkylliaTrader instance;

    private TraderDataService dataService;
    private TraderPermissions permissions;
    private MerchantService merchantService;

    public static SkylliaTrader getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        log.warn("SkylliaTrader 目前是测试版本！（T2 阶段：自然刷新 + 凭证召唤已可用，"
                + "商店购买 / 商队订单 / 折扣联动尚未实现）");

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

        MerchantKeys merchantKeys = new MerchantKeys(this);
        MerchantSpawner merchantSpawner = new MerchantSpawner(merchantKeys);
        this.merchantService = new MerchantService(this, dataService, merchantKeys, merchantSpawner);

        getServer().getPluginManager().registerEvents(new TraderIslandListener(dataService), this);
        getServer().getPluginManager().registerEvents(
                new MerchantLifecycleListener(this, merchantKeys, merchantSpawner, merchantService), this);
        getServer().getPluginManager().registerEvents(new CredentialUseListener(merchantService), this);

        // 玩家位置缓存：自然刷新用它判「成员是不是真的站在自己岛上」，
        // 避免为一座没人的岛把 spawn 区块拉起来。必须在巡检任务启动之前注册好。
        PlayerLocationTracker locationTracker = new PlayerLocationTracker();
        getServer().getPluginManager().registerEvents(locationTracker, this);

        NaturalSpawnTask.start(this, merchantService, locationTracker);

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

    /**
     * 关服清理。
     * <p>
     * <b>刻意不做任何实体清理</b>（规格 6.9）：Folia 在关服阶段已经没法再往 region/entity
     * 调度器上派任务了，试图去 remove 游商只会抛一堆异常，而且也没必要——凭证游商本来就该
     * 随区块存盘活到下次开服，自然刷新的游商是 {@code persistent = false} 的，
     * 随区块卸载自己就没了，就算存下来也会在下次加载时被过期检查清掉。
     * </p>
     * <p>
     * 这里只做三件不需要世界的事：取消调度器上的任务、摘掉 GUI 扩展入口、注销配置。
     * </p>
     */
    @Override
    public void onDisable() {
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);

        GuiExtensionRegistry.unregister(EXTENSION_ID);

        TraderConfigLoader.unregister();
        OrdersConfigLoader.unregister();
        instance = null;
    }

    /** 游商生成 / 名额管理服务。 */
    public MerchantService getMerchantService() {
        return merchantService;
    }

    public TraderDataService getDataService() {
        return dataService;
    }

    /** 本插件注册的岛屿角色权限点；T2 的游商交互监听器判权限时用。 */
    public TraderPermissions getPermissions() {
        return permissions;
    }
}
