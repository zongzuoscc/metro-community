import axios from 'axios'
import { ElMessage } from 'element-plus'
// 引入 router 以便跳转
import router from '../router'

const service = axios.create({
    baseURL: 'http://localhost:8080',
    timeout: 10000
})

// 【新增】请求拦截器：每次请求都带上 Token
service.interceptors.request.use(
    config => {
        const token = localStorage.getItem('token')
        if (token) {
            config.headers['token'] = token // 把 Token 塞进请求头
        }
        return config
    },
    error => Promise.reject(error)
)

// 响应拦截器
service.interceptors.response.use(
    response => {
        const res = response.data
        if (res.code !== 200) {
            ElMessage.error(res.msg || '系统错误')
            return Promise.reject(new Error(res.msg || 'Error'))
        }
        return res
    },
    error => {
        // 【关键】处理 401 状态码
        if (error.response && error.response.status === 401) {
            ElMessage.error('登录已过期，请重新登录')
            localStorage.removeItem('token') // 清除脏数据
            localStorage.removeItem('user')
            router.push('/login') // 强制跳转
        } else {
            ElMessage.error(error.message || '网络异常')
        }
        return Promise.reject(error)
    }
)

export default service