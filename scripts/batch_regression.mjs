import { writeFile } from 'node:fs/promises'

const DEFAULT_TERM = process.env.TVBOX_SEARCH_TERM || '爱'
const DEFAULT_TIMEOUT = Number.parseInt(process.env.TVBOX_TIMEOUT_MS || '18000', 10)
const DEFAULT_CONCURRENCY = Number.parseInt(process.env.TVBOX_CONCURRENCY || '4', 10)

function usage() {
  console.error('Usage: node scripts/batch_regression.mjs <baseUrl> <reportPath> <configUrl> [moreConfigUrls...]')
  process.exit(1)
}

if (process.argv.length < 5) {
  usage()
}

const [, , baseUrl, reportPath, ...configUrls] = process.argv

function normalizeList(payload) {
  if (!payload || typeof payload !== 'object') {
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

function normalizeClasses(payload) {
  if (!payload || typeof payload !== 'object') {
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

function classIdOf(item) {
  return String(item?.type_id || item?.typeId || item?.id || '').trim()
}

function itemIdOf(item) {
  return String(item?.vod_id || item?.id || '').trim()
}

function itemNameOf(item) {
  return String(item?.vod_name || item?.name || '').trim()
}

function sanitizePlayableUrl(value) {
  if (!value) {
    return ''
  }
  const raw = String(value).trim()
  const match = raw.match(/^[^,，]{1,30}[,，](.+)$/)
  if (!match) {
    return raw
  }
  const candidate = match[1]?.trim() || ''
  if (/^(https?:\/\/|rtmp:\/\/|rtsp:\/\/|ftp:\/\/|magnet:|thunder:|ed2k:\/\/|\/\/)/i.test(candidate)) {
    return candidate
  }
  return raw
}

function parsePlayGroups(vod) {
  const flags = String(vod?.vod_play_from || '')
    .split('$$$')
    .map((item) => item.trim())
    .filter(Boolean)

  const lines = String(vod?.vod_play_url || '')
    .split('$$$')
    .map((item) => item.trim())

  return flags.map((flagName, index) => ({
    name: flagName,
    episodes: String(lines[index] || '')
      .split('#')
      .map((item) => item.trim())
      .filter(Boolean)
      .map((row) => {
        const splitIndex = row.indexOf('$')
        if (splitIndex < 0) {
          return { name: row.trim() || '立即播放', id: sanitizePlayableUrl(row) }
        }
        return {
          name: row.slice(0, splitIndex).trim() || '立即播放',
          id: sanitizePlayableUrl(row.slice(splitIndex + 1)),
        }
      }),
  }))
}

function summarizeError(error) {
  if (!error) {
    return ''
  }
  const body = error?.body?.message || error?.body?.error || error?.body?.detail
  if (body) {
    return String(body)
  }
  return error.message || String(error)
}

async function fetchJson(url, options = {}) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(new Error(`timeout:${DEFAULT_TIMEOUT}`)), DEFAULT_TIMEOUT)
  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        ...(options.headers || {}),
      },
    })
    const text = await response.text()
    let json = null
    try {
      json = text ? JSON.parse(text) : null
    } catch {
      json = { raw: text }
    }
    if (!response.ok) {
      const error = new Error(`HTTP ${response.status}`)
      error.status = response.status
      error.body = json
      throw error
    }
    return { response, json }
  } finally {
    clearTimeout(timeout)
  }
}

async function createSession(configUrl) {
  const { response, json } = await fetchJson(`${baseUrl}/api/config/load`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ url: configUrl }),
  })
  const cookie = response.headers.get('set-cookie')?.split(';')[0] || ''
  const sites = Array.isArray(json?.config?.sites) ? json.config.sites : []
  return { cookie, config: json?.config || {}, sites }
}

async function sessionJson(session, path, options = {}) {
  return fetchJson(`${baseUrl}${path}`, {
    ...options,
    headers: {
      Cookie: session.cookie,
      ...(options.headers || {}),
    },
  })
}

async function inspectSite(session, site) {
  const result = {
    uid: site.uid,
    name: site.name,
    api: site.api,
    type: site.type,
    searchable: site.searchable,
    home: { ok: false, classes: 0, list: 0, error: '' },
    category: { ok: false, tid: '', list: 0, error: '' },
    search: { ok: false, list: 0, error: '' },
    detail: { ok: false, id: '', playGroups: 0, error: '' },
    play: { ok: false, flag: '', episodeId: '', parse: null, url: '', error: '' },
  }

  let candidate = null
  let firstClassId = ''

  try {
    const { json } = await sessionJson(session, `/api/source/${encodeURIComponent(site.uid)}/home?filter=true`)
    const classes = normalizeClasses(json)
    const list = normalizeList(json)
    firstClassId = classIdOf(classes[0])
    result.home = {
      ok: classes.length > 0 || list.length > 0,
      classes: classes.length,
      list: list.length,
      error: '',
    }
    if (list.length > 0) {
      candidate = list[0]
    }
  } catch (error) {
    result.home.error = summarizeError(error)
  }

  if (!candidate && firstClassId) {
    try {
      const { json } = await sessionJson(session, `/api/source/${encodeURIComponent(site.uid)}/category`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          tid: firstClassId,
          pg: '1',
          filter: true,
          extend: {},
        }),
      })
      const list = normalizeList(json)
      result.category = {
        ok: true,
        tid: firstClassId,
        list: list.length,
        error: '',
      }
      if (list.length > 0) {
        candidate = list[0]
      }
    } catch (error) {
      result.category = {
        ok: false,
        tid: firstClassId,
        list: 0,
        error: summarizeError(error),
      }
    }
  }

  if (site.searchable !== 0) {
    try {
      const { json } = await sessionJson(
        session,
        `/api/source/${encodeURIComponent(site.uid)}/search?wd=${encodeURIComponent(DEFAULT_TERM)}&quick=false`,
      )
      const list = normalizeList(json)
      result.search = {
        ok: true,
        list: list.length,
        error: '',
      }
      if (!candidate && list.length > 0) {
        candidate = list[0]
      }
    } catch (error) {
      result.search.error = summarizeError(error)
    }
  }

  const candidateId = itemIdOf(candidate)
  if (!candidateId) {
    return result
  }

  try {
    const { json } = await sessionJson(
      session,
      `/api/source/${encodeURIComponent(site.uid)}/detail?id=${encodeURIComponent(candidateId)}`,
    )
    const vod = normalizeList(json)[0]
    const groups = parsePlayGroups(vod)
    result.detail = {
      ok: Boolean(vod),
      id: candidateId,
      title: itemNameOf(vod),
      playGroups: groups.length,
      error: '',
    }
    const firstGroup = groups[0]
    const firstEpisode = firstGroup?.episodes?.[0]
    if (!firstGroup || !firstEpisode?.id) {
      return result
    }
    result.play.flag = firstGroup.name
    result.play.episodeId = firstEpisode.id

    const { json: playJson } = await sessionJson(
      session,
      `/api/source/${encodeURIComponent(site.uid)}/play?flag=${encodeURIComponent(firstGroup.name)}&id=${encodeURIComponent(firstEpisode.id)}`,
    )
    result.play = {
      ok: true,
      flag: firstGroup.name,
      episodeId: firstEpisode.id,
      parse: playJson?.parse ?? null,
      url: sanitizePlayableUrl(playJson?.url || ''),
      playUrl: sanitizePlayableUrl(playJson?.playUrl || ''),
      error: '',
    }
  } catch (error) {
    if (!result.detail.ok) {
      result.detail.error = summarizeError(error)
      result.detail.id = candidateId
    } else {
      result.play.error = summarizeError(error)
    }
  }

  return result
}

async function mapLimit(items, limit, worker) {
  const queue = items.slice()
  const output = []
  const runners = Array.from({ length: Math.min(limit, queue.length) }, async () => {
    while (queue.length) {
      const item = queue.shift()
      if (!item) {
        return
      }
      output.push(await worker(item))
    }
  })
  await Promise.all(runners)
  return output
}

function buildSummary(siteResults) {
  const summary = {
    total: siteResults.length,
    homeOk: 0,
    categoryOk: 0,
    searchOk: 0,
    detailOk: 0,
    playOk: 0,
    failuresByApi: {},
  }

  for (const site of siteResults) {
    if (site.home.ok) summary.homeOk += 1
    if (site.category.ok) summary.categoryOk += 1
    if (site.search.ok) summary.searchOk += 1
    if (site.detail.ok) summary.detailOk += 1
    if (site.play.ok) summary.playOk += 1

    const issues = []
    if (!site.home.ok) issues.push(`home:${site.home.error || 'empty'}`)
    if (site.home.ok && !site.category.ok && site.category.error) issues.push(`category:${site.category.error}`)
    if (site.searchable !== 0 && !site.search.ok && site.search.error) issues.push(`search:${site.search.error}`)
    if (!site.detail.ok && site.detail.error) issues.push(`detail:${site.detail.error}`)
    if (!site.play.ok && site.play.error) issues.push(`play:${site.play.error}`)

    if (!issues.length) {
      continue
    }

    const bucket = summary.failuresByApi[site.api] || { count: 0, samples: [] }
    bucket.count += 1
    if (bucket.samples.length < 6) {
      bucket.samples.push({
        name: site.name,
        issues,
      })
    }
    summary.failuresByApi[site.api] = bucket
  }

  return summary
}

const report = {
  generatedAt: new Date().toISOString(),
  baseUrl,
  configs: [],
}

for (const configUrl of configUrls) {
  console.log(`Loading config: ${configUrl}`)
  try {
    const session = await createSession(configUrl)
    const siteResults = await mapLimit(session.sites, DEFAULT_CONCURRENCY, (site) => inspectSite(session, site))
    report.configs.push({
      configUrl,
      siteCount: session.sites.length,
      summary: buildSummary(siteResults),
      sites: siteResults,
    })
  } catch (error) {
    report.configs.push({
      configUrl,
      loadError: summarizeError(error),
      siteCount: 0,
      summary: {
        total: 0,
        homeOk: 0,
        categoryOk: 0,
        searchOk: 0,
        detailOk: 0,
        playOk: 0,
        failuresByApi: {},
      },
      sites: [],
    })
  }
}

await writeFile(reportPath, JSON.stringify(report, null, 2))
console.log(`Report written to ${reportPath}`)
