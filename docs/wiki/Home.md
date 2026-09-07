# ServerProbe Wiki

ServerProbe 是一个面向 Minecraft 服务器的**运维探针**，使用同一个 jar 为 **Bukkit、Spigot、Paper、Folia、BungeeCord 与 Velocity 3.1.1–4.x** 提供：

1. **启动性能剖析** —— 量化定位服务端启动慢在哪个插件、哪个世界、哪个生命周期阶段，并与历史对比。
2. **运维指标采集** —— 统一采集、聚合、告警与可视化 TPS/MSPT/内存/GC/线程/世界负载等指标。
3. **网络取证** —— 全平台双向流量、包速率与本地 SQLite 数据包取证。
4. **深度诊断（可选）** —— 默认关闭的 MCP 控制面，内嵌 Arthas Core 支持线程/死锁/类重定义级诊断。

- 单 jar 多端运行，核心 Java 8 字节码
- 只读采集、默认安全：可选增强默认关闭、失败时降级，不影响服务器或其他插件启动
- 当前正式版本：**[v0.4.0](https://github.com/wcpe/ServerProbe/releases)**

## 文档导航

- [安装与构建](Installation)
- [命令与权限](Commands)
- [指标说明](Metrics)
- [启动剖析指南](Startup-Profiling)
- [数据呈现与对接](Data-Output)
- [版本与平台兼容](Compatibility)
- [常见问题 FAQ](FAQ)

## 快速开始

1. 从 [GitHub Releases](https://github.com/wcpe/ServerProbe/releases) 下载最新 `ServerProbe-<版本>.jar`。
2. 放入服务端 `plugins/` 目录（代理端同理）。
3. 重启服务器，首次启动生成 `plugins/ServerProbe/config.yml`。
4. 游戏内执行 `/probe health` 查看概览。

详细能力与验收标准见 [PRD](https://github.com/wcpe/ServerProbe/blob/master/docs/PRD.md) 与 [架构文档](https://github.com/wcpe/ServerProbe/blob/master/docs/ARCHITECTURE.md)。
