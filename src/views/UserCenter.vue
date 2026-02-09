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
              <el-button type="primary" round icon="Plus" @click="$router.push('/publish')">创作</el-button>
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
              <el-button plain round icon="ChatDotRound" @click="$router.push(`/chat?to=${userInfo.id}`)">私信</el-button>
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
              <div v-for="article in articleList" :key="article.id" class="article-item">
                <div class="content-main" @click="$router.push(`/article/${article.id}`)">
                  <div class="a-title">
                    {{ article.title }}
                    <el-tag v-if="article.status === 0" size="small" type="info">草稿</el-tag>
                  </div>
                  <div class="a-summary">{{ article.summary }}</div>
                  <div class="a-meta">
                    <span>{{ formatTime(article.createTime) }}</span>
                    <span><el-icon><View /></el-icon> {{ article.viewCount }}</span>
                    <span><el-icon><CaretTop /></el-icon> {{ article.likeCount }}</span>
                  </div>
                </div>

                <div v-if="isMe" class="action-btns">
                  <el-button type="primary" link icon="Edit" @click.stop="toEdit(article.id)">编辑</el-button>
                  <el-popconfirm title="确定移入回收站吗？可在7天内恢复" @confirm="handleDelete(article.id)">
                    <template #reference>
                      <el-button type="danger" link icon="Delete" @click.stop>删除</el-button>
                    </template>
                  </el-popconfirm>
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
              <div v-for="folder in favList" :key="folder.id" class="fav-card" @click="$router.push(`/favorite/${folder.id}`)">
                <div class="f-icon"><el-icon><StarFilled /></el-icon></div>
                <div class="f-name">{{ folder.name }}</div>
                <div class="f-count">{{ folder.count }} 篇文章</div>
              </div>
              <el-empty v-if="!loading && favList.length === 0" description="暂无收藏夹"></el-empty>
            </div>
          </el-tab-pane>

          <el-tab-pane v-if="isMe" label="草稿箱" name="drafts">
            <div class="article-list" v-loading="loading">
              <div v-for="draft in draftList" :key="draft.id" class="article-item draft-item">
                <div class="content-main" @click="toEdit(draft.id)">
                  <div class="a-title">{{ draft.title || '无标题草稿' }}</div>
                  <div class="a-summary">{{ draft.summary || '暂无内容...' }}</div>
                  <div class="a-meta">
                    <span>上次编辑: {{ formatTime(draft.updateTime || draft.createTime) }}</span>
                  </div>
                </div>
                <div class="action-btns">
                  <el-button type="primary" link icon="Edit" @click.stop="toEdit(draft.id)">继续编辑</el-button>
                  <el-popconfirm title="确定丢弃这个草稿吗？" @confirm="handleDelete(draft.id)">
                    <template #reference>
                      <el-button type="danger" link icon="Delete" @click.stop>丢弃</el-button>
                    </template>
                  </el-popconfirm>
                </div>
              </div>
              <el-empty v-if="!loading && draftList.length === 0" description="空空如也"></el-empty>
            </div>
          </el-tab-pane>

          <el-tab-pane v-if="isMe" label="回收站" name="recycle">
            <div class="article-list" v-loading="loading">
              <div v-for="binItem in recycleList" :key="binItem.id" class="article-item draft-item">
                <div class="content-main" style="cursor: default;">
                  <div class="a-title" style="color: #999;">{{ binItem.title || '无标题' }}</div>
                  <div class="a-summary" style="color: #bbb;">{{ binItem.summary }}</div>
                  <div class="a-meta">
                    <span style="color: #f56c6c;">将在 {{ getExpireDays(binItem.deleteTime) }} 天后自动清理</span>
                  </div>
                </div>
                <div class="action-btns">
                  <el-button type="success" link icon="RefreshLeft" @click="handleRestore(binItem.id)">恢复</el-button>
                  <el-popconfirm title="彻底删除无法找回，确定吗？" @confirm="handleHardDelete(binItem.id)">
                    <template #reference>
                      <el-button type="danger" link icon="Delete">彻底删除</el-button>
                    </template>
                  </el-popconfirm>
                </div>
              </div>
              <el-empty v-if="!loading && recycleList.length === 0" description="回收站是空的"></el-empty>
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
// 引入所有需要的 API
import { deleteArticle, getDrafts, getRecycleBin, restoreArticle, hardDeleteArticle } from '../api/article'

const route = useRoute()
const router = useRouter()

const userInfo = ref({})
const activeTab = ref(route.query.tab || 'articles')
const loading = ref(false)

const articleList = ref([])
const userList = ref([])
const favList = ref([])
const draftList = ref([]) // 草稿列表
const recycleList = ref([]) // 回收站列表

const currentUser = computed(() => {
  const str = localStorage.getItem('user')
  return str ? JSON.parse(str) : {}
})

const targetUserId = computed(() => route.params.id || currentUser.value.id)

const isMe = computed(() => {
  return String(targetUserId.value) === String(currentUser.value.id)
})

// 加载个人信息
const loadProfile = async () => {
  try {
    const id = targetUserId.value
    if(!id) return
    const res = await request.get(`/api/user/profile/${id}`)
    userInfo.value = res.data || {}
  } catch(e) {}
}

// 加载 Tab 数据
const loadTabData = async () => {
  loading.value = true
  const id = targetUserId.value

  // 清空当前列表，防止闪烁旧数据
  if (activeTab.value === 'articles') articleList.value = []
  if (activeTab.value === 'following' || activeTab.value === 'fans') userList.value = []
  if (activeTab.value === 'favorites') favList.value = []
  if (activeTab.value === 'drafts') draftList.value = []
  if (activeTab.value === 'recycle') recycleList.value = []

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
    // 【新增】加载草稿
    else if (activeTab.value === 'drafts' && isMe.value) {
      const res = await getDrafts()
      draftList.value = res.data || []
    }
    // 【新增】加载回收站
    else if (activeTab.value === 'recycle' && isMe.value) {
      const res = await getRecycleBin()
      recycleList.value = res.data || []
    }
  } catch(e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

// 监听 Tab 切换
watch(activeTab, () => { loadTabData() })

// 👇 添加监听: 如果路由参数变化，自动切换 Tab (支持深度链接)
watch(() => route.query.tab, (newTab) => {
  if (newTab) activeTab.value = newTab
})

// 跳转编辑
const toEdit = (id) => {
  router.push(`/publish?id=${id}`)
}

// 删除文章 (移入回收站)
const handleDelete = async (id) => {
  try {
    await deleteArticle(id)
    ElMessage.success('已移入回收站')
    loadTabData() // 刷新列表
    loadProfile() // 刷新统计数据
  } catch(e) {
    ElMessage.error('删除失败')
  }
}

// 恢复文章
const handleRestore = async (id) => {
  try {
    await restoreArticle(id)
    ElMessage.success('已恢复')
    loadTabData()
    loadProfile()
  } catch(e) {
    ElMessage.error('恢复失败')
  }
}

// 彻底删除
const handleHardDelete = async (id) => {
  try {
    await hardDeleteArticle(id)
    ElMessage.success('彻底删除成功')
    loadTabData()
  } catch(e) {
    ElMessage.error('操作失败')
  }
}

// 计算过期天数
const getExpireDays = (deleteTime) => {
  if(!deleteTime) return 7
  const delDate = new Date(deleteTime)
  // 假设过期时间是删除时间 + 7天
  const expireDate = new Date(delDate.getTime() + 7 * 24 * 60 * 60 * 1000)
  const diff = expireDate - new Date()
  const days = Math.ceil(diff / (1000 * 60 * 60 * 24))
  return days > 0 ? days : 0
}

// 关注操作
const handleFollow = async () => {
  if(!currentUser.value.id) return ElMessage.warning('请先登录')
  try {
    await request.post(`/api/follow/${targetUserId.value}`)
    userInfo.value.isFollowed = !userInfo.value.isFollowed
    if(userInfo.value.isFollowed) userInfo.value.fanCount++
    else userInfo.value.fanCount--
    ElMessage.success(userInfo.value.isFollowed ? '关注成功' : '已取消关注')
    if (activeTab.value === 'fans') loadTabData()
  } catch(e) {
    ElMessage.error(e.msg || '操作失败')
  }
}

const toUser = (uid) => router.push(`/user/${uid}`)
const editProfile = () => router.push('/settings')

const formatTime = (time) => {
  if(!time) return ''
  if(Array.isArray(time)) return `${time[0]}-${time[1]}-${time[2]}`
  return String(time).replace('T', ' ').substring(0, 10)
}

// 监听路由参数变化 (查看他人主页 -> 我的主页)
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
.user-center-layout { min-height: 100vh; background: #f6f6f6; padding-top: 20px; }
.nav-back { width: 1000px; margin: 0 auto 20px; cursor: pointer; display: flex; align-items: center; gap: 5px; color: #666; &:hover { color: #0066ff; } }
.main-container { width: 1000px; margin: 0 auto; }

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

.content-tabs { background: #fff; padding: 20px 30px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1); min-height: 500px; }

.article-item {
  padding: 20px 0; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; align-items: flex-start;
  .content-main {
    flex: 1; cursor: pointer;
    &:hover .a-title { color: #0066ff; }
    .a-title { font-size: 18px; font-weight: 600; color: #121212; margin-bottom: 10px; display: flex; align-items: center; gap: 8px; }
    .a-summary { font-size: 14px; color: #555; margin-bottom: 12px; line-height: 1.6; }
    .a-meta { display: flex; gap: 20px; font-size: 13px; color: #999; display: flex; align-items: center; }
  }
  .action-btns { margin-left: 20px; display: flex; align-items: center; opacity: 0; transition: opacity 0.2s; }
  &:hover .action-btns { opacity: 1; }
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