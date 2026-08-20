plugins {
    id("java")
}

group = "fr.euphyllia.skylliatrader"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")

    compileOnly(project(":api"))
    compileOnly(project(":plugin"))
    compileOnly(project(":database"))

    // night-config（TOML 解析）由根 build.gradle.kts 的 allprojects 统一提供 3.8.3，
    // 这里不要再声明一个版本 —— 写 3.6.7 并不会真的降级（Gradle 仍解析成 3.8.3），
    // 只会误导读代码的人以为本模块用的是老版本。
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}
