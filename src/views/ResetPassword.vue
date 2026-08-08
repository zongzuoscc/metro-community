<template>
  <div class="reset-container">
    <el-card class="reset-card">
      <template #header>
        <div class="card-header">
          <span>🔐 重置密码</span>
          <el-button link @click="$router.push('/login')">返回登录</el-button>
        </div>
      </template>

      <el-form :model="form" label-position="top" size="large" :rules="rules" ref="formRef">

        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="请输入注册时的邮箱" prefix-icon="Message" />
        </el-form-item>

        <el-form-item label="验证码" prop="code">
          <div class="code-box">
            <el-input v-model="form.code" placeholder="6位验证码" prefix-icon="Key" />
            <el-button type="primary" :disabled="timer > 0" @click="sendCode">
              {{ timer > 0 ? `${timer}s后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="新密码" prop="newPassword">
          <el-input
              v-model="form.newPassword"
              type="password"
              placeholder="请输入新密码"
              prefix-icon="Lock"
              show-password
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="loading" class="submit-btn" @click="handleReset">
            确认重置
          </el-button>
        </el-form-item>

      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../utils/request'

const router = useRouter()
const loading = ref(false)
const formRef = ref(null)
const timer = ref(0)
let intervalId = null

const form = reactive({
  email: '',
  code: '',
  newPassword: ''
})

const rules = {
  email: [{ required: true, message: '请输入邮箱', trigger: 'blur' }, { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { min: 6, message: '长度至少6位', trigger: 'blur' }]
}

// 发送验证码
const sendCode = async () => {
  if(!form.email) return ElMessage.warning('请先输入邮箱')

  // 简单的邮箱格式校验
  if(!/^\w+([-+.]\w+)*@\w+([-.]\w+)*\.\w+([-.]\w+)*$/.test(form.email)) {
    return ElMessage.warning('邮箱格式不正确')
  }

  try {
    await request.get('/api/auth/send-code', { params: { email: form.email } })
    ElMessage.success('验证码已发送，请查收邮件')

    // 倒计时
    timer.value = 60
    intervalId = setInterval(() => {
      timer.value--
      if(timer.value <= 0) clearInterval(intervalId)
    }, 1000)
  } catch(e) {
    ElMessage.error(e.msg || '发送失败')
  }
}

// 提交重置
const handleReset = () => {
  formRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        const res = await request.post('/api/auth/reset-password', form)
        if (res.code === 200) {
          ElMessage.success('密码重置成功，请重新登录')
          setTimeout(() => {
            router.push('/login')
          }, 1500)
        } else {
          ElMessage.error(res.msg || '重置失败')
        }
      } catch(e) {
        // error handled
      } finally {
        loading.value = false
      }
    }
  })
}

onUnmounted(() => {
  if(intervalId) clearInterval(intervalId)
})
</script>

<style scoped lang="scss">
.reset-container {
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background-color: #f6f8fa;
}
.reset-card {
  width: 400px;
  .card-header {
    display: flex; justify-content: space-between; align-items: center; font-weight: 600;
  }
}
.code-box {
  display: flex; gap: 10px; width: 100%;
}
.submit-btn {
  width: 100%;
}

.reset-container { background: var(--paper-muted); }
.reset-card { border: 1px solid var(--line); border-radius: var(--radius-sm); background: #fffdf9; box-shadow: none; }
.reset-card .card-header { color: var(--ink); font-family: "Songti SC", SimSun, serif; font-size: 20px; }
</style>
