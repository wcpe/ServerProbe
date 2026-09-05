# ADR-0025：Arthas 按 JVM 选择双运行时闭包

## 状态

已接受，取代 [ADR-0024](0024-arthas-311-runtime-closure.md)

## 背景

FR14 的 Paper 真机验收表明，官方 Arthas 3.1.1 可以完成类搜索，但其内置 ASM 无法重转换 Java 17 字节码，`watch` 与 `trace` 会报 `Unsupported class file major version 61`。同时 Java 8 服务器仍需要 3 系兼容闭包。

## 决策

发行 jar 同时内嵌两个官方最小运行闭包：Java 8–16 使用 `3.1.1`，Java 17 及以上使用 `4.3.2`。构建脚本分别以 `arthasLegacyVersion` 与 `arthasModernVersion` 管理版本，运行时按 `java.specification.version` 选择、校验并原子提取对应目录。

4 系运行时通过官方 Bootstrap 初始化命令依赖，但显式设置 Telnet/HTTP 端口为负值、MCP endpoint 为空、Tunnel 为空；ServerProbe 仍是唯一 MCP 监听者。3 系保留原有无监听内存 Shell 路径。`core` 不引用任何 Arthas 版本类型。

## 理由

- 官方 Arthas 4.x 声明支持 JDK 8+，包含 JDK 17、21 与 25，能够覆盖现代服务器字节码。
- 保留 Java 8–16 的 3.1.1 兼容路径，不把现代运行时要求传播到旧服务器。
- 双闭包仍在构建期下载并随单 jar 交付，运行时不联网，也不复制 Arthas 源码。

## 后果

- 发行 jar 体积增加，构建期必须校验两个版本各自的 Core、Boot、Agent、Spy、许可证、NOTICE 与 SHA-256 清单。
- 反射适配器须按运行时主版本隔离 3 系与 4 系 Bootstrap/Spy API 差异。
- 真机验收至少覆盖 Java 8 的类搜索，以及 Java 17+ 的 `watch` 或 `trace`；上游 Telnet、HTTP、MCP 与 Tunnel 仍不得监听或连接。

## 备选方案

- **只升级到 Arthas 4.3.2**：会丢失维护者要求保留的 Java 8/3 系兼容路径，否决。
- **继续只使用 Arthas 3.1.1**：无法对现代字节码执行真实 `watch`/`trace`，否决。
- **编译 Java 8 测试桩伪造现代 JVM 验收**：不代表真实服务器类，否决。
