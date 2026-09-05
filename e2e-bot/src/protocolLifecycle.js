'use strict'

const stages = new Map([
  ['login_success', '协议登录成功'],
  ['login_acknowledged', '协议登录确认'],
  ['finish_configuration', '协议配置完成'],
  ['configuration_acknowledged', '协议配置确认'],
  ['keep_alive', '协议保活'],
  ['disconnect', '协议断开']
])

/** 仅标记诊断所需的协议阶段；其它包名、玩家数据和载荷均不落入回执。 */
function protocolLifecycleStage(packetName) {
  return stages.get(packetName)
}

module.exports = { protocolLifecycleStage }
