<template>
  <div class="metro-layout">
    <div class="navbar-wrapper">
      <div class="navbar-container">

        <div class="navbar-left">
          <div class="logo" @click="refreshPage">Metro</div>
          <div class="nav-links">
            <span class="nav-item active">推荐</span>
            <span class="nav-item">热榜</span>
            <span class="nav-item">关注</span>
            <span class="nav-item">专栏</span>
          </div>
        </div>

        <div class="navbar-center">
          <div class="search-box">
            <el-input
                v-model="searchText"
                placeholder="搜索感兴趣的内容..."
                class="search-input"
                suffix-icon="Search"
            />
          </div>
        </div>

        <div class="navbar-right">
          <div class="action-btns">
            <el-button type="primary" round @click="$router.push('/publish')">提问</el-button>
          </div>

          <div class="user-area">
            <div class="icon-btn" @click="$router.push('/message')">
              <el-badge :value="unreadCount" :max="99" :hidden="unreadCount === 0" class="bell-badge">
                <el-icon :size="22"><Bell /></el-icon>
              </el-badge>
            </div>
            <div class="icon-btn" @click="$router.push('/chat')">
              <el-badge :value="chatUnreadCount" :max="99" :hidden="chatUnreadCount === 0" class="bell-badge">
                <el-icon :size="22"><Message /></el-icon>
              </el-badge>
            </div>

            <div class="profile-box">
              <template v-if="user && user.username">
                <el-dropdown trigger="click" @command="handleCommand">
                  <div class="avatar-wrapper">
                    <el-avatar
                        shape="square"
                        :size="34"
                        :src="user.avatar"
                        icon="UserFilled"
                        class="user-avatar"
                    >
                    </el-avatar>
                  </div>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="userCenter">
                        <el-icon><User /></el-icon> 个人中心
                      </el-dropdown-item>

                      <el-dropdown-item command="settings">
                        <el-icon><Setting /></el-icon> 个人设置
                      </el-dropdown-item>

                      <el-dropdown-item divided command="logout">
                        <el-icon><SwitchButton /></el-icon> 退出登录
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>

              <template v-else>
                <el-button type="primary" link @click="$router.push('/login')" class="login-text-btn">登录</el-button>
              </template>
            </div>
          </div>
        </div>

      </div>
    </div>

    <div class="main-container">
      <div class="feed-column">
        <el-card class="creation-card" shadow="never">
          <div class="creation-actions">
            <div class="action-item"><el-icon :size="20" color="#e6a23c"><Edit /></el-icon><span>写回答</span></div>
            <div class="action-item" @click="$router.push('/publish')">
              <el-icon :size="20" color="#409eff"><DocumentAdd /></el-icon>
              <span>写文章</span>
            </div>
            <div class="action-item"><el-icon :size="20" color="#67c23a"><Promotion /></el-icon><span>写想法</span></div>
          </div>
        </el-card>

        <div class="feed-tabs">
          <span class="tab-item active">推荐</span>
          <span class="tab-item">最新</span>
          <span class="tab-item">热榜</span>
        </div>

        <div class="article-list"
             v-infinite-scroll="loadMore"
             :infinite-scroll-disabled="disabled"
             infinite-scroll-distance="50">

          <el-card v-for="article in articleList" :key="article.id" class="feed-card" shadow="never" @click.native="toDetail(article.id)">
            <div class="card-body">
              <div class="text-content">
                <h2 class="title">{{ article.title }}</h2>
                <div class="content-preview">
                  <div class="text-summary">
                    <span class="author-tag" v-if="article.authorName">{{ article.authorName }}:</span>
                    {{ article.summary }}
                  </div>
                </div>
              </div>

              <div class="cover-box" v-if="article.cover">
                <img :src="article.cover" alt="cover" />
              </div>
            </div>

            <div class="card-actions" @click.stop>
              <button class="vote-btn up">
                <el-icon><CaretTop /></el-icon> 赞同 {{ article.likeCount }}
              </button>
              <button class="vote-btn down">
                <el-icon><CaretBottom /></el-icon>
              </button>

              <div class="action-item text-btn">
                <el-icon><ChatDotRound /></el-icon> {{ article.commentCount || 0 }} 条评论
              </div>

              <div class="action-item text-btn">
                <el-icon><Star /></el-icon> 收藏
              </div>
              <div class="action-item text-btn">
                <el-icon><Share /></el-icon> 分享
              </div>
              <div class="time-stamp">
                {{ formatTime(article.createTime) }}
              </div>
            </div>
          </el-card>
        </div>

        <div class="loading-state">
          <el-skeleton v-if="loading" :rows="3" animated />
          <p v-if="noMore" class="no-more">—— 到底啦 ——</p>
        </div>
      </div>

      <div class="sidebar-column">
        <el-card class="sidebar-card creator-center" shadow="never">
          <div class="card-header">
            <span class="title"><el-icon><Trophy /></el-icon> 创作中心</span>
            <span class="link">草稿箱 (0)</span>
          </div>
          <div class="creator-body">
            <div class="guide-text">开启你的技术创作之旅，快来发布第一篇文章吧~</div>
            <el-button type="primary" class="start-btn" icon="Plus" @click="$router.push('/publish')">开始创作</el-button>
          </div>
        </el-card>

        <el-card class="sidebar-card" shadow="never">
          <div class="card-header border-bottom">
            <span class="title">🔥 全站热榜</span>
          </div>
          <div class="hot-list">
            <div class="hot-item" v-for="(item, index) in hotTopics" :key="item.id" @click="toDetail(item.id)">
              <span class="rank" :class="{ 'top-3': index < 3 }">{{ index + 1 }}</span>
              <span class="text">{{ item.title }}</span>
              <span class="heat" v-if="index < 3">hot</span>
            </div>
            <div v-if="hotTopics.length === 0" class="empty-hot">暂无热榜数据</div>
          </div>
        </el-card>

        <div class="footer-links">
          <p>Metro </p>
          <p>© 2026 Metro 社区</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
// 【新增】引入 ElNotification 用于好友消息弹窗
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import request from '../utils/request'
import { getHotRank } from '../api/article'
import { UserFilled } from '@element-plus/icons-vue'

const router = useRouter()

// 1. 定义未读数变量
const unreadCount = ref(0)
const chatUnreadCount = ref(0) // 私信未读

// 2. 好友列表缓存 (用于判断是否弹窗)
const friendIds = ref(new Set())

// 获取用户信息
const getUser = () => {
  try {
    const u = localStorage.getItem('user')
    return u ? JSON.parse(u) : {}
  } catch(e) { return {} }
}
const user = ref(getUser())
const searchText = ref('')

const articleList = ref([])
const hotTopics = ref([])
const loading = ref(false)
const noMore = ref(false)

const disabled = computed(() => loading.value || noMore.value)

// 下拉菜单指令处理
const handleCommand = (command) => {
  if (command === 'userCenter') {
    if (user.value.id) {
      router.push(`/user/${user.value.id}`)
    } else {
      ElMessage.warning('请先登录')
      router.push('/login')
    }
  }
  else if (command === 'settings') {
    router.push('/settings')
  }
  else if (command === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗?', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(() => {
      localStorage.clear()
      router.push('/login')
      ElMessage.success('已退出')
    }).catch(() => {})
  }
}

const loadMore = async () => {
  if (loading.value || noMore.value) return
  loading.value = true
  try {
    let lastTime = null
    if (articleList.value.length > 0) {
      lastTime = articleList.value[articleList.value.length - 1].createTime
    }
    const res = await request.get('/api/article/feed', {
      params: { lastCreateTime: lastTime }
    })
    const newArticles = res.data || []
    if (newArticles.length === 0) {
      noMore.value = true
    } else {
      articleList.value.push(...newArticles)
    }
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const loadHotRank = async () => {
  try {
    const res = await getHotRank()
    hotTopics.value = res.data || []
  } catch (e) { console.error(e) }
}

const getUnreadCount = async () => {
  if (!user.value.id) return
  try {
    const res = await request.get('/api/message/unread')
    unreadCount.value = res.data || 0
  } catch(e) {}
}

const getChatUnread = async () => {
  if(!user.value.id) return
  try {
    const res = await request.get('/api/chat/unread')
    chatUnreadCount.value = res.data || 0
  } catch(e){}
}

// 【新增】加载好友ID列表 (用于通知过滤)
const loadFriendIds = async () => {
  if(!user.value.id) return
  try {
    // 复用获取会话列表接口，后端已返回 isFriend 字段
    const res = await request.get('/api/chat/friends')
    const list = res.data || []
    friendIds.value.clear()
    list.forEach(u => {
      if (u.isFriend) friendIds.value.add(u.id)
    })
  } catch(e) {}
}

// 【新增】处理全局消息通知
const handleGlobalMessage = (e) => {
  const msg = e.detail

  // 1. 如果是好友发来的，右下角弹窗提示
  if (friendIds.value.has(msg.fromId)) {
    ElNotification({
      title: '好友消息',
      message: msg.content.length > 20 ? msg.content.substring(0, 20) + '...' : msg.content,
      type: 'info',
      position: 'bottom-right',
      duration: 4000,
      onClick: () => {
        // 点击通知跳转到聊天页，并选中该用户
        router.push(`/chat?to=${msg.fromId}`)
      }
    })
  }

  // 2. 无论是否好友，都更新信封红点
  getChatUnread()
}

onMounted(() => {
  loadMore()
  loadHotRank()
  getUnreadCount()
  getChatUnread()

  // 初始化消息监听
  loadFriendIds()
  window.addEventListener('on-chat-msg', handleGlobalMessage)
})

onUnmounted(() => {
  window.removeEventListener('on-chat-msg', handleGlobalMessage)
})

const toDetail = (id) => router.push(`/article/${id}`)
const refreshPage = () => { window.location.reload() }
const formatTime = (time) => {
  if(!time) return ''
  if(Array.isArray(time)) return `${time[0]}-${time[1]}-${time[2]}`
  return String(time).replace('T', ' ').substring(0, 10)
}
</script>

<style scoped lang="scss">
.metro-layout {
  min-height: 100vh;
  background-color: #f6f6f6;
  font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", "PingFang SC", "Microsoft YaHei", sans-serif;
}

/* --- 1. 顶部导航栏 --- */
.navbar-wrapper {
  position: fixed; top: 0; left: 0; width: 100%; height: 52px; background: #fff; box-shadow: 0 1px 3px rgba(18, 18, 18, 0.1); z-index: 1000;
}

.navbar-container {
  width: 100%; height: 100%;
  padding: 0 50px 0 30px;
  display: flex; align-items: center; justify-content: space-between;
}

/* 左侧 */
.navbar-left {
  display: flex; align-items: center; flex-shrink: 0;
  .logo {
    font-size: 30px; font-weight: 900; color: #0066ff; margin-right: 30px; letter-spacing: -1px; cursor: pointer; line-height: 1;
  }
  .nav-links {
    display: flex; gap: 30px; height: 100%;
    .nav-item {
      font-size: 15px; color: #8590a6; font-weight: 500; cursor: pointer;
      display: flex; align-items: center; height: 100%; border-bottom: 3px solid transparent;
      &:hover { color: #121212; }
      &.active { color: #121212; font-weight: 600; border-bottom-color: #0066ff; }
    }
  }
}

/* 中间 */
.navbar-center {
  flex: 1; display: flex; justify-content: center; padding: 0 20px; margin-right: 20px;
  .search-box {
    width: 100%; max-width: 480px;
    :deep(.el-input__wrapper) { border-radius: 99px; background-color: #f6f6f6; box-shadow: none; }
  }
}

/* 右侧 */
.navbar-right {
  display: flex; align-items: center; gap: 20px; flex-shrink: 0; white-space: nowrap;
  margin-right: 60px;

  .action-btns { margin-right: 10px; }
  .user-area {
    display: flex; align-items: center; gap: 24px; flex-shrink: 0;
    .icon-btn { color: #8590a6; cursor: pointer; display: flex; align-items: center; &:hover { color: #76839b; } }

    .profile-box {
      display: flex; align-items: center; flex-shrink: 0;
      .avatar-wrapper {
        cursor: pointer;
        width: 34px; height: 34px; display: block;
        .user-avatar { width: 100%; height: 100%; background-color: #f0f2f5; color: #909399; }
      }
    }
    .login-text-btn { font-size: 15px; font-weight: 600; }
  }
}

/* --- 主体内容 --- */
.main-container {
  width: 1000px; margin: 64px auto 0; display: flex; align-items: flex-start; gap: 10px;
  @media (max-width: 1050px) { width: 100%; padding: 0 10px; }
}

.feed-column {
  width: 694px;
  @media (max-width: 1050px) { flex: 1; width: auto; }
  .creation-card {
    margin-bottom: 10px; border: none; box-shadow: 0 1px 3px rgba(18, 18, 18, 0.1);
    .creation-actions {
      display: flex; justify-content: space-around; padding: 4px 0;
      .action-item { display: flex; align-items: center; gap: 8px; font-size: 15px; color: #444; cursor: pointer; &:hover { color: #0066ff; } }
    }
  }
  .feed-tabs {
    background: #fff; padding: 15px 20px; border-bottom: 1px solid #f0f2f7; box-shadow: 0 1px 3px rgba(18, 18, 18, 0.1); display: flex; gap: 40px;
    .tab-item { font-size: 15px; color: #121212; cursor: pointer; &:hover { color: #0066ff; } &.active { color: #0066ff; font-weight: 600; } }
  }
  .article-list {
    .feed-card {
      border: none; border-radius: 0; border-bottom: 1px solid #f0f0f0; box-shadow: none; padding: 20px; cursor: pointer;
      &:hover { background: #fcfcfc; }

      /* 新增：文章内容布局 (左文右图) */
      .card-body {
        display: flex; gap: 20px;
        .text-content { flex: 1; min-width: 0; }
        .cover-box {
          flex-shrink: 0; width: 190px; height: 105px; border-radius: 4px; overflow: hidden; background: #f6f6f6;
          img { width: 100%; height: 100%; object-fit: cover; transition: transform 0.3s; }
        }
      }
      &:hover .cover-box img { transform: scale(1.05); }

      .title { font-size: 18px; font-weight: 600; color: #121212; margin: 0 0 10px 0; line-height: 1.6; }
      .title:hover { color: #0066ff; }
      .content-preview {
        font-size: 15px; color: #121212; line-height: 1.67; margin-bottom: 10px;
        .author-tag { font-weight: 600; color: #444; margin-right: 4px; }
        .read-more { color: #0066ff; margin-left: 4px; font-size: 14px; display: inline-flex; align-items: center; }
      }
      .card-actions {
        display: flex; align-items: center; gap: 20px; margin-top: 10px;
        .vote-btn {
          padding: 0 12px; height: 32px; line-height: 30px; border-radius: 3px; border: none; font-size: 14px; font-weight: 500; cursor: pointer; display: flex; align-items: center; gap: 4px;
          &.up { background: rgba(0, 102, 255, 0.1); color: #0066ff; &:hover { background: rgba(0, 102, 255, 0.15); } }
          &.down { background: rgba(0, 102, 255, 0.1); color: #0066ff; padding: 0 8px; &:hover { background: rgba(0, 102, 255, 0.15); } }
        }
        .text-btn { display: flex; align-items: center; gap: 4px; font-size: 14px; color: #8590a6; cursor: pointer; &:hover { color: #76839b; } }
        .time-stamp { margin-left: auto; font-size: 12px; color: #bfc1c7; }
      }
    }
  }
}

.sidebar-column {
  width: 296px;
  @media (max-width: 1000px) { display: none; }
  .sidebar-card {
    border: none; box-shadow: 0 1px 3px rgba(18, 18, 18, 0.1); margin-bottom: 10px;
    .card-header {
      display: flex; justify-content: space-between; align-items: center; font-size: 14px; color: #8590a6; margin-bottom: 12px;
      .title { color: #121212; font-weight: 600; }
      &.border-bottom { border-bottom: 1px solid #f6f6f6; padding-bottom: 10px; margin-bottom: 10px; }
    }
    &.creator-center {
      .guide-text { font-size: 13px; color: #8590a6; margin-bottom: 15px; text-align: center; }
      .start-btn { width: 100%; border-radius: 2px; }
    }
    .hot-list {
      .hot-item {
        display: flex; align-items: center; gap: 10px; margin-bottom: 12px; font-size: 14px; cursor: pointer; color: #444;
        &:hover { color: #0066ff; }
        .rank { width: 18px; text-align: center; color: #999; font-weight: 600; font-size: 14px; }
        .rank.top-3 { color: #ff9607; }
        .text { flex: 1; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
        .heat { font-size: 12px; color: #f56c6c; transform: scale(0.9); }
      }
      .empty-hot { text-align: center; color: #999; font-size: 13px; padding: 10px; }
    }
  }
  .footer-links { font-size: 13px; color: #8590a6; line-height: 2; padding: 0 5px; }
}

.loading-state { padding: 20px; text-align: center; background: #fff; }
.no-more { color: #8590a6; font-size: 14px; padding: 20px 0; }

.bell-badge {
  display: flex; align-items: center;
  :deep(.el-badge__content) {
    transform: translateY(-50%) translateX(100%) scale(0.8) !important;
  }
}
</style>