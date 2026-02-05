<template>
  <div class="detail-layout">
    <div class="navbar-placeholder">
      <div class="nav-back" @click="$router.push('/home')">
        <el-icon><ArrowLeft /></el-icon> 返回首页
      </div>
    </div>

    <div class="main-container">
      <div class="left-column">

        <div class="article-card">
          <h1 class="article-title">{{ article.title }}</h1>
          <div class="article-meta">
            <span class="meta-item author pointer" @click="toAuthorProfile">{{ article.authorName }}</span>
            <span class="meta-item time">{{ formatTime(article.createTime) }}</span>
            <span class="meta-item views">阅读 {{ article.viewCount }}</span>
          </div>
          <v-md-preview :text="article.content"></v-md-preview>

          <div class="article-actions">
            <el-button
                :type="isLiked ? 'primary' : 'default'"
                :plain="!isLiked"
                round
                size="large"
                @click="handleLike"
                :loading="likeLoading"
            >
              <el-icon style="margin-right: 5px"><CaretTop /></el-icon>
              {{ isLiked ? '已赞同' : '赞同' }} {{ article.likeCount || 0 }}
            </el-button>

            <el-button
                :type="isCollected ? 'warning' : 'default'"
                :plain="!isCollected"
                round
                size="large"
                @click="openFavoriteDialog"
            >
              <el-icon style="margin-right: 5px"><StarFilled v-if="isCollected" /><Star v-else /></el-icon>
              {{ isCollected ? '已收藏' : '收藏' }}
            </el-button>
          </div>
        </div>

        <div class="comment-card">
          <div class="comment-header">
            <span>{{ commentList.length }} 条评论</span>
          </div>

          <div class="comment-input-wrapper">
            <el-avatar :size="40" :src="currentUserAvatar" icon="UserFilled" class="my-avatar"></el-avatar>
            <div class="input-box">
              <el-input
                  v-model="commentContent"
                  type="textarea"
                  :rows="3"
                  :placeholder="replyTarget ? `回复 @${replyTarget.username}...` : '写下你的评论...'"
                  resize="none"
              />
              <div class="input-actions">
                        <span v-if="replyTarget" class="cancel-reply" @click="cancelReply">
                            取消回复
                        </span>
                <el-button type="primary" size="small" @click="submitComment" :loading="commentLoading">
                  发布
                </el-button>
              </div>
            </div>
          </div>

          <div class="comment-list">
            <div v-for="item in commentList" :key="item.id" class="comment-item">
              <el-avatar
                  :size="40"
                  :src="item.avatar"
                  icon="UserFilled"
                  class="u-avatar pointer"
                  @click="toUserProfile(item.userId)"
              ></el-avatar>

              <div class="content-box">
                <div class="u-name pointer" @click="toUserProfile(item.userId)">{{ item.username }}</div>
                <div class="u-content">{{ item.content }}</div>
                <div class="u-stat">
                  <span class="time">{{ formatTime(item.createTime) }}</span>
                  <span
                      class="action-btn"
                      :class="{ 'liked': commentLikedSet.has(item.id) }"
                      @click="handleCommentLike(item)"
                  >
                                <el-icon><CaretTop /></el-icon> {{ item.likeCount || 0 }}
                            </span>
                  <span class="action-btn" @click="replyTo(item)">
                                <el-icon><ChatDotRound /></el-icon> 回复
                            </span>
                </div>

                <div v-if="item.children && item.children.length > 0" class="sub-comment-list">
                  <div v-for="child in item.children" :key="child.id" class="sub-item">
                    <div class="sub-content">
                      <span class="sub-user pointer" @click="toUserProfile(child.userId)">{{ child.username }}</span>
                      <span v-if="child.targetUsername" class="reply-text"> 回复 </span>
                      <span v-if="child.targetUsername" class="sub-user pointer" @click="toUserProfile(child.targetUserId)">@{{ child.targetUsername }}</span>
                      <span>：{{ child.content }}</span>
                    </div>
                    <div class="u-stat">
                      <span class="time">{{ formatTime(child.createTime) }}</span>
                      <span
                          class="action-btn"
                          :class="{ 'liked': commentLikedSet.has(child.id) }"
                          @click="handleCommentLike(child)"
                      >
                                        <el-icon><CaretTop /></el-icon> {{ child.likeCount || 0 }}
                                    </span>
                      <span class="action-btn" @click="replyTo(child, item.id)">
                                        <el-icon><ChatDotRound /></el-icon> 回复
                                    </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <el-empty v-if="commentList.length === 0" description="暂无评论，快来抢沙发~" :image-size="100"></el-empty>
          </div>
        </div>
      </div>

      <div class="sidebar-column">
        <div class="author-card">
          <div class="author-header">
            <div class="author-link" @click="toAuthorProfile">
              <el-avatar :size="50" icon="UserFilled" :src="article.authorAvatar" class="author-avatar"></el-avatar>
              <div class="author-info">
                <div class="name">{{ article.authorName }}</div>
                <div class="bio" :title="article.authorIntro">
                  {{ article.authorIntro || '这位作者很懒，什么都没写' }}
                </div>
              </div>
            </div>
          </div>
          <div class="stat-row">
            <div class="stat-item">
              <div class="num">{{ article.authorArticleCount || 0 }}</div>
              <div class="label">文章</div>
            </div>
            <div class="stat-item">
              <div class="num">{{ article.authorTotalLikes || 0 }}</div>
              <div class="label">获赞</div>
            </div>
          </div>
          <el-button
              v-if="currentUser.id !== article.authorId"
              class="follow-btn"
              :type="isFollowed ? 'info' : 'primary'"
              :plain="isFollowed"
              block
              @click="handleFollow"
          >
            {{ isFollowed ? '已关注' : '+ 关注' }}
          </el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="favDialogVisible" title="添加到收藏夹" width="400px" align-center>
      <div class="fav-list">
        <div
            v-for="folder in favoriteFolders"
            :key="folder.id"
            class="fav-item"
            @click="toggleFavorite(folder)"
        >
          <div class="fav-info">
            <div class="fav-name">{{ folder.name }}</div>
            <div class="fav-count">{{ folder.count }} 篇文章</div>
          </div>
          <el-icon class="add-icon"><Plus /></el-icon>
        </div>
      </div>
      <div class="create-folder-box">
        <el-button v-if="!showCreateInput" text type="primary" icon="Plus" @click="showCreateInput = true">
          新建收藏夹
        </el-button>
        <div v-else class="create-input">
          <el-input v-model="newFolderName" placeholder="请输入收藏夹名称" size="small" />
          <div class="create-actions">
            <el-button size="small" @click="showCreateInput = false">取消</el-button>
            <el-button type="primary" size="small" @click="createFolder">创建</el-button>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../utils/request'

const route = useRoute()
const router = useRouter() // 【新增】
const article = ref({})
const isLiked = ref(false)
const likeLoading = ref(false)
const isFollowed = ref(false)

// 收藏相关
const isCollected = ref(false)
const favDialogVisible = ref(false)
const favoriteFolders = ref([])
const showCreateInput = ref(false)
const newFolderName = ref('')

// 评论相关
const commentList = ref([])
const commentContent = ref('')
const commentLoading = ref(false)
const replyTarget = ref(null)
const commentLikedSet = ref(new Set())

const currentUser = computed(() => {
  const userStr = localStorage.getItem('user')
  return userStr ? JSON.parse(userStr) : {}
})
const currentUserAvatar = computed(() => currentUser.value.avatar)

// 跳转到作者个人主页
const toAuthorProfile = () => {
  if (article.value.authorId) {
    router.push(`/user/${article.value.authorId}`)
  }
}
// 跳转到任意用户主页
const toUserProfile = (userId) => {
  if (userId) router.push(`/user/${userId}`)
}

const loadDetail = async () => {
  const id = route.params.id
  if(!id) return
  try {
    const res = await request.get(`/api/article/detail/${id}`)
    article.value = res.data || {}

    checkLikeStatus(id)
    checkCollectStatus(id)
    if (article.value.authorId) {
      checkFollowStatus(article.value.authorId)
    }

    loadComments(id)
  } catch(e) {
    console.error("加载详情失败", e)
  }
}

// ---------------- 收藏逻辑 ----------------
const checkCollectStatus = async (articleId) => {
  const token = localStorage.getItem('token')
  if(!token) return
  try {
    const res = await request.get(`/api/favorite/check?articleId=${articleId}`)
    isCollected.value = res.data
  } catch(e) {}
}

const openFavoriteDialog = async () => {
  if(!currentUser.value.id) return ElMessage.warning('请先登录')
  await loadFavoriteFolders()
  favDialogVisible.value = true
}

const loadFavoriteFolders = async () => {
  try {
    const res = await request.get('/api/favorite/list')
    favoriteFolders.value = res.data || []
  } catch(e) {
    ElMessage.error('加载收藏夹失败')
  }
}

const createFolder = async () => {
  if(!newFolderName.value.trim()) return ElMessage.warning('请输入名称')
  try {
    await request.post(`/api/favorite/folder?name=${newFolderName.value}`)
    ElMessage.success('创建成功')
    newFolderName.value = ''
    showCreateInput.value = false
    loadFavoriteFolders()
  } catch(e) {
    ElMessage.error(e.msg || '创建失败')
  }
}

const toggleFavorite = async (folder) => {
  try {
    await request.post(`/api/favorite/toggle?articleId=${article.value.id}&folderId=${folder.id}`)
    ElMessage.success('操作成功')
    loadFavoriteFolders()
    checkCollectStatus(article.value.id)
  } catch(e) {
    ElMessage.error('操作失败')
  }
}

// ---------------- 评论逻辑 ----------------
const loadComments = async (articleId) => {
  try {
    const res = await request.get(`/api/comment/list/${articleId}`)
    commentList.value = res.data || []
  } catch(e) {}
}

const handleCommentLike = async (comment) => {
  if (!currentUser.value.id) return ElMessage.warning('请先登录')
  try {
    await request.post(`/api/like?targetId=${comment.id}&targetType=2`)
    if (commentLikedSet.value.has(comment.id)) {
      commentLikedSet.value.delete(comment.id)
      comment.likeCount = (comment.likeCount || 0) - 1
    } else {
      commentLikedSet.value.add(comment.id)
      comment.likeCount = (comment.likeCount || 0) + 1
    }
  } catch(e) {}
}

const replyTo = (target, rootId) => {
  commentContent.value = ''
  replyTarget.value = {
    id: target.userId,
    username: target.username,
    parentId: rootId || target.id
  }
  document.querySelector('.comment-input-wrapper').scrollIntoView({ behavior: 'smooth' })
}

const cancelReply = () => {
  replyTarget.value = null
  commentContent.value = ''
}

const submitComment = async () => {
  if(!currentUser.value.id) return ElMessage.warning('请先登录')
  if(!commentContent.value.trim()) return ElMessage.warning('写点什么吧')
  commentLoading.value = true
  try {
    const payload = {
      articleId: article.value.id,
      content: commentContent.value,
      parentId: replyTarget.value ? replyTarget.value.parentId : 0,
      targetUserId: replyTarget.value ? replyTarget.value.id : null
    }
    const res = await request.post('/api/comment/publish', payload)
    if(res.code === 200) {
      ElMessage.success('发布成功')
      cancelReply()
      loadComments(article.value.id)
    } else {
      ElMessage.warning(res.msg || '发布失败')
    }
  } catch(e) {} finally {
    commentLoading.value = false
  }
}

// ---------------- 点赞与关注 ----------------
const checkLikeStatus = async (targetId) => {
  const token = localStorage.getItem('token')
  if(!token) return
  try {
    const res = await request.get(`/api/like/check?targetId=${targetId}&targetType=1`)
    isLiked.value = res.data
  } catch(e) {}
}

const checkFollowStatus = async (authorId) => {
  const token = localStorage.getItem('token')
  if(!token) return
  try {
    const res = await request.get(`/api/follow/check/${authorId}`)
    isFollowed.value = res.data
  } catch(e) {}
}

const handleLike = async () => {
  const token = localStorage.getItem('token')
  if(!token) return ElMessage.warning('请先登录')
  if(likeLoading.value) return
  likeLoading.value = true
  try {
    await request.post(`/api/like?targetId=${article.value.id}&targetType=1`)
    isLiked.value = !isLiked.value
    if(isLiked.value) {
      article.value.likeCount = (article.value.likeCount || 0) + 1
      article.value.authorTotalLikes = (article.value.authorTotalLikes || 0) + 1
    } else {
      article.value.likeCount = (article.value.likeCount || 0) - 1
      article.value.authorTotalLikes = (article.value.authorTotalLikes || 0) - 1
    }
  } catch(e) {} finally {
    likeLoading.value = false
  }
}

const handleFollow = async () => {
  const token = localStorage.getItem('token')
  if(!token) return ElMessage.warning('请先登录')
  try {
    const authorId = article.value.authorId
    const res = await request.post(`/api/follow/${authorId}`)
    if (res.code !== 200) {
      ElMessage.warning(res.msg || '操作失败')
      return
    }
    isFollowed.value = !isFollowed.value
    ElMessage.success(isFollowed.value ? '关注成功' : '已取消关注')
  } catch(e) {}
}

const formatTime = (time) => {
  if(!time) return ''
  if(Array.isArray(time)) return `${time[0]}-${time[1]}-${time[2]} ${time[3]}:${time[4]}`
  return String(time).replace('T', ' ').substring(0, 16)
}

onMounted(() => {
  loadDetail()
})
</script>

<style scoped lang="scss">
.detail-layout {
  min-height: 100vh;
  background-color: #f6f6f6;
  padding-top: 20px;
}
.nav-back {
  width: 1000px; margin: 0 auto 20px; cursor: pointer; display: flex; align-items: center; gap: 5px; color: #666; font-size: 14px;
  &:hover { color: #0066ff; }
}
.main-container {
  width: 1000px; margin: 0 auto; display: flex; align-items: flex-start; gap: 12px;
}
.left-column { width: 694px; }

/* 通用点击样式 */
.pointer { cursor: pointer; &:hover { color: #0066ff; } }

/* 收藏弹窗 */
.fav-list {
  max-height: 300px; overflow-y: auto; margin-bottom: 20px;
  .fav-item {
    display: flex; align-items: center; justify-content: space-between;
    padding: 12px 15px; border-radius: 4px; cursor: pointer; transition: background 0.2s;
    border-bottom: 1px solid #f0f0f0;
    &:hover { background: #f5f7fa; }
    .fav-info {
      .fav-name { font-weight: 500; color: #333; margin-bottom: 2px; }
      .fav-count { font-size: 12px; color: #999; }
    }
    .add-icon { color: #999; font-size: 16px; }
  }
}
.create-folder-box {
  border-top: 1px solid #eee; padding-top: 15px;
  .create-input {
    display: flex; gap: 10px; flex-direction: column;
    .create-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 5px; }
  }
}

/* 复用样式 */
.article-card {
  background: #fff; padding: 30px 40px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1); min-height: 400px; margin-bottom: 20px;
  .article-title { font-size: 32px; font-weight: 700; margin-bottom: 20px; line-height: 1.4; color: #121212; }
  .article-meta { display: flex; align-items: center; gap: 20px; color: #8590a6; font-size: 14px; margin-bottom: 30px; .author { font-weight: 600; color: #444; } }
  .article-actions { margin-top: 50px; display: flex; gap: 20px; }
}
.comment-card {
  background: #fff; padding: 20px 40px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1);
  .comment-header { font-weight: 600; font-size: 18px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between; }
  .comment-input-wrapper {
    display: flex; gap: 15px; margin-bottom: 30px;
    .my-avatar { flex-shrink: 0; }
    .input-box {
      flex: 1;
      .input-actions { margin-top: 10px; text-align: right; .cancel-reply { font-size: 13px; color: #8590a6; margin-right: 15px; cursor: pointer; &:hover { color: #0066ff; } } }
    }
  }
  .comment-list {
    .comment-item {
      display: flex; gap: 15px; padding: 20px 0; border-top: 1px solid #f0f2f7;
      .u-avatar { flex-shrink: 0; }
      .content-box {
        flex: 1;
        .u-name { font-weight: 600; font-size: 15px; color: #444; margin-bottom: 8px; }
        .u-content { font-size: 15px; color: #121212; line-height: 1.6; margin-bottom: 8px; }
        .u-stat {
          display: flex; align-items: center; gap: 20px; font-size: 13px; color: #8590a6;
          .action-btn {
            cursor: pointer; display: flex; align-items: center; gap: 4px; transition: all 0.2s;
            &:hover { color: #0066ff; opacity: 0.8; }
            &.liked { color: #0066ff; font-weight: 600; }
          }
        }
        .sub-comment-list {
          background: #f6f6f6; padding: 15px; border-radius: 4px; margin-top: 15px;
          .sub-item {
            margin-bottom: 12px; &:last-child { margin-bottom: 0; }
            .sub-content { font-size: 14px; line-height: 1.6; margin-bottom: 5px; }
            .sub-user { font-weight: 600; color: #444; }
            .reply-text { color: #8590a6; margin: 0 5px; }
          }
        }
      }
    }
  }
}
.sidebar-column {
  width: 294px;
  .author-card {
    background: #fff; padding: 20px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1);
    .author-header {
      .author-link { display: flex; align-items: center; gap: 15px; margin-bottom: 20px; cursor: pointer; transition: opacity 0.2s; &:hover { opacity: 0.8; } }
      .author-avatar { background: #f2f3f5; }
      .name { font-weight: 600; font-size: 16px; margin-bottom: 4px; }
      .bio { font-size: 13px; color: #8590a6; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 180px; }
    }
    .stat-row { display: flex; justify-content: space-around; margin-bottom: 20px; .stat-item { text-align: center; } .num { font-weight: 600; font-size: 16px; color: #121212; } .label { font-size: 12px; color: #8590a6; } }
    .follow-btn { width: 100%; }
  }
}

:deep(.vuepress-markdown-body img) { display: block; margin: 20px auto; max-width: 100%; border-radius: 4px; box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1); }
:deep(.vuepress-markdown-body) { padding: 0; color: #121212; }
</style>