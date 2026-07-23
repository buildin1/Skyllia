plugins {
    id("java")
    id("io.papermc.paperweight.userdev")
}


paperweight {
    paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}

dependencies {
    paperweight.foliaDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly(project(":nms:v1_21_R5"))
    compileOnly(project(":api"))

    pluginRemapper("net.fabricmc:tiny-remapper:0.14.0")
}


tasks {
    assemble {
        dependsOn(reobfJar)
    }
    compileJava {
        options.encoding = "UTF-8"
    }
}

configurations.all {
    exclude(group = "net.kyori", module = "adventure-text-serializer-ansi")
}
