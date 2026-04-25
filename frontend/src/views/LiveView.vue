<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshRight } from '@element-plus/icons-vue'
import SmartPlayer from '@/components/SmartPlayer.vue'
import { useTvboxStore } from '@/stores/tvbox'

const store = useTvboxStore()
const now = ref(Date.now())
const streamReady = ref(false)

let tickTimer = null
let failoverTimer = null

const groupOptions = computed(() => [
  { name: '全部频道', count: store.liveChannels.length },
  ...store.liveGroups,
])
const currentChannel = computed(() => store.currentLiveChannel)
const currentSource = computed(() => store.currentLiveSource)
const channelSources = computed(() => currentChannel.value?.sources || [])
const programmes = computed(() =>
  Array.isArray(currentChannel.value?.programmes) ? currentChannel.value.programmes : [],
)
const currentProgramme = computed(() => {
  const currentTime = now.value
  return (
    programmes.value.find((item) => Number(item.start) <= currentTime && Number(item.end) > currentTime) || null
  )
})
const nextProgramme = computed(() => {
  const currentTime = now.value
  return programmes.value.find((item) => Number(item.start) > currentTime) || null
})
const visibleProgrammes = computed(() => {
  if (!programmes.value.length) {
    return []
  }
  const currentTime = now.value
  const anchorIndex = programmes.value.findIndex((item) => Number(item.end) > currentTime)
  const startIndex = anchorIndex > 1 ? anchorIndex - 1 : Math.max(anchorIndex, 0)
  return programmes.value.slice(startIndex, startIndex + 12)
})
const sourceHost = computed(() => {
  if (!store.liveSourceUrl) {
    return '未填写直播源'
  }
  try {
    return new URL(store.liveSourceUrl).host
  } catch {
    return '未识别直播源地址'
  }
})
const epgHost = computed(() => {
  if (!store.liveEpgUrl) {
    return '未填写 EPG'
  }
  try {
    return new URL(store.liveEpgUrl).host
  } catch {
    return '未识别 EPG 地址'
  }
})
const progressPercent = computed(() => {
  if (!currentProgramme.value) {
    return 0
  }
  const start = Number(currentProgramme.value.start)
  const end = Number(currentProgramme.value.end)
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
    return 0
  }
  return Math.min(100, Math.max(0, Math.round(((now.value - start) / (end - start)) * 100)))
})
const currentSourceKey = computed(
  () => `${currentChannel.value?.id || ''}:${store.currentLiveSourceIndex}:${currentSource.value?.url || ''}`,
)

function formatClock(timestamp) {
  const date = new Date(Number(timestamp) || Date.now())
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

function formatProgrammeWindow(programme) {
  if (!programme) {
    return '暂无节目单'
  }
  return `${formatClock(programme.start)} - ${formatClock(programme.end)}`
}

function fallbackLogo(name) {
  return String(name || '').trim().slice(0, 1) || '台'
}

function programmeState(programme) {
  const currentTime = now.value
  const start = Number(programme?.start)
  const end = Number(programme?.end)
  if (Number.isFinite(start) && Number.isFinite(end)) {
    if (start <= currentTime && end > currentTime) {
      return 'is-live'
    }
    if (start > currentTime) {
      return 'is-upcoming'
    }
  }
  return 'is-past'
}

function clearFailoverTimer() {
  if (failoverTimer) {
    window.clearTimeout(failoverTimer)
    failoverTimer = null
  }
}

function tryAutoSwitch(reason) {
  const switched = store.selectNextLiveSource()
  if (switched) {
    ElMessage({
      type: 'warning',
      message: reason,
      duration: 2400,
      grouping: true,
      showClose: true,
    })
    return true
  }

  ElMessage({
    type: 'error',
    message: '当前频道的可用源都播放失败了',
    duration: 2600,
    grouping: true,
    showClose: true,
  })
  return false
}

async function loadLive() {
  await store.loadLiveData().catch(() => {})
}

function selectGroup(name) {
  store.setLiveGroup(name)
}

function selectChannel(id) {
  store.selectLiveChannel(id)
}

function selectSource(index) {
  store.selectLiveSource(index)
}

function handlePlayerPlayable() {
  streamReady.value = true
  clearFailoverTimer()
}

function handlePlayerError() {
  streamReady.value = false
  clearFailoverTimer()
  tryAutoSwitch('当前源不可播放，已自动切换到下一源')
}

watch(
  currentSourceKey,
  () => {
    clearFailoverTimer()
    streamReady.value = false

    if (!currentSource.value?.url) {
      return
    }

    failoverTimer = window.setTimeout(() => {
      if (!streamReady.value) {
        tryAutoSwitch('当前源响应超时，已自动切换到下一源')
      }
    }, 12000)
  },
  { immediate: true },
)

onMounted(async () => {
  if (store.liveSourceUrl && !store.liveChannels.length) {
    await loadLive()
  }

  tickTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 30000)
})

onBeforeUnmount(() => {
  clearFailoverTimer()
  if (tickTimer) {
    window.clearInterval(tickTimer)
    tickTimer = null
  }
})
</script>

<template>
  <section class="page live-page">
    <header class="live-command-bar">
      <div class="live-command-copy">
        <p class="section-kicker">Live</p>
        <h1 class="live-surface-title">直播导播台</h1>
        <p class="section-subtitle">
          左侧固定当前频道与节目单，右侧只保留频道分组和选台列表；同名频道会自动合并成多源。
        </p>

        <div class="live-meta-row">
          <span class="home-source-mark">直播源 {{ sourceHost }}</span>
          <span class="home-search-mode">
            {{ store.liveEpgUrl ? `EPG ${epgHost}` : '未配置 EPG' }}
          </span>
          <span v-if="store.liveSummary?.channelCount" class="home-search-mode">
            {{ store.liveSummary.channelCount }} 个频道
          </span>
          <span v-if="store.liveSummary?.sourceCount" class="home-search-mode">
            {{ store.liveSummary.sourceCount }} 条可切换源
          </span>
        </div>
      </div>

      <div class="live-command-actions">
        <el-button
          type="primary"
          :loading="store.loading.live"
          @click="loadLive"
        >
          <el-icon><RefreshRight /></el-icon>
          更新频道
        </el-button>
      </div>
    </header>

    <section
      v-if="!store.liveSourceUrl"
      class="live-empty-stage"
    >
      <div class="live-empty-copy">
        <p class="section-kicker">Ready</p>
        <h3>先在设置里填入直播源</h3>
        <p class="section-subtitle">
          这里支持频道 `m3u / m3u8` 清单，另外可以单独补一个 `xml / xml.gz` 的 EPG 地址。
        </p>
      </div>
    </section>

    <section
      v-else-if="store.loading.live && !store.liveChannels.length"
      class="live-empty-stage"
    >
      <div class="player-loading-state">
        <span class="loading-spinner" aria-hidden="true"></span>
        <div>
          <strong>正在整理直播频道</strong>
          <p>播放器、频道列表和节目单会在数据准备好后一起展开。</p>
        </div>
      </div>
    </section>

    <div v-else class="live-layout">
      <section class="live-stage-panel">
        <div class="player-stage-head live-stage-head">
          <div>
            <p class="hero-kicker">Now Playing</p>
            <h3>{{ currentChannel?.name || '未选择频道' }}</h3>
            <p class="detail-meta">
              {{ currentChannel?.group || '未分组' }}
              <span v-if="currentChannel?.epgChannelId"> · EPG 已匹配</span>
              <span v-if="channelSources.length > 1"> · {{ channelSources.length }} 个源</span>
            </p>
          </div>

          <div class="live-badge">
            <span class="live-dot"></span>
            LIVE
          </div>
        </div>

        <div class="live-player-shell">
          <SmartPlayer
            v-if="currentSource?.url"
            :url="currentSource.url"
            :poster="currentSource.logo || currentChannel?.logo"
            @playable="handlePlayerPlayable"
            @error="handlePlayerError"
          />
          <el-empty v-else description="当前没有可播放的直播频道" />
        </div>

        <div v-if="channelSources.length" class="live-source-switcher">
          <div class="section-head compact">
            <div>
              <p class="section-kicker">Sources</p>
              <h3>频道源切换</h3>
            </div>
            <span class="settings-mini-chip">失败会自动换到下一源</span>
          </div>

          <div class="live-source-strip">
            <button
              v-for="(source, sourceIndex) in channelSources"
              :key="source.id"
              type="button"
              class="live-source-pill"
              :class="{ active: store.currentLiveSourceIndex === sourceIndex }"
              @click="selectSource(sourceIndex)"
            >
              <strong>{{ source.label }}</strong>
              <small>{{ sourceIndex + 1 }}</small>
            </button>
          </div>
        </div>

        <div class="live-now-panel">
          <div class="live-now-copy">
            <p class="section-kicker">On Air</p>
            <h4>{{ currentProgramme?.title || currentChannel?.name || '等待节目单' }}</h4>
            <p class="section-subtitle">
              {{ formatProgrammeWindow(currentProgramme) }}
              <span v-if="nextProgramme"> · 下一档 {{ nextProgramme.title }}</span>
            </p>
          </div>

          <div v-if="currentProgramme" class="live-progress-shell">
            <div class="live-progress-track">
              <span class="live-progress-fill" :style="{ width: `${progressPercent}%` }"></span>
            </div>
            <strong>{{ progressPercent }}%</strong>
          </div>
        </div>

        <section class="live-guide-panel live-guide-panel--embedded">
          <div class="section-head compact">
            <div>
              <p class="section-kicker">EPG</p>
              <h3>{{ currentChannel?.name || '节目单' }}</h3>
            </div>
            <span class="settings-mini-chip">
              {{ store.liveEpgError ? 'EPG 异常' : store.liveEpgUrl ? 'EPG 已接入' : '未配置 EPG' }}
            </span>
          </div>

          <div v-if="store.liveEpgError" class="live-guide-alert">
            {{ store.liveEpgError }}
          </div>

          <div class="live-guide-summary">
            <article class="live-guide-card live-guide-card--accent">
              <p class="block-label">当前节目</p>
              <h4>{{ currentProgramme?.title || '暂无节目单' }}</h4>
              <span>{{ formatProgrammeWindow(currentProgramme) }}</span>
            </article>

            <article class="live-guide-card">
              <p class="block-label">下一档</p>
              <h4>{{ nextProgramme?.title || '等待更新' }}</h4>
              <span>{{ formatProgrammeWindow(nextProgramme) }}</span>
            </article>
          </div>

          <transition-group
            v-if="visibleProgrammes.length"
            name="programme-flow"
            tag="div"
            class="live-programme-list"
          >
            <article
              v-for="programme in visibleProgrammes"
              :key="`${programme.start}-${programme.title}`"
              class="live-programme-item"
              :class="programmeState(programme)"
            >
              <div class="live-programme-time">
                <strong>{{ formatClock(programme.start) }}</strong>
                <span>{{ formatClock(programme.end) }}</span>
              </div>

              <div class="live-programme-copy">
                <h4>{{ programme.title }}</h4>
                <p>{{ programme.desc || programme.subTitle || '暂无额外节目简介' }}</p>
              </div>

              <span class="live-programme-tag">
                {{
                  programmeState(programme) === 'is-live'
                    ? '直播中'
                    : programmeState(programme) === 'is-upcoming'
                      ? '即将开始'
                      : '已播'
                }}
              </span>
            </article>
          </transition-group>

          <el-empty
            v-else
            description="当前频道暂无可展示的节目单"
          />
        </section>
      </section>

      <section class="live-channel-panel">
        <div class="section-head compact">
          <div>
            <p class="section-kicker">Channels</p>
            <h3>频道选择</h3>
          </div>
          <span class="settings-mini-chip">{{ store.filteredLiveChannels.length }} 个可见频道</span>
        </div>

        <div class="live-group-strip">
          <button
            v-for="group in groupOptions"
            :key="group.name"
            type="button"
            class="live-group-pill"
            :class="{ active: store.liveGroupName === group.name }"
            @click="selectGroup(group.name)"
          >
            <span>{{ group.name }}</span>
            <small>{{ group.count }}</small>
          </button>
        </div>

        <transition-group name="channel-lane" tag="div" class="live-channel-list">
          <button
            v-for="channel in store.filteredLiveChannels"
            :key="channel.id"
            type="button"
            class="live-channel-item"
            :class="{ active: store.currentLiveChannelId === channel.id }"
            @click="selectChannel(channel.id)"
          >
            <div class="live-channel-logo">
              <img
                v-if="channel.logo"
                :src="channel.logo"
                :alt="channel.name"
                loading="lazy"
              />
              <span v-else>{{ fallbackLogo(channel.name) }}</span>
            </div>

            <div class="live-channel-copy">
              <strong>{{ channel.name }}</strong>
              <span>{{ channel.group }}</span>
            </div>

            <div class="live-channel-status">
              <span class="live-channel-status-title">
                {{
                  channel.programmes?.find((item) => Number(item.start) <= now && Number(item.end) > now)?.title ||
                  '暂无节目单'
                }}
              </span>
              <small>{{ channel.sources?.length || 1 }} 个源</small>
            </div>
          </button>
        </transition-group>
      </section>
    </div>
  </section>
</template>
