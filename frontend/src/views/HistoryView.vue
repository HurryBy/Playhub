<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Delete } from '@element-plus/icons-vue'
import VideoWall from '@/components/VideoWall.vue'
import { useTvboxStore } from '@/stores/tvbox'

const router = useRouter()
const store = useTvboxStore()

const isNaifeiTheme = computed(() => store.appTheme === 'naifei')

const wallVideos = computed(() =>
  store.history.map((item) => ({
    id: item.vodId,
    name: item.vodName,
    pic: item.vodPic,
    sourceUid: item.sourceUid,
    sourceName: item.sourceName,
    remarks: item.episodeName,
  })),
)

function openHistoryVideo(video) {
  store.setPlayerOrigin({ name: 'history' })
  router.push({
    name: 'player',
    query: {
      source: video.sourceUid,
      vod: video.id,
      resume: '1',
    },
  })
}

function goBack() {
  router.push({ name: 'home' })
}
</script>

<template>
  <section class="page history-page">
    <template v-if="isNaifeiTheme">
      <header class="naifei-home-top">
        <div class="naifei-title">
          <button type="button" class="player-back-btn" @click="goBack">
            <el-icon><ArrowLeft /></el-icon> 返回
          </button>
          <span></span>
          <h1>历史</h1>
        </div>
        <div class="naifei-status-icons">
          <el-button
            v-if="wallVideos.length"
            type="danger"
            plain
            @click="store.clearHistory()"
          >
            <el-icon><Delete /></el-icon>
            清空历史
          </el-button>
        </div>
      </header>
    </template>

    <template v-else>
      <div class="section-head">
        <div style="display: flex; align-items: center; gap: 16px;">
          <button type="button" class="player-back-btn" @click="goBack">
            <el-icon><ArrowLeft /></el-icon> 返回
          </button>
          <div>
            <p class="section-kicker">History</p>
            <h3>播放历史</h3>
          </div>
        </div>
        <el-button
          v-if="wallVideos.length"
          type="danger"
          plain
          @click="store.clearHistory()"
        >
          <el-icon><Delete /></el-icon>
          清空历史
        </el-button>
      </div>
    </template>

    <VideoWall
      v-if="wallVideos.length"
      :videos="wallVideos"
      @select="openHistoryVideo"
    />

    <el-empty v-else description="还没有播放历史，去首页挑一部视频吧" />
  </section>
</template>
