<template>
  <div class="report-page">
    <el-card>
      <el-tabs v-model="activeStatus" @tab-change="loadData">
        <el-tab-pane label="待处理" name="0"></el-tab-pane>
        <el-tab-pane label="已处理" name="1"></el-tab-pane>
        <el-tab-pane label="已驳回" name="2"></el-tab-pane>
      </el-tabs>

      <el-table :data="tableData" v-loading="loading" style="width: 100%; margin-top: 15px;">
        <el-table-column prop="reporterName" label="举报人" width="120" />
        <el-table-column label="对象类型" width="100">
          <template #default="scope">
            <el-tag v-if="scope.row.targetType === 1">文章</el-tag>
            <el-tag v-else-if="scope.row.targetType === 2" type="warning">评论</el-tag>
            <el-tag v-else type="info">用户</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetSnapshot" label="被举报内容摘要" min-width="200" show-overflow-tooltip />
        <el-table-column prop="reason" label="举报理由" width="180" />
        <el-table-column prop="createTime" label="时间" width="160">
          <template #default="scope">{{ formatTime(scope.row.createTime) }}</template>
        </el-table-column>

        <el-table-column v-if="activeStatus === '0'" label="操作" width="200">
          <template #default="scope">
            <el-button type="danger" size="small" @click="handleProcess(scope.row, true)">确认违规</el-button>
            <el-button type="info" size="small" @click="handleProcess(scope.row, false)">驳回举报</el-button>
          </template>
        </el-table-column>
        <el-table-column v-else label="处理结果" width="200">
          <template #default="scope">{{ scope.row.result || '无备注' }}</template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination background layout="prev, pager, next" :total="total" @current-change="handlePageChange" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getReportList, processReport } from '../../api/admin'
import { ElMessage, ElMessageBox } from 'element-plus'

const tableData = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const activeStatus = ref('0')

const loadData = async () => {
  loading.value = true
  try {
    const res = await getReportList(page.value, 10, activeStatus.value)
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

const handleProcess = (row, isViolation) => {
  const actionText = isViolation ? '确认违规并处罚' : '驳回举报'
  ElMessageBox.prompt(`请填写处理备注 (${actionText})`, '处理', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputValue: isViolation ? '内容违规，已删除' : '未发现违规'
  }).then(async ({ value }) => {
    try {
      await processReport({ id: row.id, isViolation, result: value })
      ElMessage.success('处理成功')
      loadData()
    } catch(e) {}
  })
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
.report-page :deep(.el-card) { background: #fffdf9; border: 1px solid var(--line); box-shadow: none; }
.report-page :deep(.el-table) { --el-table-header-bg-color: #f8f1e8; --el-table-tr-bg-color: #fffdf9; --el-table-border-color: var(--line); --el-table-row-hover-bg-color: #fff7ec; color: var(--ink); }
.report-page :deep(.el-tabs__item.is-active) { color: var(--accent); }
.report-page :deep(.el-tabs__active-bar) { background-color: var(--accent); }
.pagination { margin-top: var(--space-5); text-align: right; }
</style>
