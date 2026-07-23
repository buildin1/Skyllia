plugins {
    id("java")
    id("io.papermc.paperweight.userdev")
}


java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

paperweight {
    paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}

dependencies {
    paperweight.paperDevBundle("1.21.10-R0.1-SNAPSHOT")
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
    // Temps fix - Could not find net.kyori:adventure-text-serializer-ansi:.
    exclude(group = "net.kyori", module = "adventure-text-serializer-ansi")
}
