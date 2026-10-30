package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 仅用于的回环 Worker fixture（桥业务链路）。
 *
 * 它复用 [WebSocketFixtureServer] 的 RFC 6455 子集，严格验证握手、ping/pong、命令回执和业务事件；
 * 不依赖或模拟 JianManager 的任何运行时代码。
 *
 * ## 与分片场景的关系
 * 两个场景共用底层连接与帧编解码，但**状态机各自独立**：本类保留原有的 [Stage] 顺序校验
 * （manifest → echo → emit → fail → slow → follow-up），分片场景在 `ChunkFrameFixture` 里
 * 按 groupId 重组大帧。这样新增场景不会给 [Stage] 添分支，也不会改变本场景的既有行为。
 */
class ProtocolWorkerFixture private constructor(
    endpoint: FixtureEndpoint,
) : WebSocketFixtureServer(endpoint.serverSocket, endpoint.token, "serverprobe-e2e-worker-fixture") {

    private val commandsOpen = AtomicBoolean(false)
    private val helloSeen = AtomicBoolean(false)
    private val pongSeen = AtomicBoolean(false)
    private val manifestSent = AtomicBoolean(false)
    private val eventSeen = AtomicBoolean(false)
    private val emitResultSeen = AtomicBoolean(false)
    private val stage = AtomicReference(Stage.WAITING)

    /** Provider 注册完成后允许 fixture 下发第一条业务命令。 */
    fun openCommands() {
        commandsOpen.set(true)
        maybeSendManifest()
    }

    /** 只接受指定实例的 hello，随后驱动 RFC 6455 ping/pong。 */
    override fun handleText(text: String) {
        when (jsonString(text, "type")) {
            "hello" -> handleHello(text)
            "event" -> handleEvent(text)
            else -> throw IllegalStateException("收到未知桥帧:$text")
        }
    }

    /** hello 已被 [handleText] 识别；此处只做实例校验与 welcome/ping。 */
    private fun handleHello(text: String) {
        check(jsonString(text, "instance") == WsFixtureProtocol.INSTANCE_ID) { "hello 实例标识不匹配" }
        check(helloSeen.compareAndSet(false, true)) { "重复收到 hello" }
        sendText("{\"type\":\"welcome\"}")
        sendPing()
    }

    /** pong 到达意味着控制帧往返成立，随后尝试下发 manifest。 */
    override fun onPong() {
        pongSeen.set(true)
        maybeSendManifest()
    }

    /** 忽略连接事件；业务事件与 command_result 必须满足当前状态机。 */
    // 这里刻意保持"默认抛异常"：收到未声明的事件就说明契约漂移，
    // 让 E2E 大声失败，而不是静默放过。因此每新增一类上行事件都要在此显式登记。
    private fun handleEvent(text: String) {
        when (jsonString(text, "event")) {
            "command_result" -> handleCommandResult(text)
            "${E2E_DOMAIN}_change" -> handleBusinessEvent(text)
            "connected" -> Unit
            "player_join", "player_quit", "chat", "cross_server" -> Unit
            else -> throw IllegalStateException("收到未知业务事件:$text")
        }
    }

    /** 校验 e2e 业务事件的域、去重键和结构化字段。 */
    private fun handleBusinessEvent(text: String) {
        check(jsonString(text, "domain") == E2E_DOMAIN) { "业务事件 domain 不匹配" }
        check(jsonString(text, "dedupKey") == EVENT_DEDUP_KEY) { "业务事件 dedupKey 不匹配" }
        check(jsonString(text, "kind") == "emitted") { "业务事件 data.kind 不匹配" }
        eventSeen.set(true)
        advanceAfterEmit()
    }

    /** 按当前下发命令逐项校验 requestId、成功状态和输出后推进下一步。 */
    private fun handleCommandResult(text: String) {
        when (stage.get()) {
            Stage.MANIFEST -> verifyManifest(text)
            Stage.ECHO -> verifyEcho(text, REQUEST_ECHO, Stage.EMIT)
            Stage.EMIT -> verifyEmit(text)
            Stage.FAIL -> verifyFailure(text, REQUEST_FAIL, false, Stage.SLOW)
            Stage.SLOW -> verifyFailure(text, REQUEST_SLOW, false, Stage.FOLLOW_UP)
            Stage.FOLLOW_UP -> verifyFollowUp(text)
            Stage.WAITING -> throw IllegalStateException("未下发命令时收到 command_result")
            null -> throw IllegalStateException("协议状态机丢失当前阶段")
        }
    }

    /**
     * manifest 必须由真实 BusinessHost 汇总，且包含刚注册的 e2e 域。
     *
     * 起信封多了一层 `apiVersion`，逐域多了一个 `specDeclared`：
     * 这里同时断言两者，是因为「信封结构变了但内容对了」和「内容也变了」是两种完全不同的故障，
     * 只查域名字典里有没有 `e2e` 无法区分。
     */
    private fun verifyManifest(text: String) {
        checkCommand(text, REQUEST_MANIFEST, true)
        val output = checkNotNull(jsonString(text, "output")) { "manifest 回执缺少 output" }
        check(jsonHasField(output, "apiVersion")) { "manifest 信封缺少 apiVersion：$output" }
        check(output.contains("\"$E2E_DOMAIN\"")) { "manifest 未包含 e2e 域：$output" }
        check(output.contains("\"specDeclared\"")) { "manifest 域条目缺少 specDeclared：$output" }
        // 旧契约域必须存在且被标为未声明规格（向后兼容的真机断言）。
        check(output.contains("\"$LEGACY_DOMAIN\"")) { "manifest 未包含旧契约域 $LEGACY_DOMAIN：$output" }
        check(legacyIsUndeclared(output)) { "旧契约域 $LEGACY_DOMAIN 应标 specDeclared=false：$output" }
        sendCommand(Stage.ECHO, REQUEST_ECHO, E2E_DOMAIN, "echo", ECHO_PAYLOAD)
    }

    /**
     * 判断旧契约域在 manifest 里是否标了 `specDeclared=false`。
     *
     * 只截取该域对象原文再查，避免把别的域的 `specDeclared=false` 当成本域的证据——
     * 整个 JSON 上做 contains 会在"有多个未声明域"时假通过。
     */
    private fun legacyIsUndeclared(output: String): Boolean {
        val domainStart = output.indexOf("\"$LEGACY_DOMAIN\"")
        if (domainStart < 0) return false
        val objectStart = output.indexOf('{', domainStart)
        if (objectStart < 0) return false
        val body = balancedRegionOf(output, objectStart) ?: return false
        return Regex("\"specDeclared\"\\s*:\\s*false").containsMatchIn(body)
    }

    /** 从 `{` 起点截出配平的对象原文；未配平返回 null（坏 JSON 不应伪装成断言失败）。 */
    private fun balancedRegionOf(text: String, start: Int): String? {
        var depth = 0
        var index = start
        var inString = false
        while (index < text.length) {
            val ch = text[index]
            when {
                inString && ch == '\\' -> index++
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
            index++
        }
        return null
    }

    /** echo 输出必须保留原 payload，证明 domain/action/payload 的完整路由。 */
    private fun verifyEcho(text: String, requestId: String, next: Stage) {
        checkCommand(text, requestId, true)
        check(jsonString(text, "output") == ECHO_PAYLOAD) { "echo 回执未保留原 payload" }
        sendCommand(next, REQUEST_EMIT, E2E_DOMAIN, "emit", "{}")
    }

    /** emit 命令自身成功且对应业务 event 已到达后，才可进入失败分支。 */
    private fun verifyEmit(text: String) {
        checkCommand(text, REQUEST_EMIT, true)
        check(jsonString(text, "output") == "emitted") { "emit 回执不匹配" }
        emitResultSeen.set(true)
        advanceAfterEmit()
    }

    /** 失败与超时都必须作为失败回执保留在桥读循环内。 */
    private fun verifyFailure(text: String, requestId: String, expected: Boolean, next: Stage) {
        checkCommand(text, requestId, expected)
        sendCommand(next, requestIdFor(next), E2E_DOMAIN, actionFor(next), ECHO_PAYLOAD)
    }

    /** 超时后的 echo 仍成功，证明业务故障未中断桥读循环。 */
    private fun verifyFollowUp(text: String) {
        checkCommand(text, REQUEST_FOLLOW_UP, true)
        check(jsonString(text, "output") == ECHO_PAYLOAD) { "超时后的 echo 回执不匹配" }
        completeEvidence(
            mapOf(
                "hello" to helloSeen.get().toString(),
                "pong" to pongSeen.get().toString(),
                "event" to eventSeen.get().toString(),
                "timeoutRecovered" to "true",
            ),
        )
    }

    /** e2e event 与 emit 回执可能先后抵达，两个条件齐备才进入 fail。 */
    private fun advanceAfterEmit() {
        if (eventSeen.get() && emitResultSeen.get() && stage.compareAndSet(Stage.EMIT, Stage.FAIL)) {
            sendCommand(Stage.FAIL, REQUEST_FAIL, E2E_DOMAIN, "fail", "{}")
        }
    }

    /** 仅在 hello、pong 和 Provider 注册都完成后下发 manifest。 */
    private fun maybeSendManifest() {
        if (commandsOpen.get() && helloSeen.get() && pongSeen.get() && manifestSent.compareAndSet(false, true)) {
            sendCommand(Stage.MANIFEST, REQUEST_MANIFEST, "jbis", "manifest", "")
        }
    }

    /** 组装与 BridgeClient 当前协议形态一致的 command 文本帧。 */
    private fun sendCommand(stage: Stage, requestId: String, domain: String, action: String, payload: String) {
        this.stage.set(stage)
        sendText(
            "{\"type\":\"command\",\"action\":\"$action\",\"target\":\"\",\"reason\":\"\"," +
                "\"requestId\":\"$requestId\",\"domain\":\"$domain\"," +
                "\"payloadJson\":\"${escapeJson(payload)}\"}",
        )
    }

    /** 指定 command_result 的关联标识与 success 布尔值必须精确匹配。 */
    private fun checkCommand(text: String, requestId: String, success: Boolean) {
        check(jsonString(text, "requestId") == requestId) { "command_result requestId 不匹配" }
        check(jsonBoolean(text, "success") == success) { "command_result success 不匹配" }
    }

    /** SLOW 后继续下发 follow-up echo；其它阶段不应调用本方法。 */
    private fun requestIdFor(stage: Stage): String = when (stage) {
        Stage.SLOW -> REQUEST_SLOW
        Stage.FOLLOW_UP -> REQUEST_FOLLOW_UP
        else -> error("未定义后续 requestId:$stage")
    }

    /** 失败后下发 slow，slow 后下发 echo；其它阶段不应调用本方法。 */
    private fun actionFor(stage: Stage): String = when (stage) {
        Stage.SLOW -> "slow"
        Stage.FOLLOW_UP -> "echo"
        else -> error("未定义后续 action:$stage")
    }

    private enum class Stage {
        WAITING,
        MANIFEST,
        ECHO,
        EMIT,
        FAIL,
        SLOW,
        FOLLOW_UP,
    }

    companion object {

        /** 从启动前生成的测试配置读取一次性凭据，并在指定回环端口启动 Worker fixture。 */
        fun startFromProbeConfiguration(pluginsDirectory: File): ProtocolWorkerFixture =
            ProtocolWorkerFixture(openFixtureEndpoint(pluginsDirectory, ""))

        private const val E2E_DOMAIN = "e2e"

        /** 旧契约 fixture 的域名：与 ServerProbeE2eHarnessPlugin 里的 `LEGACY_DOMAIN` 同值。 */
        private const val LEGACY_DOMAIN = "e2e-legacy"
        private const val EVENT_DEDUP_KEY = "fixture-event"
        private const val REQUEST_MANIFEST = "e2e-manifest"
        private const val REQUEST_ECHO = "e2e-echo"
        private const val REQUEST_EMIT = "e2e-emit"
        private const val REQUEST_FAIL = "e2e-fail"
        private const val REQUEST_SLOW = "e2e-slow"
        private const val REQUEST_FOLLOW_UP = "e2e-follow-up"
        private const val ECHO_PAYLOAD = "{\"value\":\"round-trip\"}"
    }
}
