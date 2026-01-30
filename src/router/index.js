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
// ...
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

export default router