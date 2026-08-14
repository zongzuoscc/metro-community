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
    port: Number(process.env.VITE_PORT || 15173),
    proxy: {
      // 更具体的 Agent 路径必须先声明，否则会被下面的通用 /api 规则转发到主业务端口。
      '/api/agent': {
        target: process.env.VITE_AGENT_PROXY_TARGET || 'http://localhost:18081',
        changeOrigin: true,
      },
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:18080', // 后端接口地址
        changeOrigin: true,
        // rewrite: (path) => path.replace(/^\/api/, '') // ⚠️注意：如果后端 Controller 写了 /api，这里就【不要】写 rewrite
      }
    }
  }
})
