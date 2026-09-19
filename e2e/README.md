# ServerProbe E2E

此目录以 mc-testkit 在真实 Paper 1.20.1 服务端上验收 ServerProbe。

mc-testkit 是跨平台 Gradle 插件：自带服务端下载、启动与停止，Linux 与 Windows 均可直接运行
（服务端 jar 缓存在 `~/.gradle/caches/mc-testkit-jars/`）。

```powershell
# Windows
.\gradlew.bat e2eReadApi --no-daemon --console=plain
.\gradlew.bat e2eStorageSpi --no-daemon --console=plain
.\gradlew.bat e2eBridgeFixture --no-daemon --console=plain
```

```bash
# Linux / macOS
./gradlew e2eReadApi --no-daemon --console=plain
./gradlew e2eStorageSpi --no-daemon --console=plain
./gradlew e2eBridgeFixture --no-daemon --console=plain
```

GitHub Actions 上跑的是其中无 bot、无外部闭源插件的场景（手动触发或推送 `v*` 标签时），
见 `.github/workflows/e2e.yml`；依赖 bot 与真实业务插件的场景仍保留为本地验收。

每个场景的唯一结论是 `build/mc-testkit/results/<场景>.properties` 中的 `status=PASS`。

- `read-api`：独立第三方插件经公开只读门面读取 TPS、MSPT 与启动画像。
- `storage-spi`：独立第三方插件安装 `MetricStore`，验证真实写入和关闭注册后的默认存储回退。
- `bridge-fixture`：测试运行时生成随机回环地址与一次性凭据；本地 RFC 6455 Worker fixture 验证 hello/ping、manifest、命令回执、业务事件、失败/超时及超时后的恢复。

FR9 不依赖或修改 JianManager，也不接入 MultiCurrencyEconomy 或 AllinInventorySync 的真实服务端。
