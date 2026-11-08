import transport from './transport.js'
import protocol from './protocol.js'
import storage from '@system.storage'

// 手环端运行时探针：把启动过程与异常通过设备通信回传给手机伴侣应用。
// 手机端会把它们原样显示在日志区，用于定位「白图标 / 点击闪退」这类无法取日志的问题。

const MAX_QUEUE = 60
const VERSION = 'arclyric/1.0.1'
const CRASH_KEY = 'arclyric_crash'

let queue = []
let started = false
let booted = false
let timer = null
let heartbeatTimer = null
let heartbeatCount = 0

function clip(text, max) {
  const value = String(text === undefined || text === null ? '' : text)
  return value.length > max ? value.slice(0, max) + '~' : value
}

function storeSet(key, value) {
  try {
    storage.set({
      key: key,
      value: String(value === undefined || value === null ? '' : value),
      success: function () {},
      fail: function () {}
    })
  } catch (e) {}
}

function storeGet(key, done) {
  try {
    storage.get({
      key: key,
      default: '',
      success: function (data) {
        const value = data && (data.value !== undefined ? data.value : data.data)
        done(value === undefined || value === null ? '' : String(value))
      },
      fail: function () {
        done('')
      }
    })
  } catch (e) {
    done('')
  }
}

function describe(err) {
  if (err === undefined || err === null) {
    return 'empty'
  }
  if (typeof err === 'string') {
    return clip(err, 240)
  }
  let text = ''
  try {
    if (err.message) {
      text = String(err.message)
    } else if (err.name) {
      text = String(err.name)
    } else {
      text = String(err)
    }
  } catch (e) {
    text = 'unprintable'
  }
  let stack = ''
  try {
    if (err.stack) {
      stack = String(err.stack).replace(/\s+/g, ' ')
    }
  } catch (e) {}
  const merged = stack ? text + ' @ ' + stack : text
  return clip(merged, 240)
}

// 不再依赖 getReadyState 的连接状态：9 Pro 上该方法可能一直不回调，会把整条日志通道堵死。
// 直接尝试发送，失败则回队列，并退避 3 秒。
let flushBlockedUntil = 0

// 串行发送：以前一次 splice(0, 8) 后连发 8 条，连接刚建立时会把通信缓冲瞬间打满。
let flushing = false

function flush() {
  if (flushing || !queue.length) {
    return
  }
  if (Date.now() < flushBlockedUntil) {
    return
  }
  const item = queue[0]
  flushing = true
  transport.send(protocol.debug(item.level, item.tag, item.msg, item.at)).then(function () {
    flushing = false
    queue.shift()
    setTimeout(flush, 60)
  }).catch(function () {
    flushing = false
    flushBlockedUntil = Date.now() + 3000
  })
}

function push(level, tag, message) {
  const item = {
    level: level || 'info',
    tag: clip(tag || 'band', 24),
    msg: clip(message, 240),
    at: Date.now()
  }
  queue.push(item)
  if (queue.length > MAX_QUEUE) {
    queue.shift()
  }
  flush()
}

function start() {
  if (started) {
    return
  }
  started = true
  try {
    transport.init()
  } catch (e) {}
  try {
    timer = setInterval(flush, 1000)
  } catch (e) {}
  try {
    storeGet(CRASH_KEY, function (text) {
      if (text) {
        push('crash', 'previous', '上次异常：' + text)
        storeSet(CRASH_KEY, '')
      }
    })
  } catch (e) {}
}

function boot() {
  if (booted) {
    return
  }
  booted = true
  start()
  push('boot', 'app', 'BOOT ' + VERSION + ' 脚本已加载')
  if (heartbeatTimer) {
    return
  }
  heartbeatCount = 0
  heartbeatTimer = setInterval(function () {
    heartbeatCount++
    push('alive', 'app', '心跳 ' + heartbeatCount)
    if (heartbeatCount >= 2) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }, 1500)
}

function mark(tag, message) {
  start()
  push('info', tag, message)
}

function warn(tag, message) {
  start()
  push('warn', tag, message)
}

function error(tag, where, err) {
  start()
  const text = describe(err)
  push('error', tag, where + ' 出错: ' + text)
  storeSet(CRASH_KEY, clip(where + ' @ ' + tag + ' :: ' + text, 300))
}

function fatal(tag, where, err) {
  error(tag, where, err)
  flush()
}

function state() {
  return {
    started: started,
    booted: booted,
    queued: queue.length,
    connected: transport.getStatus().connected,
    lastError: transport.getStatus().lastError
  }
}

export default {
  boot: boot,
  start: start,
  mark: mark,
  warn: warn,
  error: error,
  fatal: fatal,
  flush: flush,
  describe: describe,
  state: state
}
