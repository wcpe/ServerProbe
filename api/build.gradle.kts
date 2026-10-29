plugins {
    id("serverprobe.base")
    `maven-publish`
}

java {
    withSourcesJar()
}

// api 模块为纯 Java 公开契约(ADR-13):用 Lombok 生成不可变数据模型,消除 Kotlin metadata,
// 使任意 Kotlin(含 1.x)/ Java 版本均可编译依赖。Lombok 仅编译期(compileOnly + 注解处理器),不进消费方传递依赖。
dependencies {
    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")
    // 契约测试:DomainManifest/ActionSpec 的 null 归一、防御性拷贝、
    // 风险等级 fail-safe 默认值等语义都是对外承诺，必须有测试钉死。
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    // JUnit 5.12+ 要求测试运行期显式具备 Platform launcher，否则 Gradle 报
    // "Could not start Gradle Test Executor / Failed to load JUnit Platform"。
    // 版本由 junit-jupiter 传递的 junit-bom 管理，无需在此写死。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
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
        }
    }
}