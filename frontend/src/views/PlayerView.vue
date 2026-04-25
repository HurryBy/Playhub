<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { RefreshRight } from '@element-plus/icons-vue'
import SmartPlayer from '@/components/SmartPlayer.vue'
import { useTvboxStore } from '@/stores/tvbox'

const route = useRoute()
const router = useRouter()
const store = useTvboxStore()
const playbackError = ref(false)

const detail = computed(() => store.currentPlayback?.detail || store.currentDetail)
const currentPlayback = computed(() => store.currentPlayback)
const playGroups = computed(() => detail.value?.playGroups || [])
const playerPending = computed(() => store.loading.detail || store.loading.player)
const currentGroup = computed(
  () => playGroups.value.find((item) => item.name === currentPlayback.value?.flagName) || null,
)

async function loadPlayback() {
  const sourceUid = String(route.query.source || '')
  const vodId = String(route.query.vod || '')
  playbackError.value = false

  if (!sourceUid || !vodId) {
    store.clearCurrentPlayback()
    return
  }

  store.clearCurrentPlayback()
  try {
    await store.ensureDetail(sourceUid, vodId, { force: true })
    await store.playEpisode(sourceUid, vodId, route.query.flag, route.query.episode, {
      useStoredResume: route.query.resume === '1',
    })
  } catch {
    playbackError.value = true
  }
}

function switchEpisode(flagName, episodeIndex) {
  store.setPlayerOrigin({
    name: 'detail',
    query: {
      source: detail.value?.sourceUid,
      vod: detail.value?.id,
    },
  })
  router.replace({
    name: 'player',
    query: {
      source: detail.value?.sourceUid,
      vod: detail.value?.id,
      flag: flagName,
      episode: String(episodeIndex),
    },
  })
}

function handlePlayerError() {
  if (playbackError.value) {
    return
  }
  playbackError.value = true
  ElMessage({
    type: 'warning',
    message: '播放器加载失败，可以尝试重新解析当前剧集。',
    duration: 2600,
    grouping: true,
    showClose: true,
  })
}

watch(
  () => route.fullPath,
  async () => {
    await loadPlayback()
  },
  { immediate: true },
)
</script>

<template>
  <section v-if="detail && currentPlayback" class="page player-page">
    <div class="player-layout">
      <div class="player-stage">
        <div class="player-stage-head">
          <div>
            <p class="hero-kicker">Player</p>
            <h3>{{ detail.title }}</h3>
            <p class="detail-meta">{{ currentPlayback.flagName }} · {{ currentPlayback.episodeName }}</p>
          </div>
        </div>

        <div class="player-frame">
          <SmartPlayer
            :url="currentPlayback.url"
            :poster="detail.pic"
            @error="handlePlayerError"
          />
        </div>

        <div class="player-note">
          <span>如果当前线路异常，可以切换右侧线路或重新解析当前集。</span>
          <el-button text @click="loadPlayback">
            <el-icon><RefreshRight /></el-icon>
            重新解析
          </el-button>
        </div>
      </div>

      <div class="player-side">
        <section class="sidebar-block">
          <div class="section-head compact">
            <div>
              <p class="section-kicker">Source</p>
              <h3>播放线路</h3>
            </div>
          </div>

          <div class="line-stack">
            <button
              v-for="group in playGroups"
              :key="group.name"
              type="button"
              class="line-pill"
              :class="{ active: currentPlayback.flagName === group.name }"
              @click="switchEpisode(group.name, 0)"
            >
              {{ group.name }}
            </button>
          </div>
        </section>

        <section class="sidebar-block">
          <div class="section-head compact">
            <div>
              <p class="section-kicker">Episodes</p>
              <h3>选集</h3>
            </div>
          </div>

          <div class="episode-strip episode-strip--compact">
            <button
              v-for="(episode, episodeIndex) in currentGroup?.episodes || []"
              :key="`${currentGroup?.name}-${episode.id}-${episodeIndex}`"
              type="button"
              class="episode-chip"
              :class="{ active: currentPlayback.episodeId === episode.id }"
              :title="episode.name"
              @click="switchEpisode(currentPlayback.flagName, episodeIndex)"
            >
              {{ episode.name }}
            </button>
          </div>
        </section>
      </div>
    </div>
  </section>

  <section v-else-if="playerPending" class="page">
    <div class="detail-loading-panel">
      <div class="player-loading-state">
        <span class="loading-spinner" aria-hidden="true"></span>
        <div>
          <strong>正在解析播放地址</strong>
          <p>播放器、线路和当前集数会在准备好后自动出现。</p>
        </div>
      </div>
    </div>
  </section>

  <section v-else class="page">
    <el-empty description="播放地址解析失败或当前剧集不可用">
      <el-button type="primary" @click="loadPlayback">重新解析</el-button>
    </el-empty>
  </section>
</template>
