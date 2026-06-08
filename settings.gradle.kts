
pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.wcpe.top/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("top.wcpe.taboolib.ioc") version "0.0.5"
    }
}

include("api")
include("project:core")
include("plugin")