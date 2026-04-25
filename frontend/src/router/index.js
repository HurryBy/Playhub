import { createRouter, createWebHashHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import DetailView from '@/views/DetailView.vue'
import PlayerView from '@/views/PlayerView.vue'
import HistoryView from '@/views/HistoryView.vue'
import LiveView from '@/views/LiveView.vue'

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
      meta: { title: '首页' },
    },
    {
      path: '/detail',
      name: 'detail',
      component: DetailView,
      meta: { title: '视频详情' },
    },
    {
      path: '/player',
      name: 'player',
      component: PlayerView,
      meta: { title: '播放视频' },
    },
    {
      path: '/history',
      name: 'history',
      component: HistoryView,
      meta: { title: '历史记录' },
    },
    {
      path: '/live',
      name: 'live',
      component: LiveView,
      meta: { title: '直播' },
    },
  ],
})

export default router
