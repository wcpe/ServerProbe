plugins {
    `maven-publish`
}

java {
    withSourcesJar()
}

// api 模块不需要额外依赖，root subprojects 已提供 kotlin-stdlib

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