plugins {
    id("java")
}

group = "fr.euphyllia.skyllia.stubs"

// 这个模块只提供编译期的类型签名，不参与运行时。
// 详见 src/main/java/me/earthme/luminol/api/entity/EntityTeleportAsyncEvent.java 的类注释。
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}
