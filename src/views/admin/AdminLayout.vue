<template>
  <div class="admin-layout">
    <div class="sidebar">
      <div class="logo">管理后台</div>
      <el-menu
          :default-active="$route.path"
          background-color="#304156"
          text-color="#bfcbd9"
          active-text-color="#409EFF"
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
.admin-layout { display: flex; height: 100vh; }
.sidebar { width: 220px; background-color: #304156; display: flex; flex-direction: column; }
.logo { height: 60px; line-height: 60px; text-align: center; color: #fff; font-size: 20px; font-weight: bold; background: #2b3a4d; }
.el-menu-vertical { border-right: none; }
.main-content { flex: 1; display: flex; flex-direction: column; background: #f0f2f5; }
.header { height: 60px; background: #fff; padding: 0 20px; display: flex; align-items: center; justify-content: space-between; box-shadow: 0 1px 4px rgba(0,21,41,.08); }
.breadcrumb { font-size: 16px; font-weight: 600; color: #333; }
.page-container { padding: 20px; flex: 1; overflow-y: auto; }
</style>