plugins {
    id("java")
}

group = "fr.euphyllia.skyllia.hook.luminol"

dependencies {
    // 同 plugin：org.bukkit 原先靠 luminol-api 顺带提供，现在显式声明。
    // LuminolHook 用到 io.papermc.paper.ServerBuildInfo，需要 1.21 及以上。
    // 这里刻意不加 isTransitive = false：LuminolHook 还要用 net.kyori.adventure.key.Key，
    // 它是 paper-api 的传递依赖，掐掉传递就得再手动声明一堆 adventure 模块。
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(project(":api"))
    // Luminol 的异步传送事件。上游仓库域名已过期、项目已归档，改用本仓库内的编译期占位模块。
    compileOnly(project(":stubs:luminol-api"))
}

