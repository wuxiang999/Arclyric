import interconnect from '@system.interconnect'
import protocol from './protocol.js'

let connection = null
let messageHandler = null
let statusHandler = null
let initialized = false
let heartbeatTimer = null

const status = {
  connected: false,
  apkStatus: 0,
  lastError: '',
  updatedAt: 0
}

function notifyStatus() {
  status.updatedAt = Date.now()
  if (statusHandler) {
    statusHandler({
      connected: status.connected,
      apkStatus: status.apkStatus,
      lastError: status.lastError
    })
  }
}

function refreshStatus() {
  if (!connection) {
    return Promise.resolve(status.connected)
  }
  return new Promise(function (resolve) {
    try {
      connection.getReadyState({
        success: function (res) {
          status.connected = res && Number(res.status) === 1
          status.lastError = ''
          notifyStatus()
          resolve(status.connected)
        },
        fail: function (data, code) {
          status.connected = false
          status.lastError = '状态码 ' + code
          notifyStatus()
          resolve(false)
        }
      })
    } catch (e) {
      status.connected = false
      status.lastError = String(e)
      notifyStatus()
      resolve(false)
    }
  })
}

function refreshApkStatus() {
  if (!connection || typeof connection.getApkStatus !== 'function') {
    return status.apkStatus
  }
  try {
    status.apkStatus = connection.getApkStatus()
  } catch (e) {
    status.apkStatus = 0
  }
  return status.apkStatus
}

function init() {
  if (initialized) {
    return connection
  }
  initialized = true
  try {
    connection = interconnect.instance()
  } catch (e) {
    initialized = false
    status.lastError = '当前设备不支持设备通信'
    notifyStatus()
    startHeartbeat()
    return null
  }
  connection.onmessage = function (res) {
    const decoded = protocol.decode(res && res.data !== undefined ? res.data : res)
    if (decoded && messageHandler) {
      messageHandler(decoded)
    }
  }
  connection.onopen = function () {
    status.connected = true
    status.lastError = ''
    notifyStatus()
  }
  connection.onclose = function () {
    status.connected = false
    status.lastError = '连接已断开'
    notifyStatus()
  }
  connection.onerror = function (res) {
    status.connected = false
    status.lastError = res && res.data ? String(res.data) : '连接错误'
    notifyStatus()
  }
  // 不主动查 getReadyState / getApkStatus：9 Pro 上它们经常不回调，
  // 而且重连瞬间调用会把穿戴服务拖死。连接状态由 onopen/onclose 与「发送是否成功」共同维护。
  startHeartbeat()
  return connection
}

// 手环端往往早于手机端就绪。以前每 2 秒调一次 getReadyState/getApkStatus，
// 在小米运动健康重连的瞬间这些系统调用会把穿戴服务拖死（整机重启）。
// 现在改为：靠 onopen/onclose 事件维持状态，只在长时间未连上时低频兜底一次。
function startHeartbeat() {
  if (heartbeatTimer) {
    return
  }
  let misses = 0
  try {
    heartbeatTimer = setInterval(function () {
      if (!connection) {
        initialized = false
        init()
        return
      }
      if (status.connected) {
        misses = 0
        return
      }
      misses++
      // 未连接时用一条 ping 当探针：走的是正常发送通道，
      // 不碰 getReadyState / getApkStatus 这些会拖死穿戴服务的原生查询。
      if (misses % 3 !== 0) {
        return
      }
      send({ v: 1, type: 'ping', ts: Date.now(), payload: { probe: true } }).catch(function () {})
    }, 5000)
  } catch (e) {}
}

function onMessage(handler) {
  messageHandler = handler
}

function onStatus(handler) {
  statusHandler = handler
  if (handler) {
    handler({
      connected: status.connected,
      apkStatus: status.apkStatus,
      lastError: status.lastError
    })
  }
}

// 发送超时：9 Pro 上偶发 success 回调不来，await 会永久挂住，
// 把 libraryBusy / 逐包等待卡死。这里给每次发送一个硬上限。
const SEND_TIMEOUT_MS = 2500

function send(message) {
  return new Promise(function (resolve, reject) {
    let finished = false
    let timer = null
    function settle(ok, value) {
      if (finished) {
        return
      }
      finished = true
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
      if (ok) {
        resolve(value)
      } else {
        reject(value)
      }
    }
    timer = setTimeout(function () {
      status.connected = false
      status.lastError = '发送超时'
      notifyStatus()
      settle(false, new Error('发送超时'))
    }, SEND_TIMEOUT_MS)
    if (!connection) {
      // 实例可能还没建好，补一次初始化再试
      initialized = false
      init()
    }
    if (!connection) {
      settle(false, new Error('设备通信未初始化'))
      return
    }
    // 关键：connect.send 的 data 必须是对象。传字符串会被原生层拒绝，
    // 在 9 Pro 上还会把穿戴服务带崩（整机重启），这就是之前「一条日志都收不到」的原因。
    // 官方样例统一用 { data: { data: JSON.stringify(x) } }，手机侧取 data.data 再 JSON.parse。
    let text
    try {
      text = typeof message === 'string' ? message : JSON.stringify(message)
    } catch (e) {
      settle(false, e)
      return
    }
    if (!text) {
      settle(false, new Error('空消息'))
      return
    }
    // 不以缓存的 readyState 拦截：之前 getReadyState 未及时翻转导致指令全部被挡掉。
    // 直接发送，以真实回调为准，成功即视为已连接。
    try {
      connection.send({
        data: { data: text },
        success: function () {
          if (!status.connected) {
            status.connected = true
            status.lastError = ''
            notifyStatus()
          }
          settle(true, true)
        },
        fail: function (res, code) {
          status.connected = false
          status.lastError = (res && res.data ? res.data : '发送失败') + ' (' + code + ')'
          notifyStatus()
          // 不再在这里调 getReadyState：失败时连环触发原生查询，
          // 正是小米运动健康重连瞬间把手环带崩的写法。状态交给 onopen/onclose。
          settle(false, new Error(status.lastError))
        }
      })
    } catch (e) {
      status.connected = false
      status.lastError = String(e)
      notifyStatus()
      settle(false, e)
    }
  })
}

function diagnose(timeout) {
  return new Promise(function (resolve) {
    if (!connection) {
      resolve({ ok: false, status: -1, message: '设备通信不可用' })
      return
    }
    try {
      connection.diagnosis({
        timeout: timeout || 8000,
        success: function (res) {
          const code = res && res.status !== undefined ? Number(res.status) : -1
          resolve({
            ok: code === 0,
            status: code,
            message: code === 0 ? '连接正常' : code === 1001 ? '手机端应用未安装' : code === 204 ? '连接超时' : '连接异常 (' + code + ')'
          })
        },
        fail: function (res, code) {
          resolve({ ok: false, status: code, message: '诊断失败 (' + code + ')' })
        }
      })
    } catch (e) {
      resolve({ ok: false, status: -1, message: String(e) })
    }
  })
}

function reconnect() {
  if (!connection) {
    init()
  }
  if (connection) {
    send(protocol.envelope(protocol.TYPES.HELLO, {
      client: 'band',
      name: '弧光歌词'
    })).catch(function () {})
  }
  return Promise.resolve(status.connected)
}

function getStatus() {
  return {
    connected: status.connected,
    apkStatus: status.apkStatus,
    lastError: status.lastError
  }
}

export default {
  init: init,
  onMessage: onMessage,
  onStatus: onStatus,
  send: send,
  diagnose: diagnose,
  reconnect: reconnect,
  refreshStatus: refreshStatus,
  refreshApkStatus: refreshApkStatus,
  getStatus: getStatus
}
