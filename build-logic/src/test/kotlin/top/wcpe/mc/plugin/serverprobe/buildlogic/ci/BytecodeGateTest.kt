package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Java 8 字节码门禁的判定逻辑。
 *
 * 输入由任务声明接线后传入（受检 class 目录 + 发行 jar），本测试直接构造这两类输入，
 * 覆盖：major version 解析、只扫传入的 main 目录、发行 jar 的豁免项（multi-release 目录与
 * 模块描述符）、产物缺失也算失败、超版本即违规。
 */
class BytecodeGateTest {

    /** 构造一个只含文件头的伪 class（真实内容不参与判定）。 */
    private fun classHeader(major: Int): ByteArray = ByteArray(8).also { header ->
        header[0] = 0xCA.toByte()
        header[1] = 0xFE.toByte()
        header[2] = 0xBA.toByte()
        header[3] = 0xBE.toByte()
        header[4] = 0
        header[5] = 0
        header[6] = ((major shr 8) and 0xFF).toByte()
        header[7] = (major and 0xFF).toByte()
    }

    private fun writeClass(file: File, major: Int) {
        file.parentFile.mkdirs()
        file.writeBytes(classHeader(major))
    }

    private fun writeJar(jar: File, entries: Map<String, Int>) {
        jar.parentFile.mkdirs()
        ZipOutputStream(jar.outputStream()).use { zip ->
            entries.forEach { (name, major) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(classHeader(major))
                zip.closeEntry()
            }
        }
    }

    /** 每个受检模块的 main 源集输出目录（与实际接线一致）。 */
    private fun mainClassRoots(root: File): List<File> =
        BytecodeGate.MODULES.map { module -> File(root, "$module/build/classes/java/main") }

    @DisplayName("major version 取 class 头第 7~8 字节的 big-endian 值")
    @Test
    fun `解析 major version`() {
        assertEquals(52, BytecodeGate.majorOf(classHeader(52)))
        assertEquals(61, BytecodeGate.majorOf(classHeader(61)))
        assertEquals(65, BytecodeGate.majorOf(classHeader(65)))
    }

    @DisplayName("只扫传入的 main 目录；jar 内 multi-release 与 module-info 豁免")
    @Test
    fun `扫描 main 目录并豁免 jar 内特殊条目`(@TempDir root: File) {
        val classRoots = mainClassRoots(root)
        classRoots.forEach { dir -> writeClass(File(dir, "Demo.class"), 52) }
        // test 源集产物不允许进入受检列表（接线只给 main 目录，这里额外放一份证明不会被漏进来）
        writeClass(File(root, "project/core/build/classes/java/test/DemoTest.class"), 61)
        val jar = File(root, "plugin/build/libs/ServerProbe-1.0.jar")
        writeJar(
            jar,
            mapOf(
                "top/demo/Main.class" to 52,
                "META-INF/versions/9/top/demo/Main.class" to 65,
                "module-info.class" to 65,
            ),
        )

        val outcome = BytecodeGate.evaluate(classRoots, listOf(jar), BytecodeGate.MODULES, root)

        assertTrue(outcome.passed(), "无违规且产物齐备时应通过，实际缺失=${outcome.missing}")
        assertEquals(
            BytecodeGate.MODULES.size + 1,
            outcome.entries.size,
            "每个模块一个 main class + jar 内一个 class；test 源集与豁免项不应计入",
        )
        assertTrue(
            outcome.entries.any { it.displayPath == "plugin/build/libs/ServerProbe-1.0.jar!top/demo/Main.class" },
            "jar 内普通 class 必须进入受检列表",
        )
        assertTrue(
            outcome.entries.none { it.displayPath.contains("/test/") },
            "test 源集不应被扫描",
        )
    }

    @DisplayName("产物缺失计入 missing：静默变绿比失败更危险")
    @Test
    fun `产物缺失即不通过`(@TempDir root: File) {
        val classRoots = mainClassRoots(root)
        // 只产出一个模块的 class，其余目录不存在
        writeClass(File(classRoots.first(), "Demo.class"), 52)

        val outcome = BytecodeGate.evaluate(classRoots, emptyList(), BytecodeGate.MODULES, root)

        assertFalse(outcome.passed(), "缺少其它受检模块与发行 jar 时不应通过")
        assertTrue(
            outcome.missing.any { it.startsWith("api/build/classes") },
            "实际缺失=${outcome.missing}",
        )
        assertTrue(outcome.missing.contains("plugin/build/libs/ServerProbe-*.jar"), "实际缺失=${outcome.missing}")
    }

    @DisplayName("major version 超过 52 计为违规")
    @Test
    fun `超版本计为违规`(@TempDir root: File) {
        val classRoots = mainClassRoots(root)
        classRoots.forEach { dir -> writeClass(File(dir, "Demo.class"), 52) }
        writeClass(File(classRoots.first(), "TooNew.class"), 61)
        val jar = File(root, "plugin/build/libs/ServerProbe-1.0.jar")
        writeJar(jar, mapOf("top/demo/Main.class" to 52))

        val outcome = BytecodeGate.evaluate(classRoots, listOf(jar), BytecodeGate.MODULES, root)

        assertFalse(outcome.passed())
        assertEquals(listOf(61), outcome.violations.map { it.major })
    }
}
