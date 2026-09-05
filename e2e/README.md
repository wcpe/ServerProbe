# ServerProbe E2E

此目录以 mc-testkit 在真实 Paper 1.20.1 服务端上验收 ServerProbe。

```powershell
.\gradlew.bat e2eReadApi --no-daemon --console=plain
.\gradlew.bat e2eStorageSpi --no-daemon --console=plain
.\gradlew.bat e2eBridgeFixture --no-daemon --console=plain
```

每个场景的唯一结论是 `build/mc-testkit/results/<场景>.properties` 中的 `status=PASS`。

- `read-api`：独立第三方插件经公开只读门面读取 TPS、MSPT 与启动画像。
- `storage-spi`：独立第三方插件安装 `MetricStore`，验证真实写入和关闭注册后的默认存储回退。
- `bridge-fixture`：测试运行时生成随机回环地址与一次性凭据；本地 RFC 6455 Worker fixture 验证 hello/ping、manifest、命令回执、业务事件、失败/超时及超时后的恢复。

FR9 不依赖或修改 JianManager，也不接入 MultiCurrencyEconomy 或 AllinInventorySync 的真实服务端。
