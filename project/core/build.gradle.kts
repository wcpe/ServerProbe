dependencies {
    compileOnly(project(":api"))
    compileOnly(fileTree("libs"))

    testImplementation(project(":api"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}
