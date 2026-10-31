package top.wcpe.mc.plugin.serverprobe.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ManagedConfigValues] 单测（FR-44 的 `values` / `removals` 语义）。
 *
 * 这层是"下发值怎么合并、删除怎么落地"的唯一实现，被应用链路与两代快照回退共用，
 * 故三种存放形态（扁平点分键 / 点分前缀对象 / 逐层嵌套）都要能正确删除。
 */
class ManagedConfigValuesTest {

    /** 合并：下发键覆盖同名键，其余保持；键顺序保持基线顺序。 */
    @Test
    fun `合并时下发键覆盖同名键`() {
        val base = linkedMapOf<String, Any?>("a" to 1, "b" to 2)

        val merged = ManagedConfigValues.merge(base, linkedMapOf("b" to 20, "c" to 30), emptyList())

        assertEquals(linkedMapOf("a" to 1, "b" to 20, "c" to 30), merged)
        assertEquals(listOf("a", "b", "c"), merged.keys.toList(), "基线键顺序应保持，新键追加在后")
    }

    /** 合并时 `null` 值被忽略：显式删除只走 `removals`，不在表里留 null。 */
    @Test
    fun `合并时忽略 null 值`() {
        val merged = ManagedConfigValues.merge(mapOf("a" to 1), mapOf("a" to null), emptyList())

        assertEquals(mapOf("a" to 1), merged, "null 不应覆盖原值，也不应留 null")
        assertFalse(merged.containsValue(null))
    }

    /** 删除扁平点分键（控制面常见形态：`alert.rules` 作为单个键存放）。 */
    @Test
    fun `删除扁平点分键`() {
        val base = linkedMapOf<String, Any?>("alert.rules" to mapOf("tps-warn" to 1), "history-capacity" to 360)

        val removed = ManagedConfigValues.remove(base, "alert.rules")

        assertEquals(mapOf("history-capacity" to 360), removed)
    }

    /** 删除**点分前缀对象**下的子键：表里是 `alert.rules`，待删 `alert.rules.tps-warn`。 */
    @Test
    fun `删除点分前缀对象下的子键`() {
        val base = linkedMapOf<String, Any?>(
            "alert.rules" to linkedMapOf("tps-warn" to 1, "lag-warn" to 2),
        )

        val removed = ManagedConfigValues.remove(base, "alert.rules.tps-warn")

        @Suppress("UNCHECKED_CAST")
        val rules = removed["alert.rules"] as Map<String, Any?>
        assertEquals(mapOf("lag-warn" to 2), rules, "只删目标子键，兄弟键必须保留")
    }

    /** 删除**逐层嵌套**结构里的子键。 */
    @Test
    fun `删除逐层嵌套的子键`() {
        val base = linkedMapOf<String, Any?>("alert" to linkedMapOf("rules" to linkedMapOf("tps-warn" to 1)))

        val removed = ManagedConfigValues.remove(base, "alert.rules.tps-warn")

        @Suppress("UNCHECKED_CAST")
        val alert = removed["alert"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val rules = alert["rules"] as Map<String, Any?>
        assertTrue(rules.isEmpty(), "目标子键应被删除，实际:$rules")
    }

    /** 未命中：返回入参本身（调用方可据此判断"没删掉任何东西"，避免无意义地换新表）。 */
    @Test
    fun `未命中时返回原表`() {
        val base = mapOf<String, Any?>("a" to 1)

        assertSame(base, ManagedConfigValues.remove(base, "not.there"))
    }

    /** 批量删除按序生效；空列表不改动。 */
    @Test
    fun `批量删除按序生效`() {
        val base = linkedMapOf<String, Any?>("a" to 1, "b" to 2, "c" to 3)

        assertEquals(mapOf("c" to 3), ManagedConfigValues.removeAll(base, listOf("a", "b")))
        assertSame(base, ManagedConfigValues.removeAll(base, emptyList()))
    }

    /** 所有方法都不修改入参（两代快照是不可变对象，读方可能正持有旧表）。 */
    @Test
    fun `不改动入参`() {
        val base = linkedMapOf<String, Any?>("a" to 1, "alert.rules" to linkedMapOf("x" to 2))

        ManagedConfigValues.merge(base, linkedMapOf("b" to 3), listOf("a"))
        ManagedConfigValues.remove(base, "alert.rules.x")

        assertEquals(linkedMapOf<String, Any?>("a" to 1, "alert.rules" to linkedMapOf("x" to 2)), base)
    }
}
