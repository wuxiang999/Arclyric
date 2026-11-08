const VERSION = 1

const TYPES = {
  STATE: 'state',
  LYRICS: 'lyrics',
  LYRICS_CHUNK: 'lyrics.chunk',
  COMMAND: 'command',
  HELLO: 'hello',
  ACK: 'ack',
  DEBUG: 'debug',
  PING: 'ping',
  PONG: 'pong',
  // 词库同步：手机要一份手环端歌词本，手环分片回传
  LIBRARY_REQUEST: 'library.request',
  LIBRARY_CHUNK: 'library.chunk',
  LIBRARY_DELETE: 'library.delete'
}

function envelope(type, payload, extra) {
  const msg = {
    v: VERSION,
    type: type,
    ts: Date.now(),
    payload: payload === undefined ? {} : payload
  }
  if (extra) {
    Object.keys(extra).forEach(function (key) {
      msg[key] = extra[key]
    })
  }
  return msg
}

function decodeUtf8(input) {
  let bytes
  if (input instanceof ArrayBuffer) {
    bytes = new Uint8Array(input)
  } else if (input && typeof input.byteLength === 'number' && typeof input.length === 'number') {
    bytes = new Uint8Array(input.buffer, input.byteOffset || 0, input.byteLength)
  } else {
    return ''
  }
  let out = ''
  let i = 0
  while (i < bytes.length) {
    const b = bytes[i++]
    if (b < 0x80) {
      out += String.fromCharCode(b)
    } else if (b < 0xe0) {
      const b2 = bytes[i++] & 0x3f
      out += String.fromCharCode(((b & 0x1f) << 6) | b2)
    } else if (b < 0xf0) {
      const b2 = bytes[i++] & 0x3f
      const b3 = bytes[i++] & 0x3f
      out += String.fromCharCode(((b & 0x0f) << 12) | (b2 << 6) | b3)
    } else {
      const b2 = bytes[i++] & 0x3f
      const b3 = bytes[i++] & 0x3f
      const b4 = bytes[i++] & 0x3f
      let cp = ((b & 0x07) << 18) | (b2 << 12) | (b3 << 6) | b4
      if (cp > 0xffff) {
        cp -= 0x10000
        out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff))
      } else {
        out += String.fromCharCode(cp)
      }
    }
  }
  return out
}

function parseText(text) {
  let value = String(text)
  if (value.charCodeAt(0) === 0xfeff) {
    value = value.slice(1)
  }
  value = value.trim()
  if (!value) {
    return null
  }
  // 连通层可能一层套一层地把 JSON 当字符串传过来，最多解 4 层
  let parsed = value
  for (let i = 0; i < 4; i++) {
    if (typeof parsed !== 'string') {
      break
    }
    try {
      parsed = JSON.parse(parsed)
    } catch (e) {
      return null
    }
  }
  if (parsed && typeof parsed === 'object') {
    return normalize(parsed)
  }
  return null
}

function normalize(obj) {
  if (!obj || typeof obj !== 'object') {
    return null
  }
  if (typeof obj.type === 'string' && obj.type) {
    if (!obj.payload || typeof obj.payload !== 'object') {
      obj.payload = {}
    }
    return obj
  }
  // 手机侧可能整包包一层 { data: '<json>' } 或 { d: '<json>' }
  if (obj.d !== undefined) {
    return decode(obj.d)
  }
  if (obj.data !== undefined) {
    return decode(obj.data)
  }
  return null
}

function decode(raw) {
  if (raw === null || raw === undefined) {
    return null
  }
  if (typeof raw === 'string') {
    return parseText(raw)
  }
  if (typeof raw === 'object') {
    if (raw instanceof ArrayBuffer) {
      return parseText(decodeUtf8(raw))
    }
    if (typeof raw.byteLength === 'number' && typeof raw.length === 'number') {
      return parseText(decodeUtf8(raw))
    }
    if (typeof raw.type === 'string') {
      return normalize(raw)
    }
    if (raw.d !== undefined) {
      return decode(raw.d)
    }
    if (raw.data !== undefined) {
      return decode(raw.data)
    }
    return normalize(raw)
  }
  return null
}

function command(action, value) {
  return envelope(TYPES.COMMAND, {
    action: action,
    value: value === undefined ? null : value
  })
}

function stateRequest() {
  return command('sync')
}

function debug(level, tag, message, at) {
  return envelope(TYPES.DEBUG, {
    level: level || 'info',
    tag: tag || 'band',
    msg: message === undefined || message === null ? '' : String(message),
    at: at || Date.now()
  })
}

export {
  VERSION,
  TYPES,
  envelope,
  decode,
  decodeUtf8,
  command,
  stateRequest,
  debug
}

export default {
  VERSION: VERSION,
  TYPES: TYPES,
  envelope: envelope,
  decode: decode,
  decodeUtf8: decodeUtf8,
  command: command,
  stateRequest: stateRequest,
  debug: debug
}
