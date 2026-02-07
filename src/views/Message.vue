<template>
  <div class="message-layout">
    <div class="navbar-placeholder">
      <div class="nav-back" @click="$router.push('/home')">
        <el-icon><ArrowLeft /></el-icon> 返回首页
      </div>
    </div>

    <div class="main-container">
      <div class="message-card">
        <div class="card-header">
          <span class="title">消息中心</span>
          <el-button v-if="unreadCount > 0" type="primary" link @click="readAll">
            <el-icon><Check /></el-icon> 全部已读
          </el-button>
        </div>

        <div class="msg-list" v-loading="loading">
          <div
              v-for="msg in list"
              :key="msg.id"
              class="msg-item"
              :class="{ 'unread': msg.status === 0 }"
              @click="handleClick(msg)"
          >
            <el-avatar :size="46" :src="msg.fromAvatar" icon="UserFilled" class="sender-avatar"></el-avatar>

            <div class="msg-content">
              <div class="top-row">
                <span class="sender-name">{{ msg.fromUsername }}</span>
                <span class="action-text">{{ getActionText(msg.type) }}</span>
              </div>

              <div v-if="msg.content" class="detail-text">
                "{{ msg.content }}"
              </div>

              <div class="time-text">{{ formatTime(msg.createTime) }}</div>
            </div>

            <div v-if="msg.status === 0" class="red-dot"></div>
          </div>

          <el-empty v-if="!loading && list.length === 0" description="暂无消息"></el-empty>
        </div>

        <div v-if="list.length > 0" class="load-more">
          <el-button v-if="hasMore" text bg @click="loadData(currentPage + 1)">加载更多</el-button>
          <span v-else class="no-more">没有更多了</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../utils/request'

const router = useRouter()
const list = ref([])
const loading = ref(false)
const currentPage = ref(1)
const hasMore = ref(true)
const unreadCount = ref(0) // 用于控制"全部已读"按钮的显示

// 1. 加载消息列表
const loadData = async (page = 1) => {
  loading.value = true
  try {
    const res = await request.get(`/api/message/list?page=${page}&size=10`)
    const newRecords = res.data.records || []

    if (page === 1) {
      list.value = newRecords
    } else {
      list.value.push(...newRecords)
    }

    currentPage.value = page
    hasMore.value = newRecords.length === 10 // 如果取满10条，说明可能还有下一页

    // 顺便更新一下未读状态判断
    checkUnreadStatus()
  } catch(e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

// 2. 全部已读
const readAll = async () => {
  try {
    await request.post('/api/message/read-all')
    ElMessage.success('已全部标记为已读')
    // 前端手动把所有状态置为1，避免重新请求
    list.value.forEach(item => item.status = 1)
    unreadCount.value = 0
  } catch(e) {
    ElMessage.error('操作失败')
  }
}

// 3. 点击消息跳转
const handleClick = (msg) => {
  // 如果是未读，可以在这里调个接口标为已读，或者简单点就留给"全部已读"
  // 跳转逻辑
  if (msg.type === 1 || msg.type === 2) {
    // 点赞/评论 -> 跳文章详情
    router.push(`/article/${msg.targetId}`)
  } else if (msg.type === 3) {
    // 关注 -> 跳用户主页
    router.push(`/user/${msg.fromId}`)
  }
}

// 获取动作文案
const getActionText = (type) => {
  const map = {
    1: '赞了你的文章',
    2: '评论了你的文章',
    3: '关注了你',
    4: '系统通知'
  }
  return map[type] || '有一条新消息'
}

const formatTime = (time) => {
  if(!time) return ''
  if(Array.isArray(time)) return `${time[0]}-${time[1]}-${time[2]} ${time[3]}:${time[4]}`
  return String(time).replace('T', ' ').substring(0, 16)
}

const checkUnreadStatus = () => {
  unreadCount.value = list.value.filter(i => i.status === 0).length
}

onMounted(() => {
  loadData()
})
</script>

<style scoped lang="scss">
.message-layout {
  min-height: 100vh; background: #f6f6f6; padding-top: 20px;
}
.nav-back {
  width: 800px; margin: 0 auto 20px; cursor: pointer; display: flex; align-items: center; gap: 5px; color: #666;
  &:hover { color: #0066ff; }
}
.main-container {
  width: 800px; margin: 0 auto;
}
.message-card {
  background: #fff; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1); padding: 0 20px;
  min-height: 500px;
  .card-header {
    display: flex; justify-content: space-between; align-items: center; padding: 20px 0; border-bottom: 1px solid #f0f0f0;
    .title { font-size: 18px; font-weight: 600; color: #333; }
  }
}

.msg-item {
  display: flex; gap: 15px; padding: 20px 10px; border-bottom: 1px solid #f6f6f6; cursor: pointer; transition: background 0.2s; position: relative;
  &:hover { background: #f9f9f9; }
  &.unread { background: #f0f6ff; } /* 未读高亮 */

  .sender-avatar { flex-shrink: 0; }

  .msg-content {
    flex: 1;
    .top-row {
      margin-bottom: 6px;
      .sender-name { font-weight: 600; color: #333; margin-right: 8px; }
      .action-text { color: #666; font-size: 14px; }
    }
    .detail-text { font-size: 14px; color: #333; background: #f4f4f4; padding: 8px; border-radius: 4px; margin-bottom: 6px; }
    .time-text { font-size: 12px; color: #999; }
  }

  .red-dot {
    width: 8px; height: 8px; background: #f56c6c; border-radius: 50%; position: absolute; right: 20px; top: 50%; transform: translateY(-50%);
  }
}

.load-more { text-align: center; padding: 20px; }
.no-more { font-size: 13px; color: #999; }
</style>