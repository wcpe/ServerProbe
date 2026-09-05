package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ArthasIsolatedClassLoaderTest {

    @Test
    fun `隔离加载器只接收最小闭包中的四个原始 jar`() {
        val directory = Paths.get("runtime", ArthasRuntimeLayout.defaultVersion)
        val loader = ArthasIsolatedClassLoader.create(directory)

        try {
            val names = loader.getURLs().map { url -> Paths.get(url.toURI()).fileName.toString() }.toSet()
            assertEquals(ArthasRuntimeLayout.requiredJarNames.toSet(), names)
        } finally {
            loader.close()
        }
    }
}
