<template>
  <div class="publish-container">
    <div class="publish-card">
      <div class="header">
        <h2>📝 发布文章</h2>
        <el-button type="primary" size="large" @click="handlePublish" :loading="loading">
          <el-icon style="margin-right: 5px"><Promotion /></el-icon> 发布
        </el-button>
      </div>

      <el-input
          v-model="article.title"
          placeholder="请输入文章标题..."
          class="title-input"
          maxlength="100"
          show-word-limit
      />

      <v-md-editor
          v-model="article.content"
          height="600px"
          placeholder="在此处开始你的创作 (支持截图粘贴、拖拽上传图片)..."
          :disabled-menus="[]"
          @upload-image="handleUploadImage"
          left-toolbar="undo redo clear | h bold italic strikethrough | ul ol | quote hr | code | image link"
      ></v-md-editor>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../utils/request'
import { uploadFile } from '../api/file'

const router = useRouter()
const loading = ref(false)

const article = reactive({
  title: '',
  content: ''
})

const handleUploadImage = async (event, insertImage, files) => {
  const file = files[0]
  if (!file) return

  if (file.size / 1024 / 1024 > 10) {
    return ElMessage.warning('图片大小不能超过 10MB')
  }

  const formData = new FormData()
  formData.append('file', file)

  try {
    const res = await uploadFile(formData)
    insertImage({
      url: res.data,
      desc: '图片描述',
    })
    ElMessage.success('图片上传成功')
  } catch (error) {
    console.error(error)
    ElMessage.error('图片上传失败，请检查后端配置')
  }
}

const handlePublish = async () => {
  if (!article.title.trim()) return ElMessage.warning('请输入文章标题')
  if (!article.content.trim()) return ElMessage.warning('请输入正文内容')

  loading.value = true
  try {
    await request.post('/api/article/publish', {
      title: article.title,
      content: article.content,
      summary: ''
    })
    ElMessage.success('发布成功！')
    router.push('/home')
  } catch (error) {
    console.error("发布失败", error)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.publish-container {
  min-height: 100vh;
  text-align: left;
  background-color: #f6f6f6;
  padding: 20px;
  display: flex;
  justify-content: center;
}

.publish-card {
  width: 1000px;
  background: #fff;
  border-radius: 8px;
  padding: 30px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);

  .header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
    h2 {
      margin: 0;
      color: #333;
      font-weight: 700;
      display: flex; align-items: center; gap: 8px;
    }
  }

  .title-input {
    margin-bottom: 20px;
    font-size: 24px;

    :deep(.el-input__wrapper) {
      padding: 10px 0;
      box-shadow: none !important;
      border-bottom: 1px solid #eee;
      border-radius: 0;
      background: transparent;
    }

    :deep(.el-input__inner) {
      font-weight: bold;
      color: #333;
    }
  }
}

/* --- 核心样式：让编辑器内的图片自动居中 --- */
/* 针对 VuePress 主题的渲染区域 */
:deep(.vuepress-markdown-body img) {
  display: block;  /* 变成块级元素，独占一行 */
  margin: 20px auto; /* 上下留白20px，左右自动(即居中) */
  max-width: 100%; /* 防止图片过大撑破容器 */
  border-radius: 4px; /* 加一点圆角更好看 */
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1); /* 加一点阴影更有质感 */
}

/* 确保文字依然是左对齐 */
:deep(.v-md-editor) {
  text-align: left;
}
</style>