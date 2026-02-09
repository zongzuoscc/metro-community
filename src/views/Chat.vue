<template>
  <div class="chat-layout">
    <div class="navbar-placeholder">
      <div class="nav-back" @click="$router.push('/home')">
        <el-icon><ArrowLeft /></el-icon> 返回首页
      </div>
    </div>

    <div class="chat-container">
      <div class="chat-sidebar">
        <div class="sidebar-tabs">
          <span :class="{ active: activeTab === 'friend' }" @click="activeTab = 'friend'">好友</span>
          <span :class="{ active: activeTab === 'stranger' }" @click="activeTab = 'stranger'">陌生人</span>
        </div>

        <div class="friend-list" v-loading="loadingFriends">
          <template v-if="activeTab === 'friend'">
            <div
                v-for="user in friendListFiltered"
                :key="user.id"
                class="friend-item"
                :class="{ 'active': currentTarget && currentTarget.id === user.id }"
                @click="selectTarget(user)"
            >
              <el-avatar :size="40" :src="user.avatar" icon="UserFilled"></el-avatar>
              <div class="friend-info">
                <div class="name">
                  {{ user.remark || user.username }}
                  <span v-if="user.remark" class="real-name">({{ user.username }})</span>
                </div>
                <div class="desc text-ellipsis">{{ user.description || user.intro || '暂无描述' }}</div>
              </div>
            </div>
            <el-empty v-if="friendListFiltered.length === 0" description="暂无好友" :image-size="60"></el-empty>
          </template>

          <template v-else>
            <div
                v-for="user in strangerListFiltered"
                :key="user.id"
                class="friend-item"
                :class="{ 'active': currentTarget && currentTarget.id === user.id }"
                @click="selectTarget(user)"
            >
              <el-avatar :size="40" :src="user.avatar" icon="UserFilled"></el-avatar>
              <div class="friend-info">
                <div class="name">{{ user.username }}</div>
                <div class="desc text-ellipsis">未关注或单向关注</div>
              </div>
            </div>
            <el-empty v-if="strangerListFiltered.length === 0" description="暂无陌生人消息" :image-size="60"></el-empty>
          </template>
        </div>
      </div>

      <div class="chat-main">
        <template v-if="currentTarget">
          <div class="chat-header">
            <div class="header-info">
              <span class="chat-title">{{ currentTarget.remark || currentTarget.username }}</span>
              <el-tag v-if="currentTarget.isFriend" size="small" type="success" effect="plain" class="friend-tag">好友</el-tag>
            </div>
            <div class="header-actions">
              <el-button v-if="currentTarget.isFriend" link icon="Edit" @click="openRemarkDialog">备注</el-button>
              <el-button link icon="User" @click="$router.push(`/user/${currentTarget.id}`)">主页</el-button>
            </div>
          </div>

          <div class="message-box" ref="msgBoxRef">
            <div v-if="loadingHistory" class="loading-history"><el-icon class="is-loading"><Loading /></el-icon></div>
            <div v-for="(msg, index) in messageList" :key="msg.id || index" class="message-row" :class="{ 'self': isSelf(msg) }">
              <el-avatar v-if="!isSelf(msg)" :size="36" :src="currentTarget.avatar" class="avatar"></el-avatar>
              <div class="bubble">
                <el-image
                    v-if="msg.content.startsWith('http') && (msg.content.includes('.png') || msg.content.includes('.jpg'))"
                    :src="msg.content"
                    :preview-src-list="[msg.content]"
                    class="chat-img"
                />
                <span v-else>{{ msg.content }}</span>
              </div>
              <el-avatar v-if="isSelf(msg)" :size="36" :src="currentUser.avatar" class="avatar"></el-avatar>
            </div>
          </div>

          <div class="input-area">
            <div class="tool-bar">
              <el-upload
                  action="/api/file/upload"
                  :show-file-list="false"
                  :headers="uploadHeaders"
                  :on-success="handleImgSuccess"
                  :before-upload="beforeImgUpload"
              >
                <el-icon class="tool-btn"><Picture /></el-icon>
              </el-upload>
            </div>
            <textarea
                v-model="inputContent"
                class="chat-textarea"
                placeholder="按 Enter 发送..."
                @keydown.enter.prevent="handleSend"
            ></textarea>
            <div class="send-btn-box">
              <el-button type="primary" size="small" @click="handleSend">发送</el-button>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="empty-chat">
            <el-icon :size="60" color="#e0e0e0"><ChatDotRound /></el-icon>
            <p>选择一个联系人开始聊天</p>
          </div>
        </template>
      </div>
    </div>

    <el-dialog v-model="remarkDialogVisible" title="设置备注" width="400px">
      <el-form :model="remarkForm">
        <el-form-item label="备注名">
          <el-input v-model="remarkForm.remark" placeholder="请输入备注" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="remarkForm.description" type="textarea" placeholder="更多描述..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="remarkDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRemark">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../utils/request'
import { sendWebSocketMessage } from '../utils/websocket'
import { updateRemark } from '../api/user' // 记得创建这个API

const route = useRoute()
const activeTab = ref('friend') // 'friend' | 'stranger'
const allContacts = ref([])
const currentTarget = ref(null)
const messageList = ref([])
const inputContent = ref('')
const loadingFriends = ref(false)
const loadingHistory = ref(false)
const msgBoxRef = ref(null)

// 备注相关
const remarkDialogVisible = ref(false)
const remarkForm = ref({ remark: '', description: '' })

const currentUser = computed(() => {
  const str = localStorage.getItem('user')
  return str ? JSON.parse(str) : {}
})

const uploadHeaders = computed(() => ({ token: localStorage.getItem('token') }))

// 计算属性：过滤好友和陌生人
const friendListFiltered = computed(() => allContacts.value.filter(u => u.isFriend))
const strangerListFiltered = computed(() => allContacts.value.filter(u => !u.isFriend))

// 1. 获取联系人
const loadContacts = async () => {
  loadingFriends.value = true
  try {
    const res = await request.get('/api/chat/friends')
    allContacts.value = res.data || []

    // 处理路由跳转 ?to=xxx
    const targetId = route.query.to
    if (targetId) {
      let target = allContacts.value.find(u => String(u.id) === String(targetId))
      if (!target) {
        // 如果列表里没有，查一下临时加进去
        try {
          const uRes = await request.get(`/api/user/profile/${targetId}`)
          target = uRes.data
          // 默认当作陌生人加进去
          target.isFriend = false
          allContacts.value.unshift(target)
        } catch(e){}
      }
      if (target) {
        // 自动切换到对应的 Tab
        activeTab.value = target.isFriend ? 'friend' : 'stranger'
        selectTarget(target)
      }
    }
  } catch(e) {
    console.error(e)
  } finally {
    loadingFriends.value = false
  }
}

// 2. 选择联系人
const selectTarget = async (user) => {
  currentTarget.value = user
  messageList.value = []
  loadingHistory.value = true
  try {
    const res = await request.get('/api/chat/history', { params: { friendId: user.id } })
    messageList.value = res.data || []
    scrollToBottom()
  } catch(e) {} finally {
    loadingHistory.value = false
  }
}

// 3. 发送消息
const handleSend = () => {
  const content = inputContent.value.trim()
  if (!content) return
  sendMessage(content)
}

const sendMessage = (content) => {
  if (!currentTarget.value) return
  const success = sendWebSocketMessage(currentTarget.value.id, content)
  if (success) {
    messageList.value.push({
      fromId: currentUser.value.id,
      content: content,
      createTime: new Date().toISOString()
    })
    inputContent.value = ''
    scrollToBottom()

    // 如果是陌生人列表里的，发送后不需要移动到好友列表，保持原样即可
  } else {
    ElMessage.error('网络未连接')
  }
}

// 图片上传
const handleImgSuccess = (res) => {
  if (res.code === 200) {
    sendMessage(res.data) // 直接把图片URL当消息发
  }
}
const beforeImgUpload = (file) => {
  if (file.size / 1024 / 1024 > 5) {
    ElMessage.error('图片最大5MB')
    return false
  }
  return true
}

// 备注逻辑
const openRemarkDialog = () => {
  remarkForm.value = {
    remark: currentTarget.value.remark || '',
    description: currentTarget.value.description || ''
  }
  remarkDialogVisible.value = true
}

const submitRemark = async () => {
  try {
    await updateRemark({
      targetId: currentTarget.value.id,
      remark: remarkForm.value.remark,
      description: remarkForm.value.description
    })
    ElMessage.success('设置成功')
    // 更新本地数据
    currentTarget.value.remark = remarkForm.value.remark
    currentTarget.value.description = remarkForm.value.description
    remarkDialogVisible.value = false
  } catch(e) {
    ElMessage.error(e.msg || '设置失败')
  }
}

// WS 消息监听
const onMessageReceived = (e) => {
  const msg = e.detail
  // 如果是当前对话
  if (currentTarget.value && String(msg.fromId) === String(currentTarget.value.id)) {
    messageList.value.push({
      fromId: msg.fromId,
      content: msg.content,
      createTime: new Date().toISOString()
    })
    scrollToBottom()
  }
  // 如果不在列表里，或者在另一个Tab，你可以在这里做红点提示逻辑
}

const isSelf = (msg) => String(msg.fromId) === String(currentUser.value.id)

const scrollToBottom = () => {
  nextTick(() => {
    if (msgBoxRef.value) msgBoxRef.value.scrollTop = msgBoxRef.value.scrollHeight
  })
}

onMounted(() => {
  loadContacts()
  window.addEventListener('on-chat-msg', onMessageReceived)
})
onUnmounted(() => {
  window.removeEventListener('on-chat-msg', onMessageReceived)
})
</script>

<style scoped lang="scss">
.chat-layout { height: 100vh; background: #f0f2f5; display: flex; flex-direction: column; }
.navbar-placeholder { height: 50px; background: #fff; border-bottom: 1px solid #ddd; display: flex; align-items: center; padding: 0 20px; .nav-back { cursor: pointer; color: #666; &:hover { color: #0066ff; } } }

.chat-container { flex: 1; width: 1000px; margin: 20px auto; background: #fff; border-radius: 8px; box-shadow: 0 2px 12px rgba(0,0,0,0.1); display: flex; overflow: hidden; height: calc(100vh - 100px); }

/* Sidebar */
.chat-sidebar {
  width: 260px; border-right: 1px solid #eee; display: flex; flex-direction: column;
  .sidebar-tabs {
    display: flex; border-bottom: 1px solid #f0f0f0;
    span {
      flex: 1; text-align: center; padding: 15px 0; cursor: pointer; font-size: 15px; color: #666; background: #f9f9f9;
      &.active { background: #fff; color: #0066ff; font-weight: 600; border-bottom: 2px solid #0066ff; }
    }
  }
  .friend-list {
    flex: 1; overflow-y: auto;
    .friend-item {
      display: flex; align-items: center; gap: 10px; padding: 15px; cursor: pointer; transition: background 0.2s;
      &:hover { background: #f5f5f5; }
      &.active { background: #e6f7ff; }
      .friend-info {
        flex: 1; overflow: hidden;
        .name { font-size: 14px; font-weight: 500; color: #333; margin-bottom: 4px; .real-name { font-size: 12px; color: #999; margin-left: 4px; } }
        .desc { font-size: 12px; color: #999; }
      }
    }
  }
}

/* Main Chat */
.chat-main {
  flex: 1; display: flex; flex-direction: column; background: #fcfcfc;
  .chat-header {
    height: 60px; border-bottom: 1px solid #eee; display: flex; align-items: center; justify-content: space-between; padding: 0 20px; background: #fff;
    .header-info { display: flex; align-items: center; gap: 10px; .chat-title { font-weight: 600; font-size: 16px; } }
  }
  .message-box {
    flex: 1; padding: 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 15px;
    .message-row {
      display: flex; gap: 10px; max-width: 80%;
      &.self { align-self: flex-end; flex-direction: row-reverse; }
      .avatar { flex-shrink: 0; cursor: pointer; }
      .bubble {
        padding: 10px 14px; border-radius: 8px; font-size: 14px; line-height: 1.5; word-break: break-word; position: relative; background: #fff; border: 1px solid #eee;
        .chat-img { max-width: 200px; border-radius: 4px; cursor: zoom-in; }
      }
      &.self .bubble { background: #95ec69; color: #000; border-color: #95ec69; }
    }
  }
  .input-area {
    height: 160px; border-top: 1px solid #eee; background: #fff; display: flex; flex-direction: column;
    .tool-bar { padding: 5px 15px; display: flex; gap: 10px; .tool-btn { font-size: 20px; color: #666; cursor: pointer; &:hover { color: #409EFF; } } }
    .chat-textarea { flex: 1; border: none; outline: none; padding: 10px 15px; font-family: inherit; resize: none; font-size: 14px; }
    .send-btn-box { text-align: right; padding: 0 15px 10px; }
  }
  .empty-chat { flex: 1; display: flex; flex-direction: column; justify-content: center; align-items: center; color: #ccc; gap: 10px; }
}
.text-ellipsis { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>