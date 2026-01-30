<template>
  <div class="login-container">
    <div class="left-banner">
      <div class="banner-content">
        <h1>Community 开发者社区</h1>
        <p>连接 · 分享 · 共进</p>
        <p class="sub-text">加入我们，与万千开发者一起探索技术世界</p>
      </div>
    </div>

    <div class="right-form-container">
      <el-card class="box-card">
        <div class="header">
          <h2>👋 欢迎回来</h2>
          <p>请使用账号密码登录</p>
        </div>

        <el-form :model="form" :rules="rules" ref="ruleFormRef" size="large">
          <el-form-item prop="email">
            <el-input v-model="form.email" placeholder="请输入注册邮箱" prefix-icon="Message" />
          </el-form-item>

          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" prefix-icon="Lock" show-password />
          </el-form-item>

          <el-button type="primary" class="submit-btn" :loading="loading" @click="handleLogin" round>
            立即登录
          </el-button>

          <div class="footer-links">
            <span>还没有账号？</span>
            <router-link to="/register">免费注册一个</router-link>
          </div>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<script setup>
// ... script 部分代码与之前完全一致，不用动 ...
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api/auth'
import { ElMessage } from 'element-plus'

const router = useRouter()
const ruleFormRef = ref(null)
const loading = ref(false)

const form = reactive({ email: '', password: '' })

const rules = {
  email: [{ required: true, message: '请输入邮箱', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleLogin = async () => {
  if (!ruleFormRef.value) return
  await ruleFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        const res = await login(form)
        ElMessage.success('登录成功')
        localStorage.setItem('token', res.data.token)
        localStorage.setItem('user', JSON.stringify({
          username: res.data.username,
          avatar: res.data.avatar
        }))
        router.push('/home')
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
  display: flex;
  background: #fff;

  .left-banner {
    flex: 1; // 占据左侧剩余空间
    background-image: url('https://images.unsplash.com/photo-1518770660439-4636190af475?ixlib=rb-1.2.1&auto=format&fit=crop&w=1950&q=80'); // 科技感背景图
    background-size: cover;
    background-position: center;
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;

    // 加一个半透明遮罩层，让文字更清晰
    &::before {
      content: '';
      position: absolute;
      top: 0; left: 0; right: 0; bottom: 0;
      background: rgba(0, 0, 0, 0.5);
    }

    .banner-content {
      position: relative;
      z-index: 1;
      color: #fff;
      text-align: center;
      h1 { font-size: 3rem; margin-bottom: 20px; }
      p { font-size: 1.5rem; letter-spacing: 5px; }
      .sub-text { font-size: 1rem; margin-top: 30px; opacity: 0.8; letter-spacing: 1px; }
    }
  }

  .right-form-container {
    width: 500px; // 右侧固定宽度
    display: flex;
    align-items: center;
    justify-content: center;
    background: #fff;

    .box-card {
      width: 360px;
      border: none; // 去掉卡片边框，更融合
      box-shadow: none !important; // 去掉阴影

      .header {
        margin-bottom: 40px;
        h2 { font-size: 28px; margin-bottom: 10px; color: #333; }
        p { color: #999; font-size: 16px; }
      }
      .submit-btn { width: 100%; padding: 22px 0; font-size: 18px; margin-top: 20px; font-weight: bold; }
      .footer-links { margin-top: 20px; text-align: center; color: #666; a { color: #409EFF; text-decoration: none; font-weight: bold;} }
    }
  }
}

// 响应式处理：小屏幕下隐藏左侧
@media (max-width: 900px) {
  .login-container .left-banner { display: none; }
  .login-container .right-form-container { width: 100%; background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%); }
  .login-container .right-form-container .box-card { box-shadow: 0 2px 12px 0 rgba(0,0,0,0.1) !important; background: #fff; padding: 20px; border-radius: 8px;}
}
</style>