# ADR-0021：Velocity 采用双 API 兼容源集与 Java 8 入口

## 状态

已被 [ADR-0026](0026-velocity-shared-source-dual-compile-gates.md) 取代

## 背景

Velocity 3.1.1 至 4.x 的 API/运行 JRE 跨度很大，4.1.0 需要 Java 25。若把 Java 25 类型带入插件入口，旧 Velocity/Java 8 会在加载单 jar 时失败；若只按一个 API 编译，又无法把 4.x 作为受控兼容门禁。

## 决策

新建单个 `platform-velocity` 模块，使用 Velocity 3.1.1 与 4.1.0 两个 `compileOnly` 兼容源集；入口和公共模型保持 Java 8，Velocity 4 专有适配仅在 Java 25 toolchain 编译、运行期探测到 4.x 后按需加载。

## 理由

- 保留单 jar 与 Java 8 核心承诺，同时让 3.x/4.x 编译和真机矩阵各自受控。
- 所有平台差异收敛在适配层，代理指标、FR11 和 FR14 可复用平台无关模型。
- 不需要把 Velocity API 打入发行 jar，减少冲突与类加载风险。

## 后果

- 构建/CI 需要可用 Java 25 toolchain；缺失时 4.1.0 验收门必须明确失败。
- `platform-velocity` 不得向 `core`/`api` 泄漏 Velocity 类型，且不做跨服务器聚合。
- 同一 jar 同时携带 Bukkit、BungeeCord、Velocity 描述/入口，环境探测只激活一端。

## 备选方案

- **整体升级到 Java 25**：直接放弃低版本服务端，否决。
- **仅按 Velocity 3 API 编译**：4.x 兼容没有独立门禁，否决。
- **分发独立 Velocity jar**：增加部署复杂度，违背 ADR-0006，否决。
