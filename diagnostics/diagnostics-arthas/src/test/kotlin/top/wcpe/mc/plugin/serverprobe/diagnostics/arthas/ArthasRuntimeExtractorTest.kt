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
        resources[checksumPath] = checksumManifest(ArthasRuntimeLayout.defaultVersion, resources)

        val result = extractor.extract(temporaryDirectory)

        assertTrue(result is ArthasRuntimeExtraction.Ready)
        assertEquals("已存在的可信文件", String(Files.readAllBytes(core)))
    }

    /**
     * 已解压目录里**缺 JNI 库**时必须重解，不能因为 jar 哈希对得上就跳过。
     *
     * 回归防线：真机踩过——升级只加了新原生库、四个 jar 一个字节没变，旧目录
     * 因「jar 哈希全中 → 跳过解压」而永远不落新库，`lib/` 永不出现，vmtool 恒报
     * `no ArthasJniLibrary in java.library.path`。根因就是 requiredLibraries 当时
     * 没把 JNI 库算进去，isVerified 根本不看它。
     */
    @Test
    fun `已解压目录缺 JNI 库时会重新解压补全`() {
        // JNI 库只在现代（4.x）闭包里；测试必须走 4.x 分支。
        // 用现代版本直接构造资源，避开依赖跑测试那台机器的 JVM 版本。
        val modern = "4.3.2"
        val resources = runtimeResources(modern)
        resources["META-INF/serverprobe/arthas/runtime.properties"] =
            "version=$modern\n".toByteArray()
        val extractor = ArthasRuntimeExtractor { path -> resources[path]?.inputStream() }
        extractor.extract(temporaryDirectory)
        val versionDir = temporaryDirectory.resolve("4.3.2")
        val jni = versionDir.resolve("lib").resolve("libArthasJniLibrary-x64.so")
        assertTrue(Files.exists(jni), "首次提取应已落 lib/ 下的 JNI 库")

        // 模拟「旧版本解压出来的目录」：删掉 JNI 库，其他文件全部保留且哈希不变。
        Files.delete(jni)
        assertFalse(Files.exists(jni))

        val result = extractor.extract(temporaryDirectory)

        assertTrue(result is ArthasRuntimeExtraction.Ready)
        assertTrue(Files.exists(jni), "缺库时必须重解并补回 JNI 库，而不是因为 jar 哈希匹配就跳过")
    }

    @Test
    fun `资源哈希不匹配时拒绝提取`() {
        val resources = runtimeResources()
        val manifestPath = ArthasRuntimeLayout.resourcePath(ArthasRuntimeLayout.defaultVersion, ArthasRuntimeLayout.checksumFileName)
        resources[manifestPath] = String(checksumManifest(ArthasRuntimeLayout.defaultVersion, resources))
            .replaceFirst(Regex("arthas-core\\.jar=[0-9a-f]+"), "arthas-core.jar=0000")
            .toByteArray()

        val result = ArthasRuntimeExtractor { path -> resources[path]?.inputStream() }.extract(temporaryDirectory)

        assertTrue(result is ArthasRuntimeExtraction.Failed)
        assertFalse(Files.exists(temporaryDirectory.resolve(ArthasRuntimeLayout.defaultVersion).resolve("arthas-core.jar")))
    }

    private fun runtimeResources(version: String = ArthasRuntimeLayout.defaultVersion): MutableMap<String, ByteArray> {
        val resources = linkedMapOf<String, ByteArray>()
        resources["META-INF/serverprobe/arthas/runtime.properties"] = "version=$version\n".toByteArray()
        ArthasRuntimeLayout.requiredJarNames.forEach { name ->
            resources[ArthasRuntimeLayout.resourcePath(version, name)] = "官方-$name".toByteArray()
        }
        ArthasRuntimeLayout.requiredLibraries(version).forEach { name ->
            val relative = ArthasRuntimeLayout.libraryRelativePath(name)
            resources[ArthasRuntimeLayout.resourcePath(version, relative)] = "官方-$name".toByteArray()
        }
        resources[ArthasRuntimeLayout.resourcePath(version, "LICENSE")] = "许可证".toByteArray()
        resources[ArthasRuntimeLayout.resourcePath(version, "NOTICE")] = "声明".toByteArray()
        val checksumPath = ArthasRuntimeLayout.resourcePath(version, ArthasRuntimeLayout.checksumFileName)
        resources[checksumPath] = checksumManifest(version, resources)
        return resources
    }

    private fun checksumManifest(version: String, resources: Map<String, ByteArray>): ByteArray {
        val libraryNames = ArthasRuntimeLayout.requiredLibraries(version)
            .map { ArthasRuntimeLayout.libraryRelativePath(it) }
        return (ArthasRuntimeLayout.requiredJarNames + libraryNames).joinToString("\n") { name ->
            "$name=${hash(resources.getValue(ArthasRuntimeLayout.resourcePath(version, name)))}"
        }.toByteArray()
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
