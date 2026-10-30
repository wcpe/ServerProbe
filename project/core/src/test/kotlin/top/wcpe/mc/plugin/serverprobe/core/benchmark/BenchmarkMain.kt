package top.wcpe.mc.plugin.serverprobe.core.benchmark

/**
 * 基准入口（手动运行）。
 *
 * 用法：
 * ```
 * JAVA_HOME=/usr/lib/jvm/java-21 ./gradlew :project:core:benchmarkBridge
 * # 只看并发吞吐，可选参数：线程数 秒数
 * JAVA_HOME=/usr/lib/jvm/java-21 ./gradlew :project:core:benchmarkBridge -PbenchArgs="8 5"
 * ```
 *
 * 输出仅供人工判断趋势，**不参与 CI 判定**——墙钟阈值在共享 runner 上必然 flaky
 * （本仓已有同样的约定，见 `FrameSenderTest` 类注释）。
 */
fun main(args: Array<String>) {
    BridgeBenchmarks.run()
    println()
    FieldsBenchmark.run()
    println()
    val threads = args.getOrNull(0)?.toIntOrNull() ?: 4
    val seconds = args.getOrNull(1)?.toIntOrNull() ?: 3
    BridgeBenchmarks.throughput(threads = threads, seconds = seconds)

    println()
    println("=== 回灌压测 A：分批读库（每 1000 条停 2ms），10 万条账单 ===")
    BackfillBenchmark.Strategy.entries.forEach { strategy ->
        BackfillBenchmark.run(total = 100_000, batchSize = 1_000, strategy = strategy).print()
    }

    println()
    println("=== 回灌压测 B：最坏情况（数据已在内存、零停顿全速灌入） ===")
    BackfillBenchmark.Strategy.entries.forEach { strategy ->
        BackfillBenchmark.run(total = 100_000, batchSize = 10_000, strategy = strategy, dbBatchPauseMs = 0)
            .print()
    }
}
