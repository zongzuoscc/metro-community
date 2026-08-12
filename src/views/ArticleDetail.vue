<template>
  <div class="detail-layout">
    <div class="navbar-placeholder">
      <div class="nav-back" @click="$router.push('/home')">
        <el-icon><ArrowLeft /></el-icon> 返回首页
      </div>
    </div>

    <div class="main-container" v-loading="loading">

      <div class="left-column">

        <div class="article-card">
          <div class="article-header-row">
            <h1 class="article-title">{{ article.title }}</h1>

            <div class="more-actions">
              <el-dropdown trigger="click" @command="handleArticleCommand">
                <el-icon class="action-icon"><MoreFilled /></el-icon>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="report" v-if="!isMe">举报文章</el-dropdown-item>
                    <el-dropdown-item command="edit" v-if="isMe">编辑文章</el-dropdown-item>
                    <el-dropdown-item command="delete" v-if="isMe" divided style="color: #f56c6c;">删除文章</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <div class="tags-row" v-if="article.tagList && article.tagList.length > 0">
            <el-tag
                v-for="tag in article.tagList"
                :key="tag"
                class="tag-item"
                effect="plain"
                round
                @click="toTagSearch(tag)"
            >
              # {{ tag }}
            </el-tag>
          </div>

          <div class="article-meta">
            <span class="meta-item author pointer" @click="toAuthorProfile">
              {{ article.authorName }}
            </span>
            <span class="meta-item time">{{ formatTime(article.createTime) }}</span>
            <span class="meta-item views">阅读 {{ article.viewCount || 0 }}</span>
            <span class="meta-item views" v-if="isMe">
               <el-tag size="small" type="info">本文作者</el-tag>
            </span>
          </div>

          <div class="article-cover" v-if="article.cover">
            <img :src="article.cover" alt="封面图" />
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

        <div class="comment-card" id="comment-area">
          <div class="comment-header">
            <span>{{ article.commentCount || 0 }} 条评论</span>
          </div>

          <div class="comment-input-wrapper">
            <el-avatar :size="40" :src="currentUser.avatar" icon="UserFilled" class="my-avatar"></el-avatar>
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
                <div class="u-name pointer" @click="toUserProfile(item.userId)">
                  {{ item.username }}
                  <span v-if="item.userId === article.authorId" class="author-tag">作者</span>
                </div>

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

                  <span
                      class="action-btn report-btn"
                      v-if="currentUser.id && String(currentUser.id) !== String(item.userId)"
                      @click="openReport('comment', item.id)"
                  >
                      <el-icon><Warning /></el-icon> 举报
                  </span>

                  <el-popconfirm
                      v-if="canDelete(item)"
                      title="确定删除这条评论吗？"
                      @confirm="handleDelete(item.id)"
                  >
                    <template #reference>
                          <span class="action-btn delete-btn">
                              <el-icon><Delete /></el-icon> 删除
                          </span>
                    </template>
                  </el-popconfirm>
                </div>

                <div v-if="item.children && item.children.length > 0" class="sub-comment-list">
                  <div v-for="child in item.children" :key="child.id" class="sub-item">
                    <div class="sub-content">
                      <span class="sub-user pointer" @click="toUserProfile(child.userId)">
                          {{ child.username }}
                          <span v-if="child.userId === article.authorId" class="author-tag mini">作者</span>
                      </span>

                      <template v-if="child.targetUsername">
                        <span class="reply-text"> 回复 </span>
                        <span class="sub-user pointer" @click="toUserProfile(child.targetUserId)">
                              @{{ child.targetUsername }}
                          </span>
                      </template>

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

                      <span
                          class="action-btn report-btn"
                          v-if="currentUser.id && String(currentUser.id) !== String(child.userId)"
                          @click="openReport('comment', child.id)"
                      >
                          <el-icon><Warning /></el-icon> 举报
                      </span>

                      <el-popconfirm
                          v-if="canDelete(child)"
                          title="确定删除这条回复吗？"
                          @confirm="handleDelete(child.id)"
                      >
                        <template #reference>
                              <span class="action-btn delete-btn">
                                  <el-icon><Delete /></el-icon> 删除
                              </span>
                        </template>
                      </el-popconfirm>
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
              v-if="!isMe"
              class="follow-btn"
              :type="isFollowed ? 'info' : 'primary'"
              :plain="isFollowed"
              block
              @click="handleFollow"
          >
            {{ isFollowed ? '已关注' : '+ 关注' }}
          </el-button>
          <el-button v-if="!isMe" style="width: 100%; margin: 10px 0 0 0;" plain @click="$router.push(`/chat?to=${article.authorId}`)">
            私信作者
          </el-button>
        </div>

        <div class="similar-card" v-if="similarArticles.length > 0">
          <div class="similar-header">相关推荐</div>
          <div class="similar-list">
            <div
                v-for="sim in similarArticles"
                :key="sim.id"
                class="similar-item pointer"
                @click="toSimilarArticle(sim.id)"
            >
              <div class="sim-title" :title="sim.title">{{ sim.title }}</div>
              <div class="sim-meta">{{ sim.viewCount || 0 }} 阅读 · {{ formatTimeShort(sim.createTime) }}</div>
            </div>
          </div>
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
          <el-icon class="add-icon" v-if="!folder.isCollected"><Plus /></el-icon>
          <el-icon class="add-icon" v-else color="#67C23A"><Check /></el-icon>
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

    <el-dialog title="举报内容" v-model="reportVisible" width="400px" align-center>
      <el-form label-position="top">
        <el-form-item label="请选择举报理由">
          <el-radio-group v-model="reportReason" class="report-radio">
            <el-radio label="垃圾广告">垃圾广告</el-radio>
            <el-radio label="违规内容">违规内容 (色情/暴力/政治)</el-radio>
            <el-radio label="恶意攻击">恶意攻击/谩骂</el-radio>
            <el-radio label="其他原因">其他原因</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reportVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmReport">提交</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../utils/request'

import { getArticleDetail, deleteArticle, getSimilarArticles } from '../api/article'
import { reportQualifiedView } from '../api/recommendation'
import { getCommentList, publishComment, deleteComment } from '../api/comment'
import { submitReport } from '../api/report'
import { createQualifiedArticleView } from '../utils/qualifiedArticleView'
import { createLatestRequestGuard } from '../utils/latestRequestGuard'
import { clearAgentPageContext, setAgentPageContext } from '../composables/useAgentPageContext'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
let qualifiedViewTracker = null
const detailRequestGuard = createLatestRequestGuard()

// 文章相关
const article = ref({})
const isLiked = ref(false)
const likeLoading = ref(false)
const isFollowed = ref(false)

// 相似文章数据
const similarArticles = ref([])

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

// 举报相关
const reportVisible = ref(false)
const reportReason = ref('')
const reportTarget = ref({ id: null, type: 1 }) // type: 1文章, 2评论

// 用户信息
const currentUser = computed(() => {
  const userStr = localStorage.getItem('user')
  return userStr ? JSON.parse(userStr) : {}
})
const isMe = computed(() => {
  return String(currentUser.value.id) === String(article.value.authorId)
})

const routeExposureId = () => {
  const value = Array.isArray(route.query.exposureId)
    ? route.query.exposureId[0]
    : route.query.exposureId
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return undefined
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined
}

const resetQualifiedView = (articleId) => {
  if (!currentUser.value.id || !localStorage.getItem('token')) return
  if (!qualifiedViewTracker) {
    qualifiedViewTracker = createQualifiedArticleView({
      report: reportQualifiedView,
      onError: (error) => console.warn('有效阅读上报失败', error)
    })
    qualifiedViewTracker.start(articleId, routeExposureId())
    return
  }
  qualifiedViewTracker.reset(articleId, routeExposureId())
}

const isCurrentArticleRequest = (requestToken, articleId) => {
  return requestToken.isCurrent() && String(route.params.id) === String(articleId)
}

// 1. 初始化加载
const loadDetail = async () => {
  const id = route.params.id
  if(!id) return
  detailRequestGuard.invalidate()
  const requestToken = detailRequestGuard.capture()
  qualifiedViewTracker?.reset(null, undefined)
  loading.value = true

  try {
    const res = await getArticleDetail(id)
    if (!requestToken.isCurrent() || String(route.params.id) !== String(id)) return
    article.value = res.data || {}
    setAgentPageContext({
      kind: 'article',
      articleId: article.value.id,
      title: article.value.title,
    })
    if (article.value.id) resetQualifiedView(article.value.id)

    // 并行检查状态
    checkLikeStatus(id, requestToken)
    checkCollectStatus(id, requestToken)
    if (article.value.authorId) {
      checkFollowStatus(article.value.authorId, id, requestToken)
    }

    // 加载评论
    loadComments(id, requestToken)

    // 加载相似文章
    loadSimilarArticles(id, requestToken)

  } catch(e) {
    if (requestToken.isCurrent() && String(route.params.id) === String(id)) {
      ElMessage.error("加载详情失败或文章已删除")
      router.push('/home')
    }
  } finally {
    if (requestToken.isCurrent() && String(route.params.id) === String(id)) loading.value = false
  }
}

// 获取相似文章逻辑
const loadSimilarArticles = async (id, requestToken = detailRequestGuard.capture()) => {
  try {
    const res = await getSimilarArticles(id)
    if (!isCurrentArticleRequest(requestToken, id)) return
    similarArticles.value = res.data || []
  } catch(e) {
    if (isCurrentArticleRequest(requestToken, id)) console.error('获取相似文章失败', e)
  }
}

// 点击相似文章时的跳转处理
const toSimilarArticle = (id) => {
  router.push(`/article/${id}`)
  // 让页面平滑滚动回顶部
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

// 监听路由参数变化，当用户点击右侧推荐文章时，不刷新网页直接重载数据
watch(
    () => route.params.id,
    (newId) => {
      // 确保当前还在文章详情页，且 id 发生了变化
      if (newId && route.path.startsWith('/article/')) {
        loadDetail()
      }
    }
)

// 标签跳转
const toTagSearch = (tag) => {
  router.push({ path: '/home', query: { q: tag } })
}

// ---------------- 文章操作下拉菜单 ----------------
const handleArticleCommand = (cmd) => {
  if (cmd === 'report') {
    openReport('article', article.value.id)
  } else if (cmd === 'edit') {
    router.push(`/publish?id=${article.value.id}`)
  } else if (cmd === 'delete') {
    ElMessageBox.confirm('确定删除文章吗？删除后将移入回收站。', '提示', { type: 'warning' }).then(async () => {
      try {
        await deleteArticle(article.value.id)
        ElMessage.success('已移入回收站')
        router.push('/home')
      } catch(e) {
        ElMessage.error('删除失败')
      }
    })
  }
}

// ---------------- 举报逻辑 ----------------
const openReport = (typeStr, id) => {
  if (!currentUser.value.id) return ElMessage.warning('请先登录')
  reportTarget.value.id = id
  reportTarget.value.type = typeStr === 'article' ? 1 : 2
  reportReason.value = ''
  reportVisible.value = true
}

const confirmReport = async () => {
  if (!reportReason.value) return ElMessage.warning('请选择举报理由')
  try {
    await submitReport({
      targetId: reportTarget.value.id,
      targetType: reportTarget.value.type,
      reason: reportReason.value
    })
    ElMessage.success('举报已提交，我们会尽快处理')
    reportVisible.value = false
  } catch(e) {
  }
}

// ---------------- 评论核心逻辑 ----------------

const loadComments = async (articleId = article.value.id, requestToken = detailRequestGuard.capture()) => {
  try {
    const res = await getCommentList(articleId)
    if (!isCurrentArticleRequest(requestToken, articleId)) return
    commentList.value = res.data || []
  } catch(e) {}
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

    const res = await publishComment(payload)
    if(res.code === 200) {
      ElMessage.success('发布成功')
      cancelReply()
      loadComments()
      article.value.commentCount = (article.value.commentCount || 0) + 1
    } else {
      ElMessage.warning(res.msg || '发布失败')
    }
  } catch(e) {
    ElMessage.error('发布失败')
  } finally {
    commentLoading.value = false
  }
}

const handleDelete = async (id) => {
  try {
    await deleteComment(id)
    ElMessage.success('删除成功')
    loadComments()
    if (article.value.commentCount > 0) {
      article.value.commentCount--
    }
  } catch(e) {
    ElMessage.error(e.msg || '删除失败')
  }
}

const canDelete = (comment) => {
  const uid = currentUser.value.id
  if (!uid) return false
  return String(comment.userId) === String(uid) || String(article.value.authorId) === String(uid)
}

const replyTo = (target, rootId) => {
  commentContent.value = ''
  replyTarget.value = {
    id: target.userId,
    username: target.username,
    parentId: rootId || target.id
  }
  const inputEl = document.querySelector('.comment-input-wrapper')
  if(inputEl) inputEl.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

const cancelReply = () => {
  replyTarget.value = null
  commentContent.value = ''
}

const handleCommentLike = async (comment) => {
  if (!currentUser.value.id) return ElMessage.warning('请先登录')
  try {
    await request.post(`/api/like?targetId=${comment.id}&targetType=2`)
    if (commentLikedSet.value.has(comment.id)) {
      commentLikedSet.value.delete(comment.id)
      comment.likeCount = Math.max(0, (comment.likeCount || 0) - 1)
    } else {
      commentLikedSet.value.add(comment.id)
      comment.likeCount = (comment.likeCount || 0) + 1
    }
  } catch(e) {}
}

// ---------------- 收藏逻辑 ----------------
const checkCollectStatus = async (articleId, requestToken = detailRequestGuard.capture()) => {
  if(!currentUser.value.id) return
  try {
    const res = await request.get(`/api/favorite/check?articleId=${articleId}`)
    if (!isCurrentArticleRequest(requestToken, articleId)) return
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
    folder.isCollected = !folder.isCollected
    checkCollectStatus(article.value.id)
  } catch(e) {
    ElMessage.error('操作失败')
  }
}

// ---------------- 点赞与关注 (文章) ----------------
const checkLikeStatus = async (targetId, requestToken = detailRequestGuard.capture()) => {
  if(!currentUser.value.id) return
  try {
    const res = await request.get(`/api/like/check?targetId=${targetId}&targetType=1`)
    if (!isCurrentArticleRequest(requestToken, targetId)) return
    isLiked.value = res.data
  } catch(e) {}
}

const checkFollowStatus = async (authorId, articleId = article.value.id,
                                  requestToken = detailRequestGuard.capture()) => {
  if(!currentUser.value.id) return
  try {
    const res = await request.get(`/api/follow/check/${authorId}`)
    if (!isCurrentArticleRequest(requestToken, articleId)) return
    isFollowed.value = res.data
  } catch(e) {}
}

const handleLike = async () => {
  if(!currentUser.value.id) return ElMessage.warning('请先登录')
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
  if(!currentUser.value.id) return ElMessage.warning('请先登录')
  try {
    const authorId = article.value.authorId
    const res = await request.post(`/api/follow/${authorId}`)
    isFollowed.value = !isFollowed.value
    ElMessage.success(isFollowed.value ? '关注成功' : '已取消关注')
  } catch(e) {
    ElMessage.error(e.msg || '操作失败')
  }
}

// ---------------- 路由跳转 ----------------
const toAuthorProfile = () => {
  if (article.value.authorId) router.push(`/user/${article.value.authorId}`)
}
const toUserProfile = (userId) => {
  if (userId) router.push(`/user/${userId}`)
}
const formatTime = (time) => {
  if(!time) return ''
  if(Array.isArray(time)) return `${time[0]}-${time[1]}-${time[2]} ${time[3]}:${time[4]}`
  return String(time).replace('T', ' ').substring(0, 16)
}

// 简化版的日期格式化，适合展示在小卡片上
const formatTimeShort = (time) => {
  if(!time) return ''
  let dateStr = ''
  if(Array.isArray(time)) {
    dateStr = `${time[0]}-${time[1]}-${time[2]}`
  } else {
    dateStr = String(time).split('T')[0]
  }
  return dateStr
}

onMounted(() => {
  loadDetail()
})

onUnmounted(() => {
  clearAgentPageContext()
  detailRequestGuard.invalidate()
  qualifiedViewTracker?.dispose()
  qualifiedViewTracker = null
})
</script>

<style scoped lang="scss">
.detail-layout {
  min-height: 100vh;
  background-color: #f6f6f6;
  padding-top: 20px;
}
.navbar-placeholder {
  width: 1000px; margin: 0 auto 20px;
  .nav-back { cursor: pointer; display: flex; align-items: center; gap: 5px; color: #666; font-size: 14px; &:hover { color: #0066ff; } }
}
.main-container {
  width: 1000px; margin: 0 auto; display: flex; align-items: flex-start; gap: 12px; padding-bottom: 50px;
}
.left-column { width: 694px; }

/* 给整个侧边栏加吸顶效果，而不是给单张卡片 */
.sidebar-column { width: 294px; position: sticky; top: 80px; align-self: flex-start; }

/* 通用点击样式 */
.pointer { cursor: pointer; &:hover { color: #0066ff; } }

/* 文章卡片 */
.article-card {
  background: #fff; padding: 30px 40px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1); margin-bottom: 20px; min-height: 300px;

  .article-header-row {
    display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 15px;
    .article-title { font-size: 32px; font-weight: 700; line-height: 1.4; color: #121212; flex: 1; }
    .more-actions {
      margin-left: 10px; margin-top: 5px;
      .action-icon { font-size: 20px; cursor: pointer; color: #999; transform: rotate(90deg); &:hover { color: #333; } }
    }
  }

  .tags-row {
    margin-bottom: 20px; display: flex; gap: 10px; flex-wrap: wrap;
    .tag-item { cursor: pointer; border-color: transparent; background: #f2f3f5; color: #8590a6; &:hover { color: #0066ff; background: #e6f0fd; } }
  }

  .article-meta {
    display: flex; align-items: center; gap: 20px; color: #8590a6; font-size: 14px; margin-bottom: 30px;
    .author { font-weight: 600; color: #444; }
  }
  .article-cover {
    margin-bottom: 30px; border-radius: 4px; overflow: hidden;
    img { width: 100%; max-height: 400px; object-fit: cover; display: block; }
  }
  .article-actions { margin-top: 50px; display: flex; gap: 20px; justify-content: center; }
}

/* 评论卡片 */
.comment-card {
  background: #fff; padding: 20px 40px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1);
  .comment-header { font-weight: 600; font-size: 18px; margin-bottom: 20px; border-left: 4px solid #0066ff; padding-left: 12px; }

  .comment-input-wrapper {
    display: flex; gap: 15px; margin-bottom: 30px;
    .my-avatar { flex-shrink: 0; }
    .input-box {
      flex: 1;
      .input-actions {
        margin-top: 10px; text-align: right;
        .cancel-reply { font-size: 13px; color: #8590a6; margin-right: 15px; cursor: pointer; &:hover { color: #0066ff; } }
      }
    }
  }

  .comment-list {
    .comment-item {
      display: flex; gap: 15px; padding: 20px 0; border-top: 1px solid #f0f2f7;
      &:first-child { border-top: none; }
      .u-avatar { flex-shrink: 0; }

      .content-box {
        flex: 1;
        .u-name { font-weight: 600; font-size: 15px; color: #444; margin-bottom: 8px; }
        .u-content { font-size: 15px; color: #121212; line-height: 1.6; margin-bottom: 8px; }

        .u-stat {
          display: flex; align-items: center; gap: 20px; font-size: 13px; color: #8590a6;
          .action-btn {
            cursor: pointer; display: flex; align-items: center; gap: 4px; transition: all 0.2s;
            &:hover { color: #0066ff; }
            &.liked { color: #0066ff; font-weight: 600; }
            &.delete-btn { color: #999; &:hover { color: #f56c6c; } }
            &.report-btn { &:hover { color: #e6a23c; } }
          }
        }

        .sub-comment-list {
          background: #f6f6f6; padding: 15px; border-radius: 4px; margin-top: 15px;
          .sub-item {
            margin-bottom: 12px; &:last-child { margin-bottom: 0; }
            .sub-content { font-size: 14px; line-height: 1.6; margin-bottom: 5px; color: #333; }
            .sub-user { font-weight: 600; color: #444; }
            .reply-text { color: #8590a6; margin: 0 5px; }
          }
        }
      }
    }
  }
}

/* 侧边栏作者卡片 */
.author-card {
  background: #fff; padding: 20px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1);
  .author-header {
    .author-link { display: flex; align-items: center; gap: 15px; margin-bottom: 20px; cursor: pointer; transition: opacity 0.2s; &:hover { opacity: 0.8; } }
    .name { font-weight: 600; font-size: 16px; margin-bottom: 4px; }
    .bio { font-size: 13px; color: #8590a6; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 180px; }
  }
  .stat-row { display: flex; justify-content: space-around; margin-bottom: 20px; .stat-item { text-align: center; } .num { font-weight: 600; font-size: 16px; color: #121212; } .label { font-size: 12px; color: #8590a6; } }
  .follow-btn { width: 100%; }
}

/* 相关推荐卡片样式 */
.similar-card {
  background: #fff; padding: 20px; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1); margin-top: 15px;
  .similar-header { font-weight: 600; font-size: 16px; margin-bottom: 15px; border-left: 4px solid #0066ff; padding-left: 10px; color: #121212; }
  .similar-list {
    display: flex; flex-direction: column; gap: 12px;
    .similar-item {
      border-bottom: 1px solid #f0f2f7; padding-bottom: 12px;
      &:last-child { border-bottom: none; padding-bottom: 0; }
      &:hover .sim-title { color: #0066ff; }
      .sim-title { font-size: 14px; font-weight: 500; color: #444; line-height: 1.5; margin-bottom: 6px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
      .sim-meta { font-size: 12px; color: #8590a6; }
    }
  }
}

/* 收藏夹列表 */
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

/* 标签样式 */
.author-tag {
  background: #0066ff; color: #fff; font-size: 12px; padding: 1px 4px; border-radius: 2px; margin-left: 4px; font-weight: normal; transform: scale(0.9); display: inline-block;
  &.mini { transform: scale(0.85); }
}

/* 举报理由单选 */
.report-radio {
  display: flex; flex-direction: column; gap: 10px; align-items: flex-start;
}

/* Markdown 图片修正 */
:deep(.vuepress-markdown-body img) { display: block; margin: 20px auto; max-width: 100%; border-radius: 4px; box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1); }
:deep(.vuepress-markdown-body) { padding: 0; color: #121212; }

/* A quiet paper reading surface, while every article and comment action remains intact. */
.detail-layout { background: var(--paper-muted); padding-top: var(--space-5); color: var(--ink); }
.navbar-placeholder,
.main-container { width: min(1180px, calc(100% - 32px)); }
.navbar-placeholder { margin-bottom: var(--space-4); }
.navbar-placeholder .nav-back,
.pointer:hover { color: var(--accent); }
.main-container { gap: var(--space-5); }
.left-column { width: min(800px, 100%); }
.sidebar-column { width: 330px; top: 76px; }
.article-card,
.comment-card,
.author-card,
.similar-card { background: #fffdf9; border: 1px solid var(--line); border-radius: var(--radius-sm); box-shadow: none; }
.article-card { padding: 44px min(6vw, 64px); }
.article-card .article-header-row .article-title { color: var(--ink); font-family: "Songti SC", SimSun, serif; font-size: clamp(30px, 3.4vw, 44px); font-weight: 700; letter-spacing: .02em; }
.article-card .tags-row .tag-item { background: #f5ece0; color: var(--ink-muted); border-color: var(--line); border-radius: 2px; }
.article-card .tags-row .tag-item:hover { color: var(--accent); background: #f4e1dc; }
.article-card .article-meta { color: var(--ink-muted); border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); padding: var(--space-3) 0; margin-bottom: var(--space-5); }
.article-card .article-meta .author { color: var(--ink); }
.article-card .article-cover { border-radius: var(--radius-sm); border: 1px solid var(--line); }
.article-card .article-actions { justify-content: flex-start; margin-top: 42px; padding-top: var(--space-5); border-top: 1px solid var(--line); }
.comment-card { padding: var(--space-5) min(6vw, 64px); }
.comment-card .comment-header { border-left-color: var(--accent); color: var(--ink); font-family: "Songti SC", SimSun, serif; }
.comment-card .comment-list .comment-item { border-top-color: var(--line); }
.comment-card .comment-list .comment-item .content-box .u-content { color: var(--ink); }
.comment-card .comment-list .comment-item .content-box .u-stat .action-btn:hover,
.comment-card .comment-list .comment-item .content-box .u-stat .action-btn.liked { color: var(--accent); }
.comment-card .comment-list .comment-item .content-box .sub-comment-list { background: #f8f1e8; border: 1px solid var(--line); border-radius: var(--radius-sm); }
.author-card, .similar-card { padding: var(--space-5); }
.similar-card .similar-header { border-left-color: var(--accent); font-family: "Songti SC", SimSun, serif; }
.similar-card .similar-list .similar-item:hover .sim-title { color: var(--accent); }
.author-tag { background: var(--accent); }
:deep(.vuepress-markdown-body) { color: var(--ink); font-family: ui-sans-serif, -apple-system, BlinkMacSystemFont, "Microsoft YaHei", sans-serif; line-height: 1.9; }
:deep(.vuepress-markdown-body h1),
:deep(.vuepress-markdown-body h2),
:deep(.vuepress-markdown-body h3) { color: var(--ink); font-family: "Songti SC", SimSun, serif; }
:deep(.vuepress-markdown-body blockquote) { border-left-color: var(--accent); background: #f8f1e8; color: var(--ink-muted); }
@media (max-width: 980px) {
  .sidebar-column { display: none; }
  .left-column { width: 100%; }
}
@media (max-width: 600px) {
  .detail-layout { padding-top: var(--space-4); }
  .navbar-placeholder, .main-container { width: calc(100% - 24px); }
  .article-card, .comment-card { padding: var(--space-4); }
  .article-card .article-actions { gap: var(--space-2); }
}
</style>
