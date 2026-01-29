import request from '../utils/request'

// 发送验证码
export function sendCode(email) {
    return request({
        url: '/api/auth/send-code',
        method: 'get',
        params: { email }
    })
}

// 注册
export function register(data) {
    return request({
        url: '/api/auth/register',
        method: 'post',
        data
    })
}

// 登录 (预留)
export function login(data) {
    return request({
        url: '/api/auth/login',
        method: 'post',
        data
    })
}