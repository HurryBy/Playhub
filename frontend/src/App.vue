<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Clock, House, Setting, VideoPlay } from '@element-plus/icons-vue'
import { useTvboxStore } from '@/stores/tvbox'

const route = useRoute()
const router = useRouter()
const store = useTvboxStore()

const settingsOpen = ref(false)
const draftConfigUrl = ref('')
const draftLiveSourceUrl = ref('')
const draftLiveEpgUrl = ref('')

const canGoBack = computed(() => route.name !== 'home' || store.wallMode !== 'home')
const primaryNavIcon = computed(() => (canGoBack.value ? ArrowLeft : House))
const primaryNavLabel = computed(() => (canGoBack.value ? '返回' : '首页'))
const sourceCount = computed(() => store.sources.length)
const searchableSourceCount = computed(
  () => store.sources.filter((site) => site?.searchable !== 0).length,
)
const activeSourceName = computed(() => store.selectedSource?.name || '未选择视频源')
const searchScopeLabel = computed(() =>
  store.searchScope === 'all' ? '全源逐源回填' : '仅搜索当前视频源',
)
const currentConfigHost = computed(() => parseHost(draftConfigUrl.value || store.configUrl))
const currentLiveSourceHost = computed(() => parseHost(draftLiveSourceUrl.value || store.liveSourceUrl))
const currentLiveEpgHost = computed(() => parseHost(draftLiveEpgUrl.value || store.liveEpgUrl))

function parseHost(raw) {
  const value = String(raw || '').trim()
  if (!value) {
    return '未填写'
  }
  try {
    return new URL(value).host
  } catch {
    return '未识别地址'
  }
}

function syncDrafts() {
  draftConfigUrl.value = store.configUrl || ''
  draftLiveSourceUrl.value = store.liveSourceUrl || ''
  draftLiveEpgUrl.value = store.liveEpgUrl || ''
}

function openSettings() {
  syncDrafts()
  settingsOpen.value = true
}

async function reloadConfig() {
  await store.loadConfig(draftConfigUrl.value, { autoLoadHome: true })
}

async function saveLiveConfig() {
  const nextLiveSource = String(draftLiveSourceUrl.value || '').trim()
  const nextLiveEpg = String(draftLiveEpgUrl.value || '').trim()
  const changed =
    nextLiveSource !== (store.liveSourceUrl || '') || nextLiveEpg !== (store.liveEpgUrl || '')

  store.updateLiveSettings({
    liveSourceUrl: nextLiveSource,
    liveEpgUrl: nextLiveEpg,
    resetData: changed,
  })

  if (route.name === 'live' && nextLiveSource) {
    await store.loadLiveData({
      playlistUrl: nextLiveSource,
      epgUrl: nextLiveEpg,
    })
  }
}

async function openHistory() {
  if (route.name !== 'history') {
    await router.push({ name: 'history' })
  }
}

async function openLive() {
  if (route.name !== 'live') {
    await router.push({ name: 'live' })
  }
}

function cloneTarget(target, fallbackName = 'home') {
  if (!target || typeof target !== 'object') {
    return { name: fallbackName }
  }
  return {
    name: typeof target.name === 'string' ? target.name : fallbackName,
    query: target.query && typeof target.query === 'object' ? { ...target.query } : {},
  }
}

async function handlePrimaryNav() {
  if (route.name === 'player') {
    const detailTarget =
      store.currentPlayback?.detail && store.currentPlayback.detail.sourceUid && store.currentPlayback.detail.id
        ? {
            name: 'detail',
            query: {
              source: store.currentPlayback.detail.sourceUid,
              vod: store.currentPlayback.detail.id,
            },
          }
        : { name: 'home' }
    const hasPlayerOriginDetail =
      store.playerOrigin?.name === 'detail' &&
      store.playerOrigin?.query?.source &&
      store.playerOrigin?.query?.vod
    const target =
      store.playerOrigin?.name === 'history'
        ? cloneTarget(store.playerOrigin, 'history')
        : hasPlayerOriginDetail
          ? cloneTarget(store.playerOrigin, 'detail')
          : detailTarget
    await router.replace(target)
    return
  }

  if (route.name === 'detail') {
    const target = store.detailOrigin?.name
      ? cloneTarget(store.detailOrigin, store.detailOrigin.name)
      : { name: 'home' }
    await router.replace(target)
    return
  }

  if (route.name === 'history' || route.name === 'live') {
    await router.push({ name: 'home' })
    return
  }

  if (route.name === 'home' && store.wallMode !== 'home') {
    if (store.selectedSourceUid) {
      await store.loadHome()
    } else {
      await router.push({ name: 'home' })
    }
    return
  }

  await router.push({ name: 'home' })
}

async function changeSource(uid) {
  await store.changeSource(uid)
}

watch(
  () => [store.configUrl, store.liveSourceUrl, store.liveEpgUrl],
  () => {
    if (!settingsOpen.value) {
      syncDrafts()
    }
  },
)

watch(
  () => [store.status.type, store.status.text],
  ([type, text]) => {
    if (!type || !text) {
      return
    }
    if (type !== 'success') {
      ElMessage({
        type: type === 'info' ? 'info' : type,
        message: text,
        duration: type === 'error' ? 3200 : 2400,
        grouping: true,
        showClose: true,
      })
    }
    store.clearStatus()
  },
)

onMounted(async () => {
  syncDrafts()
  await store.bootstrap().catch(() => {})
})
</script>

<template>
  <div class="app-frame">
    <aside class="app-rail">
      <button
        type="button"
        class="rail-brand"
        :aria-label="primaryNavLabel"
        @click="handlePrimaryNav"
      >
        <el-icon><component :is="primaryNavIcon" /></el-icon>
      </button>

      <div class="rail-spacer"></div>

      <button
        type="button"
        class="rail-button"
        :class="{ 'rail-button--active': route.name === 'history' }"
        aria-label="历史记录"
        @click="openHistory"
      >
        <el-icon><Clock /></el-icon>
      </button>

      <button
        type="button"
        class="rail-button"
        :class="{ 'rail-button--active': route.name === 'live' }"
        aria-label="直播"
        @click="openLive"
      >
        <el-icon><VideoPlay /></el-icon>
      </button>

      <button
        type="button"
        class="rail-button rail-button--primary"
        :class="{ 'rail-button--active': settingsOpen }"
        aria-label="设置"
        @click="openSettings"
      >
        <el-icon><Setting /></el-icon>
      </button>
    </aside>

    <main class="app-stage">
      <router-view v-slot="{ Component, route: currentRoute }">
        <transition name="page-swap" mode="out-in" appear>
          <component :is="Component" :key="currentRoute.fullPath" />
        </transition>
      </router-view>
    </main>
  </div>

  <el-drawer
    v-model="settingsOpen"
    direction="rtl"
    size="620px"
    class="settings-drawer"
  >
    <template #header>
      <div class="drawer-head drawer-head--hero drawer-head--compact">
        <p class="drawer-kicker">Control Room</p>
        <h3>源与直播</h3>
        <p class="drawer-subtitle">
          点播配置和直播配置分开管理，抽屉会按内容自适应滚动，不再让顶部说明把主体内容挤掉。
        </p>
      </div>
    </template>

    <div class="drawer-body drawer-body--stack">
      <section class="settings-block settings-block--overview">
        <div class="settings-block-head">
          <div>
            <p class="block-label">概览</p>
            <h4>当前会话状态</h4>
          </div>
          <span class="settings-mini-chip">点播与直播互不覆盖</span>
        </div>

        <div class="drawer-overview-grid drawer-overview-grid--compact">
          <article class="drawer-overview-card">
            <span>当前点播源</span>
            <strong>{{ activeSourceName }}</strong>
            <small>{{ sourceCount }} 个可用源</small>
          </article>
          <article class="drawer-overview-card">
            <span>搜索模式</span>
            <strong>{{ searchScopeLabel }}</strong>
            <small>{{ searchableSourceCount }} 个源支持搜索</small>
          </article>
          <article class="drawer-overview-card">
            <span>点播配置</span>
            <strong>{{ currentConfigHost }}</strong>
            <small>首次打开默认不预填测试源</small>
          </article>
          <article class="drawer-overview-card">
            <span>直播源</span>
            <strong>{{ currentLiveSourceHost }}</strong>
            <small>{{ store.liveSummary?.channelCount || 0 }} 个直播频道</small>
          </article>
          <article class="drawer-overview-card">
            <span>EPG</span>
            <strong>{{ currentLiveEpgHost }}</strong>
            <small>{{ store.liveSummary?.epgMatchedChannels || 0 }} 个频道已匹配</small>
          </article>
          <article class="drawer-overview-card">
            <span>直播源条数</span>
            <strong>{{ store.liveSummary?.sourceCount || 0 }}</strong>
            <small>同名频道会自动合并多源</small>
          </article>
        </div>
      </section>

      <section class="settings-block settings-block--accent">
        <div class="settings-block-head">
          <div>
            <p class="block-label">当前视频源</p>
            <h4>{{ activeSourceName }}</h4>
          </div>
          <span class="settings-mini-chip">{{ sourceCount }} 个源</span>
        </div>

        <el-select
          :model-value="store.selectedSourceUid"
          filterable
          class="source-select"
          placeholder="选择视频源"
          :disabled="!store.sources.length"
          @change="changeSource"
        >
          <el-option
            v-for="site in store.sources"
            :key="site.uid"
            :label="site.name"
            :value="site.uid"
          />
        </el-select>

        <div class="settings-chip-row">
          <span class="settings-mini-chip">可搜索 {{ searchableSourceCount }}</span>
          <span class="settings-mini-chip">{{ store.selectedSource?.api || '等待加载配置' }}</span>
        </div>
      </section>

      <section class="settings-block">
        <div class="settings-block-head">
          <div>
            <p class="block-label">搜索范围</p>
            <h4>控制搜索结果如何回填</h4>
          </div>
          <span class="settings-mini-chip">{{ searchScopeLabel }}</span>
        </div>

        <el-radio-group
          :model-value="store.searchScope"
          class="settings-radio-group"
          @update:model-value="store.updateSearchScope"
        >
          <el-radio-button :value="'current'">当前源</el-radio-button>
          <el-radio-button :value="'all'">全源</el-radio-button>
        </el-radio-group>

        <p class="block-hint">
          全源搜索会逐个源流式回填到影视墙，不用等所有源全部结束才显示结果。
        </p>
      </section>

      <section class="settings-block">
        <div class="settings-block-head">
          <div>
            <p class="block-label">点播配置 URL</p>
            <h4>加载你的点播配置文件</h4>
          </div>
          <span class="settings-mini-chip">{{ currentConfigHost }}</span>
        </div>

        <el-input
          v-model="draftConfigUrl"
          type="textarea"
          :rows="5"
          resize="none"
          placeholder="输入点播配置文件地址"
        />

        <div class="settings-actions">
          <el-button
            type="primary"
            :loading="store.loading.config"
            @click="reloadConfig"
          >
            加载点播配置
          </el-button>
        </div>
      </section>

      <section class="settings-block settings-block--accent settings-block--live">
        <div class="settings-block-head">
          <div>
            <p class="block-label">直播配置</p>
            <h4>直播源与 EPG</h4>
          </div>
          <span class="settings-mini-chip">{{ store.liveSummary?.channelCount || 0 }} 个频道</span>
        </div>

        <div class="settings-field-stack">
          <label class="settings-field">
            <span>直播源地址</span>
            <el-input
              v-model="draftLiveSourceUrl"
              placeholder="输入 m3u / m3u8 直播源地址"
            />
          </label>

          <label class="settings-field">
            <span>EPG 地址</span>
            <el-input
              v-model="draftLiveEpgUrl"
              placeholder="输入 xml / xml.gz 节目单地址（可选）"
            />
          </label>
        </div>

        <div class="settings-chip-row">
          <span class="settings-mini-chip">直播 {{ currentLiveSourceHost }}</span>
          <span class="settings-mini-chip">EPG {{ currentLiveEpgHost }}</span>
        </div>

        <div class="settings-actions">
          <el-button
            type="primary"
            :loading="store.loading.live"
            @click="saveLiveConfig"
          >
            保存直播配置
          </el-button>
        </div>
      </section>
    </div>
  </el-drawer>
</template>
