'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')
const { createLifecycleReceipt, resolveLifecycleReceiptFile } = require('../src/lifecycleReceipt')

test('协议机器人生命周期回执只记录阶段和时间', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'serverprobe-network-'))
  const receipt = path.join(directory, 'bot.jsonl')

  const record = createLifecycleReceipt(receipt, () => 123)
  record('重连成功')

  const event = JSON.parse(fs.readFileSync(receipt, 'utf8').trim())
  assert.deepEqual(event, { event: '重连成功', atMs: 123 })
  assert.deepEqual(Object.keys(event), ['event', 'atMs'])
})

test('回执文件优先使用编排路径，缺失时回退到项目结果目录', () => {
  assert.equal(resolveLifecycleReceiptFile('C:/receipt.jsonl', 'network-forensics', 'C:/workspace/e2e-bot'), 'C:/receipt.jsonl')
  assert.equal(
    resolveLifecycleReceiptFile(undefined, 'network-forensics', 'C:/workspace/e2e-bot'),
    path.resolve('C:/workspace', 'build/mc-testkit/results/bot-network-forensics.receipt.jsonl')
  )
})
