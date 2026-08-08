<template>
  <div class="metro-layout">
    <div class="navbar-wrapper">
      <div class="navbar-container">

        <div class="navbar-left">
          <div class="logo" @click="refreshPage">Metro</div>
          <div class="nav-links">
            <span class="nav-item" :class="{ active: activeNav === 'recommend' }" @click="switchNav('recommend')">推荐</span>
            <span class="nav-item" :class="{ active: activeNav === 'hot' }" @click="switchNav('hot')">热榜</span>
            <span class="nav-item" :class="{ active: activeNav === 'follow' }" @click="switchNav('follow')">关注</span>
          </div>
        </div>

        <div class="navbar-center">
          <div class="search-box">
            <el-input
                v-model="searchText"
                placeholder="搜索感兴趣的内容或用户..."
                class="search-input"
                suffix-icon="Search"
                @keydown.enter="handleSearch"
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
                      <el-dropdown-item v-if="user.role === 1" @click="router.push('/admin')" divided>
                        <el-icon><Monitor /></el-icon> 管理后台
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
        <el-card class="creation-card" shadow="never" v-if="activeNav === 'recommend'">
          <div class="creation-actions">

            <div class="action-item" @click="$router.push('/publish')">
              <el-icon :size="20"><DocumentAdd /></el-icon>
              <span>写文章</span>
            </div>

          </div>
        </el-card>

        <div class="feed-tabs">
          <template v-if="activeNav === 'search'">
            <div class="search-tabs">
              <span class="tab-item" :class="{ active: searchType === 'article' }" @click="switchSearchType('article')">文章</span>
              <span class="tab-item" :class="{ active: searchType === 'user' }" @click="switchSearchType('user')">用户</span>
            </div>
            <div class="search-info">
              关于 "{{ currentSearchKeyword }}" 的搜索结果
              <el-button link type="primary" size="small" @click="clearSearch" style="margin-left: 10px;">
                返回推荐
              </el-button>
            </div>
          </template>

          <template v-else>
                <span class="tab-item active" style="cursor: default;">
                    {{ getTabTitle() }}
                </span>
          </template>
        </div>

        <div class="article-list"
             v-infinite-scroll="loadMore"
             :infinite-scroll-disabled="disabled"
             infinite-scroll-distance="50">

          <template v-if="activeNav !== 'search' || searchType === 'article'">
            <el-card v-for="(article, index) in articleList" :key="article.id" class="feed-card" shadow="never" @click.native="toDetail(article.id)">
              <div class="card-body">
                <div class="rank-badge" v-if="activeNav === 'hot'" :class="'rank-' + (index + 1)">
                  {{ index + 1 }}
                </div>

                <div class="text-content">
                  <h2 class="title" v-html="highlightKeyword(article.title)"></h2>
                  <div class="content-preview">
                    <div class="text-summary">
                      <span class="author-tag" v-if="article.authorName">{{ article.authorName }}:</span>
                      <span v-html="highlightKeyword(article.summary)"></span>
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
                <div class="action-item text-btn">
                  <el-icon><ChatDotRound /></el-icon> {{ article.commentCount || 0 }} 条评论
                </div>
                <div class="time-stamp">
                  {{ formatTime(article.createTime) }}
                </div>
              </div>
            </el-card>

            <el-empty v-if="!loading && articleList.length === 0" :description="emptyText"></el-empty>
          </template>

          <template v-if="activeNav === 'search' && searchType === 'user'">
            <div v-for="u in userList" :key="u.id" class="user-card" @click="toUser(u.id)">
              <el-avatar :size="50" :src="u.avatar" icon="UserFilled"></el-avatar>
              <div class="u-info">
                <div class="name" v-html="highlightKeyword(u.username)"></div>
                <div class="intro text-ellipsis" v-html="highlightKeyword(u.intro || '暂无简介')"></div>
              </div>
              <el-button round size="small" @click.stop="toUser(u.id)">查看主页</el-button>
            </div>
            <el-empty v-if="!loading && userList.length === 0" description="没有找到相关用户"></el-empty>
          </template>

        </div>

        <div class="loading-state">
          <el-skeleton v-if="loading" :rows="3" animated />
          <p v-if="noMore && (articleList.length > 0 || userList.length > 0)" class="no-more">—— 到底啦 ——</p>
        </div>
      </div>

      <div class="sidebar-column">
        <el-card class="sidebar-card creator-center" shadow="never">
          <div class="card-header">
            <span class="title"><el-icon><Trophy /></el-icon> 创作中心</span>
            <span class="link" @click="toDrafts">草稿箱 ({{ draftCount }})</span>
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
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import request from '../utils/request'
// 【引入 searchUsers】
import { getHotRank, getDraftCount, getHotFeed, getFollowFeed, searchArticles } from '../api/article'
import { searchUsers } from '../api/user'
import {Monitor, Setting, SwitchButton, UserFilled} from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute() // 引入 route

// 状态变量
const activeNav = ref('recommend') // 'recommend', 'hot', 'follow', 'search'
const searchText = ref('')
const currentSearchKeyword = ref('')
const searchType = ref('article') // 'article' | 'user'
const pageNo = ref(1)

const unreadCount = ref(0)
const chatUnreadCount = ref(0)
const friendIds = ref(new Set())
const draftCount = ref(0)

const user = ref(localStorage.getItem('user') ? JSON.parse(localStorage.getItem('user')) : {})

const articleList = ref([])
const userList = ref([]) // 【新增】存储搜索到的用户
const hotTopics = ref([])
const loading = ref(false)
const noMore = ref(false)

const disabled = computed(() => loading.value || noMore.value)

const emptyText = computed(() => {
  if (activeNav.value === 'follow') return '你还没有关注任何人，或关注的人暂无动态'
  if (activeNav.value === 'hot') return '暂无热榜数据'
  if (activeNav.value === 'search') return '没有找到相关内容'
  return '暂无推荐内容'
})

const getTabTitle = () => {
  if (activeNav.value === 'recommend') return '推荐'
  if (activeNav.value === 'hot') return '🔥 7天热榜 TOP 10'
  if (activeNav.value === 'follow') return '👀 我的关注动态'
  return '列表'
}

const switchNav = (nav) => {
  if (activeNav.value === nav) return
  if (nav === 'follow' && !user.value.id) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  activeNav.value = nav
  searchText.value = ''
  resetList()
  loadMore()
}

// 【新增】切换搜索类型
const switchSearchType = (type) => {
  if (searchType.value === type) return
  searchType.value = type
  resetList()
  loadMore()
}

const handleSearch = () => {
  const kw = searchText.value.trim()
  if (!kw) return ElMessage.warning('请输入搜索关键词')

  activeNav.value = 'search'
  currentSearchKeyword.value = kw
  resetList()
  loadMore()
}

const clearSearch = () => {
  searchText.value = ''
  switchNav('recommend')
}

// 高亮关键词
const highlightKeyword = (text) => {
  if (activeNav.value !== 'search' || !currentSearchKeyword.value || !text) return text
  const reg = new RegExp(currentSearchKeyword.value, 'gi')
  return text.replace(reg, (match) => `<span style="color: #f56c6c; font-weight: bold;">${match}</span>`)
}

const resetList = () => {
  articleList.value = []
  userList.value = []
  pageNo.value = 1
  noMore.value = false
  loading.value = false
}

// --- 核心逻辑：加载数据 ---
const loadMore = async () => {
  if (loading.value || noMore.value) return
  loading.value = true

  try {
    let res;

    if (activeNav.value === 'recommend') {
      let lastTime = null
      if (articleList.value.length > 0) {
        lastTime = articleList.value[articleList.value.length - 1].createTime
      }
      res = await request.get('/api/article/feed', { params: { lastCreateTime: lastTime } })
    }
    else if (activeNav.value === 'hot') {
      res = await getHotFeed()
      noMore.value = true
    }
    else if (activeNav.value === 'follow') {
      res = await getFollowFeed(pageNo.value)
      if (res.code === 200) pageNo.value++
    }
    // 【核心新增：搜索逻辑】
    else if (activeNav.value === 'search') {
      if (searchType.value === 'article') {
        res = await searchArticles(currentSearchKeyword.value, pageNo.value)
        if (res.code === 200) {
          const newArticles = res.data?.records || []
          if (newArticles.length === 0) noMore.value = true
          else {
            articleList.value.push(...newArticles)
            pageNo.value++
          }
        }
      } else {
        // 搜索用户
        res = await searchUsers(currentSearchKeyword.value, pageNo.value)
        if (res.code === 200) {
          const newUsers = res.data?.records || []
          if (newUsers.length === 0) noMore.value = true
          else {
            userList.value.push(...newUsers)
            pageNo.value++
          }
        }
      }
      loading.value = false // 提前结束，因为我们手动处理了数据push
      return
    }

    // 处理非搜索文章的数据
    const newArticles = Array.isArray(res.data) ? res.data : (res.data?.records || [])
    if (newArticles.length === 0) noMore.value = true
    else articleList.value.push(...newArticles)

  } catch (e) {
    console.error(e)
    noMore.value = true
  } finally {
    loading.value = false
  }
}

// ... 辅助函数 ...
const handleCommand = (command) => {
  if (command === 'userCenter') {
    if (user.value.id) router.push(`/user/${user.value.id}`)
    else router.push('/login')
  } else if (command === 'settings') {
    router.push('/settings')
  } else if (command === 'logout') {
    ElMessageBox.confirm('确定退出?', '提示').then(() => {
      localStorage.clear()
      router.push('/login')
    }).catch(()=>{})
  }
}

const toDrafts = () => {
  if (!user.value.id) return ElMessage.warning('请先登录')
  router.push({ path: `/user/${user.value.id}`, query: { tab: 'drafts' } })
}

const loadDraftCount = async () => {
  if (!user.value.id) return
  try {
    const res = await getDraftCount()
    draftCount.value = res.data || 0
  } catch(e) {}
}

const loadHotRank = async () => {
  try {
    const res = await getHotRank()
    hotTopics.value = res.data || []
  } catch (e) {}
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

const loadFriendIds = async () => {
  if(!user.value.id) return
  try {
    const res = await request.get('/api/chat/friends')
    const list = res.data || []
    friendIds.value.clear()
    list.forEach(u => {
      if (u.isFriend) friendIds.value.add(u.id)
    })
  } catch(e) {}
}

const handleGlobalMessage = (e) => {
  const msg = e.detail
  if (friendIds.value.has(msg.fromId)) {
    ElNotification({
      title: '好友消息',
      message: msg.content.length > 20 ? msg.content.substring(0, 20) + '...' : msg.content,
      type: 'info',
      position: 'bottom-right',
      duration: 4000,
      onClick: () => { router.push(`/chat?to=${msg.fromId}`) }
    })
  }
  getChatUnread()
}

const toDetail = (id) => router.push(`/article/${id}`)
const toUser = (id) => router.push(`/user/${id}`)
const refreshPage = () => { window.location.reload() }
const formatTime = (time) => {
  if(!time) return ''
  if(Array.isArray(time)) return `${time[0]}-${time[1]}-${time[2]}`
  return String(time).replace('T', ' ').substring(0, 10)
}

onMounted(() => {
  // 【核心修改】检查 URL query 是否有搜索关键词
  const q = route.query.q
  if (q) {
    searchText.value = q
    handleSearch() // 触发搜索
  } else {
    loadMore() // 默认加载推荐
  }

  loadHotRank()
  getUnreadCount()
  getChatUnread()
  loadDraftCount()
  loadFriendIds()
  window.addEventListener('on-chat-msg', handleGlobalMessage)
})

onUnmounted(() => {
  window.removeEventListener('on-chat-msg', handleGlobalMessage)
})
</script>

<style scoped lang="scss">
.metro-layout {
  min-height: 100vh;
  background-color: #f6f6f6;
  font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", "PingFang SC", "Microsoft YaHei", sans-serif;
}

/* 顶部导航栏 */
.navbar-wrapper {
  position: fixed; top: 0; left: 0; width: 100%; height: 52px; background: #fff; box-shadow: 0 1px 3px rgba(18, 18, 18, 0.1); z-index: 1000;
}

.navbar-container {
  width: 100%; height: 100%; padding: 0 50px 0 30px;
  display: flex; align-items: center; justify-content: space-between;
}

.navbar-left {
  display: flex; align-items: center; flex-shrink: 0;
  .logo { font-size: 30px; font-weight: 900; color: #0066ff; margin-right: 30px; letter-spacing: -1px; cursor: pointer; line-height: 1; }
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

.navbar-center {
  flex: 1; display: flex; justify-content: center; padding: 0 20px; margin-right: 20px;
  .search-box {
    width: 100%; max-width: 480px;
    :deep(.el-input__wrapper) { border-radius: 99px; background-color: #f6f6f6; box-shadow: none; }
  }
}

.navbar-right {
  display: flex; align-items: center; gap: 20px; flex-shrink: 0; white-space: nowrap; margin-right: 60px;
  .action-btns { margin-right: 10px; }
  .user-area {
    display: flex; align-items: center; gap: 24px; flex-shrink: 0;
    .icon-btn { color: #8590a6; cursor: pointer; display: flex; align-items: center; &:hover { color: #76839b; } }
    .profile-box {
      display: flex; align-items: center; flex-shrink: 0;
      .avatar-wrapper {
        cursor: pointer; width: 34px; height: 34px; display: block;
        .user-avatar { width: 100%; height: 100%; background-color: #f0f2f5; color: #909399; }
      }
    }
    .login-text-btn { font-size: 15px; font-weight: 600; }
  }
}

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
    background: #fff; padding: 15px 20px; border-bottom: 1px solid #f0f2f7; box-shadow: 0 1px 3px rgba(18, 18, 18, 0.1);
    display: flex; gap: 40px; align-items: center; justify-content: flex-start;
    .tab-item { font-size: 15px; color: #121212; cursor: pointer; &:hover { color: #0066ff; } &.active { color: #0066ff; font-weight: 600; } }

    /* 搜索子Tab样式 */
    .search-tabs {
      display: flex; gap: 20px; border-right: 1px solid #eee; padding-right: 20px; margin-right: 10px;
    }
    .search-info { font-size: 14px; color: #666; flex: 1; display: flex; justify-content: space-between; align-items: center; }
  }

  .article-list {
    .feed-card {
      border: none; border-radius: 0; border-bottom: 1px solid #f0f0f0; box-shadow: none; padding: 20px; cursor: pointer;
      &:hover { background: #fcfcfc; }
      .card-body {
        display: flex; gap: 20px;
        .text-content { flex: 1; min-width: 0; }
        .cover-box {
          flex-shrink: 0; width: 190px; height: 105px; border-radius: 4px; overflow: hidden; background: #f6f6f6;
          img { width: 100%; height: 100%; object-fit: cover; transition: transform 0.3s; }
        }
        .rank-badge {
          flex-shrink: 0; width: 24px; height: 24px; text-align: center; line-height: 24px;
          font-weight: bold; color: #999; font-size: 16px; margin-right: 5px;
          &.rank-1 { color: #f56c6c; font-size: 20px; }
          &.rank-2 { color: #e6a23c; font-size: 18px; }
          &.rank-3 { color: #e6a23c; font-size: 18px; }
        }
      }
      &:hover .cover-box img { transform: scale(1.05); }
      .title { font-size: 18px; font-weight: 600; color: #121212; margin: 0 0 10px 0; line-height: 1.6; }
      .title:hover { color: #0066ff; }
      .content-preview {
        font-size: 15px; color: #121212; line-height: 1.67; margin-bottom: 10px;
        .author-tag { font-weight: 600; color: #444; margin-right: 4px; }
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

    /* 用户卡片样式 */
    .user-card {
      padding: 20px; border-bottom: 1px solid #f0f0f0; display: flex; align-items: center; gap: 15px; cursor: pointer; background: #fff;
      &:hover { background: #fcfcfc; }
      .u-info { flex: 1; min-width: 0;
        .name { font-weight: 600; font-size: 16px; color: #333; margin-bottom: 4px; }
        .intro { font-size: 14px; color: #8590a6; }
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
      .link { cursor: pointer; &:hover { color: #0066ff; } }
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
.text-ellipsis { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.bell-badge {
  display: flex; align-items: center;
  :deep(.el-badge__content) {
    transform: translateY(-50%) translateX(100%) scale(0.8) !important;
  }
}

/* Editorial reading surface: the layout and data flow above stay unchanged. */
.metro-layout { background: var(--paper-muted); color: var(--ink); }
.navbar-wrapper { height: 58px; background: rgba(255, 253, 249, .96); border-bottom: 1px solid var(--line); box-shadow: none; }
.navbar-container { max-width: 1244px; margin: 0 auto; padding: 0 var(--space-4); }
.navbar-left .logo { color: var(--accent); font-family: "Songti SC", SimSun, serif; font-size: 27px; font-weight: 700; letter-spacing: .04em; }
.navbar-left .nav-links { gap: var(--space-5); }
.navbar-left .nav-links .nav-item { color: var(--ink-muted); }
.navbar-left .nav-links .nav-item:hover,
.navbar-left .nav-links .nav-item.active { color: var(--accent); }
.navbar-left .nav-links .nav-item.active { border-bottom-color: var(--accent); }
.navbar-center .search-box :deep(.el-input__wrapper) { border-radius: var(--radius-sm); background: #fffdf9; }
.navbar-right { margin-right: 0; gap: var(--space-4); }
.navbar-right .user-area { gap: var(--space-4); }
.main-container { width: min(1180px, calc(100% - 32px)); margin-top: 78px; gap: var(--space-5); }
.feed-column { width: min(760px, 100%); }
.feed-column .creation-card,
.sidebar-column .sidebar-card,
.feed-column .feed-tabs,
.loading-state { border: 1px solid var(--line); background: #fffdf9; box-shadow: none; }
.feed-column .creation-card { margin-bottom: var(--space-3); }
.feed-column .creation-card .creation-actions .action-item { color: var(--accent); }
.feed-column .feed-tabs { padding: var(--space-4) var(--space-5); }
.feed-column .feed-tabs .tab-item:hover,
.feed-column .feed-tabs .tab-item.active { color: var(--accent); }
.feed-column .article-list .feed-card { margin-top: var(--space-2); border: 1px solid var(--line); border-radius: var(--radius-sm); background: #fffdf9; padding: var(--space-5); }
.feed-column .article-list .feed-card:hover { background: #fffaf3; }
.feed-column .article-list .feed-card .title { font-family: "Songti SC", SimSun, serif; font-size: 22px; line-height: 1.45; color: var(--ink); }
.feed-column .article-list .feed-card .title:hover { color: var(--accent); }
.feed-column .article-list .feed-card .content-preview { color: var(--ink-muted); }
.feed-column .article-list .feed-card .card-actions .vote-btn.up { background: #f4e1dc; color: var(--accent); }
.feed-column .article-list .feed-card .card-actions .text-btn:hover,
.sidebar-column .sidebar-card .card-header .link:hover,
.sidebar-column .sidebar-card .hot-list .hot-item:hover { color: var(--accent); }
.sidebar-column .sidebar-card { margin-bottom: var(--space-3); }
.sidebar-column .sidebar-card .card-header { color: var(--ink-muted); }
.sidebar-column .sidebar-card .card-header .title { color: var(--ink); font-family: "Songti SC", SimSun, serif; }
@media (max-width: 760px) {
  .navbar-center, .navbar-left .nav-links { display: none; }
  .navbar-container { justify-content: space-between; }
  .main-container { width: 100%; padding: 0 var(--space-3); margin-top: 70px; }
  .feed-column .article-list .feed-card { padding: var(--space-4); }
  .feed-column .article-list .feed-card .card-body { gap: var(--space-3); }
  .feed-column .article-list .feed-card .card-body .cover-box { width: 116px; height: 76px; }
}
</style>
