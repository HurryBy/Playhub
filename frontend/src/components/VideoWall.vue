<script setup>
import { onBeforeUnmount, onMounted, ref, watch, computed } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import LazyPoster from '@/components/LazyPoster.vue'
import { buildPosterTransitionName } from '@/utils/tvbox'

const props = defineProps({
  videos: {
    type: Array,
    default: () => [],
  },
  loading: {
    type: Boolean,
    default: false,
  },
  showSource: {
    type: Boolean,
    default: false,
  },
  streamProgress: {
    type: Object,
    default: () => ({}),
  },
  streamPercent: {
    type: Number,
    default: 0,
  },
  canLoadMore: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['select', 'load-more'])

const streamActive = computed(() => Boolean(props.streamProgress?.active))
const sentinelRef = ref(null)

let observer = null

function posterMediaStyle(video) {
  return {
    viewTransitionName: buildPosterTransitionName(video.sourceUid, video.id),
  }
}

function disconnectObserver() {
  if (observer) {
    observer.disconnect()
    observer = null
  }
}

function handleIntersect(entries) {
  if (!entries.some((entry) => entry.isIntersecting)) {
    return
  }
  if (!props.canLoadMore || props.loading) {
    return
  }
  emit('load-more')
}

function observeSentinel() {
  disconnectObserver()
  if (!sentinelRef.value) {
    return
  }
  observer = new IntersectionObserver(handleIntersect, {
    rootMargin: '360px 0px',
    threshold: 0.1,
  })
  observer.observe(sentinelRef.value)
}

watch(
  () => [props.canLoadMore, props.loading, props.videos.length],
  () => {
    observeSentinel()
  },
)

onMounted(() => {
  observeSentinel()
})

onBeforeUnmount(() => {
  disconnectObserver()
})
</script>

<template>
  <section class="wall-panel wall-panel--masonry">
    <div v-if="streamActive" class="stream-progress-panel">
      <div class="stream-progress-copy">
        <strong>{{ streamProgress.currentSourceName || '正在搜索源' }}</strong>
        <span>
          {{ streamProgress.completedSources || 0 }} / {{ streamProgress.totalSources || 0 }}
          <template v-if="streamProgress.matchedSources || streamProgress.failedSources">
            · 命中 {{ streamProgress.matchedSources || 0 }} · 失败 {{ streamProgress.failedSources || 0 }}
          </template>
        </span>
      </div>
      <el-progress
        :percentage="streamPercent"
        :stroke-width="7"
        :show-text="false"
        striped
        striped-flow
      />
    </div>

    <div v-if="loading && !videos.length" class="wall-grid wall-grid--masonry">
      <div v-for="item in 12" :key="item" class="poster-card skeleton-card">
        <el-skeleton animated>
          <template #template>
            <div class="skeleton-poster"></div>
            <el-skeleton-item variant="text" style="width: 78%" />
            <el-skeleton-item variant="text" style="width: 52%" />
          </template>
        </el-skeleton>
      </div>
    </div>

    <div v-else-if="videos.length" class="wall-grid wall-grid--masonry">
      <button
        v-for="video in videos"
        :key="`${video.sourceUid}-${video.id}`"
        class="poster-card"
        type="button"
        @click="$emit('select', video)"
      >
        <div class="poster-media" :style="posterMediaStyle(video)">
          <LazyPoster
            :src="video.pic"
            :alt="video.name"
            :fallback-text="video.name.slice(0, 1)"
          />
          <span v-if="showSource && video.sourceName" class="poster-source">{{ video.sourceName }}</span>
          <span v-if="video.remarks" class="poster-remarks">{{ video.remarks }}</span>
        </div>
        <div class="poster-copy">
          <p class="poster-title">{{ video.name }}</p>
          <p v-if="showSource && video.sourceName" class="poster-subtitle">{{ video.sourceName }}</p>
        </div>
      </button>
    </div>

    <div v-else class="wall-empty">
      <el-empty description="当前区域还没有内容" />
    </div>

    <div
      v-if="videos.length || loading || canLoadMore"
      ref="sentinelRef"
      class="wall-sentinel"
    >
      <div v-if="loading && videos.length" class="wall-loading-tail">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>正在继续载入内容…</span>
      </div>
      <div v-else-if="canLoadMore" class="wall-sentinel-copy">
        继续向下滚动加载更多
      </div>
    </div>
  </section>
</template>
