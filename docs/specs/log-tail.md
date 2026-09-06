# 功能规格：MCP 日志流式检索

> 状态：草拟　·　关联 PRD：FR-16　·　分支：feature/fr16-log-tail

## 1. 背景与目标

外部 agent 排查插件 bug 时，需要回溯服务器日志中的异常时间线（报错栈、WARN 序列）。现状 MCP 无读取 `logs/latest.log` 的工具，`server_command` 只回显命令输出，无法看历史日志。本 FR 提供只读的日志 tail/检索工具。

## 2. 需求（要什么）

- MCP 新增工具 `log_tail`：入参 `lines`（默认 200，上限 2000）与可选 `keyword`，返回 `logs/latest.log` 末尾 N 行；关键字过滤时返回命中行（含行号偏移）。
- MCP 新增工具 `log_search`：入参 `keyword`、`sinceOffset`（起始字节偏移，默认 0）、可选 `maxLines`（默认 100，上限 1000），返回自偏移起的命中行与下一偏移（游标分页）。
- 文件不存在 / 不可读 / 路径穿越时返回结构化错误，不抛异常。
- 只读：不写日志、不轮转、不删除、不触碰其他目录。
- 范围内：tail、关键字搜索、游标分页、文件缺失降级。
- 不做：正则搜索、跨文件搜索、实时流推送（SSE）、日志解析统计、覆盖 BungeeCord/Velocity 的日志路径差异（如适用则给出平台缺省）。

## 3. 设计（怎么做）

- **core** 新增 `LogTailToolProvider`（实现 `McpToolProvider`）：
  - 日志路径经平台适配器提供，core 契约 `interface LogPathProvider { fun latestLog(): Path? }`；Bukkit 实现返回服务端根目录下 `logs/latest.log`。**服务端根目录解析**：Bukkit 无公开根目录 API，用 `Bukkit.getWorldContainer().parentFile`（`getWorldContainer()` 返回世界目录，其父目录即服务端根目录，非"插件数据目录的 ../logs"）；也可直接相对工作目录（与既有 `StartupLoadListener.LATEST_LOG_PATH = "logs/latest.log"` 一致）。
  - **路径安全**：core 端对 `LogPathProvider` 返回的 Path 做规范化前缀校验（`toAbsolutePath().normalize()` 必须以服务端根目录为前缀），防平台实现错误/被注入。
  - **字符集**：由平台适配器提供（`LogPathProvider` 附带 `charset()`，Bukkit 实现读 `System.getProperty("file.encoding")`，与 JVM 默认一致）；Windows 低版本常为 GBK，禁止硬编码 UTF-8（会乱码）。
  - 实现用 `RandomAccessFile` 定位尾部（`seek` 到 `length` 后回退 `lines*平均行宽`，不足则从头），按平台字符集解码，跳过超长行（>8KB 截断标记）。**截断行必须跳过至下一个换行符**，保证游标总能前进。
  - 关键字匹配为大小写不敏感的子串匹配；`log_search` 用字节偏移游标，返回 `nextOffset` 与 `truncated`。
- **数据模型**：`log_tail` 返回 `{file, lines:[{lineNumber, text}], truncated}`（`lineNumber` 为相对文件头的行号）；`log_search` 返回 `{file, hits:[{offset, text}], nextOffset, truncated}`（`offset`/`nextOffset` 为字节偏移）。**行号与字节偏移是两套概念，分开命名不混用**。文件增长时 `log_search` 的游标按"读取时的文件快照大小"推进，跨页边界新追加的行可能漏检——文档提示 agent 对增量用新查询而非续页。
- **并发**：只读文件、单请求独立打开/关闭，无共享状态，MCP 请求线程安全。**大文件从头扫描限定单次上限**（默认最多扫 64 MiB 或 10 万行，超出截断并标记），避免 MCP 请求线程长时间阻塞。
- **降级**：文件不存在/不可读 → 返回结构化 `{available:false, reason}`，不打印 WARN（非事故）。
- **接线**：与 FR-15 同批，将 `LogTailToolProvider` 注册进 `McpJsonRpcDispatcher`（多 provider 聚合，见 FR-23 组合设计）。
- **与 FR-15 的依赖澄清**：本 FR 的日志检索本身不依赖 FR-15 的插件归属解析（关键字过滤独立实现）；依赖仅指**接线层面**（两者共用 FR-23 的多 provider 组合机制，且同批交付更稳妥）。实现时可独立推进，接线阶段合流。
- **数据模型**：`log_tail` 返回 `{file, lines:[{offset,text}], truncated}`；`log_search` 返回 `{file, hits:[{offset,text}], nextOffset, truncated}`。

## 4. 任务拆分

- [ ] core `LogPathProvider` 契约 + `LogTailToolProvider` 实现（tail/search/游标/降级）。
- [ ] 单测：尾部定位、超长行截断、关键字命中、游标分页、文件缺失降级。
- [ ] platform-bukkit 实现 `LogPathProvider`（指向真实 `logs/latest.log`）+ IOC 注册。
- [ ] MCP 接线（多 provider 聚合） + E2E 真机验证。
- [ ] 文档同步：PRD 状态、ARCHITECTURE、API、CHANGELOG。

## 5. 验收标准

- 单测全绿：tail 正确返回末尾行、search 游标连续、超长行截断、缺失文件结构化降级。
- 真机（Paper，受控场景）：预置一条已知日志行（如触发一次已知 WARN），`log_tail` 返回真实启动日志尾部且含该行；`log_search` 用关键字命中真实日志行；偏移游标翻页正确。
- 以真实第三方插件 bug 为锚点（可选，手动验收）：用关键字检索该插件报错，定位首个异常时间线——依赖真实报错存在，不作为自动判据。

## 6. 风险 / 待定

- 日志文件可能正被服务端写入（共享读）：`RandomAccessFile` 读 `latest.log` 在 Windows 下可能被写锁占用 → 降级为结构化错误；必要时改用 `FileChannel` 只读共享锁，需真机验证。
- `logs/latest.log` 可能很大（多日未轮转）：tail 定位按行宽回退有上限，超大文件回退失败时从头扫描并限行数/限字节（默认 64 MiB）。
- 平台差异：BungeeCord 日志名 `proxy.log`，Velocity 为 `logs/` 下不同名——本 FR 先做 Bukkit 路径，代理端缺省返回"平台不支持"。
- 字符集：低版本 Windows 服务器日志常为 GBK，禁止硬编码 UTF-8（由平台适配器提供字符集）。
