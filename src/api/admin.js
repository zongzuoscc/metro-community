import request from '../utils/request'

// 1. 获取待审核文章列表
export const getPendingArticles = (page, size) => {
    return request.get('/api/article/admin/pending', { params: { page, size } })
}

// 2. 审核文章 (pass=true 通过, false 驳回)
export const auditArticle = (data) => {
    return request.post('/api/article/admin/audit', data)
}

// 3. 获取举报列表
// status: 0待处理, 1已处理, 2已驳回
export const getReportList = (page, size, status) => {
    return request.get('/api/report/admin/list', { params: { page, size, status } })
}

// 4. 处理举报
// isViolation: true 确认违规(并处罚), false 驳回举报
export const processReport = (data) => {
    return request.post('/api/report/admin/process', data)
}

// 5. 获取用户列表 (支持搜索)
export const getUserList = (page, size, keyword) => {
    return request.get('/api/user/admin/list', { params: { page, size, keyword } })
}

// 6. 封禁/解封用户
// status: 1封禁, 0正常
export const updateUserStatus = (userId, status, days) => {
    return request.post('/api/user/admin/status', { userId, status, days })
}