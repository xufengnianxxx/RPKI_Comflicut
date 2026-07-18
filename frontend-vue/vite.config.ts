import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 开发时代理 /api → Spring Boot（默认 8082，context-path=/api）
 * 生产环境可将前端与后端同域部署，或由 Nginx 反代 /api。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8082',
        changeOrigin: true,
        /** 后端冷启动 / Fabric 调用较慢时避免过早断开（减轻 socket hang up） */
        timeout: 180_000,
        proxyTimeout: 180_000,
      },
    },
  },
})
