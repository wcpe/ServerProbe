'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { protocolLifecycleStage } = require('../src/protocolLifecycle')

test('协议生命周期只保留登录、配置、保活和断开帧名', () => {
  assert.equal(protocolLifecycleStage('login_success'), '协议登录成功')
  assert.equal(protocolLifecycleStage('finish_configuration'), '协议配置完成')
  assert.equal(protocolLifecycleStage('keep_alive'), '协议保活')
  assert.equal(protocolLifecycleStage('disconnect'), '协议断开')
  assert.equal(protocolLifecycleStage('custom_payload'), undefined)
})
