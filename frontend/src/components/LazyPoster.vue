<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  src: {
    type: String,
    default: '',
  },
  alt: {
    type: String,
    default: '',
  },
  fallbackText: {
    type: String,
    default: '',
  },
})

const hostRef = ref(null)
const shouldLoad = ref(false)
const loaded = ref(false)
const failed = ref(false)

let observer = null

function disconnectObserver() {
  if (observer) {
    observer.disconnect()
    observer = null
  }
}

function resetState() {
  shouldLoad.value = false
  loaded.value = false
  failed.value = false
}

function markReadyToLoad() {
  shouldLoad.value = Boolean(props.src)
  disconnectObserver()
}

function observeHost() {
  disconnectObserver()
  if (!hostRef.value || !props.src || shouldLoad.value) {
    return
  }
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        markReadyToLoad()
      }
    },
    {
      rootMargin: '180px 0px',
      threshold: 0.1,
    },
  )
  observer.observe(hostRef.value)
}

function handleLoad() {
  loaded.value = true
  failed.value = false
}

function handleError() {
  failed.value = true
  loaded.value = false
}

watch(
  () => props.src,
  () => {
    resetState()
    observeHost()
  },
)

onMounted(() => {
  observeHost()
})

onBeforeUnmount(() => {
  disconnectObserver()
})
</script>

<template>
  <div
    ref="hostRef"
    class="lazy-poster-shell"
    :class="{
      'is-loaded': loaded,
      'is-error': failed,
    }"
  >
    <el-image
      v-if="shouldLoad && src && !failed"
      :src="src"
      :alt="alt"
      fit="cover"
      loading="lazy"
      class="poster-image"
      @load="handleLoad"
      @error="handleError"
    >
      <template #placeholder>
        <div class="poster-loading-state">
          <span class="loading-spinner loading-spinner--poster" aria-hidden="true"></span>
        </div>
      </template>
      <template #error>
        <div class="poster-fallback">{{ fallbackText }}</div>
      </template>
    </el-image>
    <div v-else-if="src && !failed" class="lazy-poster-placeholder">
      <span class="loading-spinner loading-spinner--poster" aria-hidden="true"></span>
    </div>
    <div v-else class="poster-fallback">{{ fallbackText }}</div>
  </div>
</template>
