import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  // 【核心配置】解决 404 的关键
  server: {
    port: 5173, // 前端端口
    proxy: {
      '/api': {
        target: 'http://localhost:8080', // 后端接口地址
        changeOrigin: true,
        // rewrite: (path) => path.replace(/^\/api/, '') // ⚠️注意：如果后端 Controller 写了 /api，这里就【不要】写 rewrite
      }
    }
  }
})