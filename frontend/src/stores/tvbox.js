import { computed, reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  DEFAULT_CONFIG_URL,
  DEFAULT_WALL_PAGE_SIZE,
  buildPlayableUrl,
  classIdOf,
  classNameOf,
  extractPageMeta,
  formatMeta,
  normalizeClasses,
  normalizeDetail,
  normalizeList,
  normalizeVideoCard,
} from '@/utils/tvbox'
import { getJson, isAbortError, postJson } from '@/api/client'

const SETTINGS_KEY = 'tvbox:web:settings'
const HISTORY_KEY = 'tvbox:web:history'

function readStorage(key, fallback) {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : fallback
  } catch {
    return fallback
  }
}

function writeStorage(key, value) {
  localStorage.setItem(key, JSON.stringify(value))
}

function toStatus(type, text) {
  return { type, text }
}

function normalizeLiveChannelKey(value) {
  return String(value || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9\u4e00-\u9fa5]+/g, '')
}

function hostLabelOf(url, fallbackIndex) {
  try {
    const host = new URL(String(url || '')).host
    return host || `源 ${fallbackIndex + 1}`
  } catch {
    return `源 ${fallbackIndex + 1}`
  }
}

function mergeLiveProgrammes(currentItems = [], nextItems = []) {
  const merged = new Map()
  for (const item of [...currentItems, ...nextItems]) {
    const key = `${item.start}-${item.end}-${item.title}`
    if (!merged.has(key)) {
      merged.set(key, item)
    }
  }
  return [...merged.values()].sort((left, right) => Number(left.start) - Number(right.start))
}

function mergeLiveChannels(rawChannels = []) {
  const mergedMap = new Map()

  rawChannels.forEach((item, index) => {
    const channelName = String(item?.name || '').trim() || `频道 ${index + 1}`
    const mergeKey =
      normalizeLiveChannelKey(item?.tvgId) ||
      normalizeLiveChannelKey(item?.tvgName) ||
      normalizeLiveChannelKey(channelName)

    const source = {
      id: `${item?.id || mergeKey || 'source'}-${index}`,
      url: String(item?.url || '').trim(),
      logo: String(item?.logo || '').trim(),
      label: hostLabelOf(item?.url, index),
      epgChannelId: String(item?.epgChannelId || '').trim(),
    }

    if (!mergedMap.has(mergeKey)) {
      mergedMap.set(mergeKey, {
        id: String(item?.id || mergeKey || `live-${index}`),
        name: channelName,
        group: String(item?.group || '未分组').trim() || '未分组',
        logo: source.logo,
        tvgId: String(item?.tvgId || '').trim(),
        tvgName: String(item?.tvgName || '').trim(),
        epgChannelId: source.epgChannelId,
        programmes: Array.isArray(item?.programmes) ? item.programmes : [],
        sources: source.url ? [source] : [],
      })
      return
    }

    const target = mergedMap.get(mergeKey)
    if (!target.logo && source.logo) {
      target.logo = source.logo
    }
    if (!target.epgChannelId && source.epgChannelId) {
      target.epgChannelId = source.epgChannelId
    }
    if ((!target.group || target.group === '未分组') && item?.group) {
      target.group = String(item.group).trim() || target.group
    }
    target.programmes = mergeLiveProgrammes(
      target.programmes,
      Array.isArray(item?.programmes) ? item.programmes : [],
    )

    if (source.url && !target.sources.some((entry) => entry.url === source.url)) {
      target.sources.push(source)
    }
  })

  return [...mergedMap.values()].map((item, index) => ({
    ...item,
    id: item.id || `live-${index}`,
    sources: item.sources.length
      ? item.sources
      : [{ id: `fallback-${index}`, url: '', logo: item.logo, label: `源 ${index + 1}` }],
  }))
}

function buildLiveGroups(channels = []) {
  const counter = new Map()
  for (const item of channels) {
    const groupName = String(item?.group || '未分组').trim() || '未分组'
    counter.set(groupName, (counter.get(groupName) || 0) + 1)
  }
  return [...counter.entries()].map(([name, count]) => ({ name, count }))
}

function pickInitialSource(sources, previousUid) {
  const skipApis = new Set([
    'csp_Config',
    'csp_LocalFile',
    'csp_YGP',
    'csp_Push',
    'csp_Market',
    'csp_Douban',
  ])

  if (!Array.isArray(sources) || sources.length === 0) {
    return ''
  }
  const previous = previousUid ? sources.find((site) => site.uid === previousUid) : null
  if (previous && !skipApis.has(previous.api)) {
    return previousUid
  }
  const recommended =
    sources.find((site) => site.searchable !== 0 && !skipApis.has(site.api)) ||
    sources.find((site) => !skipApis.has(site.api)) ||
    sources[0]
  return recommended?.uid || ''
}

let searchRunId = 0
let searchAbortController = null

export const useTvboxStore = defineStore('tvbox', () => {
  const defaultConfigUrl = DEFAULT_CONFIG_URL
  const bootstrapped = ref(false)

  const configUrl = ref('')
  const appTheme = ref('naifei')
  const searchScope = ref('current')
  const liveSourceUrl = ref('')
  const liveEpgUrl = ref('')
  const config = ref(null)
  const sources = ref([])
  const selectedSourceUid = ref('')
  const classes = ref([])
  const activeClassId = ref('')
  const wallVideos = ref([])
  const wallMode = ref('home')
  const wallTitle = ref('首页视频墙')
  const searchKeyword = ref('')
  const history = ref([])
  const currentDetail = ref(null)
  const currentPlayback = ref(null)
  const liveGroups = ref([])
  const liveGroupName = ref('全部频道')
  const liveChannels = ref([])
  const currentLiveChannelId = ref('')
  const currentLiveSourceIndex = ref(0)
  const liveSummary = ref(null)
  const liveEpgError = ref('')
  const navigationPreview = ref(null)
  const detailOrigin = ref({ name: 'home', query: {} })
  const playerOrigin = ref({ name: 'detail', query: {} })
  const status = ref(toStatus('info', '正在准备 TVBOX OSC Web'))
  const wallFilters = ref({})
  const loading = reactive({
    config: false,
    wall: false,
    detail: false,
    player: false,
    live: false,
  })
  const wallPagination = reactive({
    page: 1,
    pageSize: DEFAULT_WALL_PAGE_SIZE,
    pageCount: 1,
    total: 0,
    serverPaging: false,
  })
  const searchProgress = reactive({
    active: false,
    totalSources: 0,
    completedSources: 0,
    matchedSources: 0,
    failedSources: 0,
    currentSourceName: '',
  })

  const detailCache = reactive({})

  const selectedSource = computed(
    () => sources.value.find((site) => site.uid === selectedSourceUid.value) || null,
  )
  const pagedWallVideos = computed(() => {
    if (wallPagination.serverPaging) {
      return wallVideos.value
    }
    const start = (wallPagination.page - 1) * wallPagination.pageSize
    return wallVideos.value.slice(start, start + wallPagination.pageSize)
  })
  const currentLiveChannel = computed(
    () => liveChannels.value.find((item) => item.id === currentLiveChannelId.value) || null,
  )
  const currentLiveSource = computed(
    () => currentLiveChannel.value?.sources?.[currentLiveSourceIndex.value] || null,
  )
  const filteredLiveChannels = computed(() => {
    if (liveGroupName.value === '全部频道') {
      return liveChannels.value
    }
    return liveChannels.value.filter((item) => item.group === liveGroupName.value)
  })
  const wallPageCount = computed(() => {
    if (wallPagination.serverPaging) {
      return Math.max(1, wallPagination.pageCount)
    }
    return Math.max(1, Math.ceil(wallVideos.value.length / wallPagination.pageSize))
  })
  const wallHasMore = computed(() => {
    if (!wallPagination.serverPaging) {
      return false
    }
    return wallPagination.page < wallPageCount.value
  })
  const wallTotal = computed(() => {
    if (wallPagination.serverPaging) {
      return Math.max(wallVideos.value.length, wallPagination.total)
    }
    return wallVideos.value.length
  })
  const streamProgressPercent = computed(() => {
    if (!searchProgress.totalSources) {
      return 0
    }
    return Math.min(
      100,
      Math.round((searchProgress.completedSources / searchProgress.totalSources) * 100),
    )
  })
  const showSourceBadges = computed(
    () => wallMode.value === 'search' && searchScope.value === 'all',
  )

  function persistSettings() {
    writeStorage(SETTINGS_KEY, {
      configUrl: configUrl.value,
      appTheme: appTheme.value,
      selectedSourceUid: selectedSourceUid.value,
      searchScope: searchScope.value,
      liveSourceUrl: liveSourceUrl.value,
      liveEpgUrl: liveEpgUrl.value,
    })
  }

  function persistHistory() {
    writeStorage(HISTORY_KEY, history.value.slice(0, 120))
  }

  function setStatus(type, text) {
    status.value = toStatus(type, text)
  }

  function clearStatus() {
    status.value = toStatus('', '')
  }

  function restoreLocalState() {
    const savedSettings = readStorage(SETTINGS_KEY, {})
    const savedHistory = readStorage(HISTORY_KEY, [])
    configUrl.value = savedSettings.configUrl || ''
    appTheme.value = savedSettings.appTheme || 'default'
    selectedSourceUid.value = savedSettings.selectedSourceUid || ''
    searchScope.value = savedSettings.searchScope || 'current'
    liveSourceUrl.value = savedSettings.liveSourceUrl || ''
    liveEpgUrl.value = savedSettings.liveEpgUrl || ''
    history.value = Array.isArray(savedHistory) ? savedHistory : []
  }

  function clearDetailCache() {
    for (const key of Object.keys(detailCache)) {
      delete detailCache[key]
    }
  }

  function clearSearchProgress() {
    searchProgress.active = false
    searchProgress.totalSources = 0
    searchProgress.completedSources = 0
    searchProgress.matchedSources = 0
    searchProgress.failedSources = 0
    searchProgress.currentSourceName = ''
  }

  function resetLiveState() {
    liveGroups.value = []
    liveChannels.value = []
    liveSummary.value = null
    liveEpgError.value = ''
    liveGroupName.value = '全部频道'
    currentLiveChannelId.value = ''
    currentLiveSourceIndex.value = 0
  }

  function resetWallPagination(options = {}) {
    wallPagination.page = options.page || 1
    wallPagination.pageSize = options.pageSize || DEFAULT_WALL_PAGE_SIZE
    wallPagination.pageCount = options.pageCount || 1
    wallPagination.total = options.total || 0
    wallPagination.serverPaging = Boolean(options.serverPaging)
  }

  function applyClientWall(items, options = {}) {
    wallVideos.value = items
    const pageSize = options.pageSize || DEFAULT_WALL_PAGE_SIZE
    resetWallPagination({
      page: options.page || 1,
      pageSize,
      pageCount: Math.max(1, Math.ceil(items.length / pageSize)),
      total: items.length,
      serverPaging: false,
    })
  }

  function applyServerWall(payload, items, fallbackPage = 1) {
    const meta = extractPageMeta(payload, fallbackPage, DEFAULT_WALL_PAGE_SIZE)
    wallVideos.value = items
    resetWallPagination({
      page: meta.page,
      pageSize: meta.pageSize,
      pageCount: meta.pageCount,
      total: meta.total,
      serverPaging: meta.pageCount > 1,
    })

    if (!wallVideos.value.length && wallPagination.serverPaging) {
      wallPagination.pageCount = Math.max(1, wallPagination.page)
      wallPagination.total = 0
    }
  }

  function mergeServerWall(payload, items, fallbackPage = 1) {
    const meta = extractPageMeta(payload, fallbackPage, DEFAULT_WALL_PAGE_SIZE)
    if (meta.page <= 1 || !wallVideos.value.length) {
      wallVideos.value = items
    } else {
      appendUniqueWallVideos(items)
    }

    wallPagination.page = meta.page
    wallPagination.pageSize = meta.pageSize
    wallPagination.pageCount = Math.max(1, meta.pageCount)
    wallPagination.total = Math.max(meta.total || 0, wallVideos.value.length)
    wallPagination.serverPaging = meta.pageCount > 1
  }

  function appendUniqueWallVideos(items) {
    const existing = new Set(
      wallVideos.value.map(
        (item) => `${item.sourceUid || selectedSourceUid.value}::${String(item.id)}`,
      ),
    )
    const next = [...wallVideos.value]
    for (const item of items) {
      const key = `${item.sourceUid || selectedSourceUid.value}::${String(item.id)}`
      if (existing.has(key)) {
        continue
      }
      existing.add(key)
      next.push(item)
    }
    wallVideos.value = next
    wallPagination.total = next.length
    wallPagination.pageCount = Math.max(1, Math.ceil(next.length / wallPagination.pageSize))
  }

  function prependUniqueWallVideos(items) {
    const existing = new Set(
      wallVideos.value.map(
        (item) => `${item.sourceUid || selectedSourceUid.value}::${String(item.id)}`,
      ),
    )
    const front = []
    for (const item of items) {
      const key = `${item.sourceUid || selectedSourceUid.value}::${String(item.id)}`
      if (existing.has(key)) {
        continue
      }
      existing.add(key)
      front.push(item)
    }
    if (!front.length) {
      return
    }
    const next = [...front, ...wallVideos.value]
    wallVideos.value = next
    wallPagination.total = next.length
    wallPagination.pageCount = Math.max(1, Math.ceil(next.length / wallPagination.pageSize))
  }

  function currentSourceOrThrow() {
    if (!selectedSource.value) {
      throw new Error('当前没有可用源，请先加载配置')
    }
    return selectedSource.value
  }

  function historyKeyOf(sourceUid, vodId) {
    return `${sourceUid}::${vodId}`
  }

  function getHistoryEntry(sourceUid, vodId) {
    return history.value.find(
      (item) => item.sourceUid === sourceUid && String(item.vodId) === String(vodId),
    )
  }

  function upsertHistory(detail, flagName, episodeIndex, episode, playUrl) {
    const entry = {
      sourceUid: detail.sourceUid,
      sourceName: detail.sourceName,
      vodId: detail.id,
      vodName: detail.title,
      vodPic: detail.pic,
      playFlag: flagName,
      playIndex: episodeIndex,
      episodeId: episode.id,
      episodeName: episode.name,
      playUrl,
      updatedAt: Date.now(),
    }

    history.value = [
      entry,
      ...history.value.filter(
        (item) =>
          historyKeyOf(item.sourceUid, item.vodId) !== historyKeyOf(entry.sourceUid, entry.vodId),
      ),
    ].slice(0, 120)

    if (
      currentDetail.value?.sourceUid === detail.sourceUid &&
      currentDetail.value?.id === detail.id
    ) {
      currentDetail.value = {
        ...currentDetail.value,
        resume: entry,
      }
    }

    persistHistory()
  }

  function clearCurrentDetail() {
    currentDetail.value = null
  }

  function clearCurrentPlayback() {
    currentPlayback.value = null
  }

  function updateLiveSettings(nextSettings = {}) {
    liveSourceUrl.value = String(nextSettings.liveSourceUrl ?? liveSourceUrl.value ?? '').trim()
    liveEpgUrl.value = String(nextSettings.liveEpgUrl ?? liveEpgUrl.value ?? '').trim()
    persistSettings()

    if (nextSettings.resetData) {
      resetLiveState()
    }
  }

  function setLiveGroup(groupName) {
    const nextGroup = String(groupName || '').trim() || '全部频道'
    liveGroupName.value = nextGroup

    const visibleChannels =
      nextGroup === '全部频道'
        ? liveChannels.value
        : liveChannels.value.filter((item) => item.group === nextGroup)

    if (!visibleChannels.some((item) => item.id === currentLiveChannelId.value)) {
      currentLiveChannelId.value = visibleChannels[0]?.id || ''
      currentLiveSourceIndex.value = 0
    }
  }

  function selectLiveChannel(channelId) {
    const nextId = String(channelId || '').trim()
    if (!nextId) {
      currentLiveChannelId.value = ''
      currentLiveSourceIndex.value = 0
      return
    }

    const target = liveChannels.value.find((item) => item.id === nextId)
    if (!target) {
      return
    }

    currentLiveChannelId.value = target.id
    currentLiveSourceIndex.value = 0
    if (
      target.group &&
      liveGroupName.value !== '全部频道' &&
      liveGroupName.value !== target.group
    ) {
      liveGroupName.value = target.group
    }
  }

  function selectLiveSource(sourceIndex) {
    const nextIndex = Number(sourceIndex)
    const sources = currentLiveChannel.value?.sources || []
    if (!Number.isInteger(nextIndex) || nextIndex < 0 || nextIndex >= sources.length) {
      return false
    }
    currentLiveSourceIndex.value = nextIndex
    return true
  }

  function selectNextLiveSource() {
    return selectLiveSource(currentLiveSourceIndex.value + 1)
  }

  async function loadLiveData(options = {}) {
    const playlistUrl = String(options.playlistUrl ?? liveSourceUrl.value ?? '').trim()
    const epgUrl = String(options.epgUrl ?? liveEpgUrl.value ?? '').trim()
    if (!playlistUrl) {
      resetLiveState()
      setStatus('warning', '请先填写直播源地址')
      return
    }

    if (options.persist !== false) {
      liveSourceUrl.value = playlistUrl
      liveEpgUrl.value = epgUrl
      persistSettings()
    }

    loading.live = true
    try {
      const payload = await postJson(
        '/api/live/bootstrap',
        { playlistUrl, epgUrl },
        { timeout: options.timeout || 120000 },
      )

      const nextChannels = mergeLiveChannels(
        Array.isArray(payload.channels)
          ? payload.channels.map((item) => ({
              ...item,
              group: item.group || '未分组',
              programmes: Array.isArray(item.programmes) ? item.programmes : [],
            }))
          : [],
      )

      if (!nextChannels.length) {
        resetLiveState()
        throw new Error('直播源里没有可用频道')
      }

      const nextGroups = buildLiveGroups(nextChannels)

      liveChannels.value = nextChannels
      liveGroups.value = nextGroups
      liveSummary.value = {
        ...(payload.summary || {}),
        channelCount: nextChannels.length,
        sourceCount: nextChannels.reduce((count, item) => count + (item.sources?.length || 0), 0),
        groupCount: nextGroups.length,
        epgMatchedChannels: nextChannels.filter(
          (item) => item.epgChannelId || (Array.isArray(item.programmes) && item.programmes.length),
        ).length,
      }
      liveEpgError.value = payload.epgError || ''

      const visibleGroups = new Set(['全部频道', ...nextGroups.map((item) => item.name)])
      if (!visibleGroups.has(liveGroupName.value)) {
        liveGroupName.value = '全部频道'
      }

      const visibleChannels =
        liveGroupName.value === '全部频道'
          ? nextChannels
          : nextChannels.filter((item) => item.group === liveGroupName.value)

      if (!visibleChannels.some((item) => item.id === currentLiveChannelId.value)) {
        currentLiveChannelId.value = visibleChannels[0]?.id || nextChannels[0]?.id || ''
        currentLiveSourceIndex.value = 0
      }

      if (payload.epgError) {
        setStatus('warning', `已载入 ${nextChannels.length} 个直播频道，${payload.epgError}`)
      } else {
        setStatus('success', `已载入 ${nextChannels.length} 个直播频道`)
      }
      return payload
    } catch (error) {
      resetLiveState()
      setStatus('error', `直播加载失败：${error.message}`)
      throw error
    } finally {
      loading.live = false
    }
  }

  function setNavigationPreview(video) {
    if (!video) {
      navigationPreview.value = null
      return
    }

    navigationPreview.value = {
      sourceUid: video.sourceUid || '',
      sourceName: video.sourceName || '',
      id: String(video.id || video.vodId || ''),
      title: video.name || video.title || '',
      pic: video.pic || video.vodPic || '',
    }
  }

  function clearNavigationPreview() {
    navigationPreview.value = null
  }

  function normalizeRouteTarget(target, fallbackName = 'home') {
    if (!target || typeof target !== 'object') {
      return { name: fallbackName, query: {} }
    }
    return {
      name: typeof target.name === 'string' ? target.name : fallbackName,
      query: target.query && typeof target.query === 'object' ? { ...target.query } : {},
    }
  }

  function setDetailOrigin(target) {
    detailOrigin.value = normalizeRouteTarget(target, 'home')
  }

  function setPlayerOrigin(target) {
    const fallbackName = target?.name === 'history' ? 'history' : 'detail'
    playerOrigin.value = normalizeRouteTarget(target, fallbackName)
  }

  function cancelRunningSearch() {
    searchRunId += 1
    if (searchAbortController) {
      searchAbortController.abort()
      searchAbortController = null
    }
    clearSearchProgress()
    if (wallMode.value === 'search') {
      loading.wall = false
    }
  }

  async function loadConfig(nextUrl = configUrl.value, options = {}) {
    const normalizedUrl = String(nextUrl || '').trim()
    if (!normalizedUrl) {
      setStatus('error', '请先输入配置 URL')
      return
    }

    cancelRunningSearch()
    loading.config = true
    try {
      const payload = await postJson('/api/config/load', { url: normalizedUrl })
      configUrl.value = normalizedUrl
      config.value = payload.config
      sources.value = Array.isArray(payload.config?.sites) ? payload.config.sites : []
      selectedSourceUid.value = pickInitialSource(sources.value, selectedSourceUid.value)
      persistSettings()
      clearDetailCache()
      clearCurrentDetail()
      clearCurrentPlayback()
      setStatus('success', `配置加载成功，已识别 ${sources.value.length} 个源`)

      if (options.autoLoadHome !== false && selectedSourceUid.value) {
        try {
          await loadHome()
        } catch {
          setStatus('warning', '配置已加载，但默认源首页打开失败，请切换其他源继续')
        }
      }
    } catch (error) {
      setStatus('error', `配置加载失败：${error.message}`)
      throw error
    } finally {
      loading.config = false
    }
  }

  async function changeSource(uid) {
    cancelRunningSearch()
    selectedSourceUid.value = uid
    persistSettings()
    await loadHome()
  }

  async function loadHome() {
    const source = currentSourceOrThrow()
    cancelRunningSearch()
    clearCurrentPlayback()
    loading.wall = true
    try {
      const payload = await getJson(
        `/api/source/${encodeURIComponent(source.uid)}/home?filter=true`,
      )
      classes.value = normalizeClasses(payload)
      wallFilters.value = payload?.filters || {}
      activeClassId.value = ''
      wallMode.value = 'home'
      wallTitle.value = `${source.name} · 首页`

      const items = normalizeList(payload).map((item) => normalizeVideoCard(item, source))
      applyClientWall(items)

      if (!items.length && classes.value.length) {
        await loadCategory(classIdOf(classes.value[0]), 1)
        return
      }

      setStatus('success', `${source.name} 首页已更新`)
    } catch (error) {
      setStatus('error', `首页加载失败：${error.message}`)
      throw error
    } finally {
      loading.wall = false
    }
  }

  async function loadCategory(tid, page = 1, options = {}) {
    const source = currentSourceOrThrow()
    if (!options.append) {
      cancelRunningSearch()
    }
    clearCurrentPlayback()

    const typeId = String(tid || '')
    if (!typeId) {
      return
    }

    loading.wall = true
    try {
      const payload = await postJson(`/api/source/${encodeURIComponent(source.uid)}/category`, {
        tid: typeId,
        pg: String(page),
        filter: true,
        extend: {},
      })

      activeClassId.value = typeId
      wallMode.value = 'category'
      const matchedClass = classes.value.find((item) => classIdOf(item) === typeId)
      wallTitle.value = matchedClass ? classNameOf(matchedClass) : `${source.name} 分类`

      const items = normalizeList(payload).map((item) => normalizeVideoCard(item, source))
      if (options.append && page > 1) {
        mergeServerWall(payload, items, page)
      } else {
        applyServerWall(payload, items, page)
      }
      setStatus('success', `${wallTitle.value} 已加载`)
    } catch (error) {
      setStatus('error', `分类加载失败：${error.message}`)
      throw error
    } finally {
      loading.wall = false
    }
  }

  async function searchCurrentSource(term) {
    const source = currentSourceOrThrow()
    const payload = await getJson(
      `/api/source/${encodeURIComponent(source.uid)}/search?wd=${encodeURIComponent(term)}&quick=false`,
      { timeout: 30000 },
    )

    wallMode.value = 'search'
    activeClassId.value = ''
    wallTitle.value = `${source.name} · 搜索 “${term}”`
    wallFilters.value = {}

    const items = normalizeList(payload).map((item) => normalizeVideoCard(item, source))
    applyClientWall(items)
    setStatus('success', `搜索完成，共找到 ${items.length} 条结果`)
  }

  async function searchSourceBatch(source, term, runId) {
    if (!searchAbortController?.signal || runId !== searchRunId) {
      return
    }

    searchProgress.currentSourceName = source.name
    try {
      const payload = await getJson(
        `/api/source/${encodeURIComponent(source.uid)}/search?wd=${encodeURIComponent(term)}&quick=false`,
        {
          signal: searchAbortController.signal,
          timeout: 15000,
        },
      )

      if (runId !== searchRunId) {
        return
      }

      const items = normalizeList(payload).map((item) => normalizeVideoCard(item, source))
      if (items.length) {
        prependUniqueWallVideos(items)
        searchProgress.matchedSources += 1
      }
    } catch (error) {
      if (isAbortError(error) || runId !== searchRunId) {
        return
      }
      searchProgress.failedSources += 1
    } finally {
      if (runId === searchRunId) {
        searchProgress.completedSources += 1
      }
    }
  }

  async function searchAllIncremental(term) {
    const searchableSources = sources.value.filter((site) => site.searchable !== 0)
    const runId = ++searchRunId

    if (searchAbortController) {
      searchAbortController.abort()
    }
    searchAbortController = new AbortController()

    wallMode.value = 'search'
    activeClassId.value = ''
    wallTitle.value = `全源搜索 · ${term}`
    wallFilters.value = {}
    wallVideos.value = []
    resetWallPagination({
      page: 1,
      pageSize: DEFAULT_WALL_PAGE_SIZE,
      pageCount: 1,
      total: 0,
      serverPaging: false,
    })

    searchProgress.active = true
    searchProgress.totalSources = searchableSources.length
    searchProgress.completedSources = 0
    searchProgress.matchedSources = 0
    searchProgress.failedSources = 0
    searchProgress.currentSourceName = ''

    loading.wall = true
    const queue = searchableSources.slice()
    const concurrency = Math.min(2, Math.max(1, searchableSources.length >= 4 ? 2 : 1))

    const workers = Array.from({ length: Math.min(concurrency, queue.length) }, async () => {
      while (queue.length && runId === searchRunId && !searchAbortController.signal.aborted) {
        const source = queue.shift()
        if (!source) {
          return
        }
        await searchSourceBatch(source, term, runId)
      }
    })

    await Promise.allSettled(workers)

    if (runId !== searchRunId) {
      return
    }

    loading.wall = false
    searchAbortController = null
    searchProgress.active = false
    searchProgress.currentSourceName = ''
    wallPagination.total = wallVideos.value.length
    wallPagination.pageCount = Math.max(
      1,
      Math.ceil(wallVideos.value.length / wallPagination.pageSize),
    )

    if (wallVideos.value.length) {
      setStatus(
        'success',
        `全源搜索完成，已返回 ${wallVideos.value.length} 条结果，失败 ${searchProgress.failedSources} 个源`,
      )
    } else {
      setStatus('warning', '全源搜索已结束，当前没有可显示结果')
    }
  }

  async function searchVideos(keyword = searchKeyword.value) {
    const term = String(keyword || '').trim()
    if (!term) {
      setStatus('warning', '请输入搜索关键词')
      return
    }

    searchKeyword.value = term
    clearCurrentPlayback()

    if (searchScope.value === 'all') {
      await searchAllIncremental(term)
      return
    }

    cancelRunningSearch()
    loading.wall = true
    try {
      await searchCurrentSource(term)
    } catch (error) {
      setStatus('error', `搜索失败：${error.message}`)
      throw error
    } finally {
      loading.wall = false
    }
  }

  async function ensureDetail(sourceUid, vodId, options = {}) {
    if (!sourceUid || !vodId) {
      return null
    }

    const cacheKey = historyKeyOf(sourceUid, vodId)
    if (!options.force && detailCache[cacheKey]) {
      currentDetail.value = detailCache[cacheKey]
      return detailCache[cacheKey]
    }

    const source = sources.value.find((site) => site.uid === sourceUid)
    if (!source) {
      throw new Error(`找不到源：${sourceUid}`)
    }

    loading.detail = true
    if (
      historyKeyOf(currentDetail.value?.sourceUid || '', currentDetail.value?.id || '') !== cacheKey
    ) {
      currentDetail.value = null
    }
    if (
      historyKeyOf(currentPlayback.value?.sourceUid || '', currentPlayback.value?.vodId || '') !==
      cacheKey
    ) {
      currentPlayback.value = null
    }

    try {
      const payload = await getJson(
        `/api/source/${encodeURIComponent(sourceUid)}/detail?id=${encodeURIComponent(vodId)}`,
        { timeout: 45000 },
      )
      const detail = normalizeDetail(payload, source)
      if (!detail) {
        throw new Error('详情数据为空')
      }
      detail.meta = formatMeta(detail)
      detail.resume = getHistoryEntry(sourceUid, vodId) || null
      detailCache[cacheKey] = detail
      currentDetail.value = detail
      setStatus('success', `${detail.title} 详情已打开`)
      return detail
    } catch (error) {
      currentDetail.value = null
      setStatus('error', `详情加载失败：${error.message}`)
      throw error
    } finally {
      loading.detail = false
    }
  }

  function resolveEpisode(detail, options = {}) {
    const groups = detail?.playGroups || []
    if (!groups.length) {
      return null
    }

    const resumeEntry =
      options.resume || options.useStoredResume
        ? getHistoryEntry(detail.sourceUid, detail.id)
        : null
    const group =
      groups.find((item) => item.name === options.flagName) ||
      groups.find((item) => item.name === resumeEntry?.playFlag) ||
      groups[0]

    if (!group?.episodes?.length) {
      return null
    }

    let episodeIndex = Number.isInteger(options.episodeIndex)
      ? options.episodeIndex
      : Number.parseInt(options.episodeIndex || '', 10)

    if (!Number.isInteger(episodeIndex) || episodeIndex < 0) {
      if (resumeEntry?.episodeId) {
        episodeIndex = group.episodes.findIndex((item) => item.id === resumeEntry.episodeId)
      }
    }

    if (!Number.isInteger(episodeIndex) || episodeIndex < 0) {
      episodeIndex = resumeEntry?.playIndex ?? 0
    }

    if (episodeIndex >= group.episodes.length) {
      episodeIndex = 0
    }

    return {
      group,
      episodeIndex,
      episode: group.episodes[episodeIndex],
    }
  }

  async function playEpisode(sourceUid, vodId, flagName, episodeIndex, options = {}) {
    const detail =
      currentDetail.value?.sourceUid === sourceUid && currentDetail.value?.id === vodId
        ? currentDetail.value
        : await ensureDetail(sourceUid, vodId)

    const resolved = resolveEpisode(detail, {
      flagName,
      episodeIndex,
      useStoredResume: options.useStoredResume,
    })
    if (!resolved?.episode) {
      throw new Error('当前视频没有可播放的集数')
    }

    loading.player = true
    currentPlayback.value = null
    try {
      const payload = await getJson(
        `/api/source/${encodeURIComponent(sourceUid)}/play?flag=${encodeURIComponent(
          resolved.group.name,
        )}&id=${encodeURIComponent(resolved.episode.id)}`,
        { timeout: 45000 },
      )
      const finalUrl = buildPlayableUrl(payload, resolved.episode.id)
      currentPlayback.value = {
        sourceUid,
        vodId,
        title: detail.title,
        poster: detail.pic,
        detail,
        flagName: resolved.group.name,
        episodeIndex: resolved.episodeIndex,
        episodeName: resolved.episode.name,
        episodeId: resolved.episode.id,
        url: finalUrl,
        playPayload: payload,
      }
      upsertHistory(detail, resolved.group.name, resolved.episodeIndex, resolved.episode, finalUrl)
      setStatus('success', `正在播放 ${detail.title} · ${resolved.episode.name}`)
      return currentPlayback.value
    } catch (error) {
      currentPlayback.value = null
      setStatus('error', `播放失败：${error.message}`)
      throw error
    } finally {
      loading.player = false
    }
  }

  async function bootstrap() {
    if (bootstrapped.value) {
      return
    }
    restoreLocalState()
    bootstrapped.value = true
    if (configUrl.value) {
      await loadConfig(configUrl.value, { autoLoadHome: true })
      return
    }
    setStatus('info', '请输入配置 URL 后开始使用')
  }

  async function goToWallPage(page) {
    const nextPage = Math.max(1, Number(page) || 1)
    if (wallPagination.serverPaging && wallMode.value === 'category' && activeClassId.value) {
      await loadCategory(activeClassId.value, nextPage)
      return
    }
    wallPagination.page = Math.min(nextPage, wallPageCount.value)
  }

  async function loadMoreWall() {
    if (loading.wall || !wallHasMore.value) {
      return
    }
    if (wallMode.value === 'category' && activeClassId.value) {
      await loadCategory(activeClassId.value, wallPagination.page + 1, { append: true })
    }
  }

  function updateSearchScope(scope) {
    searchScope.value = scope
    persistSettings()
  }

  function updateAppTheme(theme) {
    appTheme.value = theme === 'naifei' ? 'naifei' : 'default'
    persistSettings()
  }

  function clearHistory() {
    history.value = []
    persistHistory()
    setStatus('success', '历史记录已清空')
  }

  return {
    defaultConfigUrl,
    configUrl,
    appTheme,
    searchScope,
    liveSourceUrl,
    liveEpgUrl,
    config,
    sources,
    selectedSourceUid,
    selectedSource,
    classes,
    activeClassId,
    wallVideos,
    pagedWallVideos,
    wallMode,
    wallTitle,
    wallFilters,
    wallPagination,
    wallPageCount,
    wallHasMore,
    wallTotal,
    searchKeyword,
    searchProgress,
    streamProgressPercent,
    history,
    currentDetail,
    currentPlayback,
    liveGroups,
    liveGroupName,
    liveChannels,
    currentLiveChannel,
    currentLiveChannelId,
    currentLiveSource,
    currentLiveSourceIndex,
    filteredLiveChannels,
    liveSummary,
    liveEpgError,
    navigationPreview,
    detailOrigin,
    playerOrigin,
    status,
    loading,
    showSourceBadges,
    bootstrap,
    clearStatus,
    loadConfig,
    changeSource,
    loadHome,
    loadCategory,
    searchVideos,
    ensureDetail,
    playEpisode,
    updateAppTheme,
    updateSearchScope,
    updateLiveSettings,
    loadLiveData,
    setLiveGroup,
    selectLiveChannel,
    selectLiveSource,
    selectNextLiveSource,
    clearHistory,
    getHistoryEntry,
    resolveEpisode,
    goToWallPage,
    loadMoreWall,
    clearCurrentDetail,
    clearCurrentPlayback,
    setNavigationPreview,
    clearNavigationPreview,
    setDetailOrigin,
    setPlayerOrigin,
    cancelRunningSearch,
  }
})
