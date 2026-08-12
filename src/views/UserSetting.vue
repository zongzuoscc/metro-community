<template>
  <div class="setting-layout">
    <div class="navbar-placeholder">
      <div class="nav-back" @click="$router.push('/home')">
        <el-icon><ArrowLeft /></el-icon> 返回首页
      </div>
    </div>

    <div class="main-container">
      <div class="setting-card">
        <el-tabs tab-position="left" style="height: 100%;" class="setting-tabs">

          <el-tab-pane label="基本资料">
            <div class="pane-content">
              <h2 class="pane-title">编辑个人资料</h2>

              <el-form :model="form" label-width="80px" label-position="top">
                <el-form-item label="头像">
                  <el-upload
                      class="avatar-uploader"
                      action="/api/file/upload"
                      :show-file-list="false"
                      :on-success="handleAvatarSuccess"
                      :before-upload="beforeAvatarUpload"
                      :headers="uploadHeaders"
                  >
                    <img v-if="form.avatar" :src="form.avatar" class="avatar" />
                    <el-icon v-else class="avatar-uploader-icon"><Plus /></el-icon>
                    <div class="upload-tip">点击图片更换头像 (支持 JPG/PNG)</div>
                  </el-upload>
                </el-form-item>

                <el-form-item label="昵称">
                  <el-input v-model="form.username" maxlength="20" show-word-limit />
                </el-form-item>

                <el-form-item label="个人简介">
                  <el-input
                      v-model="form.intro"
                      type="textarea"
                      :rows="4"
                      maxlength="100"
                      show-word-limit
                      placeholder="介绍一下你自己..."
                  />
                </el-form-item>

                <el-form-item>
                  <el-button type="primary" @click="saveProfile" :loading="loading">保存修改</el-button>
                </el-form-item>
              </el-form>
            </div>
          </el-tab-pane>

          <el-tab-pane label="账号安全">
            <div class="pane-content">
              <h2 class="pane-title">修改密码</h2>

              <el-form :model="pwdForm" label-width="100px" label-position="top" :rules="pwdRules" ref="pwdFormRef">
                <el-form-item label="当前密码" prop="oldPassword">
                  <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入当前使用的密码" />
                </el-form-item>

                <el-form-item label="新密码" prop="newPassword">
                  <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="6-20位字符" />
                </el-form-item>

                <el-form-item label="确认新密码" prop="confirmPassword">
                  <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="请再次输入新密码" />
                </el-form-item>

                <el-form-item>
                  <el-button type="primary" @click="changePassword" :loading="pwdLoading">确认修改</el-button>
                  <div class="forget-tip">
                    忘记旧密码了？<el-link type="primary" @click="handleForget">通过邮箱验证重置</el-link>
                  </div>
                </el-form-item>
              </el-form>

              <el-divider />

              <h2 class="pane-title">绑定邮箱</h2>
              <div class="email-box">
                <div class="email-info">
                  <span>当前绑定邮箱：</span>
                  <span class="email-text">{{ form.email || '未获取到邮箱' }}</span>
                </div>
                <el-button text type="primary" disabled>暂不支持换绑</el-button>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="AI 模型">
            <div class="pane-content pane-content--wide">
              <AiProviderSettings />
            </div>
          </el-tab-pane>

        </el-tabs>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive, computed } from 'vue' // 【核心修复 2】引入 computed
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../utils/request'
import { closeWebSocket } from '../utils/websocket'
import AiProviderSettings from '../components/AiProviderSettings.vue'

const router = useRouter()
const loading = ref(false)
const form = reactive({
  id: '',
  username: '',
  avatar: '',
  intro: '',
  email: ''
})

// 【核心修复 3】定义上传请求头
const uploadHeaders = computed(() => {
  return { token: localStorage.getItem('token') }
})

// 密码表单
const pwdFormRef = ref(null)
const pwdLoading = ref(false)
const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

// 密码校验规则
const validatePass2 = (rule, value, callback) => {
  if (value === '') {
    callback(new Error('请再次输入密码'))
  } else if (value !== pwdForm.newPassword) {
    callback(new Error('两次输入密码不一致!'))
  } else {
    callback()
  }
}
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { min: 6, message: '长度至少6位', trigger: 'blur' }],
  confirmPassword: [{ validator: validatePass2, trigger: 'blur' }]
}

// 1. 加载当前用户信息
const loadUserInfo = async () => {
  try {
    const res = await request.get('/api/user/info')
    if(res.code === 200) {
      const u = res.data
      form.id = u.id
      form.username = u.username
      form.avatar = u.avatar
      form.intro = u.intro
      form.email = u.email
    }
  } catch(e) {}
}

// 2. 保存基本资料
const saveProfile = async () => {
  if(!form.username) return ElMessage.warning('昵称不能为空')
  loading.value = true
  try {
    await request.post('/api/user/update', {
      username: form.username,
      avatar: form.avatar,
      intro: form.intro
    })
    ElMessage.success('保存成功')
    // 更新本地缓存
    const userStr = localStorage.getItem('user')
    if(userStr) {
      const userObj = JSON.parse(userStr)
      userObj.username = form.username
      userObj.avatar = form.avatar
      localStorage.setItem('user', JSON.stringify(userObj))
    }
    // 刷新页面或重新加载
    setTimeout(() => window.location.reload(), 1000)
  } catch(e) {
    ElMessage.error(e.msg || '保存失败')
  } finally {
    loading.value = false
  }
}

// 3. 修改密码
const changePassword = () => {
  pwdFormRef.value.validate(async (valid) => {
    if (valid) {
      pwdLoading.value = true
      try {
        const res = await request.post('/api/user/password', {
          oldPassword: pwdForm.oldPassword,
          newPassword: pwdForm.newPassword
        })
        if(res.code === 200) {
          ElMessage.success('密码修改成功，请重新登录')
          // 强制退出
          closeWebSocket()
          localStorage.clear()
          window.dispatchEvent(new Event('metro-auth-changed'))
          setTimeout(() => router.push('/login'), 1500)
        } else {
          ElMessage.error(res.msg || '修改失败')
        }
      } catch(e) {
        // error handled by request.js
      } finally {
        pwdLoading.value = false
      }
    }
  })
}

// 4. 忘记密码处理
const handleForget = () => {
  ElMessageBox.confirm(
      '忘记密码需要先退出当前登录，并在重置密码页面通过邮箱验证找回。是否继续？',
      '提示',
      {
        confirmButtonText: '去重置',
        cancelButtonText: '取消',
        type: 'warning'
      }
  ).then(() => {
    closeWebSocket()
    localStorage.clear()
    window.dispatchEvent(new Event('metro-auth-changed'))
    ElMessage.success('已退出登录，正在前往重置页面...')
    setTimeout(() => {
      router.push('/reset-password')
    }, 500)
  }).catch(() => {})
}

// 头像上传相关
const handleAvatarSuccess = (response, uploadFile) => {
  if(response.code === 200) {
    form.avatar = response.data
  } else {
    ElMessage.error('上传失败')
  }
}
const beforeAvatarUpload = (rawFile) => {
  if (rawFile.size / 1024 / 1024 > 2) {
    ElMessage.error('头像大小不能超过 2MB!')
    return false
  }
  return true
}

onMounted(() => {
  loadUserInfo()
})
</script>

<style scoped lang="scss">
.setting-layout {
  min-height: 100vh; background: #f6f6f6; padding-top: 20px;
}
.nav-back {
  width: 1000px; margin: 0 auto 20px; cursor: pointer; display: flex; align-items: center; gap: 5px; color: #666;
  &:hover { color: #0066ff; }
}
.main-container {
  width: 1000px; margin: 0 auto;
}
.setting-card {
  background: #fff; border-radius: 4px; box-shadow: 0 1px 3px rgba(18,18,18,0.1);
  min-height: 600px; overflow: hidden;
}

:deep(.el-tabs__item) {
  height: 60px; line-height: 60px; font-size: 15px; padding: 0 40px !important; text-align: left;
  &.is-active { background: #f0f6ff; font-weight: 600; }
}

.pane-content {
  padding: 30px 50px; max-width: 500px;
  .pane-title { font-size: 20px; font-weight: 600; margin-bottom: 30px; color: #333; }
}
.pane-content--wide { max-width: 700px; }

.avatar-uploader {
  .avatar { width: 100px; height: 100px; border-radius: 4px; display: block; object-fit: cover; }
  .avatar-uploader-icon {
    font-size: 28px; color: #8c939d; width: 100px; height: 100px; line-height: 100px; text-align: center;
    border: 1px dashed #d9d9d9; border-radius: 4px; cursor: pointer;
    &:hover { border-color: #409EFF; }
  }
  .upload-tip { margin-top: 10px; font-size: 12px; color: #999; }
}

.forget-tip {
  margin-top: 10px; font-size: 13px; color: #666;
}

.email-box {
  background: #f8f9fa; padding: 20px; border-radius: 4px; display: flex; justify-content: space-between; align-items: center;
  .email-info { color: #666; .email-text { font-weight: 600; color: #333; margin-left: 10px; } }
}

.setting-layout { background: var(--paper-muted); padding-top: var(--space-5); }
.nav-back { width: min(1180px, calc(100% - 32px)); color: var(--ink-muted); }
.nav-back:hover { color: var(--accent); }
.main-container { width: min(1180px, calc(100% - 32px)); }
.setting-card { background: #fffdf9; border: 1px solid var(--line); border-radius: var(--radius-sm); box-shadow: none; }
:deep(.el-tabs__item) { color: var(--ink-muted); }
:deep(.el-tabs__item.is-active) { color: var(--accent); background: #f8eee8; }
.pane-content .pane-title { color: var(--ink); font-family: "Songti SC", SimSun, serif; font-size: 25px; }
.avatar-uploader .avatar-uploader-icon { border-color: var(--line); color: var(--ink-muted); }
.avatar-uploader .avatar-uploader-icon:hover { border-color: var(--accent); color: var(--accent); }
.email-box { background: #f8f1e8; border: 1px solid var(--line); }
</style>
