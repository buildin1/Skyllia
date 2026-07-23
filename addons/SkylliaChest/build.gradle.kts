plugins {
    id("java")
}

group = "fr.euphyllia.skylliachest";

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    compileOnly(project(":api"))
    compileOnly(project(":plugin"))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}