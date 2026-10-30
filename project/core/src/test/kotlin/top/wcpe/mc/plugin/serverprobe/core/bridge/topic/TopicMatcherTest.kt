package top.wcpe.mc.plugin.serverprobe.core.bridge.topic

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [TopicMatcher] 单元测试（事件主题订阅规格 §5：精确 / 前缀 / 层级前缀 / 不支持通配）。
 *
 * 匹配规则是纯字符串前缀比较，故这里全部用"表达式 → 主题 → 是否匹配"的直白断言，
 * 不引入任何 JSON 或桥组件。
 */
class TopicMatcherTest {

    /** 精确订阅：表达式与主题完全相同才命中。 */
    @Test
    fun `精确匹配`() {
        assertTrue(TopicMatcher.matches("activity.12345", "activity.12345"), "精确表达式应命中自身")
        assertFalse(TopicMatcher.matches("activity.12345", "activity.99999"), "精确表达式不得模糊命中")
        assertFalse(
            TopicMatcher.matches("activity.12345", "activity.12345.sub"),
            "精确表达式不继承层级（层级只由 .* 提供）",
        )
    }

    /** 前缀订阅：`activity.*` 命中同域下的对象。 */
    @Test
    fun `前缀通配匹配同域对象`() {
        assertTrue(TopicMatcher.matches("activity.*", "activity.1"), "activity.* 应命中 activity.1")
        assertTrue(TopicMatcher.matches("activity.*", "activity.2"), "activity.* 应命中 activity.2")
        assertFalse(TopicMatcher.matches("activity.*", "economy.1"), "前缀通配不得跨业务域命中")
    }

    /** 层级前缀：`activity.*` 匹配 `activity.12345.sub`（本 spec 的明确语义，刻意选择）。 */
    @Test
    fun `前缀通配匹配层级子主题`() {
        assertTrue(TopicMatcher.matches("activity.*", "activity.12345.sub"), "activity.* 应命中层级子主题")
    }

    /** `activity.*` 不匹配 `activity` 本身：通配的前缀是 `activity.`（带点）。 */
    @Test
    fun `前缀通配不匹配域本身`() {
        assertFalse(TopicMatcher.matches("activity.*", "activity"), "通配前缀含点，故不命中域本身")
    }

    /** 其他位置的通配一律视为不匹配（不崩溃、不误匹配）。 */
    @Test
    fun `不支持的通配位置视为不匹配`() {
        assertFalse(TopicMatcher.matches("act*ity", "activity"), "act*ity 不得命中 activity")
        assertFalse(TopicMatcher.matches("act*ity", "act.ity"), "act*ity 不得命中 act.ity")
        assertFalse(TopicMatcher.matches("act*", "activity.1"), "只有 .* 后缀才是通配")
        assertFalse(TopicMatcher.matches("*", "activity.1"), "裸 * 不是受支持的通配")
    }

    /** 空串不匹配任何主题（防御空表达式进入匹配路径）。 */
    @Test
    fun `空串不参与匹配`() {
        assertFalse(TopicMatcher.matches("", "activity.1"), "空表达式不匹配")
        assertFalse(TopicMatcher.matches("activity.*", ""), "空主题不匹配")
    }
}
