<template>
  <router-view></router-view>
  <AgentDock />
</template>
<script setup>
import { onMounted } from 'vue'
import { initWebSocket } from './utils/websocket'
import AgentDock from './components/AgentDock.vue'

onMounted(() => {
  // 页面刷新时，如果已登录，尝试连接 WebSocket
  const token = localStorage.getItem('token')
  const user = localStorage.getItem('user')
  if (token && user) {
    initWebSocket(token)
  }
})
</script>
<style>
/* 这里的 #app 样式必须极其干净 */
#app {
  width: 100%;
  height: 100%;
  /* 绝对不要写 text-align: center; */
}
</style>
