import request from '../utils/request'

// 发布评论
export const publishComment = (data) => {
    return request.post('/api/comment/publish', data)
}

// 获取评论列表
export const getCommentList = (articleId) => {
    return request.get(`/api/comment/list/${articleId}`)
}

// 【新增】删除评论
export const deleteComment = (id) => {
    return request.delete(`/api/comment/${id}`)
}