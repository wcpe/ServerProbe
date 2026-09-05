# 功能规格：BungeeCord 运行时隔离修复

> 状态：已交付@v0.2.0　·　关联 PRD：FR-02 / FR-04 / FR-06

## 背景与根因

同一发行 jar 在 BungeeCord + Java 8 启动时曾有两条 Bukkit 依赖泄漏路径：

- `BukkitUI` 使 `META-INF/taboolib/env.properties` 包含 `bukkit-nms`，Bungee 在平台过滤前加载该模块；
- `taboolib-ioc 1.2.0` 的 `ComponentVisitor` 在读取全部组件方法元数据后才处理 `@PlatformSide`，因此 Bungee 解析 Bukkit 方法签名会抛出 `NoClassDefFoundError`。

## 修复

- 移除未使用的 `BukkitUI` 环境模块，发行 jar 不再声明 `bukkit-nms`；
- 以项目内兼容实现替换重定位后的 IoC `ComponentVisitor`：先按 `@PlatformSide` 筛选类，再反射组件元数据；
- 子服延迟通过反射调用 BungeeCord `ServerInfo#ping(Callback)`，回调类型不进入 Bukkit 可见的方法签名；失败或超时统一记为不可达；
- 保持单 jar 的 `plugin.yml`、`bungee.yml` 和既有 Bukkit/Paper 功能不变。

该兼容实现仅覆盖当前项目使用的 IoC 扫描路径。项目没有 `@ConditionalOnBean`、`@ConditionalOnMissingBean`、`@Configuration` 或 `@Bean` 用例；新增这些用例前必须补全上游扫描器语义并回归验证。

## 验证记录

- 环境：BungeeCord `1.19-R0.1 #1700`，Eclipse Temurin Java `8.0.422`；
- 结果：ServerProbe 成功加载并启用，日志包含“代理端采集器已注册”“已启动定时采集”“ServerProbe 已就绪”；
- 结果：控制台 `/probe proxy` 返回 lobby 在线数、ping、玩家 ping 与路由区块；
- 结果：显式开启 `metrics.enabled` 后，`GET http://127.0.0.1:19940/metrics` 返回 200 与 `platform="BUNGEE"` 的 JVM 指标；
- 结果：启动过程不再出现 `NoClassDefFoundError: org.bukkit.*`。
- 回归：最终发行构建在 Paper 1.21.11 + JDK 21 正常加载、启用并完成初始采集。
- 集群：mc-testkit 启动 BungeeCord #2088、两个 Paper 1.20.1 后端与两名机器人玩家；`/metrics` 返回总在线 `2`、两个后端在线各 `1`、RTT `3ms/6ms` 且均可达。
- 路由：`RouteBot` 从 `backend-a` 切换到 `backend-b`，`ProbeBot` 保持在 `backend-a`；玩家路由与玩家 ping 均与真实连接一致。
- 测试：`BungeeServerPingInvokerTest` 覆盖成功、失败与超时，项目全量 `gradlew build` 通过。

## 关联兼容性验收

- Spigot 1.8.8 + Java 8：同一 jar 正常启用，JVM、TPS/MSPT、玩家与世界指标可采集；
- Folia 1.21.4 + JDK21：同一 jar 正常启用，JVM、玩家与世界指标可采集，全局 TPS 按设计降级为 N/A。
