<template>
  <div class="admin-layout">
    <div class="sidebar">
      <div class="logo">管理后台</div>
      <el-menu
          :default-active="$route.path"
          background-color="#3b2c28"
          text-color="#eadfd2"
          active-text-color="#e7a79c"
          router
          class="el-menu-vertical"
      >
        <el-menu-item index="/admin/audit">
          <el-icon><DocumentCheck /></el-icon>
          <span>文章审核</span>
        </el-menu-item>
        <el-menu-item index="/admin/report">
          <el-icon><Warning /></el-icon>
          <span>举报处理</span>
        </el-menu-item>
        <el-menu-item index="/admin/user">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item index="/home">
          <el-icon><HomeFilled /></el-icon>
          <span>返回前台</span>
        </el-menu-item>
      </el-menu>
    </div>

    <div class="main-content">
      <div class="header">
        <div class="breadcrumb">{{ $route.meta.title }}</div>
        <div class="user-info">管理员: {{ adminName }}</div>
      </div>
      <div class="page-container">
        <router-view />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const adminName = computed(() => {
  const str = localStorage.getItem('user')
  return str ? JSON.parse(str).username : 'Admin'
})
</script>

<style scoped>
.admin-layout { display: flex; height: 100vh; color: var(--ink); }
.sidebar { width: 232px; background-color: #3b2c28; display: flex; flex-direction: column; border-right: 1px solid #6d514a; }
.logo { height: 64px; line-height: 64px; text-align: center; color: #fff8ef; font-family: "Songti SC", SimSun, serif; font-size: 22px; font-weight: 700; letter-spacing: .08em; background: #332522; }
.el-menu-vertical { border-right: none; --el-menu-hover-bg-color: #513a35; --el-menu-active-color: #e7a79c; }
.main-content { flex: 1; display: flex; flex-direction: column; background: var(--paper-muted); }
.header { height: 64px; background: #fffdf9; padding: 0 28px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--line); box-shadow: none; }
.breadcrumb { font-size: 18px; font-family: "Songti SC", SimSun, serif; font-weight: 700; color: var(--ink); }
.user-info { color: var(--ink-muted); }
.page-container { padding: 28px; flex: 1; overflow-y: auto; }
@media (max-width: 650px) { .sidebar { width: 64px; } .sidebar :deep(.el-menu-item span), .logo { font-size: 0; } .page-container { padding: var(--space-3); } }
</style>
