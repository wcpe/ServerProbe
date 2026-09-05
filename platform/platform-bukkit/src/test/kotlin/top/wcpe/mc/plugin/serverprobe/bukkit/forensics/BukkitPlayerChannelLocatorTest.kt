package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import io.netty.channel.Channel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 锁定 Spigot 连接对象图中超出旧四层预算的 Channel 仍可被定位。 */
class BukkitPlayerChannelLocatorTest {

    @Test
    fun findChannelBeyondLegacyGraphBudget() {
        val channel = TestChannel()
        val root = Root(LevelOne(LevelTwo(LevelThree(LevelFour(LevelFive(channel))))))

        assertSame(channel, BukkitPlayerChannelLocator.findFromHandle(root))
    }

    @Test
    fun accessPipelineByStringNameWhenNettyAlsoExposesClassOverloads() {
        val channel = TestChannel()
        val pipeline = ChannelPipelineAccess.create(channel)!!

        assertTrue(pipeline.addBefore("decoder", "serverprobe", Any()))
        assertTrue(pipeline.contains("serverprobe"))

        pipeline.remove(listOf("serverprobe"))

        assertFalse(pipeline.contains("serverprobe"))
    }

    @Test
    fun resolveChannelFromMojangPayloadIdentifierField() {
        val packet = PacketPlayInCustomPayload(Payload(Identifier()))

        assertEquals("serverprobe:test", PluginMessageChannel.resolve(packet))
    }

    @Test
    fun resolveChannelFromSpigotObfuscatedAccessor() {
        val packet = PacketPlayInCustomPayloadWithAccessor()

        assertEquals("serverprobe:test", PluginMessageChannel.resolve(packet))
    }

    private class Root(val child: Any)
    private class LevelOne(val child: Any)
    private class LevelTwo(val child: Any)
    private class LevelThree(val child: Any)
    private class LevelFour(val child: Any)
    private class LevelFive(val channel: Channel)
    private class TestChannel : Channel {
        private val nettyPipeline = FakePipeline()

        fun pipeline(): FakePipeline = nettyPipeline
    }

    private class FakePipeline {
        private val handlers = linkedMapOf("decoder" to Any())

        fun addBefore(baseName: String, name: String, handler: Any) {
            check(handlers.containsKey(baseName))
            handlers[name] = handler
        }

        fun get(name: String): Any? = handlers[name]

        fun get(type: Class<*>): Any? = handlers.values.firstOrNull { type.isInstance(it) }

        fun remove(name: String) {
            handlers.remove(name)
        }

        fun remove(type: Class<*>): Any? = handlers.entries.firstOrNull { type.isInstance(it.value) }
            ?.let { entry -> handlers.remove(entry.key) }

        fun names(): List<String> = handlers.keys.toList()
    }

    private class PacketPlayInCustomPayload(val payload: Payload)
    private class PacketPlayInCustomPayloadWithAccessor {
        fun a(): Identifier = Identifier()
    }
    private class Payload(val identifier: Identifier)
    private class Identifier {
        override fun toString(): String = "serverprobe:test"
    }
}
