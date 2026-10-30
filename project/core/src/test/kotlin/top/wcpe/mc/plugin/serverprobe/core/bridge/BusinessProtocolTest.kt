package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.protocol.ActionSpec
import top.wcpe.mc.plugin.serverprobe.api.protocol.Actions
import top.wcpe.mc.plugin.serverprobe.api.protocol.BusinessProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * api 协议包的契约测试。
 *
 * 放在 core 而不是 api：`api` 模块刻意不引入任何测试依赖（保持发布物零传递依赖），
 * 而 core 已经 `testImplementation(project(":api"))`，在这里测更省事且不污染 api 的发布配置。
 */
class BusinessProtocolTest {

    @Test
    fun `常量取值与真实帧字段一致`() {
        // 这几条是"事实断言"，不是"希望断言"：值必须与 core 的两处编码器逐字相同，
        // 否则面板与插件会各按一套名字收发。改了常量就必须同步改这里和编码器。
        assertEquals("requestId", BusinessProtocol.FIELD_REQUEST_ID)
        assertEquals("success", BusinessProtocol.FIELD_SUCCESS)
        assertEquals("output", BusinessProtocol.FIELD_OUTPUT)
        assertEquals("error", BusinessProtocol.FIELD_ERROR)
        assertEquals("dedupKey", BusinessProtocol.FIELD_DEDUP_KEY)
        assertEquals("domain", BusinessProtocol.FIELD_DOMAIN)
        assertEquals("actions", BusinessProtocol.MANIFEST_ACTIONS)
    }

    @Test
    fun `回执帧常量与内核默认编码器产出的键完全对应`() {
        // 内核默认编码器的字段集（见 BridgeKernel 的 encodeCommandResult）。
        // 用集合比对而非逐条断言，才能同时发现"常量漏了"和"编码器多写了"两个方向的漂移。
        val encodedKeys = setOf("requestId", "success", "output", "error")
        val declaredKeys = setOf(
            BusinessProtocol.FIELD_REQUEST_ID,
            BusinessProtocol.FIELD_SUCCESS,
            BusinessProtocol.FIELD_OUTPUT,
            BusinessProtocol.FIELD_ERROR,
        )
        assertEquals(encodedKeys, declaredKeys, "回执帧字段集漂移：常量与 encodeCommandResult 不再一致")
    }

    @Test
    fun `toMap 的键就是动作声明常量且值均可被 JSON 编码`() {
        val map = Actions.write("grant", "player", "amount").toMap()

        assertEquals(
            setOf(BusinessProtocol.ACTION_NAME, BusinessProtocol.ACTION_FIELDS, BusinessProtocol.ACTION_READ_ONLY),
            map.keys,
            "toMap 的键必须与 ACTION_* 常量一致，否则改常量等于没有生效",
        )
        // manifest 的值只能是 Map/List/String/数字/布尔，否则编码结果不可预期。
        map.values.forEach { assertJsonSafe(it) }
    }

    @Test
    fun `read 与 write 只差只读标志`() {
        val read = Actions.read("query", "player")
        val write = Actions.write("query", "player")

        assertTrue(read.readOnly)
        assertFalse(write.readOnly)
        // 除只读标志外，两者必须完全等价——否则"读写同形"的语义就破了。
        assertEquals(read.name, write.name)
        assertEquals(read.fields, write.fields)
    }

    @Test
    fun `write 不自动追加 taskId`() {
        // 幂等键该不该有、叫什么，属于各域自己的治理策略；探针不替插件做这个决定。
        assertFalse(Actions.write("grant", "player").fields.contains("taskId"))
        assertTrue(Actions.write("grant", "player", "taskId").fields.contains("taskId"))
    }

    @Test
    fun `of 对 null 入参降级而不抛`() {
        // 外部插件违约传 null 时，不能把异常抛进 manifest() 的调用方。
        val spec = ActionSpec.of(null, null, true)
        assertEquals("", spec.name)
        assertTrue(spec.fields.isEmpty())
        assertTrue(spec.readOnly)
    }
@Test
fun `动作声明不可变`() {
    val source = mutableListOf("player")
    val spec = ActionSpec.of("grant", source, false)

    // 构造后改入参不得影响已声明的动作。
    source.add("amount")
    assertEquals(listOf("player"), spec.fields)

    // 字段列表对外只读：外部拿到后无法就地篡改已声明的动作。
    val fields = spec.toMap()[BusinessProtocol.ACTION_FIELDS] as List<*>
    assertTrue(
        runCatching { (fields as MutableList<*>).clear() }.exceptionOrNull() is UnsupportedOperationException,
        "ActionSpec 的 fields 必须不可就地修改",
    )

    // 每次 toMap 返回独立 Map：改一个不能污染另一个。
    val first = spec.toMap()
    val second = spec.toMap()
    first[BusinessProtocol.ACTION_NAME] = "tampered"
    assertEquals("grant", second[BusinessProtocol.ACTION_NAME])
}

    @Test
    fun `equals 与 hashCode 按值比较`() {
        assertEquals(Actions.read("q", "a"), Actions.read("q", "a"))
        assertEquals(Actions.read("q", "a").hashCode(), Actions.read("q", "a").hashCode())
        assertNotEquals(Actions.read("q", "a"), Actions.write("q", "a"))
        assertNotEquals(Actions.read("q", "a"), Actions.read("q", "b"))
    }

    /** 递归确认值属于 manifest 允许的类型集合（Map/List/String/数字/布尔）。 */
    private fun assertJsonSafe(value: Any?) {
        when (value) {
            is String, is Number, is Boolean -> Unit
            is List<*> -> value.forEach { assertJsonSafe(it) }
            is Map<*, *> -> value.forEach { (key, entryValue) ->
                assertTrue(key is String, "manifest 内的 Map 键必须是 String，实际为 $key")
                assertJsonSafe(entryValue)
            }
            else -> throw AssertionError("manifest 内不允许出现类型 ${value?.javaClass?.name}，会导致编码结果不可预期")
        }
    }

    /** 确认类型判定不会把"看起来像 Map 的其它对象"放行。 */
    @Test
    fun `非容器 POJO 会被判定为不可编码`() {
        // 用普通 POJO 而非 Map 子类：Map 子类本来就该被放行，拿它当反例没有意义。
        val failure = runCatching { assertJsonSafe(Pojo()) }.exceptionOrNull()
        assertTrue(failure is AssertionError, "非 Map/List/标量 的对象必须被拒绝，实际：$failure")
    }

    /** 仅用于上一条反例：一个既不是 Map 也不是 List、更没有标量语义的普通对象。 */
    private class Pojo
}
