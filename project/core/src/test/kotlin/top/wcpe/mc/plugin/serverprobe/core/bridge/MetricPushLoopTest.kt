package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 指标推送循环的健壮性。
 *
 * 循环跑在自起的守护线程上：过去 `pushOnce()` 裸调用，一次抛出（JSON 编码、入队都可能抛）
 * 就让线程静默死亡——控制面只会看到指标不再更新，日志里一个字都没有。
 */
class MetricPushLoopTest {

    /** 单轮抛异常只跳过该轮，循环继续。 */
    @Test
    fun `单次推送异常不会终止循环`() {
        val service = MetricPushService()
        var attempts = 0

        service.pushLoop(
            pushOnce = {
                attempts++
                if (attempts == 1) error("boom")
                null
            },
            intervalProvider = { 0L },
            shouldContinue = { attempts < 3 },
            sleeper = { },
        )

        assertEquals(3, attempts, "第一次抛异常后循环必须继续，而不是线程直接死亡")
    }

    /** 休眠被打断（stop）时恢复中断位并退出，不再推帧。 */
    @Test
    fun `休眠被中断时退出并恢复中断位`() {
        val service = MetricPushService()
        var attempts = 0

        service.pushLoop(
            pushOnce = { attempts++; null },
            intervalProvider = { 1L },
            shouldContinue = { true },
            sleeper = { throw InterruptedException("stop") },
        )

        assertEquals(0, attempts, "退出前不应推帧")
        assertTrue(Thread.currentThread().isInterrupted, "必须恢复中断位，否则上层看不到停止信号")
        Thread.interrupted()
    }
}
