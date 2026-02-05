<template>
  <div class="login-container">
    <el-card class="login-card">
      <template #header>
        <div class="card-header">
          <span>🚀 登录 Community</span>
        </div>
      </template>

      <el-form :model="loginForm" label-position="top" size="large">
        <el-form-item label="邮箱">
          <el-input v-model="loginForm.email" placeholder="请输入邮箱" prefix-icon="Message" />
        </el-form-item>

        <el-form-item label="密码">
          <el-input
              v-model="loginForm.password"
              type="password"
              placeholder="请输入密码"
              prefix-icon="Lock"
              show-password
              @keyup.enter="handleLogin"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="loading" class="login-btn" @click="handleLogin">
            立即登录
          </el-button>
        </el-form-item>

        <div class="actions">
          <el-link type="primary" @click="$router.push('/register')">没有账号？去注册</el-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'  // 1. 必须导入这个
import { ElMessage } from 'element-plus'
import request from '../utils/request'

// 2. 必须初始化 router
const router = useRouter()

const loading = ref(false)

// 3. 必须定义表单数据 (名字要和模板里的 :model="loginForm" 一致)
const loginForm = reactive({
  email: '',
  password: ''
})

// 完整的登录逻辑
const handleLogin = async () => {
  // 校验
  if (!loginForm.email || !loginForm.password) {
    return ElMessage.warning('请输入邮箱和密码')
  }

  loading.value = true

  try {
    const res = await request.post('/api/auth/login', loginForm)

    if (res.code === 200) {
      // 后端返回的数据: { id: 1, token: "...", username: "...", avatar: "..." }
      const userData = res.data

      // 1. 存 Token
      localStorage.setItem('token', userData.token)

      // 2. 存完整的 User 对象 (包含 id)
      localStorage.setItem('user', JSON.stringify(userData))

      ElMessage.success('登录成功')

      // 3. 跳转 (确保 /home 路由存在，如果你的首页是 /，请改为 push('/'))
      setTimeout(() => {
        router.push('/home')
      }, 500)
    } else {
      ElMessage.error(res.msg || '登录失败')
    }
  } catch (e) {
    console.error(e)
    // 如果被拦截器拦截了，这里可能会报错，为了用户体验可以加个提示
    // ElMessage.error('网络请求异常')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.login-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background-color: #f6f8fa;
}
.login-card {
  width: 400px;
}
.login-btn {
  width: 100%;
}
.actions {
  text-align: right;
  font-size: 14px;
}
</style>