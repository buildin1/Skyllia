plugins {
    id("java")
}

group = "fr.euphyllia.skyllia.hook.internalworld"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(project(":api"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
