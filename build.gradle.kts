plugins {
    java
    // convention 插件由 build-logic included build 供给（java/kotlin/taboolib/ioc/detekt 版本随其 implementation 固定）
    id("serverprobe.base") apply false
    id("serverprobe.e2e-harness") apply false
    id("serverprobe.bundle") apply false
    // 厂商标识隔离门禁（FR-30）：仅 api 与 project:core 应用（厂商名只允许出现在文档与 integration 模块）
    id("serverprobe.vendor-isolation") apply false
    // e2e 编排：应用 top.wcpe.mc-testkit 并声明全部 mcTestkit{} 拓扑 + 场景配置注入任务（见 build-logic）
    id("serverprobe.e2e-verification")
}
