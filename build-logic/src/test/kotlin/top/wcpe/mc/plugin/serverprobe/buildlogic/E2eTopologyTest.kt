package top.wcpe.mc.plugin.serverprobe.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * E2E 拓扑声明的构建期守卫（Gradle TestKit）。
 *
 * <h2>为什么用 TestKit 而不是读构建脚本</h2>
 * <p>原先这些断言在 `platform` 各模块 的单元测试里：把 `serverprobe.e2e-verification.gradle.kts`
 * 当文本读进来，用 `contains(...)` 去比对，连缩进空格都写死在断言里。它有两个致命问题：</p>
 * <ul>
 *   <li><b>改格式就假失败</b>：多一个空格、换个缩进层级，断言就红，而拓扑没变。</li>
 *   <li><b>它什么都证明不了</b>：只能说明“脚本里有这行字”，不能说明任务真的被创建了。
 *       而后者才是拓扑声明的意义。</li>
 * </ul>
 *
 * <p>TestKit 是 Gradle 官方推荐的构建逻辑测试方式：在真实 Gradle 运行中取得任务图，
 * 断言 <b>这些任务真的存在</b>——与“文件里写了什么字”无关。</p>
 *
 * <h2>为什么只取任务图、不真的跑 E2E</h2>
 * <p>真跑一个场景要下载服务器、占端口、等几分钟，那是 CI 里 `e2e*` 任务的事。
 * 本类守卫的是“拓扑声明没漏”，只看任务是否存在即可。</p>
 */
class E2eTopologyTest {

    /** 根项目目录（build-logic 的测试从它自己的项目目录起步）。 */
    private val rootDir: File = File(System.getProperty("user.dir")).parentFile

    private fun taskNames(): Set<String> = GradleRunner.create()
        .withProjectDir(rootDir)
        .withArguments("tasks", "--all", "--console=plain", "-q")
        .build()
        .output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.substringBefore(' ') }
        .toSet()

    @Test
    @DisplayName("网络取证场景按五个平台分别声明")
    fun `网络取证场景齐备`() {
        val tasks = taskNames()

        val expected = listOf(
            "e2eNetworkForensicsBukkit",
            "e2eNetworkForensicsPaper",
            "e2eNetworkForensicsFolia",
            "e2eNetworkForensicsBungee",
            "e2eNetworkForensicsVelocity",
        )
        val missing = expected.filterNot { it in tasks }
        assertTrue(missing.isEmpty(), "网络取证场景任务缺失：$missing。拓扑声明改漏了？")
    }

    @Test
    @DisplayName("多版本矩阵逐版本声明（Paper 多版本 + Spigot 两版本）")
    fun `矩阵场景入口齐备`() {
        val tasks = taskNames()

        // 注意：矩阵**没有**聚合入口任务（不存在 `e2eMatrixPaper` 这种），每个版本一个任务。
        // 这个事实本身就是 TestKit 帮忙发现的：
        // 原先的字符串断言只检查“脚本里有没有写 version = \"1.8.8\"”，发现不了这一点。
        val matrixTasks = tasks.filter { it.startsWith("e2eMatrix") }
        assertTrue(matrixTasks.isNotEmpty(), "多版本矩阵一个任务都没有，拓扑声明丢了")

        assertTrue(
            matrixTasks.any { it.startsWith("e2eMatrixPaper") },
            "Paper 矩阵没有任何版本任务，实际=$matrixTasks",
        )
        assertTrue(
            matrixTasks.any { it.startsWith("e2eMatrixSpigot") },
            "Spigot 矩阵没有任何版本任务，实际=$matrixTasks",
        )

        // 矩阵的价值就在“多个版本”，单个版本等于没覆盖。
        assertTrue(
            matrixTasks.count { it.startsWith("e2eMatrixPaper") } >= 2,
            "Paper 矩阵至少要有两个版本任务，否则不叫多版本兼容，实际=$matrixTasks",
        )
    }

    @Test
    @DisplayName("桥专项场景齐备")
    fun `桥专项场景齐备`() {
        val tasks = taskNames()

        val expected = listOf(
            "e2eReadApi",
            "e2eStorageSpi",
            "e2eBridgeFixture",
            "e2eChunkFixture",
            "e2eTopicFixture",
            "e2eHealthFixture",
            "e2eReloadFixture",
        )
        val missing = expected.filterNot { it in tasks }
        assertTrue(missing.isEmpty(), "桥专项场景任务缺失：${missing}")
    }
}
