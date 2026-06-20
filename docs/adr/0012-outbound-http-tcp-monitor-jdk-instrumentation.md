# ADR-12：HTTP/TCP 外呼监控插桩 JDK 层，而非逐个 HTTP 客户端库

## 状态
已接受

## 背景
现状追认：本决策早于本次 SDD 逆向即已成立，依据 docs/ARCHITECTURE.md §11 补记为独立 ADR。

ServerProbe 的可选 premain agent（见 ADR-11）提供"HTTP/TCP 对外网络外呼监控（运行期常驻）"能力：记录哪个插件/哪段代码发起对外请求、目标、耗时、响应码、脱敏后的请求头与查询串。服务端生态里发起外呼的途径众多（各类 HTTP 客户端库、TabooLib 下载、HTTPS、裸 Socket），需要决定在哪一层插桩才能既覆盖全、又不破坏 JVM 网络。

## 决策
HTTP/TCP 外呼监控插桩 JDK 层（`HttpURLConnection.getInputStream` + `Socket.connect`），而非逐个 HTTP 客户端库。

## 理由
- **JDK 层是收口点**：插桩 `HttpURLConnection.getInputStream` 一处即可覆盖绝大多数外呼（含 TabooLib 下载、HTTPS）；`Socket.connect` 兜底非 `HttpURLConnection` 客户端。一处覆盖面远大于逐个 hook 客户端库。
- **插桩极简且安全**：插桩点仅把 `this` + 起始时刻交给数据桥，逻辑全程 try/catch；经 ASM `CheckClassAdapter` 对真实 JDK 类校验合法，确保绝不破坏 JVM 网络。
- **隐私与防泄漏**：敏感头/参数在 agent 侧脱敏打码，请求体不捕获，有界缓冲防泄漏，可配开关。
- **可归因调用方**：JDK 层插桩配合栈回溯能定位发起外呼的插件/代码，弥补代理层方案无法归因的缺陷。

## 后果
- 正面：一处插桩覆盖绝大多数外呼，维护面小、覆盖全；不破坏 JVM 网络（CheckClassAdapter 校验 + 全程 try/catch）；可定位触发插件，支持实时日志 + 落盘 `data/http/` + `/probe http` 回看 + 启动期外呼并入报告。
- 负面/约束：依附于可选 premain agent（ADR-11），不启用 agent 则无此能力；敏感信息须在 agent 侧脱敏、请求体不捕获、缓冲有界、开关可配。
- **真机边界（诚实记录）**：随 ADR-11 的 agent 能力，当前**仅 1.21.4 Paper 单端真机验证**；其他端未逐一真机。

## 备选方案
- **逐个 hook OkHttp / Apache 等客户端库**：被否决。各插件各自重定位、维护面大、覆盖不全。
- **代理 / URLStreamHandler**：被否决。无法归因调用方，且漏掉 startup 阶段的外呼。
