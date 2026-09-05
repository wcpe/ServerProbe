plugins {
    java
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    // convention 插件由 build-logic included build 供给（java/kotlin/taboolib/ioc/detekt 版本随其 implementation 固定）
    id("serverprobe.base") apply false
    id("serverprobe.e2e-harness") apply false
    id("serverprobe.bundle") apply false
    // e2e 编排：应用 top.wcpe.mc-testkit 并声明全部 mcTestkit{} 拓扑 + 场景配置注入任务（见 build-logic）
    id("serverprobe.e2e-verification")
}
