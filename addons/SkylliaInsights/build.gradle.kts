plugins {
    id("java")
}

group = "fr.euphyllia.skyllia_insight_addon";

repositories {
    maven("https://repo.jsinco.dev/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    compileOnly("dev.frankheijden.insights:Insights:6.21.2")
    compileOnly(project(":api"))
    compileOnly(project(":plugin"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}