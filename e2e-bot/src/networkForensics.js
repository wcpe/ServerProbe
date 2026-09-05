'use strict'

const CHANNEL = 'serverprobe:test'
const BURST_COUNT = 24
const BURST_PAYLOAD_BYTES = 1024
const LARGE_PAYLOAD_BYTES = 24 * 1024
const PIPELINE_READY_DELAY_MS = 20_000

/** 发送协议单包上限内的白名单 Plugin Message，用于验证突发、前缀截断和完整摘要。 */
function sendFr11PluginMessages(client) {
  for (let index = 0; index < BURST_COUNT; index += 1) {
    client.write('custom_payload', {
      channel: CHANNEL,
      data: Buffer.alloc(BURST_PAYLOAD_BYTES, index)
    })
  }
  client.write('custom_payload', {
    channel: CHANNEL,
    data: Buffer.alloc(LARGE_PAYLOAD_BYTES, 0x5a)
  })
}

/** 首轮只发送小型信号，确保完整验收载荷只会在真实重连后发送。 */
function sendReconnectProbe(client) {
  client.write('custom_payload', {
    channel: CHANNEL,
    data: Buffer.from([0x01])
  })
}

module.exports = {
  LARGE_PAYLOAD_BYTES,
  PIPELINE_READY_DELAY_MS,
  sendFr11PluginMessages,
  sendReconnectProbe
}
