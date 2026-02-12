<template>
  <div class="user-page">
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>用户列表</span>
          <el-input v-model="keyword" placeholder="搜索用户名" style="width: 200px" @keyup.enter="loadData">
            <template #append>
              <el-button @click="loadData"><el-icon><Search/></el-icon></el-button>
            </template>
          </el-input>
        </div>
      </template>

      <el-table :data="tableData" v-loading="loading" style="width: 100%">
        <el-table-column label="头像" width="80">
          <template #default="scope">
            <el-avatar :src="scope.row.avatar" :size="40" icon="UserFilled"></el-avatar>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户名" width="150" />
        <el-table-column prop="email" label="邮箱" width="200" />
        <el-table-column prop="createTime" label="注册时间" width="180">
          <template #default="scope">{{ formatTime(scope.row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag v-if="scope.row.status === 1" type="danger">封禁中</el-tag>
            <el-tag v-else type="success">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作">
          <template #default="scope">
            <el-button
                v-if="scope.row.status === 0"
                type="danger"
                size="small"
                @click="openBanDialog(scope.row)"
            >
              封号
            </el-button>
            <el-button
                v-else
                type="success"
                size="small"
                @click="handleUnban(scope.row)"
            >
              解封
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
            background
            layout="prev, pager, next"
            :total="total"
            @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <el-dialog title="账号封禁" v-model="banDialogVisible" width="400px" align-center>
      <el-form label-width="80px">
        <el-form-item label="当前用户">
          <strong>{{ currentBanUser?.username }}</strong>
        </el-form-item>
        <el-form-item label="封禁时长">
          <el-select v-model="banDays" placeholder="请选择时长" style="width: 100%">
            <el-option label="1 天" :value="1"></el-option>
            <el-option label="3 天" :value="3"></el-option>
            <el-option label="7 天" :value="7"></el-option>
            <el-option label="30 天" :value="30"></el-option>
            <el-option label="永久封禁" :value="-1"></el-option>
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="banDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmBan">确定封禁</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
// 确保 api/admin.js 中的 updateUserStatus 已更新为支持 (userId, status, days) 参数
import { getUserList, updateUserStatus } from '../../api/admin'
import { ElMessage, ElMessageBox } from 'element-plus'

const tableData = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const keyword = ref('')

// 封禁弹窗相关变量
const banDialogVisible = ref(false)
const banDays = ref(1)
const currentBanUser = ref(null)

const loadData = async () => {
  loading.value = true
  try {
    const res = await getUserList(page.value, 10, keyword.value)
    tableData.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

const handlePageChange = (val) => {
  page.value = val
  loadData()
}

// 打开封禁弹窗
const openBanDialog = (row) => {
  currentBanUser.value = row
  banDays.value = 1 // 默认选1天
  banDialogVisible.value = true
}

// 确认封禁
const confirmBan = async () => {
  if (!currentBanUser.value) return

  try {
    // 调用接口：status=1 表示封禁，banDays 传递天数
    await updateUserStatus(currentBanUser.value.id, 1, banDays.value)
    ElMessage.success('封禁成功')
    banDialogVisible.value = false
    loadData() // 刷新列表
  } catch (e) {
    // 错误通常由拦截器统一处理，也可在此处理
  }
}

// 解封操作
const handleUnban = (row) => {
  ElMessageBox.confirm(`确定要解封用户 "${row.username}" 吗？`, '提示', {
    type: 'warning',
    confirmButtonText: '确定解封',
    cancelButtonText: '取消'
  }).then(async () => {
    try {
      // 调用接口：status=0 表示解封，天数传什么都行，后端会忽略
      await updateUserStatus(row.id, 0, 0)
      ElMessage.success('解封成功')
      loadData()
    } catch (e) {}
  })
}

// 时间格式化（兼容数组和字符串）
const formatTime = (val) => {
  if (!val) return ''

  // 情况1：数组 [2023, 1, 1, 12, 0, 0]
  if (Array.isArray(val)) {
    const year = val[0]
    const month = val[1] < 10 ? '0' + val[1] : val[1]
    const day = val[2] < 10 ? '0' + val[2] : val[2]
    return `${year}-${month}-${day}`
  }

  // 情况2：字符串 "2023-01-01T12:00:00"
  if (typeof val === 'string') {
    return val.replace('T', ' ').substring(0, 10)
  }

  return val
}

onMounted(() => loadData())
</script>

<style scoped>
.pagination { margin-top: 20px; text-align: right; }
</style>