package top.wcpe.mc.plugin.serverprobe.core.control

/**
 * 配置键三档分级(协议规格 `probe-managed-mode/spec.md` §3.3 与 §7.1)。
 *
 * 定档真源是协议规格 §7.1 的 66 键权威表,**本文件逐键照抄、不自行判定**:FORBIDDEN 18、GUARDED 45、SAFE 3。
 * 新增配置键时必须同步更新该表与本文件的 [TIER_BY_KEY],否则单测(66 键全覆盖/无遗漏/无重复)失败——
 * 这是为了防止"新加的键默认落到 SAFE"这类静默降级(规格 §7.1 的实现要求)。
 *
 * ## 三档的语义(摘自规格 §3.3)
 * - [SAFE]:不影响数据量级、不影响外发能力 → 校验类型后直接应用。
 * - [GUARDED]:影响指标数据量、观测覆盖或外发频率 → 校验**物理下限**后应用,越界整包拒绝。
 * - [FORBIDDEN]:一旦改错会切断救命通道 → **不接受下发**;出现在载荷中即整包拒绝并记审计。
 *
 * ## 实现取并集,比字面更严
 * 规格 §3.3 明确要求纵向双重防御时"实现取并集":不仅拒绝 `bridge` 的三个叶子键,而是整组拒绝。
 * [isForbiddenPath] 因此除了逐键命中,还会逐段检查点分前缀(如 `bridge.url.xxx` 的 `bridge.url` 与 `bridge`),
 * CP 写入侧与探针应用侧**共用本类**这一份规则,避免两侧漂移。
 */
enum class ConfigTier {

    /** 安全档:纯展示或日志级别,无副作用、可随时回退。 */
    SAFE,

    /** 危险档:影响数据量/资源占用/观测覆盖/隐私采集,有物理下限或需二次确认。 */
    GUARDED,

    /** 禁止远程档:开启监听面或承载凭证,托管模式下不得对外开任何端口、不接受远程改密钥。 */
    FORBIDDEN;

    companion object {

        /** 配置键:采集周期。 */
        private const val KEY_COLLECT_PERIOD_TICKS = "collect-period-ticks"

        /** 配置键:世界采样周期。 */
        private const val KEY_WORLD_SAMPLE_PERIOD_TICKS = "world.sample-period-ticks"

        /** 配置键:CPU 采样周期。 */
        private const val KEY_CPU_SAMPLE_PERIOD_TICKS = "cpu.sample-period-ticks"

        /** 配置键:近期历史容量。 */
        private const val KEY_HISTORY_CAPACITY = "history-capacity"

        /** 配置键:指标聚合窗口。 */
        private const val KEY_AGGREGATION_WINDOW = "aggregation.window"

        /** 配置键:慢插件榜条数。 */
        private const val KEY_STARTUP_TOP_N = "startup-top-n"

        /** 配置键:实例名覆盖。 */
        private const val KEY_SERVER_NAME = "server-name"

        /** 配置键:调试日志开关。 */
        private const val KEY_DEBUG = "debug"

        /** 托管配置键:指标推送间隔(规格 §3.3,FR-46 起已接入 `config.yml` 与取值面,见 [RESERVED_GUARDED_KEYS])。 */
        private const val KEY_METRICS_PUSH_INTERVAL_MS = "metrics-push-interval-ms"

        /** 托管配置键:托管状态上报周期(FR-45,协议规格 §3.4.3,见 [RESERVED_SAFE_KEYS])。 */
        private const val KEY_CONFIG_STATE_INTERVAL_MS = "config-state-interval-ms"

        /** 采集周期下限(ticks,约 1 秒)。 */
        private const val MIN_COLLECT_PERIOD_TICKS = 20L

        /** 采样周期下限(ticks,约 1 秒):世界采样与 CPU 采样共用。 */
        private const val MIN_SAMPLE_PERIOD_TICKS = 20L

        /** 托管指标推送间隔下限(毫秒):防止把反向 WS 打满。 */
        private const val MIN_METRICS_PUSH_INTERVAL_MS = 1_000L

        /** 历史缓冲容量上限(份):超出即有内存无界增长风险。 */
        private const val MAX_HISTORY_CAPACITY = 20_000L

        /**
         * 66 键权威定档表(顺序与规格 §7.1 一致,便于逐行比对评审)。
         *
         * 用 `linkedMapOf` 保存以固定遍历顺序;单测断言总数为 66、各档数量与规格一致。
         */
        private val TIER_BY_KEY: Map<String, ConfigTier> = linkedMapOf(
            // ── FORBIDDEN(18):开启监听面 或 承载凭证 ──
            "bridge.enabled" to ConfigTier.FORBIDDEN,
            "bridge.url" to ConfigTier.FORBIDDEN,
            "bridge.instance" to ConfigTier.FORBIDDEN,
            "bridge.token" to ConfigTier.FORBIDDEN,
            "metrics.enabled" to ConfigTier.FORBIDDEN,
            "metrics.host" to ConfigTier.FORBIDDEN,
            "metrics.port" to ConfigTier.FORBIDDEN,
            "metrics.token" to ConfigTier.FORBIDDEN,
            "metrics.allowed-ips" to ConfigTier.FORBIDDEN,
            "mcp.enabled" to ConfigTier.FORBIDDEN,
            "mcp.host" to ConfigTier.FORBIDDEN,
            "mcp.port" to ConfigTier.FORBIDDEN,
            "mcp.secret" to ConfigTier.FORBIDDEN,
            "web.enabled" to ConfigTier.FORBIDDEN,
            "web.host" to ConfigTier.FORBIDDEN,
            "web.port" to ConfigTier.FORBIDDEN,
            "web.token" to ConfigTier.FORBIDDEN,
            "web.allowed-ips" to ConfigTier.FORBIDDEN,
            // ── SAFE(3):仅展示或日志级别 ──
            "startup-top-n" to ConfigTier.SAFE,
            "server-name" to ConfigTier.SAFE,
            "debug" to ConfigTier.SAFE,
            // ── GUARDED(45):影响数据量/资源/观测覆盖/隐私 ──
            "collect-period-ticks" to ConfigTier.GUARDED,
            "history-capacity" to ConfigTier.GUARDED,
            "aggregation.window" to ConfigTier.GUARDED,
            "history-retention" to ConfigTier.GUARDED,
            "history-file.enabled" to ConfigTier.GUARDED,
            "history-file.retention-days" to ConfigTier.GUARDED,
            "history-file.max-total-mb" to ConfigTier.GUARDED,
            "history-file.archive-days" to ConfigTier.GUARDED,
            "agent-stack-max-samples" to ConfigTier.GUARDED,
            "world.sample-period-ticks" to ConfigTier.GUARDED,
            "world.entity-types" to ConfigTier.GUARDED,
            "folia.observed-region-expire-seconds" to ConfigTier.GUARDED,
            "network-forensics.enabled" to ConfigTier.GUARDED,
            "network-forensics.payload-packet-types" to ConfigTier.GUARDED,
            "network-forensics.payload-channels" to ConfigTier.GUARDED,
            "network-forensics.max-payload-bytes" to ConfigTier.GUARDED,
            "network-forensics.retention-days" to ConfigTier.GUARDED,
            "network-forensics.max-total-gib" to ConfigTier.GUARDED,
            "http-monitor.enabled" to ConfigTier.GUARDED,
            "http-monitor.log-to-console" to ConfigTier.GUARDED,
            "http-monitor.log-headers" to ConfigTier.GUARDED,
            "http-monitor.file-enabled" to ConfigTier.GUARDED,
            "http-monitor.file-retention-days" to ConfigTier.GUARDED,
            "http-monitor.file-archive-days" to ConfigTier.GUARDED,
            "http-monitor.recent-capacity" to ConfigTier.GUARDED,
            "http-monitor.drain-period-ticks" to ConfigTier.GUARDED,
            "alert.enabled" to ConfigTier.GUARDED,
            "alert.channels.log" to ConfigTier.GUARDED,
            "alert.channels.in-game" to ConfigTier.GUARDED,
            "alert.channels.webhook.enabled" to ConfigTier.GUARDED,
            "alert.channels.webhook.url" to ConfigTier.GUARDED,
            "alert.channels.webhook.timeout-ms" to ConfigTier.GUARDED,
            "alert.rules" to ConfigTier.GUARDED,
            "cpu.enabled" to ConfigTier.GUARDED,
            "incision.enabled" to ConfigTier.GUARDED,
            "cpu.sample-period-ticks" to ConfigTier.GUARDED,
            "cpu.window-rounds" to ConfigTier.GUARDED,
            "mcp.task-max-concurrent" to ConfigTier.GUARDED,
            "mcp.task-timeout-minutes" to ConfigTier.GUARDED,
            "mcp.task-output-mebibytes" to ConfigTier.GUARDED,
            "mcp.task-retention-minutes" to ConfigTier.GUARDED,
            "mcp.audit-max-file-mb" to ConfigTier.GUARDED,
            "mcp.audit-retention-days" to ConfigTier.GUARDED,
            "mcp.artifact-retention-hours" to ConfigTier.GUARDED,
            "mcp.artifact-max-gib" to ConfigTier.GUARDED,
        )

        /**
         * 预定档表但**不计入 66 键定档表**的键:出现时按 GUARDED 校验。
         *
         * 目前只有托管指标推送间隔(规格 §3.3 给出 `>= 1000ms` 下限):FR-46 起 `config.yml` 与
         * [top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig] 都已带上该键,但它不在协议规格
         * §7.1 的 66 键权威表内——保留在表外是为了不破坏"66 键"这一可断言的数量契约。
         */
        private val RESERVED_GUARDED_KEYS: Set<String> = setOf(KEY_METRICS_PUSH_INTERVAL_MS)

        /**
         * 预定档表但**不计入 66 键定档表**的键:出现时按 SAFE 校验。
         *
         * 目前只有托管状态上报周期(FR-45,协议规格 §3.4.3 "周期默认 30s,可经 SAFE 档键调整")。
         * 按 SAFE 而非 GUARDED 的理由:它是**纯上报节奏**,不改变指标采集的数据量,也不影响外发能力;
         * 规格已明确"由 SAFE 档键调整"。其物理下限(5s)在读取侧兜底(见
         * [top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig.configStateIntervalMs]),与 SAFE 档
         * "只校验类型"的语义一致。
         */
        private val RESERVED_SAFE_KEYS: Set<String> = setOf(KEY_CONFIG_STATE_INTERVAL_MS)

        /** 按档缓存的键集合(读多且表只读,预先分组避免每次调用重复过滤)。 */
        private val KEYS_BY_TIER: Map<ConfigTier, Set<String>> = TIER_BY_KEY.entries
            .groupBy({ it.value }, { it.key })
            .mapValues { it.value.toSet() }

        /** 整组禁止的根段:规格 §3.3 要求 `bridge` 整组结构性禁止,而非只堵三个叶子键。 */
        private val FORBIDDEN_ROOTS: Set<String> = setOf("bridge")

        /**
         * 数值物理边界。
         *
         * @property min 下限(含);null 表示不限。
         * @property max 上限(含);null 表示不限。
         * @property hint 中文理由(拼进拒绝原因,便于 CP 侧与运维看懂为何被拒)。
         */
        private class Bound(val min: Long? = null, val max: Long? = null, val hint: String = "")

        /** 键的物理边界表:只有**有物理下限/上限**的键才在表内。 */
        private val BOUNDS: Map<String, Bound> = mapOf(
            KEY_COLLECT_PERIOD_TICKS to Bound(
                min = MIN_COLLECT_PERIOD_TICKS,
                hint = "过小会放大指标量并压主线程",
            ),
            KEY_WORLD_SAMPLE_PERIOD_TICKS to Bound(
                min = MIN_SAMPLE_PERIOD_TICKS,
                hint = "世界采集需遍历所有世界与区块,过小会放大采集开销",
            ),
            KEY_CPU_SAMPLE_PERIOD_TICKS to Bound(
                min = MIN_SAMPLE_PERIOD_TICKS,
                hint = "CPU 采样开销压在服务器主线程,过小会拖慢服务器",
            ),
            KEY_METRICS_PUSH_INTERVAL_MS to Bound(
                min = MIN_METRICS_PUSH_INTERVAL_MS,
                hint = "推送间隔过短会把反向 WS 打满",
            ),
            KEY_HISTORY_CAPACITY to Bound(
                max = MAX_HISTORY_CAPACITY,
                hint = "内存无界增长风险",
            ),
            // 聚合窗口自身无绝对上下限,上界由"不得大于 history-capacity"的跨键约束给出
            KEY_AGGREGATION_WINDOW to Bound(hint = "逻辑一致性"),
        )

        /** 安全档的期望类型(规格 §3.3:"校验类型后直接应用")。 */
        private val SAFE_TYPES: Map<String, ScalarType> = mapOf(
            KEY_STARTUP_TOP_N to ScalarType.INTEGER,
            KEY_SERVER_NAME to ScalarType.STRING,
            KEY_DEBUG to ScalarType.BOOLEAN,
            // 预 SAFE 档键同样只要类型正确即可(下限由读取侧兜底)
            KEY_CONFIG_STATE_INTERVAL_MS to ScalarType.INTEGER,
        )

        /**
         * 查键的档位;**未收录的键按 [ConfigTier.FORBIDDEN] 处理**(失败关闭)。
         *
         * 保守默认的理由:未收录意味着"定档表没跟上",此时宁可拒绝下发也不能默认放行(规格 §7.1 的实现要求
         * 正是防止新键静默落到 SAFE)。需要区分"未知键"与"禁止键"的调用方请用 [tierOfOrNull]。
         *
         * @param key 配置键名(点分,如 `collect-period-ticks`)。
         * @return 档位;未收录时返回 [ConfigTier.FORBIDDEN]。
         */
        fun tierOf(key: String): ConfigTier = tierOfOrNull(key) ?: ConfigTier.FORBIDDEN

        /**
         * 查键的档位。
         *
         * @param key 配置键名。
         * @return 档位;定档表与两个预定档表都未收录时返回 null。
         */
        fun tierOfOrNull(key: String): ConfigTier? = TIER_BY_KEY[key] ?: reservedTierOf(key)

        /** 查"预"档表(66 键权威表之外、已接入配置面但尚未计入契约口径的键)。 */
        private fun reservedTierOf(key: String): ConfigTier? = when (key) {
            in RESERVED_GUARDED_KEYS -> ConfigTier.GUARDED
            in RESERVED_SAFE_KEYS -> ConfigTier.SAFE
            else -> null
        }

        /** 定档表的全部键(66 个,只读)。 */
        fun keys(): Set<String> = TIER_BY_KEY.keys

        /**
         * 预定档表的键(按 GUARDED 校验,**不计入 66 键口径**)。
         *
         * 供配置面自检:这些键在 `config.yml` 与 [top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig]
         * 里已存在,但按协议规格 §7.1 的说明保留在 66 键权威表外。
         */
        fun reservedKeys(): Set<String> = RESERVED_GUARDED_KEYS

        /**
         * 预 SAFE 档表的键(按 SAFE 校验,**不计入 66 键口径**)。
         *
         * 与 [reservedKeys] 同样只承担"新键必须定档"的防漂移职责:被收录但仍不在 66 键权威表内。
         */
        fun reservedSafeKeys(): Set<String> = RESERVED_SAFE_KEYS

        /** 某一档的全部键(66 键口径,不含预定档键)。 */
        fun keysOf(tier: ConfigTier): Set<String> = KEYS_BY_TIER[tier].orEmpty()

        /**
         * 该路径是否属于禁止远程面(逐键命中,或任一点分前缀命中,含整组禁止的根段)。
         *
         * @param key 配置键名,允许是更深层的点分路径(如 `alert.rules.tps-warn.threshold`)。
         * @return true 表示托管配置面不得出现该路径。
         */
        fun isForbiddenPath(key: String): Boolean {
            if (key in FORBIDDEN_ROOTS || TIER_BY_KEY[key] == ConfigTier.FORBIDDEN) {
                return true
            }
            var index = key.indexOf('.')
            while (index > 0) {
                val prefix = key.substring(0, index)
                if (prefix in FORBIDDEN_ROOTS || TIER_BY_KEY[prefix] == ConfigTier.FORBIDDEN) {
                    return true
                }
                index = key.indexOf('.', index + 1)
            }
            return false
        }

        /**
         * 校验一次下发(单键,不含跨键约束)。
         *
         * 跨键约束(`aggregation.window <= history-capacity`)需要知道当前生效的 `history-capacity`,
         * 无上下文时**跳过**该约束而非误拒;调用方应改用带 [effective] 的重载。
         *
         * @param key 配置键名。
         * @param value 下发的值(JSON 解析后的原值)。
         * @return 通过返回 null;拒绝返回**中文拒绝原因**。
         */
        fun validate(key: String, value: Any?): String? = validate(key, value) { null }

        /**
         * 校验一次下发(单键,含跨键约束)。
         *
         * @param key 配置键名。
         * @param value 下发的值。
         * @param effective 读取"合并本次下发后的生效值"的回调;返回 null 表示无从得知(该约束跳过)。
         * @return 通过返回 null;拒绝返回**中文拒绝原因**。
         */
        fun validate(key: String, value: Any?, effective: (String) -> Any?): String? {
            val tier = tierOfOrNull(key)
                ?: return "未知配置键 \"$key\":定档表未收录,禁止下发(请先在协议规格 §7.1 定档)"
            if (value == null) {
                return "配置键 \"$key\" 的值为空:不支持经下发删除键,请给出显式值"
            }
            if (tier == ConfigTier.FORBIDDEN) {
                return "配置键 \"$key\" 属禁止远程档:开启监听面或承载凭证,一旦改错会切断救命通道"
            }
            if (tier == ConfigTier.SAFE) {
                return validateSafe(key, value)
            }
            return validateGuarded(key, value, effective)
        }

        /** 安全档:只校验类型。 */
        private fun validateSafe(key: String, value: Any?): String? {
            val expected = SAFE_TYPES[key] ?: return null
            return if (expected.matches(value)) null else "配置键 \"$key\" 需要 ${expected.text},收到 ${typeName(value)}"
        }

        /**
         * 危险档:先查物理边界,再查跨键约束。
         *
         * 只有**有物理边界的键**(如采集节奏、历史容量)在这里校验整型与上下限;
         * 其余布尔/字符串/列表类危险键(如 `world.entity-types`、`alert.rules`)的类型与枚举校验
         * 留给载荷 schema(规格 §3.3:"告警阈值类由 schema 定义")。即使 schema 漏判,读取侧
         * ([ManagedConfigResolver]) 也会因类型不符而回落 `config.yml`,不会让脏值生效。
         */
        private fun validateGuarded(key: String, value: Any?, effective: (String) -> Any?): String? {
            val bound = BOUNDS[key]
            if (bound != null) {
                val numeric = asLong(value) ?: return "配置键 \"$key\" 需要整数值,收到 ${typeName(value)}"
                val rejected = checkBound(key, numeric, bound)
                if (rejected != null) {
                    return rejected
                }
            }
            return checkAggregationWindow(key, value, effective)
        }

        /** 单键上下限判定。 */
        private fun checkBound(key: String, numeric: Long, bound: Bound): String? {
            val reason = bound.hint.takeIf { it.isNotEmpty() }?.let { "($it)" }.orEmpty()
            if (bound.min != null && numeric < bound.min) {
                return "配置键 \"$key\" 不得小于 ${bound.min},收到 $numeric$reason"
            }
            if (bound.max != null && numeric > bound.max) {
                return "配置键 \"$key\" 不得大于 ${bound.max},收到 $numeric$reason"
            }
            return null
        }

        /** 跨键约束:聚合窗口不得超过历史缓冲容量(否则窗口永远填不满)。 */
        private fun checkAggregationWindow(key: String, value: Any?, effective: (String) -> Any?): String? {
            if (key != KEY_AGGREGATION_WINDOW) {
                return null
            }
            val numeric = asLong(value) ?: return "配置键 \"$key\" 需要整数值,收到 ${typeName(value)}"
            val capacity = asLong(effective(KEY_HISTORY_CAPACITY)) ?: return null
            if (numeric > capacity) {
                return "配置键 \"$key\" 不得大于 history-capacity(当前生效 $capacity),收到 $numeric" +
                    "(聚合窗口大于历史缓冲会导致窗口永远填不满)"
            }
            return null
        }

        /** 数值转换:只接受整型/浮点型(浮点仅在其为整数值时接受),其他类型返回 null。 */
        private fun asLong(value: Any?): Long? = when (value) {
            is Int, is Long, is Short, is Byte -> (value as Number).toLong()
            is Double -> value.toLong().takeIf { it.toDouble() == value }
            is Float -> value.toLong().takeIf { it.toFloat() == value }
            else -> null
        }

        /** 值的类型名(中文,用于拒绝原因)。 */
        private fun typeName(value: Any?): String = when (value) {
            null -> "空值"
            is Boolean -> "布尔值"
            is Int, is Long, is Short, is Byte -> "整数"
            is Double, is Float -> "小数"
            is String -> "字符串"
            is List<*> -> "列表"
            is Map<*, *> -> "对象"
            else -> value.javaClass.simpleName
        }

        /** 安全档期望的标量类型。 */
        private enum class ScalarType(val text: String) {
            INTEGER("整数值"),
            BOOLEAN("布尔值"),
            STRING("字符串");

            /** 值是否符合该标量类型。 */
            fun matches(value: Any?): Boolean = when (this) {
                INTEGER -> asLong(value) != null
                BOOLEAN -> value is Boolean
                STRING -> value is String
            }
        }
    }
}
