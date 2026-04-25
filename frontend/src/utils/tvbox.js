export const DEFAULT_CONFIG_URL =
  'https://gh-proxy.org/raw.githubusercontent.com/qist/tvbox/master/xiaosa/api.json'

export const FALLBACK_POSTER = ''
export const DEFAULT_WALL_PAGE_SIZE = 24
const MAX_REASONABLE_PAGE_COUNT = 999

function stripHtml(value) {
  const raw = String(value || '')
  if (!raw) {
    return ''
  }

  if (typeof DOMParser !== 'undefined') {
    try {
      const doc = new DOMParser().parseFromString(raw, 'text/html')
      return doc.body.textContent || ''
    } catch {
      // Fall through to the regex-based cleanup below.
    }
  }

  return raw
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
}

function cleanText(value, fallback = '') {
  const text = stripHtml(value).replace(/\s+/g, ' ').trim()
  return text || fallback
}

export function normalizeList(payload) {
  if (!payload) {
    return []
  }
  if (Array.isArray(payload.list)) {
    return payload.list
  }
  if (payload.data && Array.isArray(payload.data.list)) {
    return payload.data.list
  }
  if (Array.isArray(payload.videoList)) {
    return payload.videoList
  }
  return []
}

export function normalizeClasses(payload) {
  if (!payload) {
    return []
  }
  if (Array.isArray(payload.class)) {
    return payload.class
  }
  if (Array.isArray(payload.classes)) {
    return payload.classes
  }
  if (payload.classes && Array.isArray(payload.classes.sortList)) {
    return payload.classes.sortList
  }
  return []
}

export function extractPageMeta(payload, fallbackPage = 1, fallbackSize = DEFAULT_WALL_PAGE_SIZE) {
  const list = normalizeList(payload)
  const limitCandidates = [
    payload?.limit,
    payload?.pageSize,
    payload?.pagesize,
    payload?.size,
    list.length,
    fallbackSize,
  ]
  const limit = limitCandidates
    .map((value) => Number(value))
    .find((value) => Number.isFinite(value) && value > 0 && value < 500)

  const pageCandidates = [payload?.page, payload?.pg, fallbackPage]
  const page = pageCandidates
    .map((value) => Number(value))
    .find((value) => Number.isFinite(value) && value > 0)

  const totalCandidates = [payload?.total, payload?.totalCount]
  const total = totalCandidates
    .map((value) => Number(value))
    .find((value) => Number.isFinite(value) && value > 0)

  let pageCount = [payload?.pagecount, payload?.pageCount]
    .map((value) => Number(value))
    .find((value) => Number.isFinite(value) && value > 0 && value <= MAX_REASONABLE_PAGE_COUNT)

  if (!pageCount && total && limit) {
    const calculated = Math.ceil(total / limit)
    pageCount = calculated <= MAX_REASONABLE_PAGE_COUNT ? calculated : 1
  }

  return {
    page: page || fallbackPage,
    pageSize: limit || fallbackSize,
    total: total || list.length,
    pageCount: Math.max(1, pageCount || (list.length ? 1 : fallbackPage || 1)),
  }
}

export function normalizeVideoCard(raw, source) {
  return {
    id: String(raw?.vod_id || raw?.id || ''),
    name: cleanText(raw?.vod_name || raw?.name, '未命名影片'),
    pic: raw?.vod_pic || raw?.pic || FALLBACK_POSTER,
    remarks: cleanText(raw?.vod_remarks || raw?.remarks || raw?.vod_score),
    sourceUid: raw?.source_uid || raw?.source_key || raw?.sourceKey || source?.uid || '',
    sourceName: cleanText(raw?.source_name || raw?.sourceName || source?.name),
  }
}

export function classIdOf(item) {
  return String(item?.type_id || item?.typeId || item?.id || '')
}

export function classNameOf(item) {
  return cleanText(item?.type_name || item?.typeName || item?.name, '未命名分类')
}

export function buildPosterTransitionName(sourceUid, vodId) {
  const source = String(sourceUid || 'source').replace(/[^a-zA-Z0-9_-]/g, '_')
  const vod = String(vodId || 'vod').replace(/[^a-zA-Z0-9_-]/g, '_')
  return `poster-${source}-${vod}`
}

export function sanitizePlayableUrl(value) {
  if (!value) {
    return ''
  }
  const raw = String(value).trim()
  const match = raw.match(/^[^,]{1,30}[,](.+)$/)
  if (!match) {
    return raw
  }
  const candidate = match[1]?.trim() || ''
  if (/^(https?:\/\/|rtmp:\/\/|rtsp:\/\/|ftp:\/\/|magnet:|thunder:|ed2k:\/\/|\/\/)/i.test(candidate)) {
    return candidate
  }
  return raw
}

function hasPlayableScheme(value) {
  return /^(https?:\/\/|rtmp:\/\/|rtsp:\/\/|ftp:\/\/|magnet:|thunder:|ed2k:\/\/|\/\/)/i.test(value || '')
}

function joinPlayableUrl(baseUrl, targetUrl) {
  const base = sanitizePlayableUrl(baseUrl)
  const target = sanitizePlayableUrl(targetUrl)
  if (!base) {
    return target
  }
  if (!target) {
    return base
  }
  if (hasPlayableScheme(target)) {
    return target.startsWith('//') ? `https:${target}` : target
  }
  if (target.startsWith('/')) {
    try {
      return new URL(target, base).toString()
    } catch {
      return `${base}${target}`
    }
  }
  return `${base}${target}`
}

function splitPlaylistRow(row) {
  const index = row.indexOf('$')
  if (index < 0) {
    return {
      name: row.trim() || '立即播放',
      id: sanitizePlayableUrl(row),
    }
  }
  return {
    name: row.slice(0, index).trim() || '立即播放',
    id: sanitizePlayableUrl(row.slice(index + 1)),
  }
}

export function parsePlayGroups(vod) {
  const flags = String(vod?.vod_play_from || '')
    .split('$$$')
    .map((item) => item.trim())
    .filter(Boolean)

  const lines = String(vod?.vod_play_url || '')
    .split('$$$')
    .map((item) => item.trim())

  return flags.map((flagName, flagIndex) => ({
    name: flagName,
    index: flagIndex,
    episodes: String(lines[flagIndex] || '')
      .split('#')
      .map((item) => item.trim())
      .filter(Boolean)
      .map(splitPlaylistRow),
  }))
}

export function normalizeDetail(payload, source) {
  const vod = normalizeList(payload)[0]
  if (!vod) {
    return null
  }

  return {
    id: String(vod?.vod_id || vod?.id || ''),
    title: cleanText(vod?.vod_name || vod?.name, '未命名影片'),
    pic: vod?.vod_pic || vod?.pic || FALLBACK_POSTER,
    desc: cleanText(vod?.vod_content || vod?.vod_blurb || vod?.remarks),
    remarks: cleanText(vod?.vod_remarks),
    type: cleanText(vod?.type_name || vod?.vod_class),
    year: cleanText(vod?.vod_year),
    area: cleanText(vod?.vod_area),
    director: cleanText(vod?.vod_director),
    actor: cleanText(vod?.vod_actor),
    lang: cleanText(vod?.vod_lang),
    sourceUid: source?.uid || '',
    sourceName: cleanText(source?.name),
    playGroups: parsePlayGroups(vod),
  }
}

export function buildPlayableUrl(playPayload, fallbackId) {
  const rawUrl = sanitizePlayableUrl(playPayload?.url || fallbackId)
  const playUrl = sanitizePlayableUrl(playPayload?.playUrl || '')

  if (Number(playPayload?.parse || 0) === 1 && playUrl) {
    return sanitizePlayableUrl(joinPlayableUrl(playUrl, rawUrl))
  }

  if (rawUrl.startsWith('//')) {
    return `https:${rawUrl}`
  }

  return sanitizePlayableUrl(rawUrl)
}

export function formatMeta(detail) {
  return [detail?.type, detail?.year, detail?.area, detail?.remarks].filter(Boolean).join(' / ')
}

export function formatHistoryTime(timestamp) {
  if (!timestamp) {
    return '刚刚'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(timestamp)
}

export function isLikelyMediaUrl(url) {
  return /\.(m3u8|mp4|m4v|webm|mp3|flv)(\?|$)/i.test(url || '')
}
