package top.wcpe.mc.plugin.serverprobe.core.alert.channel

import taboolib.common.platform.function.submit
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertChannel
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertChannelRegistry
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertEvent
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.net.HttpURLConnection
import java.net.URL

/**
 * Webhook 告警通道(FR5):把告警事件以 JSON 经 HTTP POST 投递到外部地址(如钉钉/飞书/企业微信/自建网关)。
 *
 * **零第三方依赖**:仅用 JDK 自带 [HttpURLConnection] 发请求、手拼极简 JSON,不引入任何 HTTP/JSON 库。
 *
 * ## 线程模型:异步,绝不阻塞编排线程
 * [publish] 由编排线程(异步采集线程)调用,但网络 IO 不可在其上同步执行(可能阻塞数秒)。
 * 故 [publish] 仅做开关/URL 校验后,以 `submit(async = true)` 把实际请求转交异步任务执行(规范 R7、第 17 条)。
 *
 * ## 稳定性与安全
 * - 请求异常被 runCatching 吞并并降级为 WARN,绝不抛出影响探针(探针不成事故源);
 * - 连接/读取超时取 `alert.channels.webhook.timeout-ms`,避免请求挂死;
 * - **安全(规范第 14 条)**:日志中**绝不**输出 webhook URL 与任何请求体,失败仅记类型与错误概述,
 *   防止把回调地址(可能含 token)泄露到日志。
 *
 * 生命周期:作为 IOC [Service] 由容器实例化并注入 [registry];[register] 在依赖注入完成后
 * ([PostConstruct])仅当 `alert.channels.webhook.enabled` 为 true 且 URL 非空时自注册。
 */
@Service
class WebhookAlertChannel : AlertChannel {

    /** 告警通道注册中心,用于在初始化完成后自注册。 */
    @Inject
    lateinit var registry: AlertChannelRegistry

    /**
     * 依赖注入完成后按配置自注册。
     *
     * 仅当 Webhook 子开关开启且 URL 非空才注册——避免无效注册后每次广播都空跑校验。
     */
    @PostConstruct
    fun register() {
        if (!ProbeConfig.alertWebhookEnabled() || ProbeConfig.alertWebhookUrl().isEmpty()) return
        registry.register(this)
        // 安全:仅提示已启用,不打印 URL
        ProbeLogger.info("Webhook 告警通道已启用")
    }

    /**
     * 把告警事件转异步任务投递到 Webhook。
     *
     * @param event 告警事件(触发或恢复)。
     */
    override fun publish(event: AlertEvent) {
        val url = ProbeConfig.alertWebhookUrl()
        if (url.isEmpty()) return
        val payload = toJson(event)
        val timeoutMs = ProbeConfig.alertWebhookTimeoutMs()
        // 网络 IO 转异步,绝不阻塞编排线程
        submit(async = true) {
            runCatching { post(url, payload, timeoutMs) }
                // 安全:不输出 URL,仅记错误概述
                .onFailure { ProbeLogger.warn("Webhook 告警投递失败:${it.message}") }
        }
    }

    /**
     * 经 [HttpURLConnection] 发起一次 JSON POST。
     *
     * 设连接/读取超时;请求体以 UTF-8 写出;读取响应码以促使请求完整发出并感知服务端拒绝,
     * 非 2xx 记一条 WARN(不抛)。无论成败均 `disconnect` 释放连接(规范第 8 条)。
     *
     * @param url 目标地址(由调用方从配置取得,不在日志出现)。
     * @param payload JSON 请求体。
     * @param timeoutMs 连接与读取超时(毫秒)。
     */
    private fun post(url: String, payload: String, timeoutMs: Int) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = HTTP_POST
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.setRequestProperty(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            // use 确保输出流释放
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in HTTP_OK_MIN..HTTP_OK_MAX) {
                ProbeLogger.warn("Webhook 告警返回非成功状态码:$code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 把告警事件序列化为极简 JSON(手拼,零依赖)。
     *
     * 字段:type/level/firing/value/threshold/serverId/timestampMs;字符串字段经 [escapeJson] 转义。
     *
     * @param event 告警事件。
     * @return JSON 文本。
     */
    private fun toJson(event: AlertEvent): String {
        val rule = event.rule
        return buildString {
            append('{')
            append("\"type\":\"").append(escapeJson(rule.type.name)).append("\",")
            append("\"level\":\"").append(escapeJson(rule.level.name)).append("\",")
            append("\"firing\":").append(event.firing).append(',')
            append("\"value\":").append(event.value).append(',')
            append("\"threshold\":").append(rule.threshold).append(',')
            append("\"serverId\":\"").append(escapeJson(event.serverId)).append("\",")
            append("\"timestampMs\":").append(event.timestampMs)
            append('}')
        }
    }

    /**
     * 转义 JSON 字符串值中的特殊字符(双引号、反斜杠、控制字符等),保证产出合法 JSON。
     *
     * @param raw 原始字符串。
     * @return 可安全嵌入双引号内的转义结果。
     */
    private fun escapeJson(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    private companion object {

        /** HTTP 方法:POST。 */
        private const val HTTP_POST = "POST"

        /** 请求头名:内容类型。 */
        private const val HEADER_CONTENT_TYPE = "Content-Type"

        /** JSON 内容类型。 */
        private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"

        /** 成功状态码下界(含)。 */
        private const val HTTP_OK_MIN = 200

        /** 成功状态码上界(含)。 */
        private const val HTTP_OK_MAX = 299
    }
}
