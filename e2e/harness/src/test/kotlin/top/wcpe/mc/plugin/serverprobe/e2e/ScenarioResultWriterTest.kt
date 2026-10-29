package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ScenarioResultWriter 的断言汇总契约测试。 */
class ScenarioResultWriterTest {

    private val workDir: File = Files.createTempDirectory("scenario-result-writer").toFile()
    private val resultFile = File(workDir, "result.properties")

    @AfterTest
    fun cleanUp() {
        workDir.deleteRecursively()
    }

    @Test
    fun `断言全通过时状态为 PASS 且失败计数为零`() {
        val writer = ScenarioResultWriter(resultFile)
        writer.assertTrue("bridge.snapshot.present", true)
        writer.assertFalse("bridge.snapshot.pending", false)
        writer.assertTrue("bridge.channel.open", true)
        writer.finish("桥接改造场景完成")

        val properties = load()
        assertEquals("PASS", properties.getProperty("status"))
        assertEquals("桥接改造场景完成", properties.getProperty("message"))
        assertEquals("3", properties.getProperty("assertions.total"))
        assertEquals("3", properties.getProperty("assertions.passed"))
        assertEquals("0", properties.getProperty("assertions.failed"))
        assertEquals("", properties.getProperty("assertions.failed.names"))
    }

    @Test
    fun `存在失败断言时状态为 FAIL 且失败名可检索`() {
        val writer = ScenarioResultWriter(resultFile)
        writer.assertTrue("bridge.snapshot.present", true)
        writer.assertTrue("bridge.snapshot.pending", false, "期望 true，实际 false")
        writer.finish("场景执行结束")

        val properties = load()
        assertEquals("FAIL", properties.getProperty("status"))
        assertEquals("2", properties.getProperty("assertions.total"))
        assertEquals("1", properties.getProperty("assertions.passed"))
        assertEquals("1", properties.getProperty("assertions.failed"))
        assertEquals("bridge.snapshot.pending", properties.getProperty("assertions.failed.names"))
        // 失败断言名与实际值都要出现在 message 里，便于直接定位。
        assertTrue(properties.getProperty("message").contains("bridge.snapshot.pending"))
        assertTrue(properties.getProperty("message").contains("期望 true，实际 false"))
    }

    @Test
    fun `多次 finish 幂等且以最后一次内容为准`() {
        val writer = ScenarioResultWriter(resultFile)
        writer.assertTrue("bridge.first", true)
        writer.finish("第一次收尾")
        assertEquals("PASS", load().getProperty("status"))

        writer.assertTrue("bridge.second", false, "实际值 2")
        writer.finish("第二次收尾")

        val properties = load()
        assertEquals("FAIL", properties.getProperty("status"))
        assertEquals("第二次收尾（失败断言: bridge.second(实际值 2)）", properties.getProperty("message"))
        assertEquals("2", properties.getProperty("assertions.total"))
        assertEquals("bridge.second", properties.getProperty("assertions.failed.names"))
        // 原子写只留下最终结果文件，不残留临时文件。
        assertEquals(listOf("result.properties"), workDir.list()!!.sorted())
    }

    @Test
    fun `assertTrue 与 assertFalse 的返回值可直接用于提前 return`() {
        val writer = ScenarioResultWriter(resultFile)
        assertTrue(writer.assertTrue("true.when.true", true))
        assertFalse(writer.assertTrue("true.when.false", false))

        assertTrue(writer.assertFalse("false.when.false", false))
        assertFalse(writer.assertFalse("false.when.true", true))

        // 上面 4 条登记中成立 2 条（true.when.true、false.when.false）。
        writer.finish("返回值检查")
        val properties = load()
        assertEquals("4", properties.getProperty("assertions.total"))
        assertEquals("2", properties.getProperty("assertions.passed"))
        assertEquals("true.when.false,false.when.true", properties.getProperty("assertions.failed.names"))
    }

    @Test
    fun `同名断言重复登记按最后一次统计且不累加总数`() {
        val writer = ScenarioResultWriter(resultFile)
        writer.assertTrue("bridge.retry", false, "第一次失败")
        writer.assertTrue("bridge.retry", true)
        writer.finish("重试后收尾")

        val properties = load()
        assertEquals("PASS", properties.getProperty("status"))
        assertEquals("1", properties.getProperty("assertions.total"))
        assertEquals("0", properties.getProperty("assertions.failed"))
        assertEquals("", properties.getProperty("assertions.failed.names"))
    }

    @Test
    fun `并发登记断言不丢失也不串序`() {
        val writer = ScenarioResultWriter(resultFile)
        val threadCount = 4
        val perThread = 100
        val startGate = CountDownLatch(1)
        val threads = (0 until threadCount).map { threadIndex ->
            Thread {
                startGate.await()
                repeat(perThread) { index ->
                    writer.assertTrue("fixture.$threadIndex.$index", index % 2 == 0, "thread=$threadIndex index=$index")
                }
            }.apply { start() }
        }
        startGate.countDown()
        threads.forEach { it.join() }
        writer.finish("并发登记收尾")

        val properties = load()
        assertEquals((threadCount * perThread).toString(), properties.getProperty("assertions.total"))
        assertEquals((threadCount * perThread / 2).toString(), properties.getProperty("assertions.passed"))
        assertEquals("FAIL", properties.getProperty("status"))
    }

    private fun load(): Properties = Properties().apply {
        resultFile.inputStream().use { load(it) }
    }
}
