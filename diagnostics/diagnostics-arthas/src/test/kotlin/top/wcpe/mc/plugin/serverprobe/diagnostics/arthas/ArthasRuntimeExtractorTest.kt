package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class ArthasRuntimeExtractorTest {

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `Java8 至 Java16 选择 3 系运行时而 Java17 及以上选择 4 系运行时`() {
        assertEquals("3.1.1", ArthasRuntimeSelector.select("1.8", "3.1.1", "4.3.2"))
        assertEquals("3.1.1", ArthasRuntimeSelector.select("9", "3.1.1", "4.3.2"))
        assertEquals("3.1.1", ArthasRuntimeSelector.select("16", "3.1.1", "4.3.2"))
        assertEquals("4.3.2", ArthasRuntimeSelector.select("17", "3.1.1", "4.3.2"))
        assertEquals("4.3.2", ArthasRuntimeSelector.select("21", "3.1.1", "4.3.2"))
    }

    @Test
    fun `双运行时描述按目标 JVM 选择对应版本`() {
        val descriptor = "legacy-version=3.1.1\nmodern-version=4.3.2\n".toByteArray()
        val reader = { _: String -> descriptor.inputStream() }

        assertEquals("3.1.1", ArthasRuntimeLayout.resolveVersion(reader, "1.8"))
        assertEquals("4.3.2", ArthasRuntimeLayout.resolveVersion(reader, "21"))
    }

    @Test
    fun `现代 Bootstrap 显式禁用监听且不配置 Tunnel`() {
        val parameters = ArthasModernBootstrapSettings.parameters(temporaryDirectory)

        assertEquals("-1", parameters.getValue("arthas.telnetPort"))
        assertEquals("-1", parameters.getValue("arthas.httpPort"))
        assertEquals("", parameters.getValue("arthas.mcpEndpoint"))
        assertFalse(parameters.containsKey("arthas.tunnelServer"))
    }

    @Test
    fun `提取官方运行闭包并校验哈希`() {
        val resources = runtimeResources()
        val result = ArthasRuntimeExtractor { path -> resources[path]?.inputStream() }.extract(temporaryDirectory)

        assertTrue(result is ArthasRuntimeExtraction.Ready)
        ArthasRuntimeLayout.requiredJarNames.forEach { name ->
            assertTrue(Files.exists(temporaryDirectory.resolve(ArthasRuntimeLayout.defaultVersion).resolve(name)))
        }
        assertTrue(Files.exists(temporaryDirectory.resolve(ArthasRuntimeLayout.defaultVersion).resolve("LICENSE")))
        assertTrue(Files.exists(temporaryDirectory.resolve(ArthasRuntimeLayout.defaultVersion).resolve("NOTICE")))
    }

    @Test
    fun `已存在且校验通过时不重复覆盖`() {
        val resources = runtimeResources()
        val extractor = ArthasRuntimeExtractor { path -> resources[path]?.inputStream() }
        extractor.extract(temporaryDirectory)
        val core = temporaryDirectory.resolve(ArthasRuntimeLayout.defaultVersion).resolve("arthas-core.jar")
        Files.write(core, "已存在的可信文件".toByteArray())
        resources[ArthasRuntimeLayout.resourcePath(ArthasRuntimeLayout.defaultVersion, "arthas-core.jar")] = "已存在的可信文件".toByteArray()
        val checksumPath = ArthasRuntimeLayout.resourcePath(ArthasRuntimeLayout.defaultVersion, ArthasRuntimeLayout.checksumFileName)
        resources[checksumPath] = checksumManifest(resources)

        val result = extractor.extract(temporaryDirectory)

        assertTrue(result is ArthasRuntimeExtraction.Ready)
        assertEquals("已存在的可信文件", String(Files.readAllBytes(core)))
    }

    @Test
    fun `资源哈希不匹配时拒绝提取`() {
        val resources = runtimeResources()
        val manifestPath = ArthasRuntimeLayout.resourcePath(ArthasRuntimeLayout.defaultVersion, ArthasRuntimeLayout.checksumFileName)
        resources[manifestPath] = String(checksumManifest(resources))
            .replaceFirst(Regex("arthas-core\\.jar=[0-9a-f]+"), "arthas-core.jar=0000")
            .toByteArray()

        val result = ArthasRuntimeExtractor { path -> resources[path]?.inputStream() }.extract(temporaryDirectory)

        assertTrue(result is ArthasRuntimeExtraction.Failed)
        assertFalse(Files.exists(temporaryDirectory.resolve(ArthasRuntimeLayout.defaultVersion).resolve("arthas-core.jar")))
    }

    private fun runtimeResources(): MutableMap<String, ByteArray> {
        val resources = linkedMapOf<String, ByteArray>()
        resources["META-INF/serverprobe/arthas/runtime.properties"] = "version=${ArthasRuntimeLayout.defaultVersion}\n".toByteArray()
        ArthasRuntimeLayout.requiredJarNames.forEach { name ->
            resources[ArthasRuntimeLayout.resourcePath(ArthasRuntimeLayout.defaultVersion, name)] = "官方-$name".toByteArray()
        }
        resources[ArthasRuntimeLayout.resourcePath(ArthasRuntimeLayout.defaultVersion, "LICENSE")] = "许可证".toByteArray()
        resources[ArthasRuntimeLayout.resourcePath(ArthasRuntimeLayout.defaultVersion, "NOTICE")] = "声明".toByteArray()
        val checksumPath = ArthasRuntimeLayout.resourcePath(ArthasRuntimeLayout.defaultVersion, ArthasRuntimeLayout.checksumFileName)
        resources[checksumPath] = checksumManifest(resources)
        return resources
    }

    private fun checksumManifest(resources: Map<String, ByteArray>): ByteArray {
        return ArthasRuntimeLayout.requiredJarNames.joinToString("\n") { name ->
            "$name=${hash(resources.getValue(ArthasRuntimeLayout.resourcePath(ArthasRuntimeLayout.defaultVersion, name)))}"
        }.toByteArray()
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
