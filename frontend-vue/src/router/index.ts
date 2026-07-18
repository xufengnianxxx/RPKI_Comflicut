import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/certs' },
    {
      path: '/certs',
      name: 'cert-list',
      component: () => import('@/views/CertListView.vue'),
      meta: { title: '证书列表' },
    },
    {
      path: '/certs/:id',
      name: 'cert-detail',
      component: () => import('@/views/CertDetailView.vue'),
      meta: { title: '证书详情' },
    },
    {
      path: '/conflict-detect',
      name: 'conflict-detect',
      component: () => import('@/views/ConflictDetectView.vue'),
      meta: { title: '冲突检测' },
    },
    {
      path: '/detection-records',
      name: 'detection-records',
      component: () => import('@/views/DetectionRecordsView.vue'),
      meta: { title: '检测记录' },
    },
  ],
})

router.afterEach((to) => {
  const t = to.meta.title as string | undefined
  document.title = t ? `${t} · RPKI` : 'RPKI 证书冲突校验'
})

export default router
