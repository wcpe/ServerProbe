package top.wcpe.mc.plugin.serverprobe.core.bridge.manifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionSpec
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest
import top.wcpe.mc.plugin.serverprobe.api.bridge.RiskLevel

/**
 * [ManifestDigest] 的边界分支测试。
 *
 * <p>主路径（顺序无关、空白归一、16 位小写十六进制）已在 `BusinessDomainStatusTest` 里验过；
 * 本类只补三个以前无人覆盖的<b>防御性分支</b>：
 * 它们是外部实现违约时的兜底，平时不跑，一旦实现写坏才会触发——而那时恰恰最需要指标准确。</p>
 */
class ManifestDigestTest {

    @Test
    @DisplayName("of(null) 返回 null——表示“没有可比较指纹”而不是空指纹")
    fun `null清单返回null`() {
        // Kdoc 明确承诺这一点。null 与“空指纹”是两回事：
        // 下游拿 null 就知道“这个域没指纹可比对”，拿空串则可能误判为“规格变了”。
        assertNull(ManifestDigest.of(null), "null 清单必须返回 null")
    }

    @Test
    @DisplayName("动作列表含 null 时用占位顶替，不抛异常")
    fun `null动作不导致失败`() {
        // 契约要求 actions 非 null，但外部实现可能违约。
        // 若这里抛，指纹会整个算不出来（of() 里 runCatching 会吃成 null），
        // 于是面板永远拿不到指纹——即使是同一个域。
        val manifest = DomainManifest(
            DomainManifest.CURRENT_API_VERSION,
            "demo", "D", "",
            listOf(null, ActionSpec.read("a", "")),
            emptyMap(),
        )

        val digest = ManifestDigest.of(manifest)

        assertNotNull(digest, "含 null 动作的清单仍应算出指纹")
        assertEquals(ManifestDigest.DIGEST_CHARS, digest!!.length, "指纹长度恒为 16")
        assertTrue(digest.matches(Regex("[0-9a-f]{16}")), "指纹应为 16 位小写十六进制，实际=$digest")
    }

    @Test
    @DisplayName("相同内容但动作声明顺序不同 → 同一指纹")
    fun `动作顺序无关`() {
        val a = DomainManifest.of(
            "demo", "D", "",
            listOf(ActionSpec.read("x", ""), ActionSpec.read("y", "")),
            emptyMap(),
        )
        val b = DomainManifest.of(
            "demo", "D", "",
            listOf(ActionSpec.read("y", ""), ActionSpec.read("x", "")),
            emptyMap(),
        )

        assertEquals(
            ManifestDigest.of(a),
            ManifestDigest.of(b),
            "Provider 的 manifest 是现算的，声明顺序可能来自 Map 遍历；不排序会让指纹在内容未变时抖动",
        )
    }

    @Test
    @DisplayName("内容真变了 → 指纹必须变（宁可多报变化，不能漏报）")
    fun `内容变化指纹变化`() {
        val base = DomainManifest.of(
            "demo", "D", "",
            listOf(ActionSpec.read("x", "旧说明")),
            emptyMap(),
        )
        val changedDescription = DomainManifest.of(
            "demo", "D", "",
            listOf(ActionSpec.read("x", "新说明")),
            emptyMap(),
        )
        val changedRisk = DomainManifest.of(
            "demo", "D", "",
            listOf(ActionSpec.of("x", "旧说明", RiskLevel.DESTRUCTIVE, false, null, null, null)),
            emptyMap(),
        )
        val changedAttributes = DomainManifest.of(
            "demo", "D", "",
            listOf(ActionSpec.read("x", "旧说明")),
            mapOf("v" to "2"),
        )

        assertNotEquals(ManifestDigest.of(base), ManifestDigest.of(changedDescription), "说明变了指纹应变")
        assertNotEquals(ManifestDigest.of(base), ManifestDigest.of(changedRisk), "风险变了指纹应变")
        assertNotEquals(ManifestDigest.of(base), ManifestDigest.of(changedAttributes), "扩展位变了指纹应变")
    }

    @Test
    @DisplayName("相同内容重复计算 → 指纹稳定（可当“缓存键”用）")
    fun `指纹稳定性`() {
        val manifest = DomainManifest.of(
            "demo", "D", "",
            listOf(ActionSpec.read("x", ""), ActionSpec.read("y", "")),
            mapOf("a" to "1", "b" to "2"),
        )

        val first = ManifestDigest.of(manifest)

        // 重复 20 次：指纹必须每次都一样，否则下游的“变化即拉全量”判据失效，
        // 每次查询都会以为规格变了而重拉。
        repeat(20) {
            assertEquals(first, ManifestDigest.of(manifest), "同一内容重复计算必须得到同一指纹")
        }
    }
}
