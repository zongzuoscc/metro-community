<template>
  <div class="fav-detail-layout">
    <div class="navbar-placeholder">
      <div class="nav-back" @click="$router.go(-1)">
        <el-icon><ArrowLeft /></el-icon> 返回
      </div>
    </div>

    <div class="main-container">
      <div class="folder-header">
        <div class="f-icon-box">
          <el-icon><StarFilled /></el-icon>
        </div>
        <div class="f-info">
          <h1 class="f-name">{{ folder.name }}</h1>
          <div class="f-desc">{{ folder.description || '暂无描述' }}</div>
          <div class="f-meta">
            <span>{{ articleList.length }} 篇文章</span>
            <span class="dot">·</span>
            <span>{{ folder.isPublic ? '公开' : '私密' }}</span>
            <span class="dot">·</span>
            <span>创建于 {{ formatTime(folder.createTime) }}</span>
          </div>
        </div>
      </div>

      <div class="article-list-card">
        <div v-for="article in articleList" :key="article.id" class="article-item" @click="$router.push(`/article/${article.id}`)">
          <div class="a-title">{{ article.title }}</div>
          <div class="a-summary">{{ article.summary }}</div>
          <div class="a-meta">
            <span class="author" v-if="article.authorName">{{ article.authorName }}</span>
            <span>{{ formatTime(article.createTime) }}</span>
            <span><el-icon><View /></el-icon> {{ article.viewCount }}</span>
            <span><el-icon><CaretTop /></el-icon> {{ article.likeCount }}</span>
          </div>
        </div>
        <el-empty v-if="articleList.length === 0" description="收藏夹是空的"></el-empty>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import request from '../utils/request'

const route = useRoute()
const folder = ref({})
const articleList = ref([])

const loadData = async () => {
  const id = route.params.id
  if(!id) return
  try {
    const res = await request.get(`/api/favorite/detail/${id}`)
    if(res.code === 200) {
      folder.value = res.data.folder || {}
      articleList.value = res.data.articles || []
    }
  } catch(e) {
    console.error(e)
  }
}

const formatTime = (time) => {
  if(!time) return ''
  if(Array.isArray(time)) return `${time[0]}-${time[1]}-${time[2]}`
  return String(time).substring(0, 10)
}

onMounted(() => {
  loadData()
})
</script>

<style scoped lang="scss">
.fav-detail-layout {
  min-height: 100vh; background: #f6f6f6; padding-top: 20px;
}
.nav-back {
  width: 800px; margin: 0 auto 20px; cursor: pointer; display: flex; align-items: center; gap: 5px; color: #666;
  &:hover { color: #0066ff; }
}
.main-container {
  width: 800px; margin: 0 auto;
}

.folder-header {
  background: #fff; padding: 30px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1); margin-bottom: 20px; display: flex; gap: 20px;
  .f-icon-box {
    width: 80px; height: 80px; background: #fffbe6; color: #ffb800; font-size: 40px; display: flex; align-items: center; justify-content: center; border-radius: 8px;
  }
  .f-info {
    flex: 1;
    .f-name { margin: 0 0 10px 0; font-size: 24px; color: #333; }
    .f-desc { color: #666; font-size: 14px; margin-bottom: 15px; }
    .f-meta { font-size: 13px; color: #999; display: flex; align-items: center; gap: 8px; }
  }
}

.article-list-card {
  background: #fff; padding: 0 30px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1); min-height: 300px;
}

.article-item {
  padding: 20px 0; border-bottom: 1px solid #f0f0f0; cursor: pointer;
  &:hover .a-title { color: #0066ff; }
  .a-title { font-size: 18px; font-weight: 600; color: #121212; margin-bottom: 10px; }
  .a-summary { font-size: 14px; color: #555; margin-bottom: 12px; line-height: 1.6; }
  .a-meta { display: flex; gap: 20px; font-size: 13px; color: #999; display: flex; align-items: center;
    .author { color: #333; font-weight: 500; }
  }
}

.fav-detail-layout { background: var(--paper-muted); padding-top: var(--space-5); }
.nav-back, .main-container { width: min(900px, calc(100% - 32px)); }
.nav-back:hover, .article-item:hover .a-title { color: var(--accent); }
.folder-header, .article-list-card { background: #fffdf9; border: 1px solid var(--line); border-radius: var(--radius-sm); box-shadow: none; }
.folder-header { padding: var(--space-5) 36px; }
.folder-header .f-icon-box { background: #f4e1dc; color: var(--accent); border: 1px solid #dfbbb3; border-radius: var(--radius-sm); }
.folder-header .f-info .f-name { color: var(--ink); font-family: "Songti SC", SimSun, serif; }
.article-list-card { padding: 0 36px; }
.article-item { border-bottom-color: var(--line); }
.article-item .a-title { color: var(--ink); font-family: "Songti SC", SimSun, serif; }
.article-item .a-summary { color: var(--ink-muted); }
</style>
