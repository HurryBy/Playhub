<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Loading, Search } from '@element-plus/icons-vue'
import VideoWall from '@/components/VideoWall.vue'
import { classIdOf, classNameOf } from '@/utils/tvbox'
import { useTvboxStore } from '@/stores/tvbox'

const router = useRouter()
const store = useTvboxStore()

const categoryItems = computed(() => store.classes.filter((item) => classIdOf(item)))
const sourceLabel = computed(() => store.selectedSource?.name || '视频墙')
const searchPending = computed(() => store.loading.wall && store.wallMode === 'search')
const wallHeading = computed(() => {
  if (store.wallMode === 'search' && store.searchKeyword) {
    return `“${store.searchKeyword}” 的搜索结果`
  }
  if (store.activeClassId) {
    const current = categoryItems.value.find((item) => classIdOf(item) === store.activeClassId)
    if (current) {
      return classNameOf(current)
    }
  }
  return sourceLabel.value
})

async function handleSearch() {
  await store.searchVideos()
}

async function handleLoadMore() {
  await store.loadMoreWall()
}

function openDetail(video) {
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
</script>

<template>
  <section class="page home-page home-page--cinema">
    <header class="home-command-bar">
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
      :videos="store.wallVideos"
      :loading="store.loading.wall"
      :show-source="store.showSourceBadges"
      :stream-progress="store.searchProgress"
      :stream-percent="store.streamProgressPercent"
      :can-load-more="store.wallHasMore"
      @load-more="handleLoadMore"
      @select="openDetail"
    />
  </section>
</template>
