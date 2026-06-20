# ADR-13：api 模块改用纯 Java(Lombok),支持任意 Kotlin/Java 版本消费方

## 状态
已接受

## 背景
`api` 模块经 `maven-publish` 对外发布(`serverprobe-api`),是面向第三方的**公开契约**(只读 API + 存储 SPI + 数据模型 + 采集器接口)。原本 `api` 用 Kotlin 2.1 编写,编译产物的 class 携带 Kotlin `@Metadata`(二进制 metadata 版本)。**Kotlin 1.x 的编译器读不了 Kotlin 2.x 的 metadata**(Kotlin 2.0 抬过 metadata 版本),导致**旧 Kotlin(1.x)第三方插件在编译期依赖 `api` 时直接失败**。运行期(加载/调用)、纯 Java 消费方、Kotlin 2.x 消费方本就正常——问题只在"旧 Kotlin 编译期依赖"这一面。

## 决策
把 `api` 模块从 Kotlin 改为**纯 Java(Java 8 target)**:契约接口(`ProbeReadApi` / `MetricStore` / `ServerTickSampler` / 4 个 `*Collector`)、枚举、约 21 个数据模型全部 Java 化;数据模型用 **Lombok `@Value` + `@Builder(toBuilder = true)`** 生成不可变 POJO。Lombok 仅编译期引入(`compileOnly` + `annotationProcessor`),**不进消费方传递依赖**。

## 理由
- 纯 Java 字节码**无 Kotlin metadata**,任意 Kotlin(含 1.x)/ Java 版本均可干净编译依赖。与 ADR-3(核心 Java 8 字节码)方向一致。
- **落盘序列化兼容**(关键):启动画像/指标历史经 TabooLib `Configuration`(底层 nightconfig `ObjectConverter`)序列化。经查证其为**纯 Java 反射**(`getDeclaredFields` / `field.get` / `field.set` + `setAccessible`,顶层实例化用 `Unsafe`),不用 Kotlin 反射;原 Kotlin `data class` 的 `val` 本就是 `private final` JVM 字段且落盘往返正常,Lombok `@Value` 的 `private final` 字段与之**字节码等价**,故行为一致、**字段名 JSON 不变**(向后兼容既有 `data/` 文件)。

## 后果
- 内部 Kotlin 消费方(core / platform-* / plugin)对 api 模型的**构造一律改用 `Type.builder()....build()`**(Kotlin 不能对 Java 构造器用具名参数,且 `@Value` 全参构造器为 package-private);两处 `.copy()` 改 `.toBuilder()....build()`。
- **布尔字段用装箱 `Boolean`**(`HttpCall.error`、`StartupProfile.agentAttached`):让 Lombok 生成 `getX()` 而非 `isX()`,否则 Kotlin 把 `isX()` 暴露为属性 `isX` 致消费方读 `.x` 失败。
- 采样器实现把 `override val source` 改为 `override fun getSource()`(对齐 Java 接口 getter)。
- Lombok 成为 `api` 模块**唯一新增的(编译期)依赖**。
- **序列化往返属真机维度**:编译 + 单元测试 + detekt 已全绿,但 TabooLib 落盘/读盘往返**不可裸单测**(依赖运行期 relocate 后的 nightconfig),须 **1.21.4 Paper 真机复验**(本次按用户决定跳过一次性 PoC,改以"源码分析确认 + 真机验收兜底")。真机未过即视为本变更未完成。

## 备选方案
- **维持 Kotlin + 文档要求消费方 Kotlin 2.0+**:零成本,但放弃旧 Kotlin(1.x)第三方支持。否决——目标即广兼容。
- **仅 api 模块降级到 Kotlin 1.x 编译器**:同一构建混多 Kotlin 编译器版本,脆弱且 metadata 覆盖不全。否决。
