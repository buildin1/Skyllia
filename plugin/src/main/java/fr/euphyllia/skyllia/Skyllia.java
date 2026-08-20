package fr.euphyllia.skyllia;

import dev.faststats.ErrorTracker;
import dev.faststats.Metrics;
import dev.faststats.bukkit.BukkitContext;
import fr.euphyllia.skyllia.api.InterneAPI;
import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandRegistry;
import fr.euphyllia.skyllia.api.exceptions.UnsupportedMinecraftVersionException;
import fr.euphyllia.skyllia.api.utils.metrics.BStatsMetrics;
import fr.euphyllia.skyllia.commands.CommandRegistrar;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skyllia.hook.HookBootstrap;
import fr.euphyllia.skyllia.listeners.ListenersRegistrar;
import fr.euphyllia.skyllia.papi.SkylliaExpansion;
import fr.euphyllia.skyllia.sgbd.exceptions.DatabaseException;
import fr.euphyllia.skyllia.utils.UpdateCheckerTask;
import fr.euphyllia.skyllia.utils.WorldUtils;
import net.md_5.bungee.api.ChatColor;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("UnstableApiUsage")
public class Skyllia extends JavaPlugin {

    public static final ErrorTracker ERROR_TRACKER = ErrorTracker.contextAware();
    private static Skyllia instance;
    private final Logger logger = LogManager.getLogger(this);
    private final BukkitContext context = new BukkitContext.Factory(this, "f329c2c0e1c9562dffebed9b6786d4c9")
            .errorTrackerService(ERROR_TRACKER)
            .metrics(Metrics.Factory::create)
            .create();
    private InterneAPI interneAPI;
    private SubCommandRegistry commandRegistry;
    private SubCommandRegistry adminCommandRegistry;
    private BStatsMetrics bStatsMetrics;

    public static Skyllia getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        context.ready();
        printStartupBanner();
        File oldConfig = new File(getDataFolder(), "config.toml");
        if (oldConfig.exists()) {
            getLogger().severe("══════════════════════════════════════════════════════");
            getLogger().severe("          ❌ OUTDATED CONFIGURATION DETECTED ❌");
            getLogger().severe(" ");
            getLogger().severe("  The file 'config.toml' is no longer supported.");
            getLogger().severe("  Major internal changes have been made.");
            getLogger().severe(" ");
            getLogger().severe("  ⚠️ Please delete or manually migrate your configuration.");
            getLogger().severe("  The plugin will not continue with an outdated config.");
            getLogger().severe("══════════════════════════════════════════════════════");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (!initializeInterneAPI()) {
            logger.error("Incompatible Minecraft version detected. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (!loadConfigurations()) {
            logger.error("Configuration loading failed.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize managers
        this.interneAPI.setManagers(new fr.euphyllia.skyllia.managers.Managers(interneAPI));
        this.interneAPI.getManagers().init();

        ConfigLoader.reloadConfigs();

        this.interneAPI.initWorldModifier();

        // Register commands via CommandRegistrar
        CommandRegistrar commandRegistrar = new CommandRegistrar(this);
        commandRegistrar.registerCommands();

        this.commandRegistry = commandRegistrar.getCommandRegistry();
        this.adminCommandRegistry = commandRegistrar.getAdminCommandRegistry();

        // Register listeners
        new ListenersRegistrar(this, interneAPI).registerListeners();

        HookBootstrap.registerAll();

        bStatsMetrics = new BStatsMetrics(this, 20874);

        // 维度映射：延迟到世界加载完成后执行（sky world 此时可能还没加载完）
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            World overworld = null, netherWorld = null, endWorld = null;
            for (var cfg : ConfigLoader.worldManager.getWorldConfigs().values()) {
                World w = cfg.getWorld();
                if (w == null) continue;
                switch (cfg.getEnvironment()) {
                    case NORMAL -> overworld = w;
                    case NETHER -> netherWorld = w;
                    case THE_END -> endWorld = w;
                }
            }
            interneAPI.getWorldNMS().remapPortalDimensions(overworld, netherWorld, endWorld);
            logger.info("[Skyllia-维度映射] 延迟映射完成 overworld={} nether={} end={}",
                    overworld != null ? overworld.getName() : "null",
                    netherWorld != null ? netherWorld.getName() : "null",
                    endWorld != null ? endWorld.getName() : "null");
        }, 60L); // 3秒后执行（60 ticks）

        // 定期检查：如果玩家在主世界，强制传送回空岛
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                World world = player.getWorld();
                if (world != null && !WorldUtils.isWorldSkyblock(world.getName())) {
                    // 调试：记录玩家世界详情
                    String dimKey = interneAPI.getPlayerNMS().getDimensionKey(player);
                    logger.warn("[Skyllia-主世界防护] 玩家 {} 在非空岛世界 name={} dimKey={} env={}，准备强制传送",
                            player.getName(), world.getName(), dimKey, world.getEnvironment());

                    // 通过 NMS 传送到空岛世界，绕过 Bukkit 事件
                    World skyWorld = WorldUtils.getWorldConfigs().getFirst().getWorld();
                    if (skyWorld != null) {
                        Location target = new Location(skyWorld, player.getX(), player.getY(), player.getZ());
                        SkylliaAPI.getPlayerNMS().teleport(player, target);
                        logger.warn("[Skyllia-主世界防护] 已传送 {} -> {} @ {},{},{}",
                                player.getName(), skyWorld.getName(),
                                target.getBlockX(), target.getBlockY(), target.getBlockZ());
                    }
                }
            }
        }, 100L, 100L); // 每5秒检查一次

        ConfigLoader.permissionsV2.compileNow();
        ConfigLoader.islandFlags.compileNow();

        // ── 附属插件权限/标志的注册时序补偿 ───────────────────────────────────
        // 各 addon 的 paper-plugin.yml 都声明了 `Skyllia: load: BEFORE`，也就是核心先
        // enable、addon 后 enable。上面这次 compileNow() 跑在核心自己的 onEnable 里，
        // 此刻 addon 还没来得及调用 idOrRegister()，于是：
        //   1) permissions-v2.toml 里写好的 addon 权限行会被 readDefaultsFlat 当成
        //      "未注册的未知权限"静默丢弃；
        //   2) ensureRoleBlocksContainAllFlat 只遍历注册表，也补不上这一位；
        //   3) 结果 compiledDefaults 缺位 → buildDefaultRoleBlobs 生成的岛屿权限位图该位为 0
        //      → 新建岛屿连 OWNER 都是 false，而位图是建岛时就写库的，事后 reload 救不回来。
        // 因此在所有插件 enable 完成之后（延迟 1 tick）统一再编译一次，把各 addon 注册的
        // 权限点/标志补进 compiledDefaults。
        //
        // 幂等性：compileNow() -> loadConfig() 只会为"配置里还不存在的键"写入默认值
        // （ensureRoleBlocksContainAllFlat 遇到已有键直接 continue），管理员改过的值一律保留；
        // 没有新增键时 changed 保持 false，连文件都不会重写，所以重复执行是安全的。
        //
        // 这里用 globalRegionScheduler.runDelayed 而不是 ServerLoadEvent：本仓库已经在
        // 上面的维度映射里验证过这种延迟任务在 Folia 上可靠，而 ServerLoadEvent 在 Folia
        // 上的触发时机/线程没有现成用例可参考。
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            ConfigLoader.permissionsV2.compileNow();
            ConfigLoader.islandFlags.compileNow();
            logger.info("[Skyllia-权限] 全部插件加载完毕，已重新编译权限/标志默认值（纳入各附属插件注册的条目）");
        }, 1L);

        this.interneAPI.getActivityZoneManager().loadAll();

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SkylliaExpansion(this).register();
        }

        if (ConfigLoader.general.getUpdateCheckerSettings().enabled()) {
            UpdateCheckerTask.start(this);
        }
    }

    @Override
    public void onDisable() {
        context.shutdown();
        if (bStatsMetrics != null) {
            bStatsMetrics.shutdown();
        }

        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);

        HookBootstrap.unregisterAll();

        if (this.interneAPI != null) {
            var worldModifier = this.interneAPI.getWorldModifier();
            if (worldModifier != null) {
                worldModifier.shutdown();
            }
            if (this.interneAPI.getDatabaseLoader() != null) {
                this.interneAPI.getDatabaseLoader().closeDatabase();
            }
        }
    }

    public InterneAPI getInterneAPI() {
        return this.interneAPI;
    }

    public @NotNull SubCommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    public @NotNull SubCommandRegistry getAdminCommandRegistry() {
        return adminCommandRegistry;
    }

    private boolean loadConfigurations() {
        try {
            for (String folder : new String[]{"config", "language", "schematics"}) {
                this.interneAPI.createAndCopyResources(getFile(), folder);
            }

            ConfigLoader.init(getDataFolder());

            boolean ok = this.interneAPI.setupSGBD();
            if (!ok) {
                logger.error("[SGBD] setupSGBD() returned false (no exception). Check DB config/credentials/driver/reachability.");
            }
            return ok;
        } catch (DatabaseException exception) {
            logger.log(Level.FATAL, exception, exception);
            return false;
        }
    }

    /**
     * Initializes the internal API, handling version compatibility.
     *
     * @return true if initialization succeeded, false otherwise
     */
    private boolean initializeInterneAPI() {
        try {
            this.interneAPI = new InterneAPI(this);
            return true;
        } catch (UnsupportedMinecraftVersionException e) {
            logger.log(Level.FATAL, e.getMessage(), e);
            return false;
        }
    }

    private void printStartupBanner() {
        final String VIOLET = "§d";
        final String GRAY = "§7";
        final String WHITE = "§f";
        final String SEP = VIOLET + "━".repeat(50);

        String[][] info = {
                {"Version", getPluginMeta().getVersion()},
                {"Server", Bukkit.getName() + WHITE + " (" + VIOLET + Bukkit.getVersion() + WHITE + ")"},
                {"Thread Model", SkylliaAPI.isFolia() ? "Multi" : "Single"},
                {"CPU Cores", String.valueOf(Runtime.getRuntime().availableProcessors())},
        };

        List<String> lines = new ArrayList<>();
        lines.add(SEP);
        lines.add(VIOLET + center(getPluginMeta().getName() + " - " + getPluginMeta().getDescription(), 50));
        lines.add(SEP);
        for (String[] entry : info)
            lines.add(GRAY + " » " + WHITE + entry[0] + ": " + VIOLET + entry[1]);
        lines.add(SEP);

        ConsoleCommandSender console = Bukkit.getConsoleSender();
        lines.forEach(console::sendMessage);
    }

    private String center(String text, int lineWidth) {
        int textLength = ChatColor.stripColor(text).length();
        int padding = (lineWidth - textLength) / 2;
        return " ".repeat(Math.max(0, padding)) + text;
    }
}
