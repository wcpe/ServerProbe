# ADR-0026：Velocity 采用共享源码与双实际兼容编译门

## 状态

已接受，取代 [ADR-0021](0021-velocity-dual-api-compatibility.md)

## 背景

`platform-velocity` 当前只使用 Velocity 3.1.1 与 4.1.0 共同拥有的稳定 API。强行拆出 `velocity3`、`velocity4` 两套源码或空的 `VelocityAdapter`，不会隔离任何真实差异，反而会制造重复代码和未使用抽象。

Velocity 4.1.0 的 API 构件要求 Java 25，Gradle 解析它时也必须声明 JVM 25；但发行类仍需保持 Java 8 字节码，且 API 类不得进入单 jar。

## 决策

Velocity 平台保留一套仅使用共同 API 的 Java 8 源码。构建提供两个独立子 Gradle 兼容门：

- API 3 门以 Velocity API `3.1.1` 编译共享源码；
- API 4 门以 Velocity API `4.1.0`、本机 Java 25 Kotlin toolchain 及 JVM 25 依赖变体编译同一源码。

API 4 子构建只在 Maven Central TLS 不可用时优先使用 wcpe 镜像，仍保留 Maven Central。Java 25 缺失或目录无效必须中文失败，构建不得下载或安装 JDK。

当未来出现真实的 4.x 专有类型或行为时，再新增最小版本专有适配源码，并由运行期版本探测延迟加载；在此之前不得预置空源集或适配器。

## 理由

- 两次实际编译直接证明共同 API 的二进制兼容，且不把 Java 25 传染到核心或发行类。
- 避免没有变化点时的重复源码，保持平台模块职责单一。
- 保留单 jar、多平台和外部 Velocity API 不打包的既有承诺。

## 后果

- CI / 发布构建需显式提供本机 Java 25 才能通过 API 4 门。
- 每次修改 Velocity 共同源码均须同时通过 API 3 与 API 4 编译门；真实版本差异出现时必须另写 ADR 或补充本 ADR。
- 三版本真实代理矩阵仍是 FR13 的独立交付门，编译通过不能替代真机验收。

## 备选方案

- **保留双空源集与空适配器**：不隔离真实风险，只增加维护成本，否决。
- **只编译 Velocity 3 API**：无法证明 4.x 兼容，否决。
- **整体升级到 Java 25**：破坏 Java 8 核心兼容，否决。
