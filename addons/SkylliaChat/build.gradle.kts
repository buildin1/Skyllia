plugins {
    id("java")
}

group = "fr.euphyllia.skylliachat";

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.3-R0.1-SNAPSHOT")
    compileOnly(project(":api"))
    compileOnly(project(":plugin"))
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