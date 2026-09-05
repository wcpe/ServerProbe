'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { resolveMatrixSwitchTarget } = require('../src/velocityMatrix')

test('velocity-matrix 仅切服角色解析有效后端名', () => {
  assert.equal(resolveMatrixSwitchTarget('switcher', 'paper-velocity-matrix-v31-b'), 'paper-velocity-matrix-v31-b')
  assert.equal(resolveMatrixSwitchTarget('observer', 'paper-velocity-matrix-v31-b'), null)
  assert.equal(resolveMatrixSwitchTarget('switcher', ''), null)
})
