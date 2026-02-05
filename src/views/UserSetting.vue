<template>
  <div class="setting-container">
    <el-card class="setting-card">
      <template #header>
        <div class="card-header">
          <span>⚙️ 个人资料设置</span>
        </div>
      </template>

      <el-form label-position="top" :model="form" class="setting-form">

        <el-form-item label="头像">
          <div class="avatar-uploader" @click="triggerUpload">
            <el-avatar :size="80" :src="form.avatar" icon="UserFilled" class="user-avatar"></el-avatar>
            <div class="upload-mask"><el-icon><Camera /></el-icon> 修改</div>
          </div>
          <input type="file" ref="fileInput" @change="handleFileChange" style="display: none" accept="image/*">
        </el-form-item>

        <el-form-item label="昵称">
          <el-input v-model="form.username" placeholder="给自己起个响亮的名字" />
        </el-form-item>

        <el-form-item label="个人简介">
          <el-input v-model="form.intro" type="textarea" :rows="3" placeholder="介绍一下你自己..." />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="saveInfo" :loading="loading" class="save-btn">保存修改</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../utils/request'
import { uploadFile } from '../api/file'
import { Camera } from '@element-plus/icons-vue'

const loading = ref(false)
const fileInput = ref(null)

const form = reactive({
  id: null,
  username: '',
  avatar: '',
  intro: ''
})

// 加载当前用户信息
const loadInfo = async () => {
  try {
    const res = await request.get('/api/user/info')
    const u = res.data
    form.id = u.id
    form.username = u.username
    form.avatar = u.avatar
    form.intro = u.intro
  } catch(e) {
    console.error(e)
  }
}

onMounted(() => {
  loadInfo()
})

// 触发文件选择
const triggerUpload = () => {
  fileInput.value.click()
}

// 处理头像上传
const handleFileChange = async (e) => {
  const file = e.target.files[0]
  if(!file) return

  // 1. 上传文件到 OSS
  const formData = new FormData()
  formData.append('file', file)

  try {
    const res = await uploadFile(formData)
    // 2. 回显图片
    form.avatar = res.data
    ElMessage.success('头像上传成功，记得点击保存哦')
  } catch(error) {
    ElMessage.error('图片上传失败')
  }
}

// 保存修改
const saveInfo = async () => {
  if(!form.username) return ElMessage.warning('昵称不能为空')

  loading.value = true
  try {
    await request.post('/api/user/update', {
      username: form.username,
      avatar: form.avatar,
      intro: form.intro
    })
    ElMessage.success('保存成功！')

    // 更新本地缓存的用户信息(可选，为了让导航栏头像即时变化)
    const userCache = JSON.parse(localStorage.getItem('user') || '{}')
    userCache.username = form.username
    userCache.avatar = form.avatar
    localStorage.setItem('user', JSON.stringify(userCache))

    // 刷新一下页面让导航栏更新
    setTimeout(() => window.location.reload(), 500)

  } catch(e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.setting-container {
  width: 100%;
  min-height: 100vh;
  padding: 20px;
  background-color: #f6f6f6;
  display: flex;
  justify-content: center;
}

.setting-card {
  width: 600px;
  height: fit-content;

  .card-header {
    font-weight: 600;
    font-size: 18px;
  }
}

.setting-form {
  padding: 0 20px;
}

/* 头像上传样式 */
.avatar-uploader {
  position: relative;
  width: 80px;
  height: 80px;
  cursor: pointer;
  border-radius: 50%;
  overflow: hidden;

  .user-avatar {
    width: 100%;
    height: 100%;
  }

  .upload-mask {
    position: absolute;
    top: 0; left: 0; width: 100%; height: 100%;
    background: rgba(0,0,0,0.5);
    color: #fff;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    font-size: 12px;
    opacity: 0;
    transition: opacity 0.3s;
  }

  &:hover .upload-mask {
    opacity: 1;
  }
}

.save-btn {
  width: 100%;
  margin-top: 20px;
}
</style>