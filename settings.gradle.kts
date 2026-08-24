
pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.wcpe.top/repository/maven-public/")
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
// 公共仓库(mavenCentral / aliyun)置于最前:任一私仓(如 wcpe.top)对个别制品返回 5xx 时,
// 若其排在前面会导致 Gradle 中止不回退(detekt 工具依赖 kotlin-compiler-embeddable 曾因此解析失败)。
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()
        // Mojang 原始库完整托管 DataFixerUpper，聚合仓库缺少目标 jar 时优先从此处解析。
        maven("https://libraries.minecraft.net/") {
            content {
                includeGroup("com.mojang")
            }
        }
        // 聚合仓库仅镜像 NMS 坐标的 POM，mapped 与 universal jar 从原仓库完整解析。
        maven("https://repo.tabooproject.org/repository/releases/") {
            content {
                includeGroup("ink.ptms.core")
            }
        }
        mavenCentral()
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.wcpe.top/repository/maven-public/")
        maven("https://repo.tabooproject.org/repository/releases/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "ServerProbe"

include("api")
include("project:core")
include("platform:platform-bukkit")
include("platform:platform-bungee")
include("plugin")
