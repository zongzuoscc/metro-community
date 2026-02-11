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
    </div>

    <div class="footer-actions">
      <div class="footer-content">
        <span class="tip" v-if="isEdit">当前为编辑模式</span>
        <div class="btns">
          <el-button size="large" @click="handleSaveDraft">存草稿</el-button>
          <el-button type="primary" size="large" @click="handlePublish" :loading="publishing">
            {{ isEdit ? '更新发布' : '立即发布' }}
          </el-button>
        </div>
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
import { getHotTags } from '../api/tag'

const route = useRoute()
const router = useRouter()
const isEdit = ref(false)
const publishing = ref(false)

const form = reactive({
  id: null,
  title: '',
  content: '',
  cover: '',
  tags: []
})

const hotTags = ref([])

const uploadHeaders = computed(() => ({ token: localStorage.getItem('token') }))

onMounted(async () => {
  loadHotTags()
  const id = route.query.id
  if (id) {
    isEdit.value = true
    loadArticle(id)
  }
})

const loadHotTags = async () => {
  try {
    const res = await getHotTags()
    hotTags.value = res.data || []
  } catch(e) {}
}

const loadArticle = async (id) => {
  try {
    const res = await getArticleDetail(id)
    const data = res.data
    form.id = data.id
    form.title = data.title
    form.content = data.content
    form.cover = data.cover
    form.tags = data.tagList || []
  } catch (e) {
    ElMessage.error('加载文章失败')
  }
}

const handleSaveDraft = () => submit(false)
const handlePublish = () => submit(true)

const submit = async (isPublish) => {
  if (!form.title.trim()) return ElMessage.warning('请输入标题')
  if (!form.content.trim()) return ElMessage.warning('请输入内容')

  publishing.value = true
  try {
    const payload = {
      id: form.id,
      title: form.title,
      content: form.content,
      cover: form.cover,
      tags: form.tags,
      isPublish: isPublish // 传给后端状态
    }

    await publishArticle(payload)

    ElMessage.success(isPublish ? '发布成功' : '已存入草稿')
    router.push('/home')
  } catch(e) {
    ElMessage.error(e.msg || '操作失败')
  } finally {
    publishing.value = false
  }
}

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
  min-height: 100vh; background: #fff; padding-bottom: 80px; /* 防止内容被底部遮挡 */
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

.tag-section .tag-input { width: 100%; }

.cover-uploader {
  width: 200px; height: 112px; border: 1px dashed #d9d9d9; border-radius: 6px; cursor: pointer; position: relative; overflow: hidden; background: #fafafa;
  &:hover { border-color: #0066ff; }
  .uploader-icon { font-size: 28px; color: #8c939d; position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); }
  .cover-img { width: 100%; height: 100%; object-fit: cover; }
}

.editor-box {
  margin-bottom: 20px;
}

/* 底部操作栏增强样式 */
.footer-actions {
  position: fixed; bottom: 0; left: 0; width: 100%; height: 72px;
  background: #fff; border-top: 1px solid #e0e0e0;
  z-index: 999; /* 确保层级最高 */
  box-shadow: 0 -4px 12px rgba(0,0,0,0.05);
  display: flex; justify-content: center; align-items: center;
}

.footer-content {
  width: 900px; display: flex; justify-content: space-between; align-items: center;
  .tip { font-size: 13px; color: #999; }
  .btns { display: flex; gap: 15px; margin-left: auto; } /* 强制靠右 */
}
</style>