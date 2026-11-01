plugins {
    java
    // convention 插件由 build-logic included build 供给（java/kotlin/taboolib/ioc/detekt 版本随其 implementation 固定）
    id("serverprobe.base") apply false
    id("serverprobe.e2e-harness") apply false
    id("serverprobe.bundle") apply false
    // e2e 编排：应用 top.wcpe.mc-testkit 并声明全部 mcTestkit{} 拓扑 + 场景配置注入任务（见 build-logic）
    id("serverprobe.e2e-verification")
    // CI 辅助任务：字节码门禁 / 依赖清单转换 / 覆盖率汇总 / E2E 结果断言（替代原脚本工具）
    id("serverprobe.ci-verification")
}

// build-logic 的测试（TestKit 接线守卫）住在 included build 里，根项目的 check 图本来不含它们——
// 不接进来，那些“证明任务真的注册、依赖真的挂上”的断言就永远不会在 CI 执行（等于不存在）。
// 代价是 check/build 会多跑一次 included build 的 test（子项目级 GradleRunner，见实测耗时）。
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
