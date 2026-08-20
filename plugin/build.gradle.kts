plugins {
    id("java")
    id("maven-publish")
}
group = "fr.euphyllia.skyllia"

dependencies {
    // 此前这里没有显式声明 paper-api，org.bukkit 是靠 luminol-api（Paper 分支的 API）顺带带进来的。
    // luminol 上游仓库失效后那条路断了，补回显式声明（上游 Skyllia 本来也是这么写的）。
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT") { isTransitive = false }
    compileOnly("net.kyori:adventure-text-minimessage:4.26.1")
    compileOnly("net.kyori:adventure-text-serializer-legacy:4.26.1")
    // 这个模块此前也是靠 luminol-api 传递进来的。显式声明而不是放开 paper-api 的传递依赖，
    // 是为了让三个 adventure 模块保持同一个钉死的版本，不让 paper 自带的版本参与仲裁。
    compileOnly("net.kyori:adventure-text-serializer-plain:4.26.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
    // Luminol 的异步传送事件。上游仓库域名已过期、项目已归档，改用本仓库内的编译期占位模块。
    compileOnly(project(":stubs:luminol-api"))
    compileOnly("dev.faststats.metrics:bukkit:0.27.0")
    compileOnly(project(":api"))
    compileOnly(project(":database"))
    compileOnly(project(":hook:worldedit"))
    compileOnly(project(":hook:fastasyncworldedit"))
    compileOnly(project(":hook:internalworld"))
    compileOnly(project(":hook:canvas"))
    compileOnly(project(":hook:luminol"))
    compileOnly(project(":hook:essentialsx"))
    compileOnly(project(":hook:luckperms"))
    compileOnly(project(":hook:quickshop"))
    compileOnly(project(":hook:cmi"))
    compileOnly(project(":hook:insights"))

    // NMS Version
    compileOnly(project(":nms:v1_20_R4"))
    compileOnly(project(":nms:v1_21_R1"))
    compileOnly(project(":nms:v1_21_R2"))
    compileOnly(project(":nms:v1_21_R3"))
    compileOnly(project(":nms:v1_21_R4"))
    compileOnly(project(":nms:v1_21_R5"))
    compileOnly(project(":nms:v1_21_R6"))
    compileOnly(project(":nms:v1_21_R7"))
    //compileOnly(project(":nms:v26_1"))
    //compileOnly(project(":nms:v26_2"))
}
java.disableAutoTargetJvm()

publishing {
    publications {
        create<MavenPublication>("gpr") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "Euphyllia-Repo"
            url = uri("https://repo.euphyllia.moe/repository/maven-releases/")
            credentials {
                username = System.getenv("NEXUS_USERNAME") ?: ""
                password = System.getenv("NEXUS_PASSWORD") ?: ""
            }
        }
    }
}
