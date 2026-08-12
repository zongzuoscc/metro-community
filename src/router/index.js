import { createRouter, createWebHistory } from 'vue-router'

const AdminLayout = () => import('../views/admin/AdminLayout.vue')
const ArticleAudit = () => import('../views/admin/ArticleAudit.vue')
const ReportManage = () => import('../views/admin/ReportManage.vue')
const UserManage = () => import('../views/admin/UserManage.vue')


const routes = [
    // 根路径重定向到登录页
    { path: '/', redirect: '/login' },

    // 登录页
    {
        path: '/login',
        name: 'Login',
        component: () => import('../views/Login.vue')
    },

    // 注册页
    {
        path: '/register',
        name: 'Register',
        component: () => import('../views/Register.vue')
    },

    // 主页
    {
        path: '/home',
        name: 'Home',
        component: () => import('../views/Home.vue')
    },

    // 发布文章
    {
        path: '/publish',
        name: 'Publish',
        component: () => import('../views/Publish.vue')
    },

    // 文章详情
    {
        path: '/article/:id',  // :id 是动态参数
        name: 'ArticleDetail',
        component: () => import('../views/ArticleDetail.vue')
    },

    // 个人设置 (修改资料、密码等 - 对内)
    {
        path: '/settings', // 也可以改成 /user/settings，看你喜好
        name: 'UserSetting',
        component: () => import('../views/UserSetting.vue')
    },

    // 【新增】个人中心 (展示页 - 对外)
    {
        path: '/user/:id', // :id 是用户ID
        name: 'UserCenter',
        component: () => import('../views/UserCenter.vue')
    },
    {
        path: '/favorite/:id',
        name: 'FavoriteDetail',
        component: () => import('../views/FavoriteDetail.vue')
    },
    {
        path: '/reset-password',
        name: 'ResetPassword',
        component: () => import('../views/ResetPassword.vue')
    },
    {
        path: '/message',
        name: 'Message',
        component: () => import('../views/Message.vue')
    },
    {
        path: '/chat',
        name: 'Chat',
        component: () => import('../views/Chat.vue')
    },
    // Agent 使用登录用户的历史、记忆设置和临时会话所有权，因此必须经过统一登录守卫。
    {
        path: '/agent',
        name: 'Agent',
        component: () => import('../views/Agent.vue'),
        meta: { requiresAuth: true }
    },
    {
        path: '/admin',
        component: AdminLayout,
        redirect: '/admin/audit',
        meta: { requiresAuth: true, requiresAdmin: true }, // 标记需要管理员权限
        children: [
            { path: 'audit', component: ArticleAudit, meta: { title: '文章审核' } },
            { path: 'report', component: ReportManage, meta: { title: '举报处理' } },
            { path: 'user', component: UserManage, meta: { title: '用户管理' } }
        ]
    },
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

export default router

// 【修改】路由守卫
router.beforeEach((to, from, next) => {
    const token = localStorage.getItem('token')
    const userStr = localStorage.getItem('user')
    const user = userStr ? JSON.parse(userStr) : {}

    // 1. 检查登录
    if (to.meta.requiresAuth && !token) {
        return next('/login')
    }

    // 2. 检查管理员权限
    if (to.meta.requiresAdmin) {
        // 假设 role=1 是管理员
        if (user.role !== 1) {
            alert('无权访问管理后台')
            return next('/home')
        }
    }

    next()
})
