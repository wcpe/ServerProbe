plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(fileTree("libs"))
    // EasyQuery KSP 处理器 (生成 EntityProxy 类)
    compileOnly("com.easy-query:sql-api-proxy:3.1.82")
    ksp("com.easy-query:sql-ksp-processor:3.1.82")

    testImplementation(project(":api"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
}

tasks.test {
    useJUnitPlatform()
}
