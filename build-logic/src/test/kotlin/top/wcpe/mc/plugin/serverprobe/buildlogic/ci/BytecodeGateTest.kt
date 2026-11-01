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
 * 覆盖点与原脚本的每个分支一一对应：major version 解析、只扫 main 源集、
 * 发行 jar 的豁免项（multi-release 目录与模块描述符）、产物缺失也算失败、超版本即违规。
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

    @DisplayName("major version 取 class 头第 7~8 字节的 big-endian 值")
    @Test
    fun `解析 major version`() {
        assertEquals(52, BytecodeGate.majorOf(classHeader(52)))
        assertEquals(61, BytecodeGate.majorOf(classHeader(61)))
        assertEquals(65, BytecodeGate.majorOf(classHeader(65)))
    }

    @DisplayName("只扫 main 源集；jar 内 multi-release 与 module-info 豁免")
    @Test
    fun `扫描 main 源集并豁免 jar 内特殊条目`(@TempDir root: File) {
        BytecodeGate.MODULES.forEach { module ->
            writeClass(File(root, "$module/build/classes/java/main/Demo.class"), 52)
        }
        writeClass(File(root, "project/core/build/classes/java/test/DemoTest.class"), 61)
        writeJar(
            File(root, "plugin/build/libs/ServerProbe-1.0.jar"),
            mapOf(
                "top/demo/Main.class" to 52,
                "META-INF/versions/9/top/demo/Main.class" to 65,
                "module-info.class" to 65,
            ),
        )

        val outcome = BytecodeGate.scan(root)

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
        writeClass(File(root, "project/core/build/classes/java/main/Demo.class"), 52)

        val outcome = BytecodeGate.scan(root)

        assertFalse(outcome.passed(), "缺少其它受检模块与发行 jar 时不应通过")
        assertTrue(outcome.missing.any { it.startsWith("api/build/classes/") }, "实际缺失=${outcome.missing}")
        assertTrue(outcome.missing.contains("plugin/build/libs/ServerProbe-*.jar"), "实际缺失=${outcome.missing}")
    }

    @DisplayName("major version 超过 52 计为违规")
    @Test
    fun `超版本计为违规`(@TempDir root: File) {
        writeClass(File(root, "project/core/build/classes/java/main/Demo.class"), 61)
        writeJar(File(root, "plugin/build/libs/ServerProbe-1.0.jar"), mapOf("top/demo/Main.class" to 52))

        val outcome = BytecodeGate.scan(root)

        assertFalse(outcome.passed())
        assertEquals(listOf(61), outcome.violations.map { it.major })
    }
}
