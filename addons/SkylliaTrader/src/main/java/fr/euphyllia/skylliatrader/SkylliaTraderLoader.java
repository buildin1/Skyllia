package fr.euphyllia.skylliatrader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import org.jetbrains.annotations.NotNull;

/**
 * SkylliaTrader 的插件加载器。
 * <p>
 * 与 SkylliaIslandValue 不同，本插件目前不需要额外拉取任何运行时库
 * （TOML 解析用的 night-config、JSON 序列化用的 Gson 都已经随 Skyllia 核心/Paper 服务端
 * 一起出现在类路径上），所以这里暂时留空。如果后续阶段（T2+）引入需要独立依赖的库
 * （例如更复杂的表达式解析），照抄 SkylliaIslandLevelLoader 里用 MavenLibraryResolver
 * 追加依赖的写法即可。
 * </p>
 */
public class SkylliaTraderLoader implements PluginLoader {

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        // 暂无需要额外解析的依赖，见类注释。
    }
}
