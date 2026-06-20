plugins {
    `maven-publish`
}

java {
    withSourcesJar()
}

// api 模块为纯 Java 公开契约(ADR-13):用 Lombok 生成不可变数据模型,消除 Kotlin metadata,
// 使任意 Kotlin(含 1.x)/ Java 版本均可编译依赖。Lombok 仅编译期(compileOnly + 注解处理器),不进消费方传递依赖。
dependencies {
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
}

publishing {
    repositories {
        maven {
            credentials {
                username = project.findProperty("username").toString()
                password = project.findProperty("password").toString()
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
            val releasesRepoUrl = uri("https://maven.wcpe.top/repository/maven-releases/")
            val snapshotsRepoUrl = uri("https://maven.wcpe.top/repository/maven-snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
        }
        mavenLocal()
    }
    publications {
        // API 发布配置
        create<MavenPublication>("api") {
            groupId = project.group.toString()
            artifactId = "${rootProject.name}-${project.name}".lowercase()
            version = "${project.version}"
            from(components["java"])
            println("> Apply \"$groupId:$artifactId:$version\"")
        }
    }
}