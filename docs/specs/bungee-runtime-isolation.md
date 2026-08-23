# 功能规格：BungeeCord 运行时隔离修复

> 状态：修复完成，Java 8 启用验证通过　·　关联 PRD：FR2.5 / FR4.2 / FR6

## 背景与根因

同一发行 jar 在 BungeeCord + Java 8 启动时曾有两条 Bukkit 依赖泄漏路径：

- `BukkitUI` 使 `META-INF/taboolib/env.properties` 包含 `bukkit-nms`，Bungee 在平台过滤前加载该模块；
- `taboolib-ioc 1.2.0` 的 `ComponentVisitor` 在读取全部组件方法元数据后才处理 `@PlatformSide`，因此 Bungee 解析 Bukkit 方法签名会抛出 `NoClassDefFoundError`。

## 修复

- 移除未使用的 `BukkitUI` 环境模块，发行 jar 不再声明 `bukkit-nms`；
- 以项目内兼容实现替换重定位后的 IoC `ComponentVisitor`：先按 `@PlatformSide` 筛选类，再反射组件元数据；
- 保持单 jar 的 `plugin.yml`、`bungee.yml` 和既有 Bukkit/Paper 功能不变。

该兼容实现仅覆盖当前项目使用的 IoC 扫描路径。项目没有 `@ConditionalOnBean`、`@ConditionalOnMissingBean`、`@Configuration` 或 `@Bean` 用例；新增这些用例前必须补全上游扫描器语义并回归验证。

## 验证记录

- 环境：`D:\Game\MinecraftTest\master`，BungeeCord，Eclipse Temurin Java 8；
- 结果：ServerProbe 成功加载并启用，日志包含“代理端采集器已注册”“已启动定时采集”“ServerProbe 已就绪”；
- 结果：启动过程不再出现 `NoClassDefFoundError: org.bukkit.*`。
- 回归：最终发行构建在 Paper 1.21.11 + JDK 21 正常加载、启用并完成初始采集。

## 未完成验收

- Bungee 控制台尚未执行 `/probe health`、`/probe proxy`；
- Bungee `/metrics` 端点尚未真机访问；
- 1.8 Spigot、Folia 仍未逐端真机验证，故 FR6 保持部分交付。
