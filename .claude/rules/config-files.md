# 配置文件规范

> 适用于面向运行期的 YAML 配置文件（`*.yml` / `*.yaml`），如本项目自身的 `config.yml`、语言文件 `lang/*.yml` 等。

## 1. 文件命名（强制）

- 文件名用 **kebab-case**：全小写字母 + 数字，多词用 `-` 连接。
- 扩展名统一 `.yml`。
- 禁止 `snake_case` / `camelCase` / `PascalCase` / 空格 / 中文做分隔。
- 例外：上游框架强制命名（如 TabooLib 的 `plugin.yml`/`bukkit.yml`、i18n 语言码文件 `zh_CN.yml`/`en_US.yml`）按上游约定。

## 2. 字段命名（强制）

- 所有 YAML 字段名用 **kebab-case**，每层嵌套都遵守（如 `collect-period-ticks`、`history-retention`、`retention-days`）。
- 例外：第三方框架强制字段按上游约定。
- 字段值（枚举、ID、字符串）保留业务约定，不受约束。

## 3. 字段中文注释（强制）

- **每个字段上方必须有中文行注释**，说明用途、取值范围、默认值、影响。
- 用大白话，让非技术运维看懂；注释独占一行写在字段上方（不写行尾）。

### 示例

✅ 正确（本项目 `config.yml` 现状即此风格）
```yaml
# 指标采集周期(单位:tick;20 tick = 1 秒)。默认 100(约 5 秒)。
collect-period-ticks: 100
# 内存中近期历史保留份数(每采集周期一份)。默认 360(约覆盖最近 30 分钟)。
history-capacity: 360
```

❌ 错误（缺注释 / 注释空洞 / 行尾注释）
```yaml
collect-period-ticks: 100   # interval
history-capacity: 360
```

## 4. 文件头部说明（建议）

每个配置文件头部保留 1-3 行中文整体说明：这文件是干什么的、由哪个组件加载、何时生效。

## 5. 安全

- 敏感项（Prometheus token、Webhook URL 等）不写真实值入库；入库的 `config.yml` 只放空串/占位默认值，真实值由运维在部署环境填写。

## 6. 与现有规则的关系

- 本规则是 `comments.md` 在配置文件场景的细化；冲突以本规则为准。
- 不约束代码侧字段名（Kotlin/Java 按自身约定）。
