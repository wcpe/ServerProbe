'use strict'

const fs = require('node:fs')
const path = require('node:path')

/** 编排未下发路径时，network-forensics 项目机器人落到固定结果目录，避免诊断证据丢失。 */
function resolveLifecycleReceiptFile(configuredFile, action, botDirectory) {
  if (configuredFile) return configuredFile
  return path.resolve(botDirectory, '..', 'build', 'mc-testkit', 'results', `bot-${action}.receipt.jsonl`)
}

/** 仅追加不含玩家/IP/载荷的机器人生命周期阶段，供真实 E2E 定位连接链路。 */
function createLifecycleReceipt(file, now = Date.now) {
  if (!file) return () => {}
  return (event) => {
    fs.appendFileSync(file, `${JSON.stringify({ event, atMs: now() })}\n`)
  }
}

module.exports = { createLifecycleReceipt, resolveLifecycleReceiptFile }
