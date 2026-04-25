<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Delete } from '@element-plus/icons-vue'
import LazyPoster from '@/components/LazyPoster.vue'
import { formatHistoryTime } from '@/utils/tvbox'
import { useTvboxStore } from '@/stores/tvbox'

const router = useRouter()
const store = useTvboxStore()

const sortedHistory = computed(() => [...store.history])

function resume(item) {
  store.setPlayerOrigin({ name: 'history' })
  router.push({
    name: 'player',
    query: {
      source: item.sourceUid,
      vod: item.vodId,
      resume: '1',
    },
  })
}
</script>

<template>
  <section class="page history-page">
    <div class="section-head">
      <div>
        <p class="section-kicker">History</p>
        <h3>断点续播</h3>
      </div>
      <el-button
        v-if="sortedHistory.length"
        type="danger"
        plain
        @click="store.clearHistory()"
      >
        <el-icon><Delete /></el-icon>
        清空历史
      </el-button>
    </div>

    <div v-if="sortedHistory.length" class="history-grid">
      <button
        v-for="item in sortedHistory"
        :key="`${item.sourceUid}-${item.vodId}`"
        type="button"
        class="history-card"
        @click="resume(item)"
      >
        <div class="history-poster">
          <LazyPoster
            :src="item.vodPic"
            :alt="item.vodName"
            :fallback-text="item.vodName.slice(0, 1)"
          />
        </div>
        <div class="history-copy">
          <h4>{{ item.vodName }}</h4>
          <p>{{ item.episodeName }}</p>
          <span>{{ item.sourceName }} · {{ formatHistoryTime(item.updatedAt) }}</span>
        </div>
      </button>
    </div>

    <el-empty v-else description="还没有播放历史，去首页挑一部视频吧" />
  </section>
</template>
