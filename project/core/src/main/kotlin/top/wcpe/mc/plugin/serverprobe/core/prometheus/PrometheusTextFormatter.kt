package top.wcpe.mc.plugin.serverprobe.core.prometheus

import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot

/**
 * Prometheus 文本格式化器(FR4.2,exposition format 0.0.4)。
 *
 * 将一份 [MetricSnapshot] 渲染为 Prometheus 抓取端点所需的纯文本(text/plain; version=0.0.4)。
 * 设计为**无状态纯函数 object**:不持有任何可变状态、不触碰平台 API,仅做"数据 → 文本"的确定性映射,
 * 既便于在 HTTP 线程并发调用,也便于单元测试(给定快照即可断言输出)。
 *
 * ## 命名与标签约定
 * - 指标统一前缀 `serverprobe_`(避免与其它 exporter 命名冲突)。
 * - 每条时间序列附带两个公共 label:`serverId`(实例标识)、`platform`(BUKKIT/BUNGEE/VELOCITY)。
 * - label 值按 Prometheus 规范转义 `\`、`"`、换行符([escapeLabelValue])。
 *
 * ## 不可用字段的处理(遵循 Prometheus 惯例)
 * 探针模型以哨兵值/可空表达"该指标在当前 JDK/平台不可用":
 * - 数值型哨兵 `-1`(如 `heapMaxBytes`、世界各计数)、`-1.0`(如 CPU 占用率)→ **不导出该行**;
 * - 可空字段 `null`(如 Folia 下的 TPS/MSPT)→ **不导出该行**。
 *
 * 即"无数据不产出时间序列",而非导出一个误导性的占位值;消费方(Grafana/告警)据序列存在与否即可判断可用性。
 *
 * ## 单位
 * 时间一律转换为**秒**(Prometheus 基础单位):毫秒计数除以 1000(`*_seconds` / `*_seconds_total`)。
 *
 * ## 类型行
 * 每个指标名输出一行 `# TYPE <name> <gauge|counter>`(HELP 行从简略去以压缩体积);
 * 同名指标(不同 label)的多行样本共用一次 `# TYPE` 声明。
 */
object PrometheusTextFormatter {

    /** 所有指标统一前缀。 */
    private const val PREFIX = "serverprobe_"

    /** 毫秒 → 秒换算因子。 */
    private const val MILLIS_PER_SECOND = 1000.0

    /**
     * 将指标快照渲染为 Prometheus exposition 文本。
     *
     * 渲染顺序:JVM 指标(恒有)→ 服务器指标(服务端非空)→ 代理端指标(代理端非空)。
     * 各区块内对不可用字段静默跳过(见类 KDoc)。
     *
     * @param snapshot 待渲染的指标快照;为 null(探针尚无任何采样)时返回空字符串。
     * @return Prometheus 文本;snapshot 为 null 时为空串。
     */
    fun format(snapshot: MetricSnapshot?): String {
        if (snapshot == null) {
            return ""
        }
        // 公共 label:每条时间序列都带 serverId 与 platform
        val baseLabels = listOf(
            "serverId" to snapshot.serverId,
            "platform" to snapshot.platform.name
        )
        val sb = StringBuilder(2048)
        val writer = MetricWriter(sb, baseLabels)
        appendJvm(writer, snapshot)
        snapshot.server?.let { appendServer(writer, it) }
        snapshot.proxy?.let { appendProxy(writer, it) }
        return sb.toString()
    }

    /**
     * 追加 JVM 区块指标(FR2.1)。
     *
     * 涵盖:堆/非堆内存、内存池、GC(原始明细 + young/old 派生)、线程、类加载、CPU、运行时长。
     * 负值哨兵(max=-1、CPU=-1.0)的项按惯例跳过。
     */
    private fun appendJvm(writer: MetricWriter, snapshot: MetricSnapshot) {
        val jvm = snapshot.jvm

        // 堆 / 非堆内存(gauge,字节);max 为 -1(无上限)时不导出
        writer.gauge("heap_used_bytes", jvm.heapUsedBytes.toDouble())
        writer.gauge("heap_committed_bytes", jvm.heapCommittedBytes.toDouble())
        writer.gaugeIfNonNegative("heap_max_bytes", jvm.heapMaxBytes)
        writer.gauge("nonheap_used_bytes", jvm.nonHeapUsedBytes.toDouble())
        writer.gauge("nonheap_committed_bytes", jvm.nonHeapCommittedBytes.toDouble())
        writer.gaugeIfNonNegative("nonheap_max_bytes", jvm.nonHeapMaxBytes)

        // 内存池明细(gauge,label pool);max 为 -1 的池不导出 _max_bytes 行
        for (pool in jvm.memoryPools) {
            val poolLabel = listOf("pool" to pool.name)
            writer.gauge("memory_pool_used_bytes", pool.usedBytes.toDouble(), poolLabel)
            if (pool.maxBytes >= 0) {
                writer.gauge("memory_pool_max_bytes", pool.maxBytes.toDouble(), poolLabel)
            }
        }

        // GC 原始明细(counter,label gc);耗时 ms→s
        for (gc in jvm.gcCollectors) {
            val gcLabel = listOf("gc" to gc.name)
            writer.counter("gc_count_total", gc.collectionCount.toDouble(), gcLabel)
            writer.counter("gc_time_seconds_total", gc.collectionTimeMs / MILLIS_PER_SECOND, gcLabel)
        }
        // GC young/old 派生聚合(counter);耗时 ms→s
        writer.counter("gc_young_count_total", jvm.gcYoungCount.toDouble())
        writer.counter("gc_young_time_seconds_total", jvm.gcYoungTimeMs / MILLIS_PER_SECOND)
        writer.counter("gc_old_count_total", jvm.gcOldCount.toDouble())
        writer.counter("gc_old_time_seconds_total", jvm.gcOldTimeMs / MILLIS_PER_SECOND)

        // 线程(gauge)
        writer.gauge("threads", jvm.threadCount.toDouble())
        writer.gauge("threads_daemon", jvm.daemonThreadCount.toDouble())
        writer.gauge("threads_peak", jvm.peakThreadCount.toDouble())
        writer.gauge("threads_deadlocked", jvm.deadlockedThreadCount.toDouble())

        // 类加载:当前已加载(gauge)+ 累计已加载(counter)
        writer.gauge("classes_loaded", jvm.loadedClassCount.toDouble())
        writer.counter("classes_loaded_total", jvm.totalLoadedClassCount.toDouble())

        // CPU 占用率(gauge,0.0–1.0);-1.0 表示当前 JDK 不提供,跳过
        writer.gaugeIfCpuAvailable("process_cpu_load", jvm.processCpuLoad)
        writer.gaugeIfCpuAvailable("system_cpu_load", jvm.systemCpuLoad)

        // JVM 运行时长(gauge,ms→s)
        writer.gauge("uptime_seconds", jvm.uptimeMs / MILLIS_PER_SECOND)
    }

    /**
     * 追加服务器区块指标(FR2.2/FR2.3,仅服务端非空)。
     *
     * 涵盖:TPS(1m/5m/15m)、MSPT 分位(avg/p95/p99,ms→s)、在线/容量、运行时长、各世界计数。
     * TPS/MSPT 为 null(如 Folia 全局 N/A)按惯例跳过;世界各计数 -1(Folia 受限)亦跳过。
     */
    private fun appendServer(writer: MetricWriter, server: top.wcpe.mc.plugin.serverprobe.api.model.ServerMetrics) {
        val tick = server.tick
        // TPS(gauge,label window);各窗口值为 null 时不导出该行
        writer.gaugeIfPresent("tps", tick.tps1m, listOf("window" to "1m"))
        writer.gaugeIfPresent("tps", tick.tps5m, listOf("window" to "5m"))
        writer.gaugeIfPresent("tps", tick.tps15m, listOf("window" to "15m"))
        // MSPT(gauge,label quantile;ms→s);各分位值为 null 时不导出该行
        writer.gaugeIfPresent("mspt_seconds", tick.msptAvg?.div(MILLIS_PER_SECOND), listOf("quantile" to "avg"))
        writer.gaugeIfPresent("mspt_seconds", tick.msptP95?.div(MILLIS_PER_SECOND), listOf("quantile" to "p95"))
        writer.gaugeIfPresent("mspt_seconds", tick.msptP99?.div(MILLIS_PER_SECOND), listOf("quantile" to "p99"))

        // 在线 / 容量 / 运行时长(gauge)
        writer.gauge("players_online", server.onlinePlayers.toDouble())
        writer.gauge("players_max", server.maxPlayers.toDouble())
        writer.gauge("server_uptime_seconds", server.uptimeMs / MILLIS_PER_SECOND)

        // 各世界计数(gauge,label world);-1(Folia 受限/N/A)不导出
        val worlds = server.worlds ?: return
        for (world in worlds) {
            val worldLabel = listOf("world" to world.name)
            writer.gaugeIfNonNegative("world_loaded_chunks", world.loadedChunks.toLong(), worldLabel)
            writer.gaugeIfNonNegative("world_entities", world.entityCount.toLong(), worldLabel)
            writer.gaugeIfNonNegative("world_tile_entities", world.tileEntityCount.toLong(), worldLabel)
        }
    }

    /**
     * 追加代理端区块指标(M1,仅代理端非空)。
     *
     * 涵盖:代理总在线、各后端子服在线(label backend)。
     */
    private fun appendProxy(writer: MetricWriter, proxy: top.wcpe.mc.plugin.serverprobe.api.model.ProxyMetrics) {
        writer.gauge("proxy_players_online", proxy.totalOnline.toDouble())
        for (backend in proxy.backends) {
            writer.gauge("proxy_backend_players_online", backend.online.toDouble(), listOf("backend" to backend.name))
        }
    }

    /**
     * 转义 Prometheus label 值。
     *
     * 按 exposition format 规范,label 值中的反斜杠、双引号、换行符需转义,
     * 否则会破坏 `name="value"` 的解析(如世界名/收集器名含特殊字符时)。
     *
     * @param value 原始 label 值。
     * @return 转义后的 label 值。
     */
    private fun escapeLabelValue(value: String): String {
        // 先转义反斜杠自身,再处理双引号与换行,避免二次转义
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
    }

    /**
     * 指标行写出器:对单个 [StringBuilder] 累加 Prometheus 文本,并统一处理
     * 公共 label 合并、`# TYPE` 去重声明、数值格式化与 label 转义。
     *
     * 仅在 [format] 的一次调用内使用(随 StringBuilder 创建、用后即弃),不跨线程共享,故无需同步。
     *
     * @property sb 目标文本缓冲。
     * @property baseLabels 公共 label(serverId/platform),会前置到每条时间序列。
     */
    private class MetricWriter(
        private val sb: StringBuilder,
        private val baseLabels: List<Pair<String, String>>
    ) {

        /** 已声明过 `# TYPE` 的指标名(去前缀前的短名),避免对同名多 label 序列重复声明。 */
        private val declaredTypes = HashSet<String>()

        /**
         * 写出一条 gauge 样本(无额外 label)。
         *
         * @param name 去前缀的指标短名(如 `heap_used_bytes`)。
         * @param value 样本值。
         */
        fun gauge(name: String, value: Double) = sample(name, TYPE_GAUGE, value, emptyList())

        /**
         * 写出一条 gauge 样本(带额外 label)。
         *
         * @param name 去前缀的指标短名。
         * @param value 样本值。
         * @param labels 额外 label(会拼接在公共 label 之后)。
         */
        fun gauge(name: String, value: Double, labels: List<Pair<String, String>>) =
            sample(name, TYPE_GAUGE, value, labels)

        /**
         * 写出一条 counter 样本(带额外 label)。
         *
         * @param name 去前缀的指标短名(惯例以 `_total` 结尾)。
         * @param value 样本值(单调累计量)。
         * @param labels 额外 label。
         */
        fun counter(name: String, value: Double, labels: List<Pair<String, String>> = emptyList()) =
            sample(name, TYPE_COUNTER, value, labels)

        /**
         * 仅当 [raw] 非负时写出 gauge:用于 max 类哨兵(-1=无上限/不可用)与世界计数(-1=N/A)。
         *
         * @param name 去前缀的指标短名。
         * @param raw 原始 long 值;< 0 时整行跳过。
         * @param labels 额外 label。
         */
        fun gaugeIfNonNegative(name: String, raw: Long, labels: List<Pair<String, String>> = emptyList()) {
            if (raw >= 0) {
                sample(name, TYPE_GAUGE, raw.toDouble(), labels)
            }
        }

        /**
         * 仅当 CPU 占用率可用([raw] >= 0)时写出 gauge:CPU 指标以 -1.0 表示当前 JDK 不提供。
         *
         * @param name 去前缀的指标短名。
         * @param raw 原始占用率(0.0–1.0),-1.0 时整行跳过。
         */
        fun gaugeIfCpuAvailable(name: String, raw: Double) {
            if (raw >= 0.0) {
                sample(name, TYPE_GAUGE, raw, emptyList())
            }
        }

        /**
         * 仅当 [value] 非 null 时写出 gauge:用于 TPS/MSPT 等"N/A 即 null"的可空指标。
         *
         * @param name 去前缀的指标短名。
         * @param value 可空样本值;为 null 时整行跳过。
         * @param labels 额外 label。
         */
        fun gaugeIfPresent(name: String, value: Double?, labels: List<Pair<String, String>>) {
            if (value != null) {
                sample(name, TYPE_GAUGE, value, labels)
            }
        }

        /**
         * 写出一条完整样本:必要时先补 `# TYPE` 声明,再输出 `前缀+名{labels} 值`。
         *
         * @param name 去前缀的指标短名。
         * @param type 指标类型(gauge/counter)。
         * @param value 样本值。
         * @param extraLabels 额外 label(拼接在公共 label 之后)。
         */
        private fun sample(name: String, type: String, value: Double, extraLabels: List<Pair<String, String>>) {
            val fullName = PREFIX + name
            if (declaredTypes.add(name)) {
                sb.append("# TYPE ").append(fullName).append(' ').append(type).append('\n')
            }
            sb.append(fullName)
            appendLabels(extraLabels)
            sb.append(' ').append(formatValue(value)).append('\n')
        }

        /**
         * 追加 label 块 `{k1="v1",k2="v2"}`(公共 label 在前、额外 label 在后);无任何 label 时不输出花括号。
         *
         * @param extraLabels 额外 label。
         */
        private fun appendLabels(extraLabels: List<Pair<String, String>>) {
            if (baseLabels.isEmpty() && extraLabels.isEmpty()) {
                return
            }
            sb.append('{')
            var first = true
            for ((key, rawValue) in baseLabels) {
                first = appendOneLabel(key, rawValue, first)
            }
            for ((key, rawValue) in extraLabels) {
                first = appendOneLabel(key, rawValue, first)
            }
            sb.append('}')
        }

        /**
         * 追加单个 `key="escapedValue"`,按需补逗号分隔。
         *
         * @param key label 名。
         * @param rawValue 未转义的 label 值。
         * @param first 当前是否为 label 块内第一个;用于决定是否前置逗号。
         * @return 追加后是否仍处于"第一个"状态(恒为 false,供链式更新)。
         */
        private fun appendOneLabel(key: String, rawValue: String, first: Boolean): Boolean {
            if (!first) {
                sb.append(',')
            }
            sb.append(key).append("=\"").append(escapeLabelValue(rawValue)).append('"')
            return false
        }

        /**
         * 格式化样本数值:整数值去掉多余小数(如 `1024` 而非 `1024.0`),非整数保留原始 double 文本。
         *
         * 不强制科学计数法/定点格式,交由 [Double.toString] 输出 Prometheus 可解析的浮点文本。
         *
         * @param value 样本值。
         * @return 文本化的数值。
         */
        private fun formatValue(value: Double): String {
            // 整数值(且在 long 可表示范围内)输出为整数形式,避免无意义的 .0 尾巴
            if (value.isFinite() && value == Math.floor(value) && !value.isInfinite() &&
                value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()
            ) {
                return value.toLong().toString()
            }
            return value.toString()
        }

        private companion object {

            /** Prometheus 指标类型:瞬时可增减值。 */
            private const val TYPE_GAUGE = "gauge"

            /** Prometheus 指标类型:单调累计值。 */
            private const val TYPE_COUNTER = "counter"
        }
    }
}
