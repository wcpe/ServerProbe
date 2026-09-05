package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** 将 E2E 判定原子写入 mc-testkit 结果文件。 */
class ScenarioResultWriter(private val resultFile: File) {

    fun pass(message: String, details: Map<String, String>) = write(STATUS_PASS, message, details)

    fun fail(message: String) = write(STATUS_FAIL, message, emptyMap())

    private fun write(status: String, message: String, details: Map<String, String>) {
        resultFile.parentFile?.mkdirs()
        val temporary = File.createTempFile("result-", ".tmp", resultFile.parentFile)
        temporary.outputStream().use { output ->
            Properties().apply {
                details.forEach { (key, value) -> setProperty(key, value) }
                setProperty("status", status)
                setProperty("message", message)
                store(output, "mc-testkit E2E 结果")
            }
        }
        moveAtomically(temporary)
    }

    private fun moveAtomically(temporary: File) {
        try {
            Files.move(temporary.toPath(), resultFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), resultFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        private const val STATUS_PASS = "PASS"
        private const val STATUS_FAIL = "FAIL"
    }
}
