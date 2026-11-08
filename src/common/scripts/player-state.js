import transport from './transport.js'
import protocol from './protocol.js'
import lrc from './lrc.js'
import store from './store.js'
import library from './library.js'
import demo from './demo-lyrics.js'
import debug from './debug.js'

// 手环 JS 堆很小：单曲歌词超过这个长度直接截断，避免解析/渲染把内存打爆
const MAX_LYRICS = 12000

const state = {
  ready: false,
  connected: false,
  mock: true,
  playing: false,
  title: '',
  artist: '',
  album: '',
  duration: 0,
  position: 0,
  anchorAt: 0,
  lyricsText: '',
  lyrics: { meta: {}, lines: [] },
  lyricIndex: -1,
  offsetMs: 0,
  follow: true,
  autoSave: true,
  fontSize: 1,
  swipeStep: 1,
  vibrate: true,
  currentKey: '',
  currentSong: '',
  lastError: '',
  source: 'demo'
}

let listeners = []
let timer = null
let initialized = false
let initPromise = null
let mockIndex = 0
let mockPosition = 0
let mockAnchor = 0
let mockPlaying = true
let chunkMap = {}

// 真实的歌词分包进度：手机一包一包发，手环一包一包收。
// 播放页照这个数字画进度条，用户能看到「已收到 4/11 包」。
let transfer = { id: '', received: 0, total: 0, at: 0 }

function resetTransfer() {
  transfer = { id: '', received: 0, total: 0, at: 0 }
}
let lyricsToken = 0
let lastPingAt = 0
let lastPingLogAt = 0
// 上一次已知的连接状态，用于只在「断开 -> 连上」时发握手
let linkUp = false

function startTimer() {
  if (timer) {
    clearInterval(timer)
  }
  // 500ms 一次会让页面每秒重建两遍歌词窗，手环内存吃紧；1s 足够进度条使用
  timer = setInterval(tick, 1000)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function emit(reason) {
  const view = snapshot()
  for (let i = 0; i < listeners.length; i++) {
    try {
      listeners[i](reason, view)
    } catch (e) {
      console.log('listener error: ' + e)
    }
  }
}

function subscribe(fn) {
  if (typeof fn !== 'function') {
    return function () {}
  }
  listeners.push(fn)
  return function () {
    listeners = listeners.filter(function (item) {
      return item !== fn
    })
  }
}

function metaOffset() {
  return (state.lyrics.meta && Number(state.lyrics.meta.offset)) || 0
}

function getPosition() {
  if (state.mock) {
    const base = mockPlaying ? mockPosition + (Date.now() - mockAnchor) : mockPosition
    return Math.max(0, Math.min(base, state.duration || 0))
  }
  const base = state.playing ? state.position + (Date.now() - state.anchorAt) : state.position
  const max = state.duration || 0
  if (max > 0) {
    return Math.max(0, Math.min(base, max))
  }
  return Math.max(0, base)
}

function computeIndex() {
  if (!state.lyrics.lines.length) {
    return -1
  }
  return lrc.findIndex(state.lyrics.lines, getPosition() + state.offsetMs - metaOffset())
}

function snapshot() {
  const position = getPosition()
  const duration = state.duration || 0
  const lines = state.lyrics.lines || []
  const index = state.lyricIndex
  return {
    ready: state.ready,
    connected: state.connected,
    mock: state.mock,
    playing: state.playing,
    title: state.title,
    artist: state.artist,
    album: state.album,
    duration: duration,
    position: position,
    percent: duration > 0 ? Math.max(0, Math.min(100, Math.round((position / duration) * 100))) : 0,
    positionText: lrc.formatTime(position),
    durationText: lrc.formatTime(duration),
    lyricIndex: index,
    lines: lines,
    lyricWindow: lrc.sliceAround(lines, index, 2, 3),
    hasLyrics: lines.length > 0,
    offsetMs: state.offsetMs,
    offsetText: (state.offsetMs / 1000).toFixed(1) + 's',
    follow: state.follow,
    autoSave: state.autoSave,
    fontSize: state.fontSize,
    swipeStep: state.swipeStep,
    vibrate: state.vibrate,
    currentKey: state.currentKey,
    transferActive: transfer.id !== '' && transfer.received < transfer.total,
    transferReceived: transfer.received,
    transferTotal: transfer.total,
    transferPercent: transfer.total > 0
      ? Math.max(0, Math.min(100, Math.round((transfer.received / transfer.total) * 100)))
      : 0,
    lastError: state.lastError,
    source: state.source
  }
}

function setLyricsText(text, title, artist, key) {
  state.lyricsText = text || ''
  state.lyrics = lrc.parse(text || '', title, artist)
  state.currentKey = key || ''
  state.lyricIndex = computeIndex()
}

async function loadLyricsFor(title, artist, force) {
  const song = (title || '') + '|' + (artist || '')
  if (!force && song === state.currentSong) {
    return
  }
  state.currentSong = song
  const token = ++lyricsToken
  try {
    const item = await library.find(title, artist)
    if (token !== lyricsToken) {
      return
    }
    if (item) {
      const text = await library.read(item.key)
      if (token !== lyricsToken) {
        return
      }
      setLyricsText(text, title || item.title, artist || item.artist, item.key)
      debug.mark('lyrics', '本地命中 ' + (item.title || title))
    } else {
      setLyricsText('', title, artist, '')
    }
  } catch (e) {
    debug.error('lyrics', 'loadLyricsFor', e)
    setLyricsText('', title, artist, '')
  }
  emit('lyrics')
}

async function handleState(payload) {
  const p = payload || {}
  const previous = state.title + '|' + state.artist
  if (state.mock) {
    state.mock = false
    store.save('mock', '0')
    chunkMap = {}
  }
  state.connected = true
  state.source = p.source || 'phone'
  state.playing = !!p.playing
  state.title = p.title || ''
  state.artist = p.artist || ''
  state.album = p.album || ''
  state.duration = Math.max(0, Number(p.duration) || 0)
  state.position = Math.max(0, (Number(p.position) || 0) + (p.playing ? 120 : 0))
  state.anchorAt = Date.now()
  state.lastError = ''
  const current = state.title + '|' + state.artist
  // 以前这里还有 `|| !state.lyrics.lines.length`，导致没有歌词的歌每秒都去扫一遍词库，
  // 同时把 lyricsToken 不断自增，正在加载的歌词永远被判定为过期 -> 歌词始终不显示。
  if (current !== previous) {
    // 换歌先清空旧歌词，避免新歌词到达前一直显示上一首的词
    state.lyricsText = ''
    state.lyrics = { meta: {}, lines: [] }
    state.currentKey = ''
    state.lyricIndex = -1
    resetTransfer()
    loadLyricsFor(state.title, state.artist, true)
  }
  state.lyricIndex = computeIndex()
  emit('state')
}

async function handleLyrics(payload) {
  const p = payload || {}
  const title = p.title || state.title
  const artist = p.artist || state.artist
  // 迟到的旧歌歌词：换歌后仍在路上的分片，绝不能覆盖当前这首歌
  if (!state.mock && state.currentSong && (title + '|' + artist) !== state.currentSong) {
    debug.mark('lyrics', '丢弃旧歌歌词 ' + title)
    return
  }
  let text = typeof p.lrc === 'string' ? p.lrc : ''
  if (text.length > MAX_LYRICS) {
    text = text.slice(0, MAX_LYRICS)
  }
  let key = ''
  if (p.persist && state.autoSave && text) {
    try {
      key = await library.save(title, artist, text.slice(0, 3000))
    } catch (e) {
      debug.error('lyrics', 'library.save', e)
    }
  }
  setLyricsText(text, title, artist, key)
  emit('lyrics')
  debug.mark('lyrics', '应用歌词 ' + text.length + ' 字')
  if (key) {
    // 词库有变化就告诉手机一次，手机端「歌词库」是手环这本的镜像
    reportLibraryLater()
  }
}

function handleChunk(payload) {
  const p = payload || {}
  try {
    const now = Date.now()
    Object.keys(chunkMap).forEach(function (key) {
      if (now - (chunkMap[key].at || 0) > 15000) {
        delete chunkMap[key]
        if (transfer.id === key) {
          resetTransfer()
          emit('transfer')
        }
      }
    })
    const id = p.id || 'default'
    if (!chunkMap[id]) {
      chunkMap[id] = {
        total: Math.max(1, Number(p.total) || 1),
        parts: [],
        meta: p,
        at: now
      }
    }
    const entry = chunkMap[id]
    entry.at = now
    entry.meta = p
    transfer.id = id
    transfer.total = entry.total
    transfer.at = now
    const index = Number(p.index) || 0
    entry.parts[index] = typeof p.text === 'string' ? p.text : ''
    let received = 0
    for (let i = 0; i < entry.parts.length; i++) {
      if (typeof entry.parts[i] === 'string') {
        received++
      }
    }
    transfer.received = received
    emit('transfer')
    // 逐包回执：手机端靠它做「发一包等一包」，不会一口气灌爆通信缓冲
    transport.send(protocol.envelope(protocol.TYPES.ACK, {
      for: 'chunk',
      id: id,
      i: index,
      ok: true
    })).catch(function () {})
    if (received >= entry.total) {
      let text = entry.parts.join('')
      if (text.length > MAX_LYRICS) {
        text = text.slice(0, MAX_LYRICS)
      }
      const meta = entry.meta
      delete chunkMap[id]
      resetTransfer()
      handleLyrics({
        title: meta.title,
        artist: meta.artist,
        lrc: text,
        persist: meta.persist
      })
      transport.send(protocol.envelope(protocol.TYPES.ACK, {
        for: 'lyrics',
        id: id,
        ok: true,
        chars: text.length
      })).catch(function () {})
    }
  } catch (e) {
    debug.error('lyrics', 'handleChunk', e)
  }
}

function handleMessage(msg) {
  if (!msg) {
    return
  }
  if (msg.type === protocol.TYPES.PING) {
    lastPingAt = Date.now()
    transport.send(protocol.envelope(protocol.TYPES.PONG, {
      client: 'band',
      ready: state.ready,
      booted: true
    })).catch(function () {})
    if (Date.now() - lastPingLogAt > 10000) {
      lastPingLogAt = Date.now()
      debug.mark('app', '收到手机 ping，已回 pong')
    }
    return
  }
  if (msg.type === protocol.TYPES.ACK) {
    return
  }
  if (msg.type === protocol.TYPES.STATE) {
    handleState(msg.payload)
    return
  }
  if (msg.type === protocol.TYPES.LYRICS) {
    handleLyrics(msg.payload)
    return
  }
  if (msg.type === protocol.TYPES.LYRICS_CHUNK) {
    handleChunk(msg.payload)
    return
  }
  if (msg.type === protocol.TYPES.HELLO) {
    requestSync()
    reportLibraryLater()
    return
  }
  if (msg.type === protocol.TYPES.LIBRARY_REQUEST) {
    sendLibrary().catch(function (e) {
      debug.error('app', 'sendLibrary', e)
    })
    return
  }
  if (msg.type === protocol.TYPES.LIBRARY_DELETE) {
    handleLibraryDelete(msg.payload)
  }
}

function applyMock(index, position) {
  const tracks = demo.demos
  mockIndex = ((index % tracks.length) + tracks.length) % tracks.length
  const track = tracks[mockIndex]
  mockPosition = position || 0
  mockAnchor = Date.now()
  state.mock = true
  state.source = 'demo'
  state.title = track.title
  state.artist = track.artist
  state.album = '夜航手记'
  state.duration = demo.demoDuration
  state.playing = mockPlaying
  const parsed = lrc.parse(track.lrc, track.title, track.artist)
  state.lyricsText = track.lrc
  state.lyrics = parsed
  state.currentKey = ''
  state.currentSong = track.title + '|' + track.artist
  state.lyricIndex = computeIndex()
  emit('state')
}

function mockAction(action, value) {
  if (action === 'toggle') {
    mockPlaying = !mockPlaying
    if (mockPlaying) {
      mockAnchor = Date.now()
    } else {
      mockPosition = getPosition()
    }
    state.playing = mockPlaying
  } else if (action === 'play') {
    if (!mockPlaying) {
      mockPlaying = true
      mockAnchor = Date.now()
    }
    state.playing = true
  } else if (action === 'pause') {
    if (mockPlaying) {
      mockPosition = getPosition()
      mockPlaying = false
    }
    state.playing = false
  } else if (action === 'seek') {
    mockPosition = Math.max(0, Number(value) || 0)
    mockAnchor = Date.now()
  } else if (action === 'next') {
    applyMock(mockIndex + 1, 0)
    return
  } else if (action === 'prev') {
    applyMock(mockIndex - 1, 0)
    return
  } else if (action === 'sync' || action === 'lyrics') {
    return
  }
  state.lyricIndex = computeIndex()
  emit('state')
}

function command(action, value) {
  if (state.mock) {
    mockAction(action, value)
    return Promise.resolve(true)
  }
  return transport.send(protocol.command(action, value)).then(function (ok) {
    debug.mark('cmd', action + ' 已发送')
    return ok
  }).catch(function (err) {
    const message = err && err.message ? err.message : String(err)
    state.lastError = message
    debug.error('cmd', action + ' 失败：' + message, err)
    emit('error')
    throw err
  })
}

function requestSync() {
  return command('sync')
}

function requestLyrics() {
  return command('lyrics')
}

function toggle() {
  return command('toggle')
}

function play() {
  return command('play')
}

function pause() {
  return command('pause')
}

function next() {
  return command('next')
}

function previous() {
  return command('prev')
}

function seekTo(ms) {
  const target = Math.max(0, Math.round(ms))
  if (!state.mock) {
    state.position = target
    state.anchorAt = Date.now()
    state.lyricIndex = computeIndex()
    emit('line')
  }
  return command('seek', target)
}

function adjustOffset(delta) {
  state.offsetMs += delta
  store.save('offset', state.offsetMs)
  state.lyricIndex = computeIndex()
  emit('line')
}

function resetOffset() {
  state.offsetMs = 0
  store.save('offset', 0)
  state.lyricIndex = computeIndex()
  emit('line')
}

function setMock(enabled) {
  state.mock = !!enabled
  store.save('mock', enabled ? '1' : '0')
  if (enabled) {
    mockPlaying = true
    applyMock(mockIndex, 0)
  } else {
    if (state.connected) {
      requestSync().catch(function () {})
    }
    state.title = ''
    state.artist = ''
    state.album = ''
    state.lyricsText = ''
    state.lyrics = { meta: {}, lines: [] }
    state.lyricIndex = -1
    state.playing = false
    state.duration = 0
    emit('state')
  }
}

function clampInt(value, min, max, fallback) {
  const n = Number(value)
  if (isNaN(n)) {
    return fallback
  }
  return Math.max(min, Math.min(max, Math.round(n)))
}

function setFontSize(size) {
  state.fontSize = clampInt(size, 0, 2, 1)
  store.save('fontSize', state.fontSize)
  emit('settings')
}

function setSwipeStep(step) {
  state.swipeStep = clampInt(step, 1, 3, 1)
  store.save('swipeStep', state.swipeStep)
  emit('settings')
}

function setVibrate(enabled) {
  state.vibrate = !!enabled
  store.save('vibrate', enabled ? '1' : '0')
  emit('settings')
}

function setFollow(enabled) {
  state.follow = !!enabled
  store.save('follow', enabled ? '1' : '0')
  emit('settings')
}

function setAutoSave(enabled) {
  state.autoSave = !!enabled
  store.save('autoSave', enabled ? '1' : '0')
  emit('settings')
}

function handleStatus(status) {
  const was = linkUp
  state.connected = !!status.connected
  linkUp = state.connected
  if (status.lastError) {
    state.lastError = status.lastError
  }
  // 只在「断开 -> 连上」这一下打日志、发握手；以前每次状态回调都发，
  // 重连时会连着灌好几条消息，正是重启的触发点。
  if (!linkUp || was) {
    emit('status')
    return
  }
  debug.mark('link', '设备通信已接通 apkStatus=' +
    (status.apkStatus === undefined ? '?' : status.apkStatus))
  transport.send(protocol.envelope(protocol.TYPES.HELLO, {
    client: 'band',
    name: '歌词播放器'
  })).catch(function () {})
  emit('status')
}

function tick() {
  if (!state.ready) {
    return
  }
  const index = computeIndex()
  if (index !== state.lyricIndex) {
    state.lyricIndex = index
    emit('line')
  }
  emit('tick')
}

async function init() {
  if (initPromise) {
    return initPromise
  }
  initPromise = (async function () {
    debug.mark('init', '开始初始化')
    let settings = store.DEFAULTS
    try {
      settings = await store.load()
      debug.mark('init', 'store.load 完成 mock=' + settings.mock)
    } catch (e) {
      debug.error('init', 'store.load', e)
    }
    state.mock = settings.mock
    state.follow = settings.follow
    state.autoSave = settings.autoSave
    state.offsetMs = settings.offset || 0
    state.fontSize = clampInt(settings.fontSize, 0, 2, 1)
    state.swipeStep = clampInt(settings.swipeStep, 1, 3, 1)
    state.vibrate = settings.vibrate !== false
    try {
      await library.seed(demo.demos)
      debug.mark('init', 'library.seed 完成')
    } catch (e) {
      debug.error('init', 'library.seed', e)
    }
    try {
      transport.init()
      transport.onMessage(handleMessage)
      transport.onStatus(handleStatus)
      debug.mark('init', 'transport 已就绪')
    } catch (e) {
      debug.error('init', 'transport', e)
    }
    try {
      if (state.mock) {
        applyMock(0, 0)
      } else {
        const first = await library.list()
        if (first.length) {
          const text = await library.read(first[0].key)
          setLyricsText(text, first[0].title, first[0].artist, first[0].key)
        }
      }
      debug.mark('init', '内容已就绪 title=' + state.title)
    } catch (e) {
      debug.error('init', 'content', e)
    }
    state.ready = true
    startTimer()
    emit('ready')
    debug.mark('init', '初始化完成')
  })()
  return initPromise
}

async function saveCurrentLyrics() {
  if (!state.lyricsText) {
    return ''
  }
  const key = await library.save(state.title, state.artist, state.lyricsText)
  if (key) {
    state.currentKey = key
    emit('lyrics')
    reportLibraryLater()
  }
  return key
}

// ---------- 词库同步（手环 -> 手机） ----------

// 300 字一片：手环的发送通道同样吃不下大包，分片 + 间隔最稳
const LIBRARY_CHUNK = 300
const LIBRARY_GAP_MS = 90

let libraryTimer = null
let libraryBusy = false
let libraryDirty = false

function wait(ms) {
  return new Promise(function (resolve) {
    setTimeout(resolve, ms)
  })
}

// 连续写词库时只在最后一次之后上报，避免一次换歌发两遍
function reportLibraryLater() {
  libraryDirty = true
  if (libraryTimer) {
    return
  }
  libraryTimer = setTimeout(function () {
    libraryTimer = null
    if (libraryBusy) {
      return
    }
    sendLibrary().catch(function () {})
  }, 1200)
}

async function sendLibrary() {
  if (libraryBusy) {
    libraryDirty = true
    return false
  }
  libraryBusy = true
  libraryDirty = false
  try {
    const items = await library.list()
    const rows = []
    for (let i = 0; i < items.length; i++) {
      const item = items[i]
      rows.push([
        item.key || '',
        item.title || '',
        item.artist || '',
        String(item.lines || 0)
      ].join('\t'))
    }
    const text = rows.join('\n')
    const id = 'lib' + Date.now()
    const total = Math.max(1, Math.ceil(text.length / LIBRARY_CHUNK))
    for (let seq = 0; seq < total; seq++) {
      const part = text.slice(seq * LIBRARY_CHUNK, (seq + 1) * LIBRARY_CHUNK)
      await transport.send(protocol.envelope(protocol.TYPES.LIBRARY_CHUNK, {
        id: id,
        seq: seq,
        total: total,
        text: part,
        count: items.length
      }))
      if (seq + 1 < total) {
        await wait(LIBRARY_GAP_MS)
      }
    }
    debug.mark('app', '已上报词库 ' + items.length + ' 首')
    return true
  } catch (e) {
    debug.error('app', 'sendLibrary', e)
    return false
  } finally {
    libraryBusy = false
    if (libraryDirty && !libraryTimer) {
      libraryTimer = setTimeout(function () {
        libraryTimer = null
        sendLibrary().catch(function () {})
      }, 800)
    }
  }
}

async function handleLibraryDelete(payload) {
  const key = (payload && payload.key) || ''
  if (!key) {
    return
  }
  try {
    const ok = await library.remove(key)
    if (state.currentKey === key) {
      state.currentKey = ''
    }
    debug.mark('app', '删除词库 ' + key + ' -> ' + ok)
    emit('lyrics')
  } catch (e) {
    debug.error('app', 'library.delete', e)
  }
  libraryBusy = false
  sendLibrary().catch(function () {})
}

function getLyrics() {
  return {
    title: state.title,
    artist: state.artist,
    text: state.lyricsText,
    meta: state.lyrics.meta,
    lines: state.lyrics.lines,
    index: state.lyricIndex,
    currentKey: state.currentKey
  }
}

function setActive(active) {
  if (!state.ready) {
    return
  }
  if (active) {
    startTimer()
    state.lyricIndex = computeIndex()
    emit('tick')
  } else {
    stopTimer()
  }
}

function dispose() {
  stopTimer()
  listeners = []
}

function getState() {
  return state
}

export default {
  init: init,
  subscribe: subscribe,
  snapshot: snapshot,
  getLyrics: getLyrics,
  getState: getState,
  setActive: setActive,
  dispose: dispose,
  getPosition: getPosition,
  toggle: toggle,
  play: play,
  pause: pause,
  next: next,
  previous: previous,
  seekTo: seekTo,
  requestSync: requestSync,
  requestLyrics: requestLyrics,
  adjustOffset: adjustOffset,
  resetOffset: resetOffset,
  setMock: setMock,
  setFollow: setFollow,
  setAutoSave: setAutoSave,
  setFontSize: setFontSize,
  setSwipeStep: setSwipeStep,
  setVibrate: setVibrate,
  saveCurrentLyrics: saveCurrentLyrics,
  loadLyricsFor: loadLyricsFor,
  sendLibrary: sendLibrary
}
