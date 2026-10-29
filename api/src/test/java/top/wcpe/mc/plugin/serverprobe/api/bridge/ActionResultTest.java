package top.wcpe.mc.plugin.serverprobe.api.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ActionResult} 与 {@link ActionRequest} 的契约测试。
 *
 * <h2>为什么必须单独测</h2>
 * <p>这两个类是第三方插件最常见的两个类型（返回结果、收请求）。
 * 但此前 api 层对它们<b>零测试</b>——所有覆盖都是“经 BusinessHost 转一圈”的间接断言，
 * 它们自身的行为（null 归一、单例复用、元数据不可变）无人守。</p>
 *
 * <p>直接后果举例：若 `metadata` 忘了做防御性拷贝，调用方改自己的 Map 就能篡改一个
 * “已完成的结果”；而间接测试永远发现不了，因为它只读几个字段。</p>
 */
class ActionResultTest {

    // ────────────────── 成功工厂 ──────────────────

    @Test
    @DisplayName("ok() 复用同一实例，避免高频路径上的无谓分配")
    void okReusesSingleton() {
        assertSame(ActionResult.ok(), ActionResult.ok(), "无参 ok() 应复用 EMPTY_OK 单例");
        assertTrue(ActionResult.ok().getSuccess());
        assertEquals("", ActionResult.ok().getOutputJson(), "无输出时空串而非 null");
    }

    @Test
    @DisplayName("ok(output) 与 ok(output, metadata) 分别带输出与诊断")
    void okWithOutput() {
        ActionResult plain = ActionResult.ok("{\"a\":1}");
        assertTrue(plain.getSuccess());
        assertEquals("{\"a\":1}", plain.getOutputJson());
        assertEquals("", plain.getErrorCode(), "成功时错误码为空串");

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("costMs", "12");
        ActionResult withMeta = ActionResult.ok("{}", meta);
        assertEquals("12", withMeta.getMetadata().get("costMs"));
    }

    @Test
    @DisplayName("ok(null) 输出归一为空串，不是 null")
    void okNullOutput() {
        assertEquals("", ActionResult.ok((String) null).getOutputJson(), "null 应归一为空串，下游无需判空");
    }

    // ────────────────── 失败工厂 ──────────────────

    @Test
    @DisplayName("fail(message) 没有错误码；fail(code, message) 两者都有")
    void failVariants() {
        ActionResult noCode = ActionResult.fail("目标不存在");
        assertFalse(noCode.getSuccess());
        assertEquals("", noCode.getErrorCode(), "单参 fail 不产生错误码");
        assertEquals("目标不存在", noCode.getErrorMessage());

        ActionResult withCode = ActionResult.fail("TARGET_NOT_FOUND", "目标不存在");
        assertFalse(withCode.getSuccess());
        assertEquals("TARGET_NOT_FOUND", withCode.getErrorCode(), "错误码是面板分流依据");
        assertEquals("目标不存在", withCode.getErrorMessage());
    }

    @Test
    @DisplayName("fail(null, null) 两个字符串都归一为空串")
    void failNulls() {
        ActionResult result = ActionResult.fail(null, null);
        assertFalse(result.getSuccess());
        assertEquals("", result.getErrorCode());
        assertEquals("", result.getErrorMessage());
    }

    // ────────────────── 不可变性 ──────────────────

    @Test
    @DisplayName("metadata 是防御性拷贝：调用方事后改自己的 Map 不影响已有结果")
    void metadataDefensiveCopy() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("a", "1");

        ActionResult result = ActionResult.ok("{}", mutable);

        // 改原 Map
        mutable.put("a", "CHANGED");
        mutable.put("b", "2");

        assertEquals("1", result.getMetadata().get("a"), "结果内的 metadata 不得随外部 Map 变化");
        assertNull(result.getMetadata().get("b"), "事后新增的键不得出现");
    }

    @Test
    @DisplayName("metadata 不可修改：写入会抛 UnsupportedOperationException")
    void metadataUnmodifiable() {
        ActionResult result = ActionResult.ok("{}", new LinkedHashMap<>());

        boolean threw = false;
        try {
            result.getMetadata().put("x", "y");
        } catch (UnsupportedOperationException expected) {
            threw = true;
        }
        assertTrue(threw, "metadata 必须是不可变视图，否则一次已送达的结果会被调用方篡改");
    }

    // ────────────────── 相等性 ──────────────────

    @Test
    @DisplayName("equals/hashCode 把 metadata 纳入比较")
    void equalityIncludesMetadata() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("costMs", "12");

        assertEquals(ActionResult.ok("{}", meta), ActionResult.ok("{}", meta));
        assertEquals(ActionResult.ok("{}", meta).hashCode(), ActionResult.ok("{}", meta).hashCode());

        // metadata 不同 → 不相等（它是结果的一部分，不能忽略）
        assertNotEquals(ActionResult.ok("{}", meta), ActionResult.ok("{}"));
        // 成功/失败不同 → 不相等
        assertNotEquals(ActionResult.ok("m"), ActionResult.fail("m"));
        // 错误码不同 → 不相等
        assertNotEquals(ActionResult.fail("A", "m"), ActionResult.fail("B", "m"));
    }

    @Test
    @DisplayName("不同于自己的其它类型不相等，且不抛")
    void equalityWithOtherType() {
        assertNotEquals(ActionResult.ok(), "not a result");
        assertNotEquals(ActionResult.ok(), null);
    }

    // ────────────────── ActionRequest ──────────────────

    @Test
    @DisplayName("ActionRequest 各字段 null 归一；principal 归一为 unknown")
    void requestNullNormalization() {
        ActionRequest request = new ActionRequest(null, null, null, null, null);

        assertEquals("", request.getDomain());
        assertEquals("", request.getAction());
        assertEquals("", request.getPayloadJson());
        assertEquals("", request.getRequestId());
        assertSame(Principal.unknown(), request.getPrincipal(), "null 应归一为 unknown 单例");
    }

    @Test
    @DisplayName("ActionRequest.of(Map) 把结构化参数规范化为 JSON 文本")
    void requestOfMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("player", "alice");
        payload.put("amount", "100.50");

        ActionRequest request = ActionRequest.of("demo", "apply", payload, "req-1", null);

        assertEquals("demo", request.getDomain());
        assertEquals("apply", request.getAction());
        assertEquals("req-1", request.getRequestId());
        assertTrue(request.getPayloadJson().contains("\"player\""), "payload 应为 JSON 文本");
        assertTrue(request.getPayloadJson().contains("100.50"), "字符串值应原样写出，不经浮点");
        assertSame(Principal.unknown(), request.getPrincipal());
    }

    @Test
    @DisplayName("ActionRequest.of(Map) 传 null 视为空对象")
    void requestOfNullPayload() {
        ActionRequest request = ActionRequest.of("demo", "ping", null);
        assertEquals("{}", request.getPayloadJson(), "null 参数应写成空对象，而不是空串");
        assertEquals("", request.getRequestId(), "3 参重载不产生 requestId");
    }

    @Test
    @DisplayName("ActionRequest.equals 逐字段比较，含 principal")
    void requestEquality() {
        Principal alice = new Principal("alice", 1L, "node", "reason");

        assertEquals(
                new ActionRequest("d", "a", "{}", "r", alice),
                new ActionRequest("d", "a", "{}", "r", alice)
        );
        assertEquals(
                new ActionRequest("d", "a", "{}", "r", alice).hashCode(),
                new ActionRequest("d", "a", "{}", "r", alice).hashCode()
        );

        // 任何字段不同都不相等
        assertNotEquals(
                new ActionRequest("d", "a", "{}", "r", alice),
                new ActionRequest("d", "a", "{}", "r", Principal.unknown()),
                "principal 是请求的一部分，不同操作者不得视为同一请求"
        );
        assertNotEquals(
                new ActionRequest("d", "a", "{}", "r1", alice),
                new ActionRequest("d", "a", "{}", "r2", alice),
                "requestId 是幂等去重的唯一依据，不同则必须不相等"
        );
        assertNotSame(new ActionRequest("d", "a", "{}", "r", alice), new ActionRequest("d", "a", "{}", "r", alice));
    }
}
