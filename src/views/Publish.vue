<template>
  <div class="publish-container">
    <div class="navbar-placeholder">
      <div class="nav-back" @click="$router.go(-1)">
        <el-icon><ArrowLeft /></el-icon> 返回
      </div>
      <div class="nav-title">{{ isEdit ? '编辑文章' : '写文章' }}</div>
    </div>

    <div class="editor-main">
      <el-input
          v-model="form.title"
          class="title-input"
          placeholder="请输入标题..."
          maxlength="80"
          show-word-limit
      />

      <div class="cover-section">
        <div class="section-label">文章封面 (可选)</div>
        <el-upload
            class="cover-uploader"
            action="/api/file/upload"
            :show-file-list="false"
            :on-success="handleCoverSuccess"
            :before-upload="beforeCoverUpload"
            :headers="uploadHeaders"
        >
          <img v-if="form.cover" :src="form.cover" class="cover-img" />
          <el-icon v-else class="cover-uploader-icon"><Plus /></el-icon>
          <div slot="tip" class="el-upload__tip" v-if="!form.cover">支持 JPG/PNG，小于 2MB</div>
        </el-upload>
        <el-button v-if="form.cover" type="text" class="remove-cover" @click.stop="form.cover = ''">移除封面</el-button>
      </div>

      <v-md-editor
          v-model="form.content"
          height="600px"
          placeholder="开始你的创作..."
          :disabled-menus="[]"
          @upload-image="handleUploadImage"
      ></v-md-editor>

      <div class="action-bar">
        <div class="draft-tip" v-if="lastSaveTime">上次保存: {{ lastSaveTime }}</div>
        <div class="btns">
          <el-button @click="handleSaveDraft" :loading="draftLoading">存草稿</el-button>
          <el-button type="primary" @click="handlePublish" :loading="publishLoading">
            {{ isEdit ? '更新发布' : '发布文章' }}
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router' // 引入 useRoute
import { ElMessage } from 'element-plus'
import { publishArticle, saveDraft, getArticleForEdit } from '../api/article' // 引入新接口
import request from '../utils/request'

const router = useRouter()
const route = useRoute()

const form = reactive({
  id: null, // 如果有ID，说明是修改
  title: '',
  content: '',
  cover: '' // 新增封面字段
})

const publishLoading = ref(false)
const draftLoading = ref(false)
const lastSaveTime = ref('')

const isEdit = computed(() => !!form.id) // 是否处于编辑模式

// 上传请求头 (如果有Token验证)
const uploadHeaders = computed(() => {
  return { token: localStorage.getItem('token') }
})

// 1. 初始化：检查是否是编辑模式
onMounted(async () => {
  const editId = route.query.id
  if (editId) {
    loadArticleForEdit(editId)
  }
})

// 2. 加载旧数据
const loadArticleForEdit = async (id) => {
  try {
    const res = await getArticleForEdit(id)
    const data = res.data
    form.id = data.id
    form.title = data.title
    form.content = data.content
    form.cover = data.cover
  } catch(e) {
    ElMessage.error('加载文章失败，可能已被删除')
    router.push('/home')
  }
}

// 3. 发布
const handlePublish = async () => {
  if(!validate()) return
  publishLoading.value = true
  try {
    const res = await publishArticle(form)
    if(res.code === 200) {
      ElMessage.success(isEdit.value ? '更新成功' : '发布成功')
      router.push(`/article/${res.data}`)
    } else {
      ElMessage.error(res.msg || '发布失败')
    }
  } catch(e) {
    console.error(e)
  } finally {
    publishLoading.value = false
  }
}

// 4. 存草稿
const handleSaveDraft = async () => {
  if(!form.title) return ElMessage.warning('至少写个标题吧')
  draftLoading.value = true
  try {
    const res = await saveDraft(form)
    if(res.code === 200) {
      ElMessage.success('草稿保存成功')
      // 如果是新增保存的草稿，要把返回的ID赋给form，防止下次保存变成新增
      if (!form.id) {
        form.id = res.data
      }
      lastSaveTime.value = new Date().toLocaleTimeString()
    }
  } catch(e) {
    console.error(e)
  } finally {
    draftLoading.value = false
  }
}

// 校验
const validate = () => {
  if(!form.title.trim()) {
    ElMessage.warning('标题不能为空')
    return false
  }
  if(!form.content.trim()) {
    ElMessage.warning('内容不能为空')
    return false
  }
  return true
}

// 编辑器上传图片处理
const handleUploadImage = async (event, insertImage, files) => {
  // 这里需要你自己实现图片上传逻辑，复用 file/upload 接口
  // 简单示例:
  const formData = new FormData()
  formData.append('file', files[0])
  try {
    const res = await request.post('/api/file/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    if(res.code === 200) {
      insertImage({
        url: res.data,
        desc: '图片'
      })
    }
  } catch(e) {
    ElMessage.error('图片上传失败')
  }
}

// 封面上传成功
const handleCoverSuccess = (res) => {
  if(res.code === 200) {
    form.cover = res.data
    ElMessage.success('封面上传成功')
  } else {
    ElMessage.error('上传失败')
  }
}
const beforeCoverUpload = (file) => {
  const isLt2M = file.size / 1024 / 1024 < 2
  if (!isLt2M) {
    ElMessage.error('封面图大小不能超过 2MB!')
  }
  return isLt2M
}
</script>

<style scoped lang="scss">
.publish-container {
  min-height: 100vh; background: #fff;
}
.navbar-placeholder {
  height: 60px; border-bottom: 1px solid #eee; display: flex; align-items: center; justify-content: space-between; padding: 0 30px;
  position: sticky; top: 0; background: #fff; z-index: 100;
  .nav-back { cursor: pointer; display: flex; align-items: center; gap: 5px; color: #666; font-size: 16px; &:hover { color: #0066ff; } }
  .nav-title { font-size: 18px; font-weight: 600; color: #333; position: absolute; left: 50%; transform: translateX(-50%); }
}

.editor-main {
  width: 1000px; margin: 30px auto;

  .title-input {
    margin-bottom: 20px;
    :deep(.el-input__wrapper) { box-shadow: none; border-bottom: 1px solid #eee; padding: 10px 0; border-radius: 0; }
    :deep(.el-input__inner) { font-size: 32px; font-weight: 600; color: #333; height: 60px; line-height: 60px; }
  }

  .cover-section {
    margin-bottom: 20px; display: flex; align-items: flex-start; gap: 20px;
    .section-label { font-size: 14px; color: #666; margin-top: 10px; width: 80px; }
    .cover-uploader {
      .cover-img { width: 160px; height: 90px; object-fit: cover; border-radius: 4px; border: 1px solid #eee; }
      .cover-uploader-icon {
        font-size: 28px; color: #8c939d; width: 160px; height: 90px; line-height: 90px; text-align: center; border: 1px dashed #d9d9d9; border-radius: 4px; cursor: pointer;
        &:hover { border-color: #409EFF; }
      }
    }
    .remove-cover { margin-left: 10px; color: #f56c6c; font-size: 13px; margin-top: 5px; }
  }
}

.action-bar {
  margin-top: 30px; display: flex; justify-content: flex-end; align-items: center; gap: 20px; padding-bottom: 50px;
  .draft-tip { color: #999; font-size: 13px; }
}
</style>