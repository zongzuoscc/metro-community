<template>
  <div class="register-container">
    <el-card class="box-card">
      <div class="header">
        <h2>🚀 加入 Community</h2>
        <p>开发者技术分享社区</p>
      </div>
      
      <el-form :model="form" :rules="rules" ref="ruleFormRef" label-position="top">
        
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="请输入邮箱">
            <template #prefix><el-icon><Message /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item label="验证码" prop="code">
          <div class="code-container">
            <el-input v-model="form.code" placeholder="6位验证码">
                <template #prefix><el-icon><Key /></el-icon></template>
            </el-input>
            <el-button type="primary" :disabled="timer > 0" @click="handleSendCode">
              {{ timer > 0 ? `${timer}s后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="给自己起个好听的名字">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="设置密码">
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-button type="primary" class="submit-btn" :loading="loading" @click="handleRegister">
          立即注册
        </el-button>

        <div class="footer-links">
          <span>已有账号？</span>
          <router-link to="/login">去登录</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { sendCode, register } from '../api/auth'
import { ElMessage } from 'element-plus'

const router = useRouter()
const ruleFormRef = ref(null)
const loading = ref(false)
const timer = ref(0)

const form = reactive({
  email: '',
  code: '',
  username: '',
  password: ''
})

const rules = {
  email: [{ required: true, message: '请输入邮箱', trigger: 'blur' }],
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

// 发送验证码逻辑
const handleSendCode = async () => {
  if (!form.email) return ElMessage.warning('请先填写邮箱')
  
  try {
    await sendCode(form.email)
    ElMessage.success('验证码已发送')
    // 倒计时
    timer.value = 60
    const interval = setInterval(() => {
      timer.value--
      if (timer.value <= 0) clearInterval(interval)
    }, 1000)
  } catch (error) {
    // 错误在 request.js 里已经处理过了，这里不用管
  }
}

// 注册逻辑
const handleRegister = async () => {
  if (!ruleFormRef.value) return
  
  await ruleFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        await register(form)
        ElMessage.success('注册成功，快去登录吧！')
        router.push('/login') // 跳转到登录页
      } finally {
        loading.value = false
      }
    }
  })
}
</script>

<style scoped lang="scss">
.register-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  
  .box-card {
    width: 400px;
    padding: 20px;
    
    .header {
      text-align: center;
      margin-bottom: 30px;
      h2 { margin: 0 0 10px; color: #333; }
      p { margin: 0; color: #666; font-size: 14px; }
    }

    .code-container {
      display: flex;
      gap: 10px;
      .el-input { flex: 1; }
    }

    .submit-btn {
      width: 100%;
      margin-top: 10px;
      padding: 20px 0;
      font-size: 16px;
    }

    .footer-links {
      text-align: center;
      margin-top: 20px;
      font-size: 14px;
      color: #666;
      a {
        color: #409eff;
        text-decoration: none;
        margin-left: 5px;
      }
    }
  }
}
</style>