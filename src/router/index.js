import { createRouter, createWebHistory } from 'vue-router'

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
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

export default router