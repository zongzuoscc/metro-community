import request from '../utils/request'

export const updateRemark = (data) => {
    return request.post('/api/follow/remark', null, { params: data })
}

// 【新增】搜索用户
export const searchUsers = (keyword, page) => {
    return request.get('/api/user/search', {
        params: { keyword, page }
    })
}