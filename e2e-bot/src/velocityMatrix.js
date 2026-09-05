'use strict'

const MATRIX_SWITCHER = 'switcher'

/** 只允许切服角色发出目标后端命令，观察角色始终保持首个路由。 */
function resolveMatrixSwitchTarget(role, targetBackend) {
  if (role !== MATRIX_SWITCHER || !targetBackend) return null
  return targetBackend
}

module.exports = { resolveMatrixSwitchTarget }
