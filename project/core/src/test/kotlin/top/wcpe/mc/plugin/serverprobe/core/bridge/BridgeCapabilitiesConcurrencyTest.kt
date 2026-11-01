package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 能力表的并发与撤回语义。
 *
 * 写入发生在组件装配线程（MCP 挂载等），读取发生在桥线程（每次 `hello` 取快照）。
 * 过去用 `LinkedHashSet`：并发写入 + 遍历会抛 `ConcurrentModificationException`，
 * 而它落在 hello 序列化路径上，表现为“连不上”。
 */
class BridgeCapabilitiesConcurrencyTest {

    /** 多线程声明能力 + 同时取快照：不抛异常、不丢更新。 */
    @Test
    fun `并发声明能力不丢更新也不抛异常`() {
        val names = (0 until 8).map { index -> "test_cap_$index" }
        val workers = names.map { name ->
            Thread {
                repeat(100) {
                    BridgeCapabilities.addCapability(name)
                    BridgeCapabilities.snapshot()
                }
            }
        }
        try {
            workers.forEach { it.start() }
            workers.forEach { it.join() }

            val snapshot = BridgeCapabilities.snapshot()
            names.forEach { name ->
                assertTrue(snapshot.contains(name), "并发声明后 $name 不应丢失，实际:$snapshot")
            }
        } finally {
            names.forEach { BridgeCapabilities.removeCapability(it) }
        }
    }

    /** 撤回后快照不再包含该项（供组件卸载使用）。 */
    @Test
    fun `撤回能力后快照不再包含`() {
        val name = "test_cap_removable"
        try {
            BridgeCapabilities.addCapability(name)
            assertTrue(BridgeCapabilities.snapshot().contains(name))

            BridgeCapabilities.removeCapability(name)
            assertFalse(BridgeCapabilities.snapshot().contains(name), "撤回后仍声明能力属于谎报")
        } finally {
            BridgeCapabilities.removeCapability(name)
        }
    }
}
