<template>
  <div class="login-container">
    <div class="bg-shape shape-1"></div>
    <div class="bg-shape shape-2"></div>

    <div class="login-content">
      <div class="login-left">
        <div class="welcome-text">
          <h1>Metro Community</h1>
          <p>连接开发者，分享技术见解，共同成长。</p>
          <p>Join our community to start your journey.</p>
        </div>
<!--        <img src="https://cdni.iconscout.com/illustration/premium/thumb/developer-working-on-laptop-5696120-4750917.png" alt="Login Illustration" class="illustration" />-->
      </div>

      <div class="login-right">
        <div class="form-box">
          <h2>欢迎回来 👋</h2>
          <p class="sub-title">请输入您的账号信息进行登录</p>

          <el-form
              ref="formRef"
              :model="loginForm"
              :rules="rules"
              label-position="top"
              size="large"
              class="login-form"
          >
            <el-form-item prop="email">
              <el-input
                  v-model="loginForm.email"
                  placeholder="请输入邮箱"
                  :prefix-icon="Message"
              />
            </el-form-item>

            <el-form-item prop="password">
              <el-input
                  v-model="loginForm.password"
                  type="password"
                  placeholder="请输入密码"
                  :prefix-icon="Lock"
                  show-password
                  @keyup.enter="handleLogin"
              />
            </el-form-item>

            <div class="actions-row">
              <el-checkbox v-model="rememberMe">记住我</el-checkbox>
              <el-link type="primary" :underline="false" @click="$router.push('/reset-password')">
                忘记密码？
              </el-link>
            </div>

            <el-form-item>
              <el-button type="primary" :loading="loading" class="login-btn" @click="handleLogin">
                立即登录
              </el-button>
            </el-form-item>

            <div class="register-row">
              还没有账号？
              <el-link type="primary" @click="$router.push('/register')">去注册</el-link>
            </div>
          </el-form>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Message, Lock } from '@element-plus/icons-vue'
import request from '../utils/request'

const router = useRouter()
const loading = ref(false)
const rememberMe = ref(false)
const formRef = ref(null)

const loginForm = reactive({
  email: '',
  password: ''
})

const rules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少6位', trigger: 'blur' }
  ]
}

// 登录逻辑
const handleLogin = async () => {
  // 表单校验
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        const res = await request.post('/api/auth/login', loginForm)

        if (res.code === 200) {
          // 后端返回的数据: { id: 1, token: "...", username: "...", avatar: "..." }
          const userData = res.data

          // 存储 Token 和 用户信息
          localStorage.setItem('token', userData.token)
          localStorage.setItem('user', JSON.stringify(userData))

          ElMessage.success('登录成功，欢迎回来！')

          // 稍微延迟跳转，提升体验
          setTimeout(() => {
            router.push('/home')
          }, 800)
        } else {
          ElMessage.error(res.msg || '登录失败')
        }
      } catch (e) {
        console.error(e)
      } finally {
        loading.value = false
      }
    }
  })
}
</script>

<style scoped lang="scss">
.login-container {
  height: 100vh;
  width: 100%;
  background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
  overflow: hidden;
}

/* 背景装饰球 */
.bg-shape {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  z-index: 1;
}
.shape-1 {
  width: 300px; height: 300px; background: #a18cd1; top: -50px; left: -50px; opacity: 0.6;
}
.shape-2 {
  width: 400px; height: 400px; background: #fbc2eb; bottom: -100px; right: -100px; opacity: 0.5;
}

.login-content {
  width: 1000px;
  height: 600px;
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(20px); /* 毛玻璃效果 */
  border-radius: 20px;
  box-shadow: 0 15px 35px rgba(0,0,0,0.1);
  display: flex;
  overflow: hidden;
  z-index: 2;
}

/* 左侧区域 */
.login-left {
  flex: 1.2;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  color: #fff;
  padding: 40px;
  position: relative;

  .welcome-text {
    z-index: 2; text-align: center; margin-bottom: 30px;
    h1 { font-size: 32px; font-weight: 700; margin-bottom: 15px; }
    p { font-size: 14px; opacity: 0.9; margin: 5px 0; }
  }
  .illustration {
    width: 80%; z-index: 2; object-fit: contain;
  }

  /* 装饰圆圈 */
  &::before {
    content: ''; position: absolute; width: 200px; height: 200px;
    background: rgba(255,255,255,0.1); border-radius: 50%; top: -50px; left: -50px;
  }
  &::after {
    content: ''; position: absolute; width: 150px; height: 150px;
    background: rgba(255,255,255,0.1); border-radius: 50%; bottom: 50px; right: -30px;
  }
}

/* 右侧区域 */
.login-right {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 40px;
  background: #fff;

  .form-box {
    width: 100%; max-width: 320px;
    h2 { font-size: 26px; font-weight: 700; color: #333; margin-bottom: 10px; }
    .sub-title { font-size: 14px; color: #999; margin-bottom: 30px; }
  }
}

.actions-row {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;
}

.login-btn {
  width: 100%; height: 44px; font-size: 16px; border-radius: 6px;
  background: linear-gradient(90deg, #667eea 0%, #764ba2 100%);
  border: none;
  &:hover { opacity: 0.9; }
}

.register-row {
  text-align: center; margin-top: 20px; font-size: 14px; color: #666;
}

/* 响应式适配 */
@media (max-width: 900px) {
  .login-left { display: none; }
  .login-content { width: 90%; max-width: 450px; height: auto; border-radius: 12px; }
  .login-right { padding: 40px 20px; }
}
</style>