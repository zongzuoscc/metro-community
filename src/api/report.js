import request from '../utils/request'

// 提交举报
// data: { targetId, targetType, reason }
export const submitReport = (data) => {
    return request.post('/api/report/submit', data)
}