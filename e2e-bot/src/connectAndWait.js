'use strict'

const net = require('net')
const mineflayer = require('mineflayer')
const { createLifecycleReceipt, resolveLifecycleReceiptFile } = require('./lifecycleReceipt')
const { protocolLifecycleStage } = require('./protocolLifecycle')
const {
  LARGE_PAYLOAD_BYTES,
  PIPELINE_READY_DELAY_MS,
  sendFr11PluginMessages,
  sendReconnectProbe
} = require('./networkForensics')
const { resolveMatrixSwitchTarget } = require('./velocityMatrix')

const config = {
  action: process.env.MC_TESTKIT_E2E_BOT_ACTION || 'unspecified',
  host: process.env.MC_TESTKIT_E2E_BOT_HOST || 'localhost',
  port: Number.parseInt(process.env.MC_TESTKIT_E2E_BOT_PORT || '25565', 10),
  username: process.env.MC_TESTKIT_E2E_BOT_USERNAME || 'ServerProbeE2e',
  auth: process.env.MC_TESTKIT_E2E_BOT_AUTH || 'offline',
  version: process.env.MC_TESTKIT_E2E_BOT_VERSION || undefined,
  connectTimeoutMs: Number.parseInt(process.env.MC_TESTKIT_E2E_BOT_CONNECT_TIMEOUT_MS || '300000', 10),
  retryDelayMs: Number.parseInt(process.env.MC_TESTKIT_E2E_BOT_RETRY_DELAY_MS || '3000', 10)
}

let stopping = false
let spawned = false
let startedAt = Date.now()
let retryTimer = null
let activeBot = null
let connectionCount = 0
const observedProtocolStages = new Set()

const NETWORK_FORENSICS_ACTION = 'network-forensics'
const VELOCITY_MATRIX_ACTION = 'velocity-matrix'
const receipt = createLifecycleReceipt(
  resolveLifecycleReceiptFile(process.env.MC_TESTKIT_E2E_BOT_RECEIPT_FILE, config.action, __dirname + '/..')
)

function log(message) {
  console.log(`[ServerProbe E2E Bot][${config.action}] ${message}`)
}

function stop(code) {
  if (stopping) return
  stopping = true
  if (retryTimer) clearTimeout(retryTimer)
  activeBot?.quit('mc-testkit 场景结束')
  setTimeout(() => process.exit(code), 50)
}

function retry(reason) {
  if (stopping || spawned) return
  if (Date.now() - startedAt >= config.connectTimeoutMs) {
    receipt('重连失败')
    log(`连接超时：${reason}`)
    stop(1)
    return
  }
  retryTimer = setTimeout(waitForPort, config.retryDelayMs)
}

function waitForPort() {
  if (stopping || spawned) return
  const socket = net.createConnection({ host: config.host, port: config.port }, () => {
    socket.end()
    connect()
  })
  socket.once('error', (error) => {
    socket.destroy()
    retry(error.message)
  })
}

function connect() {
  const options = { host: config.host, port: config.port, username: config.username, auth: config.auth }
  if (config.version) options.version = config.version
  receipt('握手开始')
  activeBot = mineflayer.createBot(options)
  const client = activeBot._client
  client?.once('connect', () => receipt('底层连接成功'))
  client?.on('packet', (_packet, meta) => recordProtocolStage(meta?.name))
  client?.once('error', (error) => receipt(`底层连接错误:${error?.name || '未知'}`))
  client?.once('end', () => receipt('底层连接结束'))
  activeBot.once('login', () => receipt('Mineflayer登录成功'))
  activeBot.once('spawn', () => {
    spawned = true
    connectionCount += 1
    receipt(connectionCount === 1 ? '连接成功' : '重连成功')
    if (config.action === NETWORK_FORENSICS_ACTION) {
      driveNetworkForensics(activeBot, connectionCount)
      return
    }
    if (config.action === VELOCITY_MATRIX_ACTION) {
      driveVelocityMatrix(activeBot)
      return
    }
    log(`已作为 ${config.username} 进入服务器，保持在线等待桩验收`)
  })
  activeBot.once('end', (reason) => {
    activeBot = null
    receipt('Mineflayer连接结束')
    if (config.action === NETWORK_FORENSICS_ACTION && connectionCount === 1 && !stopping) {
      spawned = false
      receipt('重连开始')
      log('首轮流量已发送，开始真实协议重连')
      retryTimer = setTimeout(waitForPort, config.retryDelayMs)
      return
    }
    if (spawned) stop(0)
    else retry(reason)
  })
  activeBot.once('error', (error) => {
    if (config.action === NETWORK_FORENSICS_ACTION && connectionCount === 1) receipt('重连失败')
    log(`连接异常：${error.message}`)
  })
}

/** 仅让切服角色在首次出生后切到第二个后端，观察角色留在初始路由。 */
function driveVelocityMatrix(bot) {
  const target = resolveMatrixSwitchTarget(process.env.SP_MATRIX_ROLE, process.env.SP_MATRIX_TARGET_BACKEND)
  if (!target) {
    log(`观察角色 ${config.username} 已进入初始后端`)
    return
  }
  setTimeout(() => {
    if (stopping) return
    bot.chat(`/server ${target}`)
    receipt('velocity-matrix 切服命令已发送')
    log(`切服角色 ${config.username} 已请求切换到 ${target}`)
  }, 1_000)
}

/** 每类低层协议阶段每条连接仅记录一次，防止保活帧造成诊断刷屏。 */
function recordProtocolStage(packetName) {
  const stage = protocolLifecycleStage(packetName)
  if (!stage || observedProtocolStages.has(stage)) return
  observedProtocolStages.add(stage)
  receipt(stage)
}

/** 等待代理完成管线附着后，首轮断连重连，第二轮发送完整白名单 Plugin Message 突发。 */
function driveNetworkForensics(bot, connectionNumber) {
  const client = bot._client
  setTimeout(() => {
    try {
      if (connectionNumber === 1) {
        sendReconnectProbe(client)
        receipt('合法帧发送完成')
        log('首轮连接已发送合法 Plugin Message，开始真实协议重连')
        receipt('主动断连')
        bot.quit('network-forensics 重连验证')
        return
      }
      sendFr11PluginMessages(client)
      receipt('合法帧发送完成')
      log(`重连后已发送 Plugin Message 突发与 ${LARGE_PAYLOAD_BYTES} 字节载荷`)
    } catch (error) {
      log(`发送取证流量失败：${error.message}`)
      stop(1)
    }
  }, PIPELINE_READY_DELAY_MS)
}

process.once('SIGINT', () => stop(0))
process.once('SIGTERM', () => stop(0))
waitForPort()
