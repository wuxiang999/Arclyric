import transport from './transport.js'
import protocol from './protocol.js'
import lrc from './lrc.js'
import store from './store.js'
import library from './library.js'
import demo from './demo-lyrics.js'
import debug from './debug.js'

// 手环 JS 堆很小：单曲歌词超过这个长度直接截断，避免解析/渲染把内存打爆
const MAX_LYRICS = 12000

// 歌名兜底：词库文件名就是「歌名 - 歌手.lrc」。
// 手机上抓不到媒体元数据时，靠这个文件名的前半段也能把歌名显示出来。
function keyLabel(key) {
  const base = String(key || '').replace(/\.lrc$/i, '').trim()
  if (!base) {
    return { title: '', artist: '' }
  }
  const index = base.indexOf(' - ')
  if (index > 0) {
    return {
      title: base.slice(0, index).trim(),
      artist: base.slice(index + 3).trim()
    }
  }
  return { title: base, artist: '' }
}

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
  autoSave: false,
  fontSize: 1,
  swipeStep: 1,
  vibrate: true,
  browseIdle: 6,
  offsetStep: 500,
  showProgress: true,
  volume: 70,
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
// 最后一次收到手机 STATE 的时间：太久没更新就主动要一次，防止停在上一首
let lastStateAt = 0
let lastWatchdogAt = 0
// 上一次已知的连接状态，用于只在「断开 -> 连上」时发握手
let linkUp = false

// ---------- 退出恢复 ----------
// 退出、断开、切后台都会把「当前歌曲 + 进度」写进 lyric.last；
// 下次进来自动恢复标题、时长、进度和本地歌词，可以直接接着播。
const LAST_KEY = 'lyric.last'
let persistTimer = null
let lastPersistAt = 0

function lastPayload() {
  return JSON.stringify({
    v: 1,
    title: state.title || '',
    artist: state.artist || '',
    album: state.album || '',
    duration: Math.round(state.duration || 0),
    position: Math.round(getPosition()),
    playing: !!state.playing,
    mock: !!state.mock,
    mockIndex: mockIndex,
    mockPosition: Math.round(mockPosition),
    key: state.currentKey || '',
    volume: Math.round(state.volume),
    at: Date.now()
  })
}

async function persistNow() {
  if (!state.ready) {
    return false
  }
  if (persistTimer) {
    clearTimeout(persistTimer)
    persistTimer = null
  }
  lastPersistAt = Date.now()
  try {
    return await store.save('last', lastPayload())
  } catch (e) {
    debug.error('state', 'persistNow', e)
    return false
  }
}

// delay 不传时等 1.2s 合并连续变化；传 0 表示尽快写但也不阻塞当前流程
function persistSoon(delay) {
  if (!state.ready) {
    return
  }
  if (persistTimer) {
    return
  }
  const wait = typeof delay === 'number' ? Math.max(0, delay) : 1200
  persistTimer = setTimeout(function () {
    persistTimer = null
    persistNow()
  }, wait)
}

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
    title: state.title || keyLabel(state.currentKey).title,
    artist: state.artist || keyLabel(state.currentKey).artist,
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
    browseIdle: state.browseIdle,
    offsetStep: state.offsetStep,
    showProgress: state.showProgress,
    volume: Math.max(0, Math.min(100, Math.round(state.volume))),
    volumeLevel: Math.max(0, Math.min(100, Math.round(state.volume / 10) * 10)),
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

// ---------- 歌词缓存 ----------
// 每个页面是独立 JS 环境，播放页收到的歌词进了歌词页就看不见了，
// 于是每次点开歌词页都要找手机再收一遍分包。这里把最近一首歌词落到本地：
// 不写进用户词库（不污染歌词本），只用来「同一首歌别再收第二次」。
function normSong(text) {
  return String(text || '').trim().toLowerCase().replace(/\s+/g, '')
}

function cacheMatches(cache, title, artist) {
  if (!cache) {
    return false
  }
  const sameTitle = normSong(cache.t) === normSong(title)
  const cacheArtist = normSong(cache.a)
  const nowArtist = normSong(artist)
  if (!sameTitle) {
    return false
  }
  // 歌手任意一边为空就不参与比较，标题对上就算同一首
  if (!cacheArtist || !nowArtist) {
    return true
  }
  return cacheArtist === nowArtist
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
      // 手机没给歌名/歌手时，用词库文件名补上（文件名就是「歌名 - 歌手」）
      if (!state.title) {
        state.title = item.title || ''
      }
      if (!state.artist) {
        state.artist = item.artist || ''
      }
      setLyricsText(text, title || item.title, artist || item.artist, item.key)
      debug.mark('lyrics', '本地命中 ' + (item.title || title))
    } else {
      // 词库没存过，但刚刚从手机收过同一首：直接用缓存，别再让手机重发一遍
      const cache = await library.readLastLyrics()
      if (token !== lyricsToken) {
        return
      }
      if (cacheMatches(cache, title, artist)) {
        setLyricsText(cache.lrc, title || cache.t, artist || cache.a, '')
        debug.mark('lyrics', '缓存命中 ' + (cache.t || title))
      } else {
        setLyricsText('', title, artist, '')
      }
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
  // 手机偶尔拿不到标题（部分播放器元数据为空）：保留上一首标题，
  // 实在没有就用本地歌词文件名兜底，别让界面变成空白或旧信息
  if (p.title) {
    state.title = p.title
    state.artist = p.artist || ''
  }
  if (p.album) {
    state.album = p.album
  }
  state.duration = Math.max(0, Number(p.duration) || 0)
  state.position = Math.max(0, (Number(p.position) || 0) + (p.playing ? 120 : 0))
  state.anchorAt = Date.now()
  state.lastError = ''
  lastStateAt = Date.now()
  if (p.volume !== undefined && p.volume !== null) {
    state.volume = clampInt(p.volume, 0, 100, state.volume)
  }
  if (!state.title && state.currentKey) {
    const label = keyLabel(state.currentKey)
    state.title = label.title
    state.artist = state.artist || label.artist
  }
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
  persistSoon(1500)
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
      // 存全文：之前只存前 3000 字，长歌在歌词本里会莫名少一大截
      key = await library.save(title, artist, text)
    } catch (e) {
      debug.error('lyrics', 'library.save', e)
    }
  }
  setLyricsText(text, title, artist, key)
  // 缓存最近一首：换页/退出重进都能立刻显示，不用向手机再收一遍分包
  library.saveLastLyrics(title, artist, text).catch(function () {})
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
    // 手机重连后会主动要状态；词库不在这里上报 ——
    // 以前每条 hello 都触发一次词库上报，手机回的 hello 和手环的词库包来回叠加，
    // 通道一直满着，最后就是两边一起超时。词库只在真的写入了新歌时上报。
    const now = Date.now()
    if (now - lastHelloAt > 5000) {
      lastHelloAt = now
      requestSync()
    }
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
  if (state.ready) {
    persistSoon(800)
  }
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
  persistSoon(800)
}

function command(action, value) {
  if (state.mock) {
    mockAction(action, value)
    return Promise.resolve(true)
  }
  return transport.send(protocol.command(action, value)).then(function (ok) {
    debug.mark('cmd', action + ' 已发送')
    // 暂停/拖动/切歌都由手机回 STATE 确认，但先落一次盘，避免回包丢了就丢进度
    persistSoon(800)
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

// 页面重新可见时补一次状态。手环上每个页面各自持有一份状态，切页/熄屏期间
// 漏掉的状态包靠这个立刻补回来，不用等用户发现「还停在上一首和结尾秒数」。
function requestResync(maxAge) {
  if (state.mock || !state.connected) {
    return Promise.resolve(false)
  }
  const age = lastStateAt > 0 ? Date.now() - lastStateAt : 999999
  if (age < (maxAge || 4000)) {
    return Promise.resolve(false)
  }
  if (Date.now() - lastWatchdogAt < 3000) {
    return Promise.resolve(false)
  }
  lastWatchdogAt = Date.now()
  debug.mark('state', '页面回到前台，请求同步')
  return requestSync()
}

// 页面切换后，通信实例的 onmessage 有可能被后初始化的页面接管（每个页面各自
// 一份模块实例）。页面重新可见时把消息处理挂回自己，避免「明明连着却收不到状态」。
function reattach() {
  try {
    transport.init()
    transport.onMessage(handleMessage)
  } catch (e) {
    debug.error('state', 'reattach', e)
  }
}

// 请求手机重推当前歌曲的歌词。
// 关键：已经拿到这首歌的歌词就不再要第二次 —— 之前播放页要一次、点开歌词页又要一次，
// 手机上就会把同一份歌词分片发两遍。force 只在用户手动「重试」时传。
let lyricsRequestAt = 0
let lyricsRequestKey = ''
function requestLyrics(force) {
  if (state.mock) {
    return Promise.resolve(false)
  }
  const key = state.title + '|' + state.artist
  const now = Date.now()
  if (force) {
    // 手动重试也限一下：5 秒内连点不会连着发请求
    if (now - lyricsRequestAt < 5000) {
      return Promise.resolve(false)
    }
  } else {
    if (state.lyrics.lines.length && state.currentSong === key) {
      return Promise.resolve(false)
    }
    if (lyricsRequestKey === key && now - lyricsRequestAt < 15000) {
      return Promise.resolve(false)
    }
  }
  lyricsRequestKey = key
  lyricsRequestAt = now
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

function setBrowseIdle(seconds) {
  state.browseIdle = clampInt(seconds, 3, 10, 6)
  store.save('browseIdle', state.browseIdle)
  emit('settings')
}

function setOffsetStep(ms) {
  state.offsetStep = clampInt(ms, 100, 1000, 500)
  store.save('offsetStep', state.offsetStep)
  emit('settings')
}

function setShowProgress(enabled) {
  state.showProgress = !!enabled
  store.save('showProgress', enabled ? '1' : '0')
  emit('settings')
}

// 音量：手环只发 0-100 的目标值，手机那边换算成系统音乐音量
function setVolume(value) {
  const next = clampInt(value, 0, 100, state.volume)
  state.volume = next
  emit('settings')
  if (state.mock || !state.connected) {
    return Promise.resolve(true)
  }
  return command('volume', next)
}

function volumeStep(delta) {
  return setVolume(state.volume + (Number(delta) || 0))
}

// 恢复默认设置：只还原播放/显示偏好，不动演示模式与当前连接，
// 避免用户点一下就被切回演示曲目。
function resetSettings() {
  const d = store.DEFAULTS
  state.follow = d.follow !== false
  state.autoSave = !!d.autoSave
  state.vibrate = d.vibrate !== false
  state.fontSize = clampInt(d.fontSize, 0, 2, 1)
  state.swipeStep = clampInt(d.swipeStep, 1, 3, 1)
  state.browseIdle = clampInt(d.browseIdle, 3, 10, 6)
  state.offsetStep = clampInt(d.offsetStep, 100, 1000, 500)
  state.showProgress = d.showProgress !== false
  state.offsetMs = 0
  store.save('follow', state.follow ? '1' : '0')
  store.save('autoSave', state.autoSave ? '1' : '0')
  store.save('vibrate', state.vibrate ? '1' : '0')
  store.save('fontSize', state.fontSize)
  store.save('swipeStep', state.swipeStep)
  store.save('browseIdle', state.browseIdle)
  store.save('offsetStep', state.offsetStep)
  store.save('showProgress', state.showProgress ? '1' : '0')
  store.save('offset', 0)
  state.lyricIndex = computeIndex()
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
  // 断开的这一下立刻落盘：等 1.2s 可能已经被系统杀掉了，进度就丢了
  if (was && !linkUp) {
    linkDownAt = Date.now()
    debug.mark('link', '设备通信断开，保存当前进度')
    persistNow()
  }
  if (!linkUp || was) {
    emit('status')
    return
  }
  // 真重连要满足两条：断开够久（>=1.5s）或从来没连过，并且不在 hello 冷却里。
  // 收发超时抖动会连着上报「已接通」，不能每次都当成新连接。
  const now = Date.now()
  const realReconnect = !lastHelloAt || (linkDownAt && now - linkDownAt >= 1500)
  if (!realReconnect || now - lastHelloAt < HELLO_GAP_MS) {
    emit('status')
    return
  }
  lastHelloAt = now
  linkDownAt = 0
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
  // 手机切歌手环没收到新状态时，会永远停在上一首和结尾秒数。
  // 播到尾 + 6 秒没收到状态，都主动要一次，5 秒最多一次避免刷屏。
  // 手机端每 2 秒兜底发一次状态，6 秒收不到说明这轮消息丢了（换页、通道抖动），
  // 早一点补要，比等 20 秒让用户看着上一首强。
  if (!state.mock && state.connected) {
    const now = Date.now()
    const atEnd = state.playing && state.duration > 0 && getPosition() >= state.duration - 1200
    const stale = lastStateAt > 0 && now - lastStateAt > 6000
    if ((atEnd || stale) && now - lastWatchdogAt > 5000) {
      lastWatchdogAt = now
      debug.mark('state', atEnd ? '播放到结尾，请求同步' : '状态超时，请求同步')
      requestSync().catch(function () {})
    }
  }
  // 播放中每 15 秒落一次盘：直接断电/被杀也能恢复到差不多的地方
  if (Date.now() - lastPersistAt > 15000) {
    persistSoon(0)
  }
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
    state.browseIdle = clampInt(settings.browseIdle, 3, 10, 6)
    state.offsetStep = clampInt(settings.offsetStep, 100, 1000, 500)
    state.showProgress = settings.showProgress !== false
    // 示例歌词只在「第一次安装」时写进词库一次。
    // 以前每次启动都 seed，用户删掉的示例会被原样写回来，表现为歌词本删不掉。
    try {
      const seeded = await store.get('lyric.seeded')
      if (seeded !== '1') {
        await library.seed(demo.demos)
        await store.save('seeded', '1')
        debug.mark('init', 'library.seed 完成（首次）')
      } else {
        debug.mark('init', 'library.seed 跳过（已初始化过）')
      }
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
    let last = null
    try {
      const raw = await store.get(LAST_KEY)
      if (raw) {
        last = JSON.parse(raw)
      }
    } catch (e) {
      debug.error('init', 'restore.last', e)
      last = null
    }
    try {
      if (last && last.volume !== undefined) {
        state.volume = clampInt(last.volume, 0, 100, state.volume)
      }
      if (state.mock) {
        // 演示模式：接着上次那首、那个位置继续
        const idx = last && last.mock ? Number(last.mockIndex) || 0 : 0
        const pos = last && last.mock ? Math.max(0, Number(last.mockPosition) || 0) : 0
        mockPlaying = last && last.mock ? last.playing !== false : true
        applyMock(idx, pos)
      } else if (last && (last.title || last.key)) {
        // 真实歌曲：恢复标题、时长和退出时的进度，歌词从本地词库取。
        // 标题为空时用歌词文件名兜底（文件名就是「歌名 - 歌手」）。
        const label = keyLabel(last.key)
        state.title = last.title || label.title || ''
        state.artist = last.artist || label.artist || ''
        state.album = last.album || ''
        state.duration = Math.max(0, Number(last.duration) || 0)
        state.position = Math.max(0, Number(last.position) || 0)
        // 恢复成暂停：断开手机时进度条不能自己往前跑，但一按播放就接着放
        state.playing = false
        state.anchorAt = Date.now()
        state.source = 'restore'
        state.lastError = ''
        state.currentSong = ''
        await loadLyricsFor(state.title, state.artist, true)
        debug.mark('init', '恢复上次：' + state.title + ' @' + state.position)
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
// 握手冷却：通信层一旦抖动就会连着回调「已接通」，
// 以前每次回调都发一条 hello，手机收到就重推一整首歌词，来回放大成发包风暴。
let lastHelloAt = 0
let linkDownAt = 0
const HELLO_GAP_MS = 20000

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
  persistNow()
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
  requestResync: requestResync,
  reattach: reattach,
  requestLyrics: requestLyrics,
  adjustOffset: adjustOffset,
  resetOffset: resetOffset,
  setMock: setMock,
  setFollow: setFollow,
  setAutoSave: setAutoSave,
  setFontSize: setFontSize,
  setSwipeStep: setSwipeStep,
  setVibrate: setVibrate,
  setBrowseIdle: setBrowseIdle,
  setOffsetStep: setOffsetStep,
  setShowProgress: setShowProgress,
  setVolume: setVolume,
  volumeStep: volumeStep,
  resetSettings: resetSettings,
  saveCurrentLyrics: saveCurrentLyrics,
  loadLyricsFor: loadLyricsFor,
  sendLibrary: sendLibrary,
  persistNow: persistNow,
  persistSoon: persistSoon
}
