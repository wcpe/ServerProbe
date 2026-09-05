rootProject.name = "build-logic"

pluginManagement {
    // 本机对 plugins.gradle.org 直连 TLS 握手不稳定（与 root settings 排除死镜像同因），
    // 优先走 aliyun gradle-plugin/central 镜像，portal 不声明。
    repositories {
        mavenLocal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
        maven("https://maven.wcpe.top/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        mavenCentral()
        maven("https://maven.wcpe.top/repository/maven-public/")
    }
}
