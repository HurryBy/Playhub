<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Clock,
  Folder,
  Grid,
  Loading,
  Menu,
  Promotion,
  Search,
  Setting,
  VideoPlay,
} from '@element-plus/icons-vue'
import VideoWall from '@/components/VideoWall.vue'
import { classIdOf, classNameOf } from '@/utils/tvbox'
import { useTvboxStore } from '@/stores/tvbox'

const router = useRouter()
const store = useTvboxStore()
const openSettings = inject('openSettings', () => {})
const now = ref(new Date())
const CLASSIC_WALL_LAYOUT_KEY = 'tvbox:web:classic-wall-layout'

let clockTimer = null

function readClassicWallLayout() {
  if (typeof localStorage === 'undefined') {
    return 'masonry'
  }
  return localStorage.getItem(CLASSIC_WALL_LAYOUT_KEY) === 'row' ? 'row' : 'masonry'
}

const categoryItems = computed(() => store.classes.filter((item) => classIdOf(item)))
const sourceLabel = computed(() => store.selectedSource?.name || '视频源')
const searchPending = computed(() => store.loading.wall && store.wallMode === 'search')
const isNaifeiTheme = computed(() => store.appTheme === 'naifei')
const classicWallLayout = ref(readClassicWallLayout())
const preSearchTarget = ref({ mode: 'home', classId: '' })
const classicWallPanelClass = computed(() => ({
  'wall-panel--classic-row': isNaifeiTheme.value && classicWallLayout.value === 'row',
}))
const keyboardKeys = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890'.split('')
const naifeiHotSearchSeeds = [
  '匆匆那年',
  '璀璨之下',
  'cctv 5体育..',
  'cctv1中央电..',
  'cctv5直播',
  'CCTV',
  'cctv13新闻..',
  'cctv1中央电..',
  '初次爱你',
]
const filteredNaifeiHotSearches = computed(() => {
  const term = String(store.searchKeyword || '').trim()
  const candidates = [
    ...naifeiHotSearchSeeds,
    ...store.wallVideos.map((item) => item?.name).filter(Boolean),
    ...store.history.map((item) => item?.title).filter(Boolean),
  ]
  const unique = [...new Set(candidates.map((item) => String(item).trim()).filter(Boolean))]

  if (!term) {
    return unique.slice(0, 9)
  }

  const lowerTerm = term.toLowerCase()
  const matched = unique.filter((item) => item.toLowerCase().includes(lowerTerm))
  const hasExact = matched.some((item) => item.toLowerCase() === lowerTerm)
  return (hasExact ? matched : [term, ...matched]).slice(0, 9)
})
const quickActions = [
  { key: 'history', label: '历史', icon: Clock, action: () => router.push({ name: 'history' }) },
  { key: 'live', label: '直播', icon: VideoPlay, action: () => router.push({ name: 'live' }) },
  { key: 'search', label: '搜索', icon: Search, action: () => openNaifeiSearch() },
  { key: 'push', label: '推送', icon: Promotion, action: () => openSettings() },
  { key: 'file', label: '文件', icon: Folder, action: () => openSettings() },
]
const wallHeading = computed(() => {
  if (store.wallMode === 'search' && store.searchKeyword) {
    return `“${store.searchKeyword}”的搜索结果`
  }
  if (store.activeClassId) {
    const current = categoryItems.value.find((item) => classIdOf(item) === store.activeClassId)
    if (current) {
      return classNameOf(current)
    }
  }
  return sourceLabel.value
})
const naifeiTimeLabel = computed(() => {
  const value = now.value
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  const hour = value.getHours()
  const hour12 = hour % 12 || 12
  const minute = String(value.getMinutes()).padStart(2, '0')
  const period = hour < 12 ? '上午' : '下午'
  return `${month}月${day}日, ${weekdays[value.getDay()]} ${hour12}:${minute} ${period}`
})

async function handleSearch() {
  await store.searchVideos()
}

function openNaifeiSearch() {
  preSearchTarget.value = {
    mode: store.wallMode === 'category' && store.activeClassId ? 'category' : 'home',
    classId: store.activeClassId || '',
  }
  store.wallMode = 'search'
}

async function closeNaifeiSearch() {
  const target = preSearchTarget.value
  store.wallMode = target.mode === 'category' && target.classId ? 'category' : 'home'
  store.activeClassId = target.mode === 'category' && target.classId ? target.classId : ''

  if (preSearchTarget.value.mode === 'category' && preSearchTarget.value.classId) {
    await store.loadCategory(preSearchTarget.value.classId, 1).catch(() => {})
    return
  }
  await store.loadHome().catch(() => {})
}

function toggleClassicWallLayout() {
  classicWallLayout.value = classicWallLayout.value === 'row' ? 'masonry' : 'row'
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(CLASSIC_WALL_LAYOUT_KEY, classicWallLayout.value)
  }
}

async function handleLoadMore() {
  await store.loadMoreWall()
}

function openDetail(video) {
  if (isNaifeiTheme.value) {
    openPlayerDirectly(video)
    return
  }

  store.setDetailOrigin({ name: 'home' })
  store.setNavigationPreview(video)

  const navigate = () =>
    router.push({
      name: 'detail',
      query: {
        source: video.sourceUid || store.selectedSourceUid,
        vod: video.id,
      },
    })

  if (typeof document !== 'undefined' && typeof document.startViewTransition === 'function') {
    document.startViewTransition(navigate)
    return
  }

  navigate()
}

function openPlayerDirectly(video) {
  const sourceUid = video.sourceUid || store.selectedSourceUid
  if (!sourceUid || !video.id) {
    return
  }

  store.setPlayerOrigin({ name: 'home' })
  store.setNavigationPreview(video)

  const navigate = () =>
    router.push({
      name: 'player',
      query: {
        source: sourceUid,
        vod: video.id,
        resume: '1',
      },
    })

  if (typeof document !== 'undefined' && typeof document.startViewTransition === 'function') {
    document.startViewTransition(navigate)
    return
  }

  navigate()
}

function appendSearchKey(key) {
  store.searchKeyword = `${store.searchKeyword || ''}${key}`
}

function deleteSearchKey() {
  store.searchKeyword = String(store.searchKeyword || '').slice(0, -1)
}

async function searchHot(term) {
  store.searchKeyword = term
  await handleSearch()
}

onMounted(() => {
  clockTimer = window.setInterval(() => {
    now.value = new Date()
  }, 30_000)
})

onBeforeUnmount(() => {
  if (clockTimer) {
    window.clearInterval(clockTimer)
  }
})
</script>

<template>
  <section
    class="page home-page home-page--cinema"
    :class="{
      'home-page--naifei': isNaifeiTheme,
      'home-page--naifei-search': isNaifeiTheme && store.wallMode === 'search',
    }"
  >
    <template v-if="isNaifeiTheme && store.wallMode === 'search'">
      <div class="naifei-search-layout">
        <aside class="naifei-search-pad">
          <div class="naifei-search-input-row">
            <button
              type="button"
              class="naifei-search-back"
              aria-label="返回首页"
              @click="closeNaifeiSearch"
            >
              ‹
            </button>
            <el-input
              v-model.trim="store.searchKeyword"
              size="large"
              class="naifei-search-input"
              placeholder="输入关键词"
              clearable
              @keyup.enter="handleSearch"
            />
            <button type="button" class="naifei-icon-button" @click="handleSearch">
              <el-icon><Menu /></el-icon>
            </button>
          </div>

          <div class="naifei-search-actions">
            <button type="button" @click="handleSearch">搜索</button>
            <button type="button" @click="store.searchKeyword = ''">清空</button>
            <button type="button" @click="handleSearch">远程搜索</button>
            <button type="button" @click="deleteSearchKey">删除</button>
          </div>

          <div class="naifei-keyboard">
            <button
              v-for="key in keyboardKeys"
              :key="key"
              type="button"
              @click="appendSearchKey(key)"
            >
              {{ key }}
            </button>
          </div>
        </aside>

        <aside class="naifei-hot-panel">
          <h2>热门搜索</h2>
          <button
            v-for="item in filteredNaifeiHotSearches"
            :key="item"
            type="button"
            @click="searchHot(item)"
          >
            {{ item }}
          </button>
        </aside>

        <VideoWall
          class="naifei-search-results"
          :videos="store.wallVideos"
          :loading="store.loading.wall"
          :show-source="store.showSourceBadges"
          :stream-progress="store.searchProgress"
          :stream-percent="store.streamProgressPercent"
          :can-load-more="store.wallHasMore"
          @load-more="handleLoadMore"
          @select="openDetail"
        />
      </div>
    </template>

    <template v-else>
      <header v-if="isNaifeiTheme" class="naifei-home-top">
        <div class="naifei-title">
          <span></span>
          <h1>{{ sourceLabel }}</h1>
        </div>

        <div class="naifei-status-icons">
          <a href="https://www.github.com/HurryBy/Playhub" target="_blank" rel="noopener" class="naifei-github-link">
            <svg height="26" width="26" viewBox="0 0 16 16" fill="currentColor"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>
          </a>
          <button
            type="button"
            class="naifei-chevron-pair"
            :class="{ active: classicWallLayout === 'row' }"
            :title="classicWallLayout === 'row' ? '切换为瀑布排列' : '切换为横向排列'"
            aria-label="切换视频排版"
            @click="toggleClassicWallLayout"
          >
            <span></span>
            <span></span>
          </button>
          <el-icon><Grid /></el-icon>
          <button type="button" class="naifei-plain-icon" @click="openSettings">
            <el-icon><Setting /></el-icon>
          </button>
          <strong>{{ naifeiTimeLabel }}</strong>
        </div>
      </header>

      <nav v-if="isNaifeiTheme" class="naifei-category-tabs">
        <button
          v-for="item in categoryItems"
          :key="classIdOf(item)"
          type="button"
          :class="{ active: store.activeClassId === classIdOf(item) }"
          @click="store.loadCategory(classIdOf(item), 1)"
        >
          {{ classNameOf(item) }}
        </button>
      </nav>

      <div v-if="isNaifeiTheme" class="naifei-action-grid">
        <button
          v-for="item in quickActions"
          :key="item.key"
          type="button"
          @click="item.action"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </button>
      </div>

      <header v-if="!isNaifeiTheme" class="home-command-bar">
        <div class="home-command-main">
          <div class="home-command-copy">
            <p class="section-kicker">Video Wall</p>
            <h1 class="home-surface-title">{{ wallHeading }}</h1>
          </div>

          <div
            v-if="categoryItems.length"
            class="wall-filter-row wall-filter-row--aligned home-command-filters"
          >
            <button
              v-for="item in categoryItems"
              :key="classIdOf(item)"
              type="button"
              class="category-pill"
              :class="{ active: store.activeClassId === classIdOf(item) }"
              @click="store.loadCategory(classIdOf(item), 1)"
            >
              {{ classNameOf(item) }}
            </button>
          </div>

          <div class="home-command-foot home-command-foot--left">
            <span class="home-source-mark">{{ sourceLabel }}</span>
            <span class="home-search-mode">
              {{ store.searchScope === 'all' ? '全源流式搜索' : '当前源搜索' }}
            </span>
          </div>
        </div>

        <div class="home-command-stack">
          <div class="home-search-shell">
            <el-input
              v-model.trim="store.searchKeyword"
              size="large"
              placeholder="搜索影片、剧集、综艺或动漫"
              class="home-search-input home-search-input--floating"
              clearable
              @keyup.enter="handleSearch"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>

            <button
              type="button"
              class="home-search-submit"
              :class="{ 'is-loading': searchPending }"
              @click="handleSearch"
            >
              <span v-if="!searchPending">搜索</span>
              <span v-else class="home-search-submit-copy">
                <el-icon class="is-loading"><Loading /></el-icon>
                搜索中
              </span>
            </button>
          </div>
        </div>
      </header>

      <VideoWall
        :class="classicWallPanelClass"
        :videos="store.wallVideos"
        :loading="store.loading.wall"
        :show-source="store.showSourceBadges"
        :stream-progress="store.searchProgress"
        :stream-percent="store.streamProgressPercent"
        :can-load-more="store.wallHasMore"
        @load-more="handleLoadMore"
        @select="openDetail"
      />
    </template>
  </section>
</template>
