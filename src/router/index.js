import { createRouter, createWebHistory } from 'vue-router'

const routes = [
    { path: '/', redirect: '/login' },
    { path: '/login', name: 'Login', component: () => import('../views/Login.vue') },
    { path: '/register', name: 'Register', component: () => import('../views/Register.vue') },
    // 新增主页路由
    { path: '/home', name: 'Home', component: () => import('../views/Home.vue') },
    // ...
    {
        path: '/publish',
        name: 'Publish',
        component: () => import('../views/Publish.vue')
    },
    {
        path: '/article/:id',  // :id 是动态参数
        name: 'ArticleDetail',
        component: () => import('../views/ArticleDetail.vue')
    },
    {
        path: '/settings',
        name: 'UserSetting',
        component: () => import('../views/UserSetting.vue')
    },
// ...
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

export default router