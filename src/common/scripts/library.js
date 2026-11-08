import file from '@system.file'

const DIR = 'internal://files/lyrics/'

// 目录索引缓存：以前每次 find() 都要把所有 .lrc 逐个读一遍，
// 手环上每秒触发一次就会把 CPU/IO 打满，进而看门狗重启。
let listCache = null

function invalidate() {
  listCache = null
}

function ensureDir() {
  return new Promise(function (resolve) {
    file.mkdir({
      uri: DIR,
      recursive: true,
      success: function () {
        resolve(true)
      },
      fail: function () {
        resolve(false)
      }
    })
  })
}

function sanitize(name) {
  return String(name || '未命名')
    .replace(/[\\/:*?"<>|\n\r\t]/g, '_')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 64)
}

function fileName(title, artist) {
  const t = sanitize(title || '未命名')
  const a = sanitize(artist || '')
  return (a ? t + ' - ' + a : t) + '.lrc'
}

function normalizeKey(uri) {
  if (!uri) {
    return ''
  }
  const value = String(uri)
  if (value.indexOf('internal://') === 0) {
    const index = value.lastIndexOf('/')
    return index >= 0 ? value.slice(index + 1) : value
  }
  const index = value.lastIndexOf('/')
  return index >= 0 ? value.slice(index + 1) : value
}

function uriOf(key) {
  if (String(key).indexOf('internal://') === 0) {
    return String(key)
  }
  return DIR + key
}

function listRaw() {
  return new Promise(function (resolve) {
    file.list({
      uri: DIR,
      success: function (data) {
        const items = (data && data.fileList) || []
        resolve(items.filter(function (item) {
          return /\.lrc$/i.test(item.uri)
        }))
      },
      fail: function () {
        resolve([])
      }
    })
  })
}

function readText(key) {
  return new Promise(function (resolve) {
    file.readText({
      uri: uriOf(key),
      success: function (data) {
        resolve((data && data.text) || '')
      },
      fail: function () {
        resolve('')
      }
    })
  })
}

function writeText(key, text) {
  return new Promise(function (resolve) {
    file.writeText({
      uri: DIR + key,
      text: text,
      success: function () {
        resolve(true)
      },
      fail: function () {
        resolve(false)
      }
    })
  })
}

function exists(key) {
  return new Promise(function (resolve) {
    file.access({
      uri: DIR + key,
      success: function () {
        resolve(true)
      },
      fail: function () {
        resolve(false)
      }
    })
  })
}

function removeFile(key) {
  return new Promise(function (resolve) {
    file.delete({
      uri: DIR + key,
      success: function () {
        invalidate()
        resolve(true)
      },
      fail: function () {
        resolve(false)
      }
    })
  })
}

// 统计 LRC 时间轴行数，手机端词库列表直接用这个数字
function countLines(text) {
  let total = 0
  const lines = String(text || '').split(/\r?\n/)
  for (let i = 0; i < lines.length; i++) {
    if (/^\[\d{1,2}:\d{1,2}/.test(lines[i].trim())) {
      total++
    }
  }
  return total
}

function parseHeader(text) {
  const result = { title: '', artist: '' }
  const lines = String(text || '').split(/\r?\n/)
  for (let i = 0; i < Math.min(lines.length, 12); i++) {
    const line = lines[i].trim()
    const title = line.match(/^\[ti:(.*)\]$/i)
    const artist = line.match(/^\[ar:(.*)\]$/i)
    if (title) {
      result.title = title[1].trim()
    }
    if (artist) {
      result.artist = artist[1].trim()
    }
  }
  return result
}

async function list() {
  if (listCache) {
    return listCache
  }
  await ensureDir()
  const items = await listRaw()
  items.sort(function (a, b) {
    return (b.lastModifiedTime || 0) - (a.lastModifiedTime || 0)
  })
  const result = []
  for (let i = 0; i < items.length; i++) {
    const key = normalizeKey(items[i].uri)
    const text = await readText(key)
    const header = parseHeader(text)
    let title = header.title
    let artist = header.artist
    if (!title) {
      const base = key.replace(/\.lrc$/i, '')
      const parts = base.split(' - ')
      title = parts[0]
      artist = parts[1] || ''
    }
    result.push({
      key: key,
      title: title,
      artist: artist,
      lines: countLines(text),
      size: items[i].length || 0,
      updatedAt: items[i].lastModifiedTime || 0
    })
  }
  listCache = result
  return result
}

async function save(title, artist, text) {
  await ensureDir()
  const key = fileName(title, artist)
  const ok = await writeText(key, text)
  invalidate()
  return ok ? key : ''
}

// ---------- 最近一首歌词的缓存 ----------
// 与词库分开：不占用户的歌词本，只解决「换个页面就要重新收一遍歌词分包」。
// 第一行是 JSON 头（歌名/歌手），后面是完整 LRC 原文。
const CACHE_DIR = 'internal://files/cache/'
const CACHE_URI = CACHE_DIR + 'last.txt'

function ensureCacheDir() {
  return new Promise(function (resolve) {
    file.mkdir({
      uri: CACHE_DIR,
      recursive: true,
      success: function () {
        resolve(true)
      },
      fail: function () {
        resolve(false)
      }
    })
  })
}

function writeRaw(uriPath, text) {
  return new Promise(function (resolve) {
    file.writeText({
      uri: uriPath,
      text: text,
      success: function () {
        resolve(true)
      },
      fail: function () {
        resolve(false)
      }
    })
  })
}

function readRaw(uriPath) {
  return new Promise(function (resolve) {
    file.readText({
      uri: uriPath,
      success: function (data) {
        resolve((data && data.text) || '')
      },
      fail: function () {
        resolve('')
      }
    })
  })
}

async function saveLastLyrics(title, artist, text) {
  if (!text) {
    return false
  }
  await ensureCacheDir()
  const head = JSON.stringify({
    t: String(title || '').slice(0, 80),
    a: String(artist || '').slice(0, 80)
  })
  return writeRaw(CACHE_URI, head + '\n' + text)
}

async function readLastLyrics() {
  const raw = await readRaw(CACHE_URI)
  if (!raw) {
    return null
  }
  const index = raw.indexOf('\n')
  if (index <= 0) {
    return null
  }
  let meta = null
  try {
    meta = JSON.parse(raw.slice(0, index))
  } catch (e) {
    return null
  }
  const text = raw.slice(index + 1)
  if (!meta || !text) {
    return null
  }
  return { t: meta.t || '', a: meta.a || '', lrc: text }
}

async function seed(demos) {
  invalidate()
  await ensureDir()
  for (let i = 0; i < demos.length; i++) {
    const demo = demos[i]
    const key = fileName(demo.title, demo.artist)
    const has = await exists(key)
    if (!has) {
      await writeText(key, demo.lrc)
    }
  }
  // 写完再失效一次，否则本轮已经读过的空目录缓存会让词本仍然显示 0 首
  invalidate()
}

function normalize(text) {
  return String(text || '')
    .toLowerCase()
    .replace(/[\s\-_—·・.,，。'"'']/g, '')
}

async function find(title, artist) {
  const items = await list()
  const targetTitle = normalize(title)
  const targetArtist = normalize(artist)
  if (!targetTitle) {
    return null
  }
  let best = null
  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    const itemTitle = normalize(item.title)
    const itemArtist = normalize(item.artist)
    if (!itemTitle) {
      continue
    }
    if (itemTitle === targetTitle) {
      if (!targetArtist || itemArtist === targetArtist) {
        return item
      }
      if (!best) {
        best = item
      }
    } else if (!best && targetTitle.indexOf(itemTitle) >= 0 && itemTitle.length >= 2) {
      best = item
    }
  }
  return best
}

async function loadByKey(key) {
  const text = await readText(key)
  return text
}

export default {
  DIR: DIR,
  ensureDir: ensureDir,
  list: list,
  seed: seed,
  read: loadByKey,
  save: save,
  saveLastLyrics: saveLastLyrics,
  readLastLyrics: readLastLyrics,
  remove: removeFile,
  find: find,
  parseHeader: parseHeader
}
