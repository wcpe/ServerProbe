package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ListenerMatcher] 单元测试（配置重载规格 §3.4）。
 *
 * 核心断言是"**不做 glob、不做正则**"：`*` 与 `**` 都是普通字符，
 * `plugins/Foo/` 匹配的是**该目录下任意层级**，而 `plugins/Foo`（无斜杠）只匹配这一份。
 */
class ListenerMatcherTest {

    /** 精确声明只命中同名路径。 */
    @Test
    fun `精确声明只命中同名路径`() {
        assertTrue(ListenerMatcher.matches("plugins/Foo/config.yml", "plugins/Foo/config.yml"))
        assertFalse(ListenerMatcher.matches("plugins/Foo/config.yml", "plugins/Foo/other.yml"))
        assertFalse(ListenerMatcher.matches("plugins/Foo/config.yml", "plugins/Foo/config.yml.bak"))
    }

    /** 目录前缀（末尾斜杠）命中任意层级。 */
    @Test
    fun `目录前缀命中任意层级`() {
        val declared = "plugins/Foo/"
        assertTrue(ListenerMatcher.matches(declared, "plugins/Foo/config.yml"))
        assertTrue(ListenerMatcher.matches(declared, "plugins/Foo/sub/deep/config.yml"), "目录前缀应覆盖任意层级")
    }

    /** 目录前缀不命中兄弟目录与父目录。 */
    @Test
    fun `目录前缀不命中兄弟与父目录`() {
        val declared = "plugins/Foo/"
        assertFalse(ListenerMatcher.matches(declared, "plugins/Bar/config.yml"))
        assertFalse(ListenerMatcher.matches(declared, "plugins/FooBar/config.yml"), "必须按路径段匹配，不能按字符前缀")
        assertFalse(ListenerMatcher.matches(declared, "plugins/config.yml"))
    }

    /** 无末尾斜杠的声明按精确匹配，不会被当成目录。 */
    @Test
    fun `无末尾斜杠不当作目录`() {
        assertFalse(ListenerMatcher.matches("plugins/Foo", "plugins/Foo/config.yml"))
    }

    /** glob 与正则元字符都是普通字符（防 ReDoS 与歧义）。 */
    @Test
    fun `星号与正则元字符不被解释`() {
        assertFalse(ListenerMatcher.matches("plugins/*", "plugins/Foo/config.yml"), "`*` 不是通配符")
        assertFalse(ListenerMatcher.matches("plugins/.*", "plugins/Foo/config.yml"), "正则语法不生效")
        assertTrue(ListenerMatcher.matches("plugins/*", "plugins/*"), "`*` 只能精确命中同名字符")
    }

    /** 空声明 / 空路径一律不命中。 */
    @Test
    fun `空串不命中`() {
        assertFalse(ListenerMatcher.matches("", "plugins/Foo/config.yml"))
        assertFalse(ListenerMatcher.matches("plugins/Foo/", ""))
    }
}
