<template>
  <div class="register-container">
    <div class="left-banner">
      <div class="banner-content">
        <h1>创建您的账号</h1>
        <p>开启技术之旅</p>
      </div>
    </div>

    <div class="right-form-container">
      <el-card class="box-card">
        <div class="header">
          <h2>🚀 加入 Community</h2>
          <p>只需几步，轻松注册</p>
        </div>

        <el-form :model="form" :rules="rules" ref="ruleFormRef" size="large">
          <el-form-item prop="email">
            <el-input v-model="form.email" placeholder="邮箱" prefix-icon="Message" />
          </el-form-item>

          <el-form-item prop="code">
            <div style="display: flex; width: 100%; gap: 10px;">
              <el-input v-model="form.code" placeholder="6位验证码" prefix-icon="Key" style="flex: 1"/>
              <el-button type="primary" :disabled="timer > 0" @click="handleSendCode" plain>
                {{ timer > 0 ? `${timer}s后重发` : '获取验证码' }}
              </el-button>
            </div>
          </el-form-item>

          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" prefix-icon="User" />
          </el-form-item>

          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="密码 (至少6位)" prefix-icon="Lock" show-password />
          </el-form-item>

          <el-button type="primary" class="submit-btn" :loading="loading" @click="handleRegister" round>
            立即注册
          </el-button>

          <div class="footer-links">
            <span>已有账号？</span>
            <router-link to="/login">直接登录</router-link>
          </div>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<script setup>
// ... script 部分代码与之前完全一致 ...
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { sendCode, register } from '../api/auth'
import { ElMessage } from 'element-plus'

const router = useRouter()
const ruleFormRef = ref(null)
const loading = ref(false)
const timer = ref(0)

const form = reactive({ email: '', code: '', username: '', password: '' })

const rules = {
  email: [{ required: true, message: '请输入邮箱', trigger: 'blur' }],
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleSendCode = async () => {
  if (!form.email) return ElMessage.warning('请先填写邮箱')
  try {
    await sendCode(form.email)
    ElMessage.success('验证码已发送')
    timer.value = 60
    const interval = setInterval(() => {
      timer.value--
      if (timer.value <= 0) clearInterval(interval)
    }, 1000)
  } catch (error) {}
}

const handleRegister = async () => {
  if (!ruleFormRef.value) return
  await ruleFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        await register(form)
        ElMessage.success('注册成功，快去登录吧！')
        router.push('/login')
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
  background: #fff;

  .left-banner {
    flex: 1;
    // 换一张背景图
    background-image: url('https://images.unsplash.com/photo-1498050108023-c5249f4df085?ixlib=rb-1.2.1&auto=format&fit=crop&w=1952&q=80');
    background-size: cover;
    background-position: center;
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    &::before { content: ''; position: absolute; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0, 0, 0, 0.4); }
    .banner-content {
      position: relative; z-index: 1; color: #fff; text-align: center;
      h1 { font-size: 3rem; margin-bottom: 20px; }
      p { font-size: 1.5rem; letter-spacing: 3px; }
    }
  }

  .right-form-container {
    width: 500px;
    display: flex; align-items: center; justify-content: center; background: #fff;
    .box-card {
      width: 380px; border: none; box-shadow: none !important;
      .header { margin-bottom: 30px; h2 { font-size: 26px; margin-bottom: 10px; color: #333; } p { color: #999; font-size: 16px; }}
      .submit-btn { width: 100%; padding: 22px 0; font-size: 18px; margin-top: 10px; font-weight: bold; }
      .footer-links { margin-top: 20px; text-align: center; color: #666; a { color: #409EFF; text-decoration: none; font-weight: bold;} }
    }
  }
}
// 同样加上响应式处理
@media (max-width: 900px) {
  .register-container .left-banner { display: none; }
  .register-container .right-form-container { width: 100%; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); }
  .register-container .right-form-container .box-card { box-shadow: 0 2px 12px 0 rgba(0,0,0,0.1) !important; background: #fff; padding: 20px; border-radius: 8px;}
}
</style>