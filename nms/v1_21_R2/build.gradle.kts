plugins {
    id("java")
    id("io.papermc.paperweight.userdev")
}

repositories {
    maven("https://github.com/Euphillya/FoliaDevBundle/raw/gh-pages/")
}

paperweight {
    paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}

dependencies {
    paperweight.foliaDevBundle("1.21.3-R0.1-SNAPSHOT")
    compileOnly(project(":api"))
}


tasks {
    assemble {
        dependsOn(reobfJar)
    }
    compileJava {
        options.encoding = "UTF-8"
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}