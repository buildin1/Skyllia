plugins {
    id("java")
}

group = "fr.euphyllia.skylliaupgrade"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")

    compileOnly(project(":api"))
    compileOnly(project(":plugin"))
    compileOnly(project(":database"))
    compileOnly(project(":addons:SkylliaIslandValue"))

    compileOnly("com.electronwill.night-config:toml:3.6.7")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}
