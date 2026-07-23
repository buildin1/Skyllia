plugins {
    id("java")
}

group = "fr.euphyllia.skyllia.hook.internalworld"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    compileOnly(project(":api"))
}

