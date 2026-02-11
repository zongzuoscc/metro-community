<template>
  <div class="publish-page">
    <div class="navbar-placeholder">
      <div class="nav-back" @click="$router.go(-1)">
        <el-icon><ArrowLeft /></el-icon> 返回
      </div>
      <div class="page-title">{{ isEdit ? '编辑文章' : '发布文章' }}</div>
    </div>

    <div class="main-container">
      <div class="form-group">
        <input
            v-model="form.title"
            type="text"
            class="title-input"
            placeholder="请输入文章标题..."
        />
      </div>

      <div class="form-group">
        <div class="section-label">文章封面 (可选)</div>
        <el-upload
            class="cover-uploader"
            action="/api/file/upload"
            :show-file-list="false"
            :headers="uploadHeaders"
            :on-success="handleCoverSuccess"
            :before-upload="beforeCoverUpload"
        >
          <img v-if="form.cover" :src="form.cover" class="cover-img" />
          <el-icon v-else class="uploader-icon"><Plus /></el-icon>
        </el-upload>
        <el-button v-if="form.cover" type="danger" link size="small" @click.stop="form.cover = ''">移除封面</el-button>
      </div>

      <div class="form-group tag-section">
        <div class="section-label">添加标签</div>
        <el-select
            v-model="form.tags"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="输入标签后按回车，如：Java, 提问"
            class="tag-input"
            size="large"
        >
          <el-option
              v-for="item in hotTags"
              :key="item"
              :label="item"
              :value="item"
          />
        </el-select>
      </div>

      <div class="editor-box">
        <v-md-editor
            v-model="form.content"
            height="600px"
            :disabled-menus="[]"
            @upload-image="handleUploadImage"
        ></v-md-editor>
      </div>

      <div class="footer-actions">
        <el-button size="large" @click="handleSaveDraft">存草稿</el-button>
        <el-button type="primary" size="large" @click="handlePublish" :loading="publishing">
          {{ isEdit ? '更新发布' : '立即发布' }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../utils/request'
import { getArticleDetail, publishArticle } from '../api/article'
import { getHotTags } from '../api/tag' // 引入标签接口

const route = useRoute()
const router = useRouter()
const isEdit = ref(false)
const publishing = ref(false)

const form = reactive({
  id: null,
  title: '',
  content: '',
  cover: '',
  tags: [] // 【新增】标签数组
})

const hotTags = ref([]) // 热门标签备选

const uploadHeaders = computed(() => ({ token: localStorage.getItem('token') }))

// 初始化
onMounted(async () => {
  loadHotTags() // 加载推荐标签
  const id = route.query.id
  if (id) {
    isEdit.value = true
    loadArticle(id)
  }
})

// 加载热门标签
const loadHotTags = async () => {
  try {
    const res = await getHotTags()
    hotTags.value = res.data || []
  } catch(e) {}
}

// 加载文章详情 (回显)
const loadArticle = async (id) => {
  try {
    const res = await getArticleDetail(id)
    const data = res.data
    form.id = data.id
    form.title = data.title
    form.content = data.content
    form.cover = data.cover
    form.tags = data.tagList || [] // 回显标签
  } catch (e) {
    ElMessage.error('加载文章失败')
  }
}

// 存草稿
const handleSaveDraft = () => submit(false)
// 发布
const handlePublish = () => submit(true)

const submit = async (isPublish) => {
  if (!form.title.trim()) return ElMessage.warning('请输入标题')
  if (!form.content.trim()) return ElMessage.warning('请输入内容')

  publishing.value = true
  try {
    // 构造 payload
    const payload = {
      id: form.id,
      title: form.title,
      content: form.content,
      cover: form.cover,
      tags: form.tags, // 传给后端
      isPublish: isPublish // 这个字段用于后端判断状态(如果有这个字段的话，或者通过 URL 区分)
    }

    // 注意：之前的 publishArticle 接口可能没传 isPublish 参数
    // 我们通常约定：status=1 发布, status=0 草稿。
    // 这里为了兼容你的后端 publishOrSave 逻辑，我们需要确认一下 API 定义。
    // 假设我们复用 publishArticle，但在 payload 里带上 isPublish 标记

    await publishArticle(payload, isPublish) // 修改 api/article.js 支持第二个参数，或者直接把 isPublish 放到 payload 里

    ElMessage.success(isPublish ? '发布成功' : '已存入草稿')
    router.push('/home')
  } catch(e) {
    ElMessage.error(e.msg || '操作失败')
  } finally {
    publishing.value = false
  }
}

// 封面上传相关
const handleCoverSuccess = (res) => {
  if(res.code === 200) form.cover = res.data
}
const beforeCoverUpload = (file) => {
  if (file.size / 1024 / 1024 > 5) {
    ElMessage.error('图片最大 5MB')
    return false
  }
  return true
}

// 编辑器上传图片
const handleUploadImage = async (event, insertImage, files) => {
  const file = files[0]
  const formData = new FormData()
  formData.append('file', file)
  try {
    const res = await request.post('/api/file/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    if (res.code === 200) {
      insertImage({ url: res.data, desc: 'image' })
    }
  } catch (e) {
    ElMessage.error('图片上传失败')
  }
}
</script>

<style scoped lang="scss">
.publish-page {
  min-height: 100vh; background: #fff;
}
.navbar-placeholder {
  height: 60px; border-bottom: 1px solid #eee; display: flex; align-items: center; padding: 0 40px; justify-content: space-between;
  .nav-back { cursor: pointer; display: flex; align-items: center; gap: 5px; color: #666; &:hover { color: #0066ff; } }
  .page-title { font-size: 18px; font-weight: 600; color: #333; }
}

.main-container {
  width: 900px; margin: 30px auto;
}

.form-group {
  margin-bottom: 25px;
  .title-input {
    width: 100%; border: none; outline: none; font-size: 32px; font-weight: 600; color: #121212;
    &::placeholder { color: #ccc; }
  }
  .section-label { font-size: 14px; color: #666; margin-bottom: 10px; }
}

/* 标签选择器样式 */
.tag-section {
  .tag-input { width: 100%; }
}

.cover-uploader {
  width: 200px; height: 112px; border: 1px dashed #d9d9d9; border-radius: 6px; cursor: pointer; position: relative; overflow: hidden; background: #fafafa;
  &:hover { border-color: #0066ff; }
  .uploader-icon { font-size: 28px; color: #8c939d; position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); }
  .cover-img { width: 100%; height: 100%; object-fit: cover; }
}

.editor-box {
  margin-bottom: 80px;
}

.footer-actions {
  position: fixed; bottom: 0; left: 0; width: 100%; height: 70px; background: #fff; border-top: 1px solid #eee; display: flex; align-items: center; justify-content: flex-end; padding: 0 100px; gap: 20px; z-index: 100; box-shadow: 0 -2px 10px rgba(0,0,0,0.05);
}
</style>