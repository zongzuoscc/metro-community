import axios from 'axios'
import { ElMessage } from 'element-plus'

// 创建 axios 实例
const service = axios.create({
    baseURL: 'http://localhost:8080', // 后端地址
    timeout: 10000 // 请求超时时间
})

// 响应拦截器 (后端返回结果后，先经过这里)
service.interceptors.response.use(
    response => {
        const res = response.data
        // 如果后端返回 code 不是 200，说明出错了
        if (res.code !== 200) {
            ElMessage.error(res.msg || '系统错误')
            return Promise.reject(new Error(res.msg || 'Error'))
        }
        return res
    },
    error => {
        console.error('err' + error)
        ElMessage.error(error.message)
        return Promise.reject(error)
    }
)

export default service