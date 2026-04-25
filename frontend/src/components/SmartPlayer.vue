<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Artplayer from 'artplayer'
import Hls from 'hls.js'

const props = defineProps({
  url: {
    type: String,
    default: '',
  },
  poster: {
    type: String,
    default: '',
  },
  autoplay: {
    type: Boolean,
    default: true,
  },
})

const emit = defineEmits(['error', 'ready', 'playable'])

const containerRef = ref(null)
const loading = ref(false)
const loadingPercent = ref(0)
const loadingText = ref('正在准备播放器…')

let art = null
let hls = null
let readyTimer = null
let removeVideoEvents = null
let playableEmitted = false

function clearReadyTimer() {
  if (readyTimer) {
    window.clearTimeout(readyTimer)
    readyTimer = null
  }
}

function emitPlayable() {
  if (playableEmitted) {
    return
  }
  playableEmitted = true
  emit('playable')
}

function destroyHls() {
  if (hls) {
    hls.destroy()
    hls = null
  }
}

function detachVideoEvents() {
  if (removeVideoEvents) {
    removeVideoEvents()
    removeVideoEvents = null
  }
}

function setLoadingState(nextLoading, nextPercent = loadingPercent.value, nextText = loadingText.value) {
  loading.value = nextLoading
  loadingPercent.value = Math.max(0, Math.min(100, Math.round(nextPercent)))
  loadingText.value = nextText
}

function finishLoading() {
  setLoadingState(true, 100, '播放器已就绪')
  emitPlayable()
  clearReadyTimer()
  readyTimer = window.setTimeout(() => {
    loading.value = false
  }, 180)
}

function updateBufferedProgress(video) {
  if (!video?.buffered?.length) {
    return
  }

  let duration = Number(video.duration)
  if (!Number.isFinite(duration) || duration <= 0) {
    duration = 0
  }

  try {
    const end = video.buffered.end(video.buffered.length - 1)
    if (duration > 0) {
      const percent = Math.round((end / duration) * 100)
      loadingPercent.value = Math.max(loadingPercent.value, Math.min(96, percent))
    } else {
      loadingPercent.value = Math.min(92, Math.max(loadingPercent.value, 72))
    }
  } catch {
    loadingPercent.value = Math.max(loadingPercent.value, 48)
  }
}

function handleFatalError() {
  clearReadyTimer()
  setLoadingState(false, 0, '播放加载失败')
  emit('error')
}

function bindVideoEvents(video) {
  const listeners = [
    ['loadstart', () => setLoadingState(true, 8, '正在连接视频流…')],
    ['loadedmetadata', () => setLoadingState(true, 26, '已获取媒体信息…')],
    ['progress', () => updateBufferedProgress(video)],
    ['waiting', () => setLoadingState(true, Math.max(loadingPercent.value, 34), '正在缓冲…')],
    [
      'canplay',
      () => {
        setLoadingState(true, Math.max(loadingPercent.value, 84), '即将开始播放…')
        emitPlayable()
      },
    ],
    ['playing', () => finishLoading()],
    ['error', () => handleFatalError()],
  ]

  listeners.forEach(([name, handler]) => video.addEventListener(name, handler))
  removeVideoEvents = () => {
    listeners.forEach(([name, handler]) => video.removeEventListener(name, handler))
  }
}

function destroyPlayer() {
  clearReadyTimer()
  detachVideoEvents()
  destroyHls()
  if (art) {
    art.destroy(false)
    art = null
  }
}

function buildHlsPlayer(video, url) {
  if (Hls.isSupported()) {
    hls = new Hls({
      enableWorker: true,
      lowLatencyMode: true,
      backBufferLength: 90,
    })
    hls.loadSource(url)
    hls.attachMedia(video)
    hls.on(Hls.Events.MANIFEST_LOADING, () => {
      setLoadingState(true, Math.max(loadingPercent.value, 12), '正在获取播放列表…')
    })
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      setLoadingState(true, Math.max(loadingPercent.value, 28), '播放列表已解析…')
      if (props.autoplay) {
        video.play().catch(() => {})
      }
    })
    hls.on(Hls.Events.LEVEL_LOADED, () => {
      loadingPercent.value = Math.max(loadingPercent.value, 46)
    })
    hls.on(Hls.Events.FRAG_LOADED, () => {
      loadingPercent.value = Math.min(92, Math.max(loadingPercent.value + 6, 52))
    })
    hls.on(Hls.Events.BUFFER_APPENDED, () => {
      updateBufferedProgress(video)
    })
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (data?.fatal) {
        handleFatalError()
      }
    })
    return
  }

  if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = url
    if (props.autoplay) {
      video.play().catch(() => {})
    }
    return
  }

  handleFatalError()
}

function buildPlayer() {
  destroyPlayer()
  if (!containerRef.value || !props.url) {
    return
  }

  playableEmitted = false
  setLoadingState(true, 4, '正在初始化播放器…')

  const isHls = /\.m3u8(\?|$)/i.test(props.url)
  const options = {
    container: containerRef.value,
    url: props.url,
    poster: props.poster,
    autoplay: props.autoplay,
    autoSize: false,
    fullscreen: true,
    fullscreenWeb: true,
    pip: true,
    mutex: true,
    hotkey: true,
    setting: true,
    playbackRate: true,
    theme: '#d6aa57',
    customType: {
      m3u8(video, url) {
        buildHlsPlayer(video, url)
      },
    },
  }

  if (isHls) {
    options.type = 'm3u8'
  }

  try {
    art = new Artplayer(options)
  } catch {
    handleFatalError()
    return
  }

  bindVideoEvents(art.video)

  art.on('ready', () => {
    loadingPercent.value = Math.max(loadingPercent.value, 18)
    emit('ready')
  })
  art.on('video:error', () => {
    handleFatalError()
  })
}

watch(
  () => [props.url, props.poster],
  () => {
    buildPlayer()
  },
)

onMounted(() => {
  buildPlayer()
})

onBeforeUnmount(() => {
  destroyPlayer()
})
</script>

<template>
  <div class="smart-player-wrap">
    <div ref="containerRef" class="smart-player"></div>
    <transition name="player-overlay">
      <div v-if="loading" class="player-loading-overlay">
        <div class="player-loading-card">
          <strong>{{ loadingText }}</strong>
          <el-progress
            :percentage="loadingPercent"
            :stroke-width="8"
            striped
            striped-flow
          />
        </div>
      </div>
    </transition>
  </div>
</template>
