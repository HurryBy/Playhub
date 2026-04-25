<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { VideoPlay } from '@element-plus/icons-vue'
import LazyPoster from '@/components/LazyPoster.vue'
import { buildPosterTransitionName, formatHistoryTime } from '@/utils/tvbox'
import { useTvboxStore } from '@/stores/tvbox'

const route = useRoute()
const router = useRouter()
const store = useTvboxStore()

const detail = computed(() => store.currentDetail)
const selectedGroupName = ref('')

const resumeEntry = computed(() => {
  if (!detail.value) {
    return null
  }
  return store.getHistoryEntry(detail.value.sourceUid, detail.value.id)
})

const playGroups = computed(() => detail.value?.playGroups || [])
const activeGroup = computed(
  () => playGroups.value.find((item) => item.name === selectedGroupName.value) || playGroups.value[0] || null,
)
const previewCard = computed(() => {
  if (detail.value) {
    return detail.value
  }

  const sourceUid = String(route.query.source || '')
  const vodId = String(route.query.vod || '')
  const preview = store.navigationPreview
  if (!preview) {
    return null
  }
  if (preview.sourceUid !== sourceUid || String(preview.id) !== vodId) {
    return null
  }
  return preview
})
const posterTransitionName = computed(() =>
  buildPosterTransitionName(
    previewCard.value?.sourceUid || String(route.query.source || ''),
    previewCard.value?.id || String(route.query.vod || ''),
  ),
)

let autoRouted = false

async function loadViewData() {
  const sourceUid = String(route.query.source || '')
  const vodId = String(route.query.vod || '')
  autoRouted = false

  if (!sourceUid || !vodId) {
    store.clearCurrentDetail()
    store.clearCurrentPlayback()
    return
  }

  store.clearCurrentDetail()
  try {
    await store.ensureDetail(sourceUid, vodId, { force: true })
  } catch {
    // The store already exposes the failure state.
  }
}

function selectGroup(groupName) {
  selectedGroupName.value = groupName
}

function pushPlayer(flagName, episodeIndex) {
  if (!detail.value) {
    return
  }
  store.setPlayerOrigin({
    name: 'detail',
    query: {
      source: detail.value.sourceUid,
      vod: detail.value.id,
    },
  })
  router.push({
    name: 'player',
    query: {
      source: detail.value.sourceUid,
      vod: detail.value.id,
      flag: flagName,
      episode: String(episodeIndex),
    },
  })
}

function resumePlayback() {
  if (!detail.value) {
    return
  }
  store.setPlayerOrigin({
    name: 'detail',
    query: {
      source: detail.value.sourceUid,
      vod: detail.value.id,
    },
  })
  router.push({
    name: 'player',
    query: {
      source: detail.value.sourceUid,
      vod: detail.value.id,
      resume: '1',
    },
  })
}

watch(
  () => [route.query.source, route.query.vod],
  async () => {
    await loadViewData()
  },
)

watch(
  () => detail.value?.id,
  (id) => {
    if (id) {
      store.clearNavigationPreview()
    }
  },
)

watch(
  () => [playGroups.value.map((item) => item.name).join('|'), resumeEntry.value?.playFlag],
  () => {
    if (!playGroups.value.length) {
      selectedGroupName.value = ''
      return
    }

    const currentStillExists = playGroups.value.some((item) => item.name === selectedGroupName.value)
    if (currentStillExists) {
      return
    }

    const resumeGroup = resumeEntry.value?.playFlag
      ? playGroups.value.find((item) => item.name === resumeEntry.value.playFlag)
      : null

    selectedGroupName.value = resumeGroup?.name || playGroups.value[0].name
  },
  { immediate: true },
)

watch(
  () => [detail.value?.id, route.query.resume, route.query.autoplay],
  () => {
    if (autoRouted || !detail.value) {
      return
    }
    if (route.query.resume !== '1' && route.query.autoplay !== '1') {
      return
    }
    const resolved = store.resolveEpisode(detail.value, {
      resume: true,
    })
    if (!resolved) {
      return
    }
    autoRouted = true
    pushPlayer(resolved.group.name, resolved.episodeIndex)
  },
)

onMounted(async () => {
  await loadViewData()
})
</script>

<template>
  <section v-if="detail" class="page detail-page">
    <div class="detail-hero">
      <div class="detail-cover-shell" :style="{ viewTransitionName: posterTransitionName }">
        <div class="detail-cover">
          <LazyPoster
            :src="detail.pic"
            :alt="detail.title"
            :fallback-text="detail.title.slice(0, 1)"
          />
        </div>
      </div>

      <div class="detail-copy">
        <div class="detail-topline">
          <div>
            <p class="hero-kicker">Detail</p>
            <h3>{{ detail.title }}</h3>
          </div>

          <el-button
            v-if="resumeEntry"
            type="primary"
            class="detail-resume"
            @click="resumePlayback"
          >
            <el-icon><VideoPlay /></el-icon>
            继续播放 {{ resumeEntry.episodeName }}
          </el-button>
        </div>

        <p class="detail-meta">{{ detail.meta || '暂无额外信息' }}</p>
        <p class="detail-description">
          {{ detail.desc || '这个条目暂时没有更详细的简介，直接选择线路与集数即可进入播放。' }}
        </p>

        <div class="detail-stats">
          <div class="metric-card">
            <span>来源</span>
            <strong>{{ detail.sourceName }}</strong>
          </div>
          <div class="metric-card">
            <span>线路</span>
            <strong>{{ playGroups.length }}</strong>
          </div>
          <div class="metric-card">
            <span>断点记录</span>
            <strong>
              {{
                resumeEntry
                  ? `${resumeEntry.episodeName} · ${formatHistoryTime(resumeEntry.updatedAt)}`
                  : '暂无'
              }}
            </strong>
          </div>
        </div>
      </div>
    </div>

    <section v-if="playGroups.length && activeGroup" class="playlist-panel">
      <div class="section-head">
        <div>
          <p class="section-kicker">Play Groups</p>
          <h3>线路与选集</h3>
        </div>
        <p class="section-subtitle">
          左侧切换线路，右侧只展示当前线路的剧集，切换时保留更清晰的聚焦感。
        </p>
      </div>

      <div class="detail-play-browser">
        <aside class="group-sidebar">
          <button
            v-for="(group, groupIndex) in playGroups"
            :key="group.name"
            type="button"
            class="group-select-card"
            :class="{ active: activeGroup?.name === group.name }"
            @click="selectGroup(group.name)"
          >
            <span class="group-select-index">{{ String(groupIndex + 1).padStart(2, '0') }}</span>
            <strong class="group-select-name">{{ group.name }}</strong>
            <span class="group-select-meta">{{ group.episodes.length }} 集</span>
          </button>
        </aside>

        <transition name="group-swap" mode="out-in">
          <div :key="activeGroup.name" class="group-stage">
            <div class="group-stage-head">
              <div>
                <p class="section-kicker">Current Line</p>
                <h4>{{ activeGroup.name }}</h4>
              </div>
              <span class="group-stage-count">{{ activeGroup.episodes.length }} 集可播放</span>
            </div>

            <div class="episode-strip">
              <button
                v-for="(episode, episodeIndex) in activeGroup.episodes"
                :key="`${activeGroup.name}-${episode.id}-${episodeIndex}`"
                type="button"
                class="episode-chip"
                :class="{
                  remembered:
                    resumeEntry &&
                    resumeEntry.playFlag === activeGroup.name &&
                    resumeEntry.episodeId === episode.id,
                }"
                :title="episode.name"
                @click="pushPlayer(activeGroup.name, episodeIndex)"
              >
                {{ episode.name }}
              </button>
            </div>
          </div>
        </transition>
      </div>
    </section>

    <el-empty v-else description="当前详情没有可解析的播放列表" />
  </section>

  <section v-else-if="store.loading.detail" class="page detail-loading-page">
    <div class="detail-loading-scene">
      <div class="detail-loading-cover-shell" :style="{ viewTransitionName: posterTransitionName }">
        <div class="detail-loading-cover">
          <LazyPoster
            v-if="previewCard?.pic"
            :src="previewCard.pic"
            :alt="previewCard.title"
            :fallback-text="previewCard.title?.slice(0, 1) || '影'"
          />
          <div v-else class="detail-loading-fallback">
            <span class="loading-spinner" aria-hidden="true"></span>
          </div>
        </div>
      </div>

      <div class="detail-loading-copy">
        <p class="hero-kicker">Loading Detail</p>
        <h3>{{ previewCard?.title || '正在加载影片详情' }}</h3>
        <p>正在获取简介、线路和选集信息，请稍等片刻。</p>
        <div class="detail-loading-bars">
          <span></span>
          <span></span>
          <span></span>
        </div>
      </div>
    </div>
  </section>

  <section v-else class="page">
    <el-empty description="详情加载失败或当前条目已失效">
      <el-button
        type="primary"
        @click="
          route.query.source && route.query.vod
            ? loadViewData()
            : router.push({ name: 'home' })
        "
      >
        重新加载
      </el-button>
    </el-empty>
  </section>
</template>
