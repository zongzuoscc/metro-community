<template>
  <div class="user-center-layout">
    <div class="navbar-placeholder">
      <div class="nav-back" @click="$router.push('/home')">
        <el-icon><ArrowLeft /></el-icon> 返回首页
      </div>
    </div>

    <div class="main-container">
      <div class="profile-header">
        <div class="info-row">
          <el-avatar :size="80" :src="userInfo.avatar" icon="UserFilled" class="u-avatar"></el-avatar>
          <div class="info-content">
            <div class="u-name">{{ userInfo.username }}</div>
            <div class="u-intro">{{ userInfo.intro || '这个人很懒，什么都没写' }}</div>
          </div>
          <div class="action-box">
            <template v-if="isMe">
              <el-button type="primary" plain round @click="editProfile">编辑资料</el-button>
            </template>
            <template v-else>
              <el-button
                  :type="userInfo.isFollowed ? 'info' : 'primary'"
                  :plain="userInfo.isFollowed"
                  round
                  @click="handleFollow"
              >
                {{ userInfo.isFollowed ? '已关注' : '+ 关注' }}
              </el-button>
              <el-button plain round icon="ChatDotRound">私信</el-button>
            </template>
          </div>
        </div>

        <el-divider style="margin: 20px 0;" />

        <div class="stat-row">
          <div class="stat-item" @click="activeTab = 'articles'">
            <div class="num">{{ userInfo.articleCount || 0 }}</div>
            <div class="label">文章</div>
          </div>
          <div class="stat-item">
            <div class="num">{{ userInfo.likeCount || 0 }}</div>
            <div class="label">获赞</div>
          </div>
          <div class="stat-item" @click="activeTab = 'following'">
            <div class="num">{{ userInfo.followingCount || 0 }}</div>
            <div class="label">关注</div>
          </div>
          <div class="stat-item" @click="activeTab = 'fans'">
            <div class="num">{{ userInfo.fanCount || 0 }}</div>
            <div class="label">粉丝</div>
          </div>
        </div>
      </div>

      <div class="content-tabs">
        <el-tabs v-model="activeTab" class="custom-tabs">

          <el-tab-pane label="文章" name="articles">
            <div class="article-list" v-loading="loading">
              <div v-for="article in articleList" :key="article.id" class="article-item" @click="$router.push(`/article/${article.id}`)">
                <div class="a-title">{{ article.title }}</div>
                <div class="a-summary">{{ article.summary }}</div>
                <div class="a-meta">
                  <span>{{ formatTime(article.createTime) }}</span>
                  <span><el-icon><View /></el-icon> {{ article.viewCount }}</span>
                  <span><el-icon><CaretTop /></el-icon> {{ article.likeCount }}</span>
                </div>
              </div>
              <el-empty v-if="!loading && articleList.length === 0" description="暂无文章"></el-empty>
            </div>
          </el-tab-pane>

          <el-tab-pane label="关注" name="following">
            <div class="user-list" v-loading="loading">
              <div v-for="user in userList" :key="user.id" class="user-item" @click="toUser(user.id)">
                <el-avatar :size="50" :src="user.avatar" icon="UserFilled"></el-avatar>
                <div class="u-info">
                  <div class="name">{{ user.username }}</div>
                  <div class="bio">{{ user.intro || '暂无简介' }}</div>
                </div>
              </div>
              <el-empty v-if="!loading && userList.length === 0" description="没有关注任何人"></el-empty>
            </div>
          </el-tab-pane>

          <el-tab-pane label="粉丝" name="fans">
            <div class="user-list" v-loading="loading">
              <div v-for="user in userList" :key="user.id" class="user-item" @click="toUser(user.id)">
                <el-avatar :size="50" :src="user.avatar" icon="UserFilled"></el-avatar>
                <div class="u-info">
                  <div class="name">{{ user.username }}</div>
                  <div class="bio">{{ user.intro || '暂无简介' }}</div>
                </div>
              </div>
              <el-empty v-if="!loading && userList.length === 0" description="暂无粉丝"></el-empty>
            </div>
          </el-tab-pane>

          <el-tab-pane v-if="isMe" label="收藏" name="favorites">
            <div class="fav-grid" v-loading="loading">
              <div
                  v-for="folder in favList"
                  :key="folder.id"
                  class="fav-card"
                  @click="$router.push(`/favorite/${folder.id}`)"
              >
                <div class="f-icon"><el-icon><StarFilled /></el-icon></div>
                <div class="f-name">{{ folder.name }}</div>
                <div class="f-count">{{ folder.count }} 篇文章</div>
              </div>
              <el-empty v-if="!loading && favList.length === 0" description="暂无收藏夹"></el-empty>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../utils/request'

const route = useRoute()
const router = useRouter()

const userInfo = ref({})
const activeTab = ref('articles')
const loading = ref(false)

const articleList = ref([])
const userList = ref([])
const favList = ref([])

const currentUser = computed(() => {
  const str = localStorage.getItem('user')
  return str ? JSON.parse(str) : {}
})

const targetUserId = computed(() => route.params.id || currentUser.value.id)

const isMe = computed(() => {
  return String(targetUserId.value) === String(currentUser.value.id)
})

// 1. 加载用户信息
const loadProfile = async () => {
  try {
    const id = targetUserId.value
    if(!id) return
    const res = await request.get(`/api/user/profile/${id}`)
    userInfo.value = res.data || {}
  } catch(e) {
    console.error(e)
  }
}

// 2. 加载 Tab 数据
const loadTabData = async () => {
  loading.value = true
  const id = targetUserId.value
  // 清空旧数据，防止闪烁
  articleList.value = []
  userList.value = []
  favList.value = []

  try {
    if (activeTab.value === 'articles') {
      const res = await request.get(`/api/article/user/${id}?page=1&size=20`)
      articleList.value = res.data.records || []
    }
    else if (activeTab.value === 'following') {
      const res = await request.get(`/api/follow/following/${id}?page=1&size=50`)
      userList.value = res.data.records || []
    }
    else if (activeTab.value === 'fans') {
      const res = await request.get(`/api/follow/fans/${id}?page=1&size=50`)
      userList.value = res.data.records || []
    }
    else if (activeTab.value === 'favorites' && isMe.value) {
      const res = await request.get(`/api/favorite/list`)
      favList.value = res.data || []
    }
  } catch(e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

// 【核心修复】监听 activeTab 的变化
// 无论是点击 Tab 标签，还是点击上面的数字，都会触发这个监听器
watch(activeTab, () => {
  loadTabData()
})

// 关注操作
const handleFollow = async () => {
  if(!currentUser.value.id) return ElMessage.warning('请先登录')
  try {
    await request.post(`/api/follow/${targetUserId.value}`)
    userInfo.value.isFollowed = !userInfo.value.isFollowed
    if(userInfo.value.isFollowed) {
      userInfo.value.fanCount = (userInfo.value.fanCount || 0) + 1
    } else {
      userInfo.value.fanCount = (userInfo.value.fanCount || 0) - 1
    }
    ElMessage.success(userInfo.value.isFollowed ? '关注成功' : '已取消关注')

    // 如果当前在粉丝列表Tab，刷新一下列表
    if (activeTab.value === 'fans') {
      loadTabData()
    }
  } catch(e) {
    ElMessage.error(e.msg || '操作失败')
  }
}

const toUser = (uid) => {
  router.push(`/user/${uid}`)
}

// 【修复】跳转到设置页
const editProfile = () => {
  router.push('/settings')
}

const formatTime = (time) => {
  if(!time) return ''
  if(Array.isArray(time)) return `${time[0]}-${time[1]}-${time[2]}`
  return String(time).substring(0, 10)
}

// 监听路由参数ID变化 (例如从我的主页跳到别人的主页)
watch(() => route.params.id, () => {
  loadProfile()
  loadTabData()
})

onMounted(() => {
  loadProfile()
  loadTabData()
})
</script>

<style scoped lang="scss">
.user-center-layout {
  min-height: 100vh; background: #f6f6f6; padding-top: 20px;
}
.nav-back {
  width: 1000px; margin: 0 auto 20px; cursor: pointer; display: flex; align-items: center; gap: 5px; color: #666;
  &:hover { color: #0066ff; }
}
.main-container {
  width: 1000px; margin: 0 auto;
}

/* 头部卡片 */
.profile-header {
  background: #fff; padding: 40px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1); margin-bottom: 20px;
  .info-row {
    display: flex; align-items: center; gap: 25px;
    .u-avatar { border: 2px solid #fff; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
    .info-content {
      flex: 1;
      .u-name { font-size: 26px; font-weight: 700; color: #121212; margin-bottom: 10px; }
      .u-intro { font-size: 14px; color: #666; }
    }
  }
  .stat-row {
    display: flex; gap: 60px; padding-left: 10px;
    .stat-item {
      text-align: center; cursor: pointer;
      &:hover .num { color: #0066ff; }
      .num { font-size: 20px; font-weight: 600; color: #121212; margin-bottom: 4px; }
      .label { font-size: 14px; color: #8590a6; }
    }
  }
}

/* 内容 Tab */
.content-tabs {
  background: #fff; padding: 20px 30px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1); min-height: 500px;
}

/* 列表通用样式 */
.article-item {
  padding: 20px 0; border-bottom: 1px solid #f0f0f0; cursor: pointer;
  &:hover .a-title { color: #0066ff; }
  .a-title { font-size: 18px; font-weight: 600; color: #121212; margin-bottom: 10px; }
  .a-summary { font-size: 14px; color: #555; margin-bottom: 12px; line-height: 1.6; }
  .a-meta { display: flex; gap: 20px; font-size: 13px; color: #999; display: flex; align-items: center; }
}

.user-item {
  display: flex; align-items: center; gap: 15px; padding: 15px 0; border-bottom: 1px solid #f0f0f0; cursor: pointer;
  .u-info {
    .name { font-weight: 600; font-size: 16px; color: #333; }
    .bio { font-size: 13px; color: #999; margin-top: 4px; }
  }
}

.fav-grid {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; padding-top: 10px;
  .fav-card {
    border: 1px solid #eee; border-radius: 8px; padding: 20px; text-align: center; cursor: pointer; transition: all 0.2s;
    &:hover { border-color: #0066ff; transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.05); }
    .f-icon { font-size: 32px; color: #ffb800; margin-bottom: 10px; }
    .f-name { font-weight: 600; color: #333; margin-bottom: 5px; }
    .f-count { font-size: 12px; color: #999; }
  }
}
</style>