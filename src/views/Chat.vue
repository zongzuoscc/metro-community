<template>
  <div class="chat-layout">
    <div class="navbar-placeholder">
      <div class="nav-back" @click="$router.push('/home')">
        <el-icon><ArrowLeft /></el-icon> 返回首页
      </div>
    </div>

    <div class="chat-container">
      <div class="chat-sidebar">
        <div class="sidebar-header">
          <span>我的好友</span>
        </div>
        <div class="friend-list" v-loading="loadingFriends">
          <div
              v-for="friend in friendList"
              :key="friend.id"
              class="friend-item"
              :class="{ 'active': currentFriend && currentFriend.id === friend.id }"
              @click="selectFriend(friend)"
          >
            <el-avatar :size="40" :src="friend.avatar" icon="UserFilled"></el-avatar>
            <div class="friend-info">
              <div class="name">{{ friend.username }}</div>
              <div class="last-msg text-ellipsis">{{ friend.intro || '暂无简介' }}</div>
            </div>
          </div>
          <el-empty v-if="friendList.length === 0" description="暂无互关好友" :image-size="60"></el-empty>
        </div>
      </div>

      <div class="chat-main">
        <template v-if="currentFriend">
          <div class="chat-header">
            <span class="chat-title">{{ currentFriend.username }}</span>
            <el-button link icon="More"></el-button>
          </div>

          <div class="message-box" ref="msgBoxRef">
            <div v-if="loadingHistory" class="loading-history"><el-icon class="is-loading"><Loading /></el-icon></div>

            <div v-for="(msg, index) in messageList" :key="msg.id || index" class="message-row" :class="{ 'self': isSelf(msg) }">
              <el-avatar v-if="!isSelf(msg)" :size="36" :src="currentFriend.avatar" class="avatar" @click="$router.push(`/user/${msg.fromId}`)"></el-avatar>

              <div class="bubble">
                {{ msg.content }}
              </div>

              <el-avatar v-if="isSelf(msg)" :size="36" :src="currentUser.avatar" class="avatar"></el-avatar>
            </div>
          </div>

          <div class="input-area">
            <el-input
                v-model="inputContent"
                type="textarea"
                :rows="4"
                placeholder="按 Enter 发送..."
                resize="none"
                @keydown.enter.prevent="handleSend"
            />
            <div class="action-bar">
              <span class="tip">Enter 发送</span>
              <el-button type="primary" size="small" @click="handleSend">发送</el-button>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="empty-chat">
            <el-icon :size="60" color="#ddd"><ChatDotRound /></el-icon>
            <p>选择一个好友开始聊天</p>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../utils/request'
import { sendWebSocketMessage } from '../utils/websocket'

const route = useRoute()
const friendList = ref([])
const currentFriend = ref(null)
const messageList = ref([])
const inputContent = ref('')
const loadingFriends = ref(false)
const loadingHistory = ref(false)
const msgBoxRef = ref(null)

const currentUser = computed(() => {
  const str = localStorage.getItem('user')
  return str ? JSON.parse(str) : {}
})

// 1. 获取好友列表
const loadFriends = async () => {
  loadingFriends.value = true
  try {
    const res = await request.get('/api/chat/friends')
    friendList.value = res.data || []

    // 如果路由带了参数 ?to=xxx (从个人主页点"私信"进来)
    const targetId = route.query.to
    if (targetId) {
      const target = friendList.value.find(f => String(f.id) === String(targetId))
      if (target) selectFriend(target)
    }
  } catch(e) {
    console.error(e)
  } finally {
    loadingFriends.value = false
  }
}

// 2. 选择好友，加载历史记录
const selectFriend = async (friend) => {
  currentFriend.value = friend
  messageList.value = [] // 清空旧数据
  loadingHistory.value = true
  try {
    const res = await request.get('/api/chat/history', {
      params: { friendId: friend.id }
    })
    messageList.value = res.data || []
    scrollToBottom()
  } catch(e) {
    console.error(e)
  } finally {
    loadingHistory.value = false
  }
}

// 3. 发送消息
const handleSend = async () => {
  const content = inputContent.value.trim()
  if (!content) return
  if (!currentFriend.value) return

  // 1. 通过 WebSocket 发送
  const success = sendWebSocketMessage(currentFriend.value.id, content)

  if (success) {
    // 2. 乐观更新：直接推入本地列表
    messageList.value.push({
      fromId: currentUser.value.id,
      toId: currentFriend.value.id,
      content: content,
      createTime: new Date().toISOString()
    })
    inputContent.value = ''
    scrollToBottom()
  } else {
    ElMessage.error('网络连接断开，发送失败')
  }
}

// 4. 监听 WebSocket 接收消息
const onMessageReceived = (e) => {
  const msg = e.detail
  // 只有当前正在聊天的对象发来的消息，才显示
  if (currentFriend.value && String(msg.fromId) === String(currentFriend.value.id)) {
    messageList.value.push({
      fromId: msg.fromId,
      content: msg.content,
      createTime: new Date().toISOString() // 模拟时间
    })
    scrollToBottom()
  }
  // 如果不是当前聊天对象，可以在好友列表里加个小红点 (这里暂略)
}

const isSelf = (msg) => {
  return String(msg.fromId) === String(currentUser.value.id)
}

const scrollToBottom = () => {
  nextTick(() => {
    if (msgBoxRef.value) {
      msgBoxRef.value.scrollTop = msgBoxRef.value.scrollHeight
    }
  })
}

onMounted(() => {
  loadFriends()
  window.addEventListener('on-chat-msg', onMessageReceived)
})

onUnmounted(() => {
  window.removeEventListener('on-chat-msg', onMessageReceived)
})
</script>

<style scoped lang="scss">
.chat-layout {
  height: 100vh; background: #f0f2f5; display: flex; flex-direction: column;
}
.navbar-placeholder {
  height: 50px; background: #fff; border-bottom: 1px solid #ddd; display: flex; align-items: center; padding: 0 20px;
  .nav-back { cursor: pointer; display: flex; align-items: center; gap: 5px; color: #666; &:hover { color: #0066ff; } }
}

.chat-container {
  flex: 1; width: 1000px; margin: 20px auto; background: #fff; border-radius: 8px; box-shadow: 0 2px 12px rgba(0,0,0,0.1);
  display: flex; overflow: hidden; height: calc(100vh - 100px);
}

/* 左侧好友列表 */
.chat-sidebar {
  width: 250px; border-right: 1px solid #eee; display: flex; flex-direction: column;
  .sidebar-header { padding: 15px; font-weight: 600; border-bottom: 1px solid #f5f5f5; }
  .friend-list {
    flex: 1; overflow-y: auto;
    .friend-item {
      display: flex; align-items: center; gap: 10px; padding: 12px 15px; cursor: pointer; transition: background 0.2s;
      &:hover { background: #f5f5f5; }
      &.active { background: #e6f7ff; }
      .friend-info {
        flex: 1; overflow: hidden;
        .name { font-size: 14px; font-weight: 500; color: #333; margin-bottom: 4px; }
        .last-msg { font-size: 12px; color: #999; }
      }
    }
  }
}

/* 右侧聊天窗口 */
.chat-main {
  flex: 1; display: flex; flex-direction: column; background: #fcfcfc;
  .chat-header {
    height: 50px; border-bottom: 1px solid #eee; display: flex; align-items: center; justify-content: space-between; padding: 0 20px; background: #fff;
    .chat-title { font-weight: 600; font-size: 16px; }
  }

  .message-box {
    flex: 1; padding: 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 15px;
    .loading-history { text-align: center; color: #999; }

    .message-row {
      display: flex; gap: 10px; max-width: 80%;
      &.self { align-self: flex-end; flex-direction: row-reverse; }

      .avatar { flex-shrink: 0; cursor: pointer; }
      .bubble {
        padding: 10px 14px; border-radius: 8px; font-size: 14px; line-height: 1.5; word-break: break-word; position: relative;
      }
      /* 对方的消息 */
      &:not(.self) .bubble { background: #fff; border: 1px solid #eee; color: #333; border-top-left-radius: 2px; }
      /* 自己的消息 */
      &.self .bubble { background: #95ec69; color: #000; border-top-right-radius: 2px; }
    }
  }

  .input-area {
    height: 140px; border-top: 1px solid #eee; background: #fff; display: flex; flex-direction: column;
    :deep(.el-textarea__inner) { border: none; box-shadow: none; padding: 10px 20px; background: transparent; }
    .action-bar {
      display: flex; justify-content: flex-end; align-items: center; padding: 0 20px 10px; gap: 10px;
      .tip { font-size: 12px; color: #ccc; }
    }
  }

  .empty-chat { flex: 1; display: flex; flex-direction: column; justify-content: center; align-items: center; color: #ccc; gap: 10px; }
}

.text-ellipsis { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>