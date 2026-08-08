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
                    <el-tag v-if="article.status === 2" size="small" type="warning" effect="dark" style="margin-left: 8px;">审核中</el-tag>
                    <el-tag v-else-if="article.status === 3" size="small" type="danger" effect="dark" style="margin-left: 8px;">未通过</el-tag>
                    <el-tag v-else-if="article.status === 0" size="small" type="info" effect="plain" style="margin-left: 8px;">草稿</el-tag>
                    <el-tag v-else-if="article.status === 1 && isMe" size="small" type="success" effect="plain" style="margin-left: 8px;">已发布</el-tag>
                  </div>
                  <div class="a-summary">{{ article.summary }}</div>
                  <div class="a-meta">
                    <span>{{ formatTime(article.createTime) }}</span>
                    <span><el-icon><View /></el-icon> {{ article.viewCount }}</span>
                    <span><el-icon><CaretTop /></el-icon> {{ article.likeCount }}</span>
                    <span v-if="isMe && article.status === 3" style="color: #f56c6c; margin-left: 10px;">(请编辑修改后重新发布)</span>
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
                  <div class="a-title">
                    {{ draft.title || '无标题草稿' }}
                    <el-tag size="small" type="info" style="margin-left: 8px;">草稿</el-tag>
                  </div>
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
// 引入所有需要的 API，包括新加入的 getMyArticles
import {
  deleteArticle,
  getDrafts,
  getRecycleBin,
  restoreArticle,
  hardDeleteArticle,
  getMyArticles // 【核心引入】
} from '../api/article'

const route = useRoute()
const router = useRouter()

const userInfo = ref({})
const activeTab = ref(route.query.tab || 'articles')
const loading = ref(false)

const articleList = ref([])
const userList = ref([])
const favList = ref([])
const draftList = ref([])
const recycleList = ref([])

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

  // 清空当前列表
  if (activeTab.value === 'articles') articleList.value = []
  if (activeTab.value === 'following' || activeTab.value === 'fans') userList.value = []
  if (activeTab.value === 'favorites') favList.value = []
  if (activeTab.value === 'drafts') draftList.value = []
  if (activeTab.value === 'recycle') recycleList.value = []

  try {
    if (activeTab.value === 'articles') {
      if (isMe.value) {
        // 【修改点】如果是看自己的主页，调用 getMyArticles 获取全部状态的文章
        const res = await getMyArticles({ page: 1, size: 20 })
        articleList.value = res.data.records || []
      } else {
        // 如果是看别人主页，调用旧接口（只看已发布）
        const res = await request.get(`/api/article/user/${id}?page=1&size=20`)
        articleList.value = res.data.records || []
      }
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
    else if (activeTab.value === 'drafts' && isMe.value) {
      const res = await getDrafts()
      draftList.value = res.data || []
    }
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

watch(() => route.query.tab, (newTab) => {
  if (newTab) activeTab.value = newTab
})

const toEdit = (id) => {
  router.push(`/publish?id=${id}`)
}

const handleDelete = async (id) => {
  try {
    await deleteArticle(id)
    ElMessage.success('已移入回收站')
    loadTabData()
    loadProfile()
  } catch(e) {
    ElMessage.error('删除失败')
  }
}

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

const handleHardDelete = async (id) => {
  try {
    await hardDeleteArticle(id)
    ElMessage.success('彻底删除成功')
    loadTabData()
  } catch(e) {
    ElMessage.error('操作失败')
  }
}

const getExpireDays = (deleteTime) => {
  if(!deleteTime) return 7
  const delDate = new Date(deleteTime)
  const expireDate = new Date(delDate.getTime() + 7 * 24 * 60 * 60 * 1000)
  const diff = expireDate - new Date()
  const days = Math.ceil(diff / (1000 * 60 * 60 * 24))
  return days > 0 ? days : 0
}

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

.user-center-layout { background: var(--paper-muted); padding-top: var(--space-5); }
.nav-back, .article-item .content-main:hover .a-title { color: var(--accent); }
.nav-back, .main-container { width: min(1180px, calc(100% - 32px)); }
.profile-header, .content-tabs { background: #fffdf9; border: 1px solid var(--line); border-radius: var(--radius-sm); box-shadow: none; }
.profile-header { padding: 36px 42px; }
.profile-header .info-row .info-content .u-name { color: var(--ink); font-family: "Songti SC", SimSun, serif; font-size: 32px; }
.profile-header .stat-row .stat-item:hover .num { color: var(--accent); }
.profile-header .stat-row .stat-item .num,
.article-item .content-main .a-title { color: var(--ink); }
.content-tabs { padding: var(--space-5) 42px; }
.article-item, .user-item { border-bottom-color: var(--line); }
.article-item .content-main .a-summary { color: var(--ink-muted); }
.fav-grid .fav-card { border-color: var(--line); border-radius: var(--radius-sm); background: #fffaf3; }
.fav-grid .fav-card:hover { border-color: var(--accent); box-shadow: none; transform: translateY(-1px); }
.fav-grid .fav-card .f-icon { color: var(--accent); }
@media (max-width: 640px) { .profile-header, .content-tabs { padding: var(--space-4); } .profile-header .stat-row { gap: var(--space-5); padding-left: 0; } .fav-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
