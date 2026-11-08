import storage from '@system.storage'

const DEFAULTS = {
  mock: true,
  // 默认不自动保存：每首歌都塞进词库会把词本淹掉，改成播放页手动「加入歌词本」。
  // 想恢复自动保存的用户可以在设置里打开这个开关。
  autoSave: false,
  follow: true,
  offset: 0,
  lineHeight: 1.6,
  lastOffset: 0,
  // 显示相关：字号 0 小 / 1 中 / 2 大；每次滑动翻几句；是否振动
  fontSize: 1,
  swipeStep: 1,
  vibrate: true,
  // 浏览歌词后多久自动回到当前句（秒）；偏移按钮的步进（毫秒）；是否显示进度条
  browseIdle: 6,
  offsetStep: 500,
  showProgress: true
}

function readValue(data) {
  if (typeof data === 'string') {
    return data
  }
  if (data && typeof data.value === 'string') {
    return data.value
  }
  if (data && typeof data.data === 'string') {
    return data.data
  }
  return ''
}

function get(key) {
  return new Promise(function (resolve) {
    storage.get({
      key: key,
      default: '',
      success: function (data) {
        resolve(readValue(data))
      },
      fail: function () {
        resolve('')
      }
    })
  })
}

function set(key, value) {
  return new Promise(function (resolve) {
    storage.set({
      key: key,
      value: String(value),
      success: function () {
        resolve(true)
      },
      fail: function () {
        resolve(false)
      }
    })
  })
}

function remove(key) {
  return new Promise(function (resolve) {
    storage.delete({
      key: key,
      success: function () {
        resolve(true)
      },
      fail: function () {
        resolve(false)
      }
    })
  })
}

function toBool(value, fallback) {
  if (value === '' || value === undefined || value === null) {
    return fallback
  }
  return value === '1' || value === 'true'
}

async function load() {
  const keys = Object.keys(DEFAULTS)
  const result = {}
  for (let i = 0; i < keys.length; i++) {
    const key = keys[i]
    const raw = await get('lyric.' + key)
    if (key === 'mock' || key === 'autoSave' || key === 'follow' || key === 'vibrate' || key === 'showProgress') {
      result[key] = toBool(raw, DEFAULTS[key])
    } else if (key === 'offset' || key === 'lastOffset' || key === 'offsetStep') {
      const n = Number(raw)
      result[key] = isNaN(n) ? DEFAULTS[key] : n
    } else if (key === 'fontSize' || key === 'swipeStep' || key === 'browseIdle') {
      const n = Number(raw)
      result[key] = isNaN(n) || raw === '' ? DEFAULTS[key] : n
    } else if (key === 'lineHeight') {
      const n = Number(raw)
      result[key] = isNaN(n) || n <= 0 ? DEFAULTS[key] : n
    } else {
      result[key] = raw || DEFAULTS[key]
    }
  }
  return result
}

async function save(key, value) {
  return set('lyric.' + key, value)
}

export default {
  DEFAULTS: DEFAULTS,
  load: load,
  save: save,
  get: get,
  remove: remove
}
