
pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.wcpe.top/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("top.wcpe.taboolib.ioc") version "0.0.6"
    }
}

// 依赖解析统一收口到本处声明的仓库(PREFER_SETTINGS):忽略各工程 / 插件运行期注入的仓库。
// 起因:io.izzel.taboolib 插件硬编码向工程注入 repo.spongepowered.org/maven 镜像,该站近期持续 522
// (Cloudflare 源站超时);Gradle 解析依赖一旦命中其 5xx 即整体中止、不回退其它仓库,致探针构建失败。
// 在此集中声明全部健康仓库(不含 spongepowered),令插件注入的死镜像被忽略;所需制品均可正常解析:
// asm→mavenCentral / aliyun,taboolib-ioc→maven.wcpe.top,taboolib 框架与 ink.ptms.core→tabooproject。
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()
        maven("https://maven.wcpe.top/repository/maven-public/")
        maven("https://repo.tabooproject.org/repository/releases/")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://jitpack.io")
        mavenCentral()
    }
}

rootProject.name = "ServerProbe"

include("api")
include("project:core")
include("platform:platform-bukkit")
include("platform:platform-bungee")
include("plugin")