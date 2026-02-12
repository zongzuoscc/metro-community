<template>
  <div class="audit-page">
    <el-card>
      <div slot="header" class="clearfix">
        <span>待审核文章</span>
        <el-button style="float: right; padding: 3px 0" type="text" @click="loadData">刷新</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" style="width: 100%">
        <el-table-column prop="title" label="标题" min-width="200">
          <template #default="scope">
            <a :href="`/article/${scope.row.id}`" target="_blank" class="title-link">{{ scope.row.title }}</a>
          </template>
        </el-table-column>
        <el-table-column prop="authorName" label="作者" width="120" />
        <el-table-column prop="createTime" label="提交时间" width="180">
          <template #default="scope">{{ formatTime(scope.row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="scope">
            <el-button type="success" size="small" @click="handleAudit(scope.row, true)">通过</el-button>
            <el-button type="danger" size="small" @click="openRejectDialog(scope.row)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination background layout="prev, pager, next" :total="total" @current-change="handlePageChange" />
      </div>
    </el-card>

    <el-dialog title="驳回原因" v-model="rejectVisible" width="400px">
      <el-input v-model="rejectReason" type="textarea" placeholder="请输入驳回原因..."></el-input>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getPendingArticles, auditArticle } from '../../api/admin'
import { ElMessage } from 'element-plus'

const tableData = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)

const rejectVisible = ref(false)
const rejectReason = ref('')
const currentItem = ref(null)

const loadData = async () => {
  loading.value = true
  try {
    const res = await getPendingArticles(page.value, 10)
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

// 审核通过
const handleAudit = async (row, pass) => {
  try {
    await auditArticle({ id: row.id, pass: pass, reason: pass ? '通过' : rejectReason.value })
    ElMessage.success(pass ? '已通过' : '已驳回')
    loadData()
  } catch(e) {}
}

// 打开驳回弹窗
const openRejectDialog = (row) => {
  currentItem.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}

const confirmReject = async () => {
  if (!currentItem.value) return
  await handleAudit(currentItem.value, false)
  rejectVisible.value = false
}

const formatTime = (val) => {
  if (!val) return ''

  // 情况1：如果是数组 [2023, 1, 1, 10, 20, 30]
  if (Array.isArray(val)) {
    const year = val[0]
    const month = val[1]
    const day = val[2]
    // 补零操作，防止出现 2023-1-1 这种格式，改为 2023-01-01
    const m = month < 10 ? '0' + month : month
    const d = day < 10 ? '0' + day : day
    return `${year}-${m}-${d}`
  }

  // 情况2：如果是字符串 "2023-01-01T10:20:30"
  if (typeof val === 'string') {
    return val.replace('T', ' ').substring(0, 10) // 只取年月日
  }

  return val
}

onMounted(() => loadData())
</script>

<style scoped>
.title-link { color: #409EFF; text-decoration: none; font-weight: bold; }
.title-link:hover { text-decoration: underline; }
.pagination { margin-top: 20px; text-align: right; }
</style>