# 代码风格与静态检查（防风格 / 质量漂移）

> 统一格式化与静态检查口径——风格一致、低级问题挡在合并前。

## 1. 当前生效的门

- **构建即门**：`./gradlew build` 必须通过——含 Kotlin 编译、全部单元测试、**detekt 静态检查**（detekt 已挂入 `check`/`build`）。提交前本地至少跑一次（见 `testing-and-quality.md` §1）。
- **detekt 静态检查**：配置 `config/detekt/detekt.yml`（`buildUponDefaultConfig = true` + 少量降噪微调），版本固定 detekt 1.23.7。**存量问题由各模块 `detekt-baseline.xml` 冻结**——只对**新增**代码的问题告警；新写的坏味道（长方法、复杂度、空 catch 等）会让 `build` 失败。
  - 单跑：`./gradlew detekt`；重建基线（极少，慎用）：`./gradlew detektBaseline`。
- **CI 门禁**：`.github/workflows/ci.yml` 每次 push / PR 跑 `./gradlew build`（= 构建 + 测试 + detekt）。**当前仓库无公开 remote,工作流尚未实际运行**；建立 GitHub 仓库后即生效,应在分支保护里设为合并前门禁。
- **`.editorconfig`**：统一缩进 / 换行 / 编码（Kotlin/Java 4 空格、YAML 2 空格、UTF-8、LF、行尾去空白）。IDE 应启用。
- **代码风格随大流**：新增代码匹配既有 Kotlin 风格（命名、缩进、KDoc 中文），不夹带个人偏好的格式化重排（精准修改原则）。

## 2. 待引入（技术债，引入前先确认）

- **ktlint 格式化**（可选）：detekt 管代码味道,格式化可再补 ktlint;落地涉及改 `build.gradle.kts`,先与维护者确认。
- **依赖漏洞扫描**：如 OWASP dependency-check / `osv-scanner`，纳入 CI 定期检查。

## 3. 约定

- **不轻易扩大 detekt 基线**：基线是冻结**存量**问题的一次性快照,**不是**给新代码开后门。新代码触发 detekt 应**修代码**,而非 `detektBaseline` 重刷基线把新问题也冻进去。
- 禁用某条 detekt 规则须在 `config/detekt/detekt.yml` **集中声明并注明中文原因**,不在代码里零散 `@Suppress` 关闭（除非有明确理由并写明中文说明）。
- 本规则是 `testing-and-quality.md` 的补充：测试管"行为对不对"，静态检查管"写法干不干净"。
