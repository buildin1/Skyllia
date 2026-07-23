plugins {
    id("java")
    id("io.papermc.paperweight.userdev")
}

//  Could not find me.lucko:spark-paper:1.10.105-SNAPSHOT.
configurations.all {
    exclude(group = "me.lucko", module = "spark-paper")
    exclude(group = "me.lucko", module = "spark-common")
    exclude(group = "me.lucko", module = "spark-api")
}


repositories {
    maven("https://github.com/Euphillya/FoliaDevBundle/raw/gh-pages/")
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
    paperweight.foliaDevBundle("1.21.1-R0.1-SNAPSHOT")
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

