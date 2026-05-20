plugins {
    id("java")
}

group = "fr.euphyllia.skylliaislandlevel"

repositories {
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")

    compileOnly(project(":api"))
    compileOnly(project(":plugin"))
    compileOnly(project(":database"))

    compileOnly("com.electronwill.night-config:toml:3.6.7")
    compileOnly("com.ezylang:EvalEx:3.6.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}
