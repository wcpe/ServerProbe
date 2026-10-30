package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.Registration
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessEventSink
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainStatus
import top.wcpe.mc.plugin.serverprobe.api.bridge.LegacyBusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.protocol.Fields
import top.wcpe.mc.plugin.serverprobe.api.protocol.Payloads
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture

/**
 * [Payloads] 值规范化、[Fields] 链式装配，以及 [BusinessHost] / [BusinessEventSink] 便利重载的单测。
 *
 * 为什么放在 core 测试里：api 模块没有测试依赖（保持纯契约、零运行期依赖），
 * 而 [Payloads] / [Fields] 本身是纯 Java 无第三方依赖的类型，core 的测试类路径可直接调用它们。
 *
 * 覆盖重点：
 * - 标量化规则（null 跳过 / 字符串原样 / 数字布尔枚举字符串化）；
 * - **浮点精度**：`BigDecimal`/`BigInteger` 与 `Double`/`Float` 一律完整十进制串，不得出现科学计数法；
 * - JSON 转义与嵌套结构；
 * - **`Fields` 链式装配**：插入顺序、不可变性、null 跳过、withIf、与 `toFields` 同源；
 * - 空载荷常量 `EMPTY_FIELDS` / `empty()` 的不可变性与复用；
 * - `default` 重载确实委托到既有抽象方法（这也验证了"实现类零改动即自动继承"）。
 */
class PayloadsTest {

    /** 只记录 payloadJson 的假宿主：验证 default 重载确实把 Map 序列化后交给唯一的抽象方法。 */
    private class RecordingHost : BusinessHost {
        var lastPayloadJson: String? = null

        override fun dispatch(request: ActionRequest?): ActionResult {
            // 兼容入口 default 方法最终都会走到这里（后唯一的抽象派发点）。
            lastPayloadJson = request?.payloadJson
            return ActionResult.ok(request?.payloadJson)
        }

        override fun dispatchAsync(request: ActionRequest?): CompletableFuture<ActionResult> {
            lastPayloadJson = request?.payloadJson
            return CompletableFuture.completedFuture(ActionResult.ok(request?.payloadJson))
        }

        override fun register(provider: BusinessProvider): Registration =
            throw UnsupportedOperationException("测试桩：本用例不涉及注册")

        override fun registerLegacy(provider: LegacyBusinessProvider): Registration =
            throw UnsupportedOperationException("测试桩：本用例不涉及注册")

        override fun unregister(provider: BusinessProvider): Boolean =
            throw UnsupportedOperationException("测试桩：本用例不涉及注销")

        override fun domains(): Set<String> = emptySet()

        override fun manifest(): Map<String, Any?> = emptyMap()

        override fun healthOf(domain: String): DomainStatus =
            throw UnsupportedOperationException("测试桩：本用例不涉及健康查询")
    }

    /** 只记录落到事件入口的字段的假事件出口。 */
    private class RecordingSink : BusinessEventSink {
        var lastFields: Map<String, String>? = null
        var lastDedupKey: String? = null
        var reliableUsed = false

        override fun emit(domain: String, dedupKey: String, topic: String, fields: Map<String, String>): Boolean {
            lastDedupKey = dedupKey
            lastFields = fields
            return true
        }

        override fun emitReliable(domain: String, dedupKey: String, topic: String, fields: Map<String, String>): Boolean {
            lastDedupKey = dedupKey
            lastFields = fields
            reliableUsed = true
            return true
        }
    }

    /** 测试用枚举：验证取 name() 而不是可能被业务覆写的 toString()。 */
    private enum class Color {
        RED,
        GREEN,
        ;

        override fun toString(): String = "颜色-$name"
    }

    // ---------------------------------------------------------------- toFields

    /** null 值必须跳过该键：写 "null" 字面量会让面板把缺省当成真实取值。 */
    @Test
    fun `toFields 跳过 null 值并保留字符串原样`() {
        val fields = Payloads.toFields(linkedMapOf("amount" to " 0100.50 ", "note" to null))

        assertEquals(mapOf("amount" to " 0100.50 "), fields)
        assertFalse(fields.containsKey("note"), "null 值应整键跳过，而不是写成字符串 null")
    }

    /** 数字/布尔/枚举按标量字符串化；枚举取 name() 而非被覆写的 toString()。 */
    @Test
    fun `toFields 把数字布尔与枚举字符串化`() {
        val fields = Payloads.toFields(linkedMapOf("count" to 7, "vip" to true, "color" to Color.RED, "initial" to 'A'))

        assertEquals("7", fields["count"])
        assertEquals("true", fields["vip"])
        assertEquals("RED", fields["color"])
        assertEquals("A", fields["initial"])
    }

    /** 金额精度：BigDecimal 的 toString() 会输出 1E+18，toPlainString() 才是完整数字。 */
    @Test
    fun `toFields 大额金额不出现科学计数法`() {
        val fields = Payloads.toFields(
            linkedMapOf(
                "huge" to BigDecimal("1E+18"),
                "scaled" to BigDecimal("1.500"),
                "integer" to java.math.BigInteger("123456789012345678901234567890"),
            ),
        )

        assertEquals("1000000000000000000", fields["huge"], "金额不得写成 1E+18")
        assertEquals("1.500", fields["scaled"], "尾随零应保留（它是业务给出的精度）")
        assertEquals("123456789012345678901234567890", fields["integer"])
    }

    /** 嵌套结构一律判错，且消息必须点名键与类型，便于调用方定位。 */
    @Test
    fun `toFields 嵌套集合抛异常且消息点名键与类型`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Payloads.toFields(mapOf("items" to listOf("a", "b")))
        }

        assertTrue(error.message!!.contains("items"), "消息应点名出错的键：${error.message}")
        assertTrue(error.message!!.contains("ArrayList"), "消息应点名具体类型：${error.message}")
    }

    /** null/空入参归一为空 Map：调用方无需先判空，也不会拿到 null。 */
    @Test
    fun `toFields null 或空入参返回空 Map`() {
        assertTrue(Payloads.toFields(null).isEmpty())
        assertTrue(Payloads.toFields(emptyMap<String, Any>()).isEmpty())
    }

    // ------------------------------------------------------------------ toJson

    /** 空载荷必须写成可解析的空对象，而不是 null 或空串。 */
    @Test
    fun `toJson null 或空入参写出空对象`() {
        assertEquals("{}", Payloads.toJson(null))
        assertEquals("{}", Payloads.toJson(emptyMap<String, Any>()))
    }

    /** 转义规则：引号、反斜杠、换行、制表符与控制字符都要产出合法 JSON。 */
    @Test
    fun `toJson 转义引号反斜杠与控制字符`() {
        val json = Payloads.toJson(linkedMapOf("text" to "say \"hi\"\\ok\n\t\u0001"))

        assertEquals("{\"text\":\"say \\\"hi\\\"\\\\ok\\n\\t\\u0001\"}", json)
    }

    /** 中文与 emoji 原样写出（不做多余的 \\uXXXX 膨胀），保证载荷大小不被放大。 */
    @Test
    fun `toJson 保留中文与 emoji`() {
        val json = Payloads.toJson(mapOf("msg" to "你好🚀"))

        assertEquals("{\"msg\":\"你好🚀\"}", json)
    }

    /** 嵌套 Map / List / 基本类型数组按 JSON 结构展开。 */
    @Test
    fun `toJson 写出嵌套对象与数组`() {
        val json = Payloads.toJson(
            linkedMapOf(
                "player" to linkedMapOf("id" to "alice", "level" to 12),
                "tags" to listOf("a", 2, true),
                "codes" to intArrayOf(1, 2),
            ),
        )

        assertEquals("{\"player\":{\"id\":\"alice\",\"level\":12},\"tags\":[\"a\",2,true],\"codes\":[1,2]}", json)
    }

    /** 显式 null 值写成 JSON null（与 toFields 跳过 null 的策略不同：JSON 本身能表达 null）。 */
    @Test
    fun `toJson 保留显式 null 值`() {
        assertEquals("{\"note\":null}", Payloads.toJson(linkedMapOf("note" to null)))
    }

    /** 大额金额同样不得写成科学计数法，否则面板解析后就是失真值。 */
    @Test
    fun `toJson 大额金额不出现科学计数法`() {
        val json = Payloads.toJson(
            linkedMapOf(
                "amount" to BigDecimal("1E+18"),
                "negative" to BigDecimal("-2.5E-7"),
            ),
        )

        assertEquals("{\"amount\":1000000000000000000,\"negative\":-0.00000025}", json)
    }

    /** 不支持的类型判错，消息点名键与类型。 */
    @Test
    fun `toJson 不支持的取值抛异常且消息点名键`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Payloads.toJson(mapOf("when" to java.time.Instant.EPOCH))
        }

        assertTrue(error.message!!.contains("when"), "消息应点名出错的键：${error.message}")
        assertTrue(error.message!!.contains("Instant"), "消息应点名具体类型：${error.message}")
    }

    // ------------------------------------------------- BusinessHost 便利重载

    /** dispatch(Map) 的默认实现必须产出可被 Provider 解析的 JSON 文本。 */
    @Test
    fun `dispatch 的 Map 重载把结构化参数写成 JSON 文本`() {
        val host = RecordingHost()

        val result = host.dispatch("economy", "settle", linkedMapOf("playerId" to "alice", "amount" to "100.50"))

        assertTrue(result.success)
        assertEquals("{\"playerId\":\"alice\",\"amount\":\"100.50\"}", host.lastPayloadJson)
    }

    /** 空载荷走重载等价于 {}（而不是 null / 空串）。 */
    @Test
    fun `dispatch 的 Map 重载把空参数写成空对象`() {
        val host = RecordingHost()

        host.dispatch("economy", "ping", emptyMap<String, Any>())

        assertEquals("{}", host.lastPayloadJson)
    }

    /** dispatchAsync(Map) 同样委托到字符串版本，且以结果（非异常）完成。 */
    @Test
    fun `dispatchAsync 的 Map 重载把结构化参数写成 JSON 文本`() {
        val host = RecordingHost()

        val result = host.dispatchAsync("economy", "query", mapOf("playerId" to "alice")).join()

        assertTrue(result.success)
        assertEquals("{\"playerId\":\"alice\"}", host.lastPayloadJson)
    }

    /** emitFields / emitFieldsReliable 必须标量化后转交给既有事件入口，不改变通道语义。 */
    @Test
    fun `emitFields 系列把字段标量化后转交既有入口`() {
        val sink = RecordingSink()

        assertTrue(sink.emitFields("economy", "tx-1", "settle", linkedMapOf("amount" to BigDecimal("1E+18"))))
        assertEquals(mapOf("amount" to "1000000000000000000"), sink.lastFields)
        assertEquals("tx-1", sink.lastDedupKey)
        assertFalse(sink.reliableUsed, "可丢入口不应落到可靠通道")

        assertTrue(sink.emitFieldsReliable("economy", "tx-2", "settle", mapOf("vip" to false)))
        assertEquals(mapOf("vip" to "false"), sink.lastFields)
        assertTrue(sink.reliableUsed, "可靠入口必须落到可靠通道")
    }

    /**
     * 长链（64 字段）装配后展平仍正确：`with` 是共享父链（O(1)），
     * 展平只在 `build()` 发生一次，因此**顺序与去重规则必须完全不受链长影响**。
     *
     * 这条用例钉的是"性能优化不改变语义"——链越长越容易暴露逆序展平写错的问题
     * （例如把最早装配的字段排到最后）。
     */
    @Test
    fun `Fields 长链装配的顺序与去重正确`() {
        var chain = Fields.create()
        for (index in 0 until 64) chain = chain.with("k$index", index)
        // 末尾覆盖一个早期键：位置应仍在最前，值取最新。
        chain = chain.with("k0", 999)

        val built = chain.build()
        assertEquals(64, built.size, "64 个不同键 + 1 次覆盖 = 仍 64 个字段")
        assertEquals("k0", built.keys.first(), "覆盖不改位置：k0 仍在最前")
        assertEquals("999", built["k0"], "取值以最后一次 with 为准")
        assertEquals("k63", built.keys.last(), "最后装配的字段在最后")
        assertEquals(64, chain.size(), "size 与 build 结果一致")
    }

    /**
     * 前缀共享：从同一个 `base` 派生多个实例互不影响（共享父链而非复制整表）。
     *
     * 这是把 `with` 做到 O(1) 的前提——派生只是挂节点，父链被多方共享且不可变。
     */
    @Test
    fun `Fields 前缀可被多个派生实例共享且互不影响`() {
        val base = Fields.create().with("currency", "CNY").with("channel", "backfill")

        val first = base.with("signedAmount", 100)
        val second = base.with("signedAmount", -200)
        val third = base.with("signedAmount", 300).with("entryType", "TRANSFER")

        assertEquals("100", first.build()["signedAmount"])
        assertEquals("-200", second.build()["signedAmount"])
        assertEquals("TRANSFER", third.build()["entryType"])
        assertEquals(2, base.size(), "base 不应被任何派生实例污染")
        assertFalse(base.build().containsKey("signedAmount"), "base 自身不含派生字段")
    }

    // ------------------------------------------------------------------ Fields

    /**
     * `Fields` 有专用重载，可直接传给 `emitFields` / `dispatch`，**不必 `.build()`**。
     *
     * 这是封装的核心收益：调用点从「装 Map → 传 Map」变成一条链直接落地。
     * 也顺带钉住一个设计取舍：`Fields` **不实现 `Map`**——否则 Kotlin 消费方得把
     * `size()` 写成属性 `size`，与本仓 API 的方法调用形态不一致（同「布尔访问器一律 get 前缀」的理由）。
     */
    @Test
    fun `Fields 可直接传给便利入口而无需build`() {
        val sink = RecordingSink()

        // 注意：这里没有 .build()，走的是 emitFieldsReliable(..., Fields) 专用重载。
        assertTrue(
            sink.emitFieldsReliable(
                "economy",
                "tx-9",
                "settle",
                Fields.of("amount", BigDecimal("1E+18")).with("vip", false),
            ),
        )
        assertEquals(mapOf("amount" to "1000000000000000000", "vip" to "false"), sink.lastFields)

        val fields = Fields.of("a", 1).with("b", 2)
        assertEquals(2, fields.size(), "size 是方法调用，不是 Map 属性")
        assertFalse(fields.isEmpty())
        // 显式取 wire 形态时仍可 build，且与专用重载等价。
        assertEquals(mapOf("a" to "1", "b" to "2"), fields.build())
    }

    /** 链式构造：of → with → build，键值按装配顺序落地，取值按 toFields 的规则规范化。 */
    @Test
    fun `Fields 链式构造保留插入顺序`() {
        val built = Fields.of("ledgerId", "tx-1")
            .with("signedAmount", BigDecimal("1E+18"))
            .with("entryType", "BILL")
            .with("vip", true)
            .build()

        assertEquals(listOf("ledgerId", "signedAmount", "entryType", "vip"), built.keys.toList())
        assertEquals("tx-1", built["ledgerId"])
        assertEquals("1000000000000000000", built["signedAmount"], "BigDecimal 仍走 toPlainString")
        assertEquals("BILL", built["entryType"])
        assertEquals("true", built["vip"])
    }

    /** with 返回新实例：原实例必须保持不变，否则"公共前缀"会被后续派生污染。 */
    @Test
    fun `Fields 的 with 不修改原实例`() {
        val base = Fields.create().with("currency", "CNY")
        val derived = base.with("amount", 100)

        assertEquals(1, base.size())
        assertEquals(listOf("currency"), base.build().keys.toList())
        assertFalse(base.build().containsKey("amount"), "派生不应写回原实例")

        assertEquals(2, derived.size())
        assertEquals("100", derived.build()["amount"])
    }

    /** 回灌/批量场景：前缀只装配一次，循环内只补每条记录独有的字段，前缀不被污染。 */
    @Test
    fun `Fields 前缀可在循环中复用`() {
        val base = Fields.create().with("currency", "CNY").with("channel", "backfill")

        val built = (1..3).map { index -> base.with("seq", index).build() }

        assertEquals(listOf("1", "2", "3"), built.map { it["seq"] })
        built.forEach { assertEquals("CNY", it["currency"]) }
        assertEquals(2, base.size(), "前缀在循环中不应被污染")
        assertFalse(base.build().containsKey("seq"))
    }

    /** null 值按 toFields 的既有约定整键跳过，绝不写成 "null" 字面量。 */
    @Test
    fun `Fields 跳过 null 值`() {
        val built = Fields.create().with("note", null).with("amount", "1.00").build()

        assertFalse(built.containsKey("note"), "null 值应整键跳过")
        assertEquals(mapOf("amount" to "1.00"), built)
        assertTrue(Fields.of("note", null).isEmpty(), "只带 null 字段的构造结果应为空")
    }

    /** withIf：条件不成立时原样返回 this（既不新增字段，也不新建实例）。 */
    @Test
    fun `Fields 的 withIf 只在条件成立时携带字段`() {
        val base = Fields.of("a", 1)

        val skipped = base.withIf(false, "b", 2)
        val added = base.withIf(true, "b", 2)

        assertSame(base, skipped, "条件不成立时应原样返回 this")
        assertFalse(skipped.build().containsKey("b"))
        assertEquals(mapOf("a" to "1", "b" to "2"), added.build())
    }

    /** isEmpty/size 反映真实字段数；同名键后写覆盖前写，位置沿用首次出现（与 Map.put 一致）。 */
    @Test
    fun `Fields 的 isEmpty 与 size 以及重复键覆盖`() {
        assertTrue(Fields.create().isEmpty())
        assertEquals(0, Fields.create().size())

        val overwritten = Fields.create().with("a", 1).with("b", 2).with("a", 3)

        assertEquals(2, overwritten.size(), "同名键只算一个字段")
        assertEquals(listOf("a", "b"), overwritten.build().keys.toList(), "位置沿用首次出现")
        assertEquals("3", overwritten.build()["a"], "取值以最后一次 with 为准")
    }

    /** build 只负责转交 toFields：类型判错与消息格式（点名字段）必须与直接调用一致。 */
    @Test
    fun `Fields 的 build 委托 toFields 并点名字段`() {
        val draft = Fields.create().with("items", listOf("a", "b"))

        val error = assertThrows(IllegalArgumentException::class.java) { draft.build() }

        assertTrue(error.message!!.contains("items"), "消息应点名字段：${error.message}")
        assertEquals(Payloads.toFields(mapOf("a" to 1)), Fields.of("a", 1).build(), "规范化只有一份实现")
    }

    /** null 键属于调用方编程错误：with 当场判错，而不是留到 build 时才暴露。 */
    @Test
    fun `Fields 拒绝 null 键`() {
        assertThrows(IllegalArgumentException::class.java) { Fields.create().with(null, 1) }
        assertThrows(IllegalArgumentException::class.java) { Fields.of(null, "v") }
    }

    /** build 结果必须不可变：调用方拿到后当作只读快照直接上送，不该还能被改写。 */
    @Test
    fun `Fields 的 build 结果不可变`() {
        val built = Fields.of("a", 1).build()

        assertThrows(UnsupportedOperationException::class.java) {
            (built as MutableMap<String, String>)["b"] = "2"
        }
    }

    // ------------------------------------------------------ 浮点精度（Double/Float）

    /** Double 一律写出完整十进制串：1e18 不得是 1.0E18，1e-7 不得是 1.0E-7，0.1 不得展开尾数。 */
    @Test
    fun `toFields 的 Double 不出现科学计数法`() {
        val fields = Payloads.toFields(
            linkedMapOf(
                "huge" to 1e18,
                "tiny" to 1e-7,
                "ratio" to 0.1,
                "whole" to 1.0,
                "computed" to (0.1 + 0.2),
            ),
        )

        assertEquals("1000000000000000000", fields["huge"])
        assertEquals("0.0000001", fields["tiny"], "指数展开产生的多余尾零应被去掉")
        assertEquals("0.1", fields["ratio"], "BigDecimal.valueOf 保留最短往返表示，不得展开二进制尾数")
        assertEquals("1.0", fields["whole"], "浮点不应被悄悄降级成整数形态")
        assertEquals("0.30000000000000004", fields["computed"], "不做任何舍入：算出来的误差原样呈现")
        fields.values.forEach {
            assertFalse(it.contains('E') || it.contains('e'), "浮点文本不得含指数：$it")
        }
    }

    /** Float 先取自身的最短表示再转十进制：0.1f 必须是 0.1，不能是 float→double 的尾数噪声。 */
    @Test
    fun `toFields 的 Float 不引入 double 尾数噪声`() {
        val fields = Payloads.toFields(linkedMapOf("ratio" to 0.1f, "huge" to 1e20f))

        assertEquals("0.1", fields["ratio"])
        assertEquals("100000000000000000000", fields["huge"], "Float.toString 的 1.0E20 应展开成完整数字串")
    }

    /** toJson 与 toFields 必须共用同一套浮点格式，否则两个出口的数字形态会分叉。 */
    @Test
    fun `toJson 的 Double 与 Float 不出现科学计数法`() {
        val json = Payloads.toJson(linkedMapOf("huge" to 1e18, "tiny" to 1e-7, "ratio" to 0.1f))

        assertEquals("{\"huge\":1000000000000000000,\"tiny\":0.0000001,\"ratio\":0.1}", json)
        assertFalse(json.contains('E'), "JSON 数字不得含指数：$json")
    }

    /** NaN 与无穷既不是合法 JSON 数字、也不该进桥字段，两个出口都要判错并点名字段。 */
    @Test
    fun `NaN 与无穷在两个出口都抛异常`() {
        val badValues = listOf<Number>(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Float.NaN,
            Float.NEGATIVE_INFINITY,
        )

        badValues.forEach { bad ->
            val fromFields = assertThrows(IllegalArgumentException::class.java) {
                Payloads.toFields(mapOf("amount" to bad))
            }
            assertTrue(fromFields.message!!.contains("amount"), "toFields 消息应点名字段：${fromFields.message}")

            val fromJson = assertThrows(IllegalArgumentException::class.java) {
                Payloads.toJson(mapOf("amount" to bad))
            }
            assertTrue(fromJson.message!!.contains("amount"), "toJson 消息应点名字段：${fromJson.message}")
        }
    }

    // ---------------------------------------------------------------- 空载荷常量

    /** 空载荷常量与 empty() 是同一个不可变实例；null/空入参、空 Fields 也归一到这里。 */
    @Test
    fun `EMPTY_FIELDS 与 empty 是同一个不可变实例`() {
        assertTrue(Payloads.EMPTY_FIELDS.isEmpty())
        assertSame(Payloads.EMPTY_FIELDS, Payloads.empty())
        assertSame(Payloads.EMPTY_FIELDS, Payloads.toFields(null))
        assertSame(Payloads.EMPTY_FIELDS, Fields.create().build())
        assertThrows(UnsupportedOperationException::class.java) {
            (Payloads.EMPTY_FIELDS as MutableMap<String, String>)["k"] = "v"
        }
    }

    /** 空载荷在两个便利入口上都能直接使用：不必写 Collections.emptyMap()，也不该传 null。 */
    @Test
    fun `empty 可直接用于 dispatch 与 emit`() {
        val host = RecordingHost()

        host.dispatch("economy", "ping", Payloads.empty())

        assertEquals("{}", host.lastPayloadJson)

        val sink = RecordingSink()

        assertTrue(sink.emitFields("economy", "tx-1", "settle", Payloads.EMPTY_FIELDS))
        assertEquals(emptyMap<String, String>(), sink.lastFields)
    }
}
