const TIME_TAG = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g
const META_TAG = /^\[(ti|ar|al|by|offset|re|ve|length):(.*)\]$/i

function parseTimestamp(minutes, seconds, fraction) {
  let ms = 0
  if (fraction) {
    if (fraction.length === 1) {
      ms = Number(fraction) * 100
    } else if (fraction.length === 2) {
      ms = Number(fraction) * 10
    } else {
      ms = Number(fraction.slice(0, 3))
    }
  }
  return Number(minutes) * 60000 + Number(seconds) * 1000 + ms
}

function parse(text, fallbackTitle, fallbackArtist) {
  const meta = {
    title: fallbackTitle || '',
    artist: fallbackArtist || '',
    album: '',
    by: '',
    offset: 0
  }
  const lines = []
  if (typeof text !== 'string' || !text) {
    return { meta: meta, lines: lines }
  }
  const rawLines = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
  rawLines.forEach(function (raw) {
    const trimmed = raw.trim()
    if (!trimmed) {
      return
    }
    const metaMatch = trimmed.match(META_TAG)
    if (metaMatch) {
      const key = metaMatch[1].toLowerCase()
      const value = metaMatch[2].trim()
      if (key === 'offset') {
        const n = Number(value)
        if (!isNaN(n)) {
          meta.offset = n
        }
      } else if (key === 'ti') {
        meta.title = value
      } else if (key === 'ar') {
        meta.artist = value
      } else if (key === 'al') {
        meta.album = value
      } else if (key === 'by') {
        meta.by = value
      }
      return
    }
    const times = []
    let lastTagEnd = -1
    TIME_TAG.lastIndex = 0
    let match = TIME_TAG.exec(trimmed)
    while (match) {
      if (lastTagEnd >= 0 && trimmed.slice(lastTagEnd, match.index).trim() !== '') {
        break
      }
      times.push(parseTimestamp(match[1], match[2], match[3]))
      lastTagEnd = match.index + match[0].length
      match = TIME_TAG.exec(trimmed)
    }
    if (!times.length || lastTagEnd < 0) {
      return
    }
    let content = trimmed.slice(lastTagEnd).trim()
    if (!content) {
      content = ' '
    }
    times.forEach(function (time) {
      lines.push({ time: time, text: content })
    })
  })
  lines.sort(function (a, b) {
    return a.time - b.time
  })
  const deduped = []
  lines.forEach(function (line) {
    const prev = deduped[deduped.length - 1]
    if (prev && prev.time === line.time && prev.text === line.text) {
      return
    }
    deduped.push(line)
  })
  return { meta: meta, lines: deduped }
}

function findIndex(lines, timeMs) {
  if (!lines || !lines.length) {
    return -1
  }
  if (timeMs < lines[0].time) {
    return -1
  }
  let low = 0
  let high = lines.length - 1
  let result = 0
  while (low <= high) {
    const mid = Math.floor((low + high) / 2)
    if (lines[mid].time <= timeMs) {
      result = mid
      low = mid + 1
    } else {
      high = mid - 1
    }
  }
  return result
}

function sliceAround(lines, index, before, after) {
  const items = []
  if (!lines || !lines.length) {
    return { items: items, start: 0, end: -1 }
  }
  const center = index < 0 ? 0 : index
  const start = Math.max(0, center - before)
  const end = Math.min(lines.length - 1, center + after)
  for (let i = start; i <= end; i++) {
    items.push({
      index: i,
      time: lines[i].time,
      text: lines[i].text,
      active: i === index
    })
  }
  return { items: items, start: start, end: end }
}

// 手环上没有 text-overflow / 多行截断，超长的一行会直接溢出被裁掉。
// 这里按「显示宽度」手工折行：中日韩字符算 1 个单位，西文/数字算 0.55 个单位。
// maxUnits 必须比「可用宽度 / 字号」再小一点，保证每行都不会二次换行。
function charUnit(ch) {
  const code = ch.charCodeAt(0)
  if (code < 0x1100) {
    return 0.55
  }
  return 1
}

function textUnit(text) {
  let total = 0
  for (let i = 0; i < text.length; i++) {
    total += charUnit(text.charAt(i))
  }
  return total
}

function wrap(text, maxUnits, maxLines) {
  const value = String(text === undefined || text === null ? '' : text)
    .replace(/\s+/g, ' ')
    .trim()
  if (!value) {
    return []
  }
  const limit = Math.max(3, Number(maxUnits) || 10)
  const max = Math.max(1, Number(maxLines) || 2)
  const lines = []
  let line = ''
  let width = 0
  let index = 0
  while (index < value.length) {
    const ch = value.charAt(index)
    const w = charUnit(ch)
    const lastLine = lines.length >= max - 1
    // 最后一行要给省略号留位置，否则会多出一点点被折行
    const budget = lastLine ? limit - 1 : limit
    if (line && width + w > budget) {
      if (lastLine) {
        lines.push(line + '…')
        return lines
      }
      lines.push(line)
      line = ''
      width = 0
      continue
    }
    line += ch
    width += w
    index++
  }
  if (line) {
    lines.push(line)
  }
  return lines
}

function formatTime(ms) {  if (ms === undefined || ms === null || isNaN(ms) || ms < 0) {
    return '0:00'
  }
  const total = Math.floor(ms / 1000)
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return minutes + ':' + (seconds < 10 ? '0' + seconds : String(seconds))
}

function toText(parsed) {
  const meta = parsed.meta || {}
  const head = []
  if (meta.title) {
    head.push('[ti:' + meta.title + ']')
  }
  if (meta.artist) {
    head.push('[ar:' + meta.artist + ']')
  }
  if (meta.album) {
    head.push('[al:' + meta.album + ']')
  }
  if (meta.offset) {
    head.push('[offset:' + meta.offset + ']')
  }
  const body = (parsed.lines || []).map(function (line) {
    const total = line.time
    const minutes = Math.floor(total / 60000)
    const seconds = Math.floor((total % 60000) / 1000)
    const fraction = total % 1000
    const mm = minutes < 10 ? '0' + minutes : String(minutes)
    const ss = seconds < 10 ? '0' + seconds : String(seconds)
    const xx = fraction < 100 ? (fraction < 10 ? '00' + fraction : '0' + fraction) : String(fraction)
    return '[' + mm + ':' + ss + '.' + xx + ']' + line.text
  })
  return head.concat(body).join('\n')
}

export default {
  parse: parse,
  findIndex: findIndex,
  sliceAround: sliceAround,
  wrap: wrap,
  textUnit: textUnit,
  formatTime: formatTime,
  toText: toText
}
