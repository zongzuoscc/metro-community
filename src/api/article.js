import request from '../utils/request'

// 获取热榜
export const getHotRank = () => {
    return request.get('/api/article/hot-rank')
}

// 获取文章详情
export const getArticleDetail = (id) => {
    return request.get(`/api/article/detail/${id}`)
}

// ---------------- 新增接口 ----------------

// 发布文章 (新增或修改，status=1)
// 发布文章 (修改后：不再需要 isPublish 参数，因为它在 data 里)
export const publishArticle = (data) => {
    return request.post('/api/article/publish', data)
}
// 存为草稿 (新增或修改，status=0)
export const saveDraft = (data) => {
    return request.post('/api/article/draft', data)
}

// 删除文章
export const deleteArticle = (id) => {
    return request.delete(`/api/article/${id}`)
}

// 获取我的草稿列表
export const getDrafts = () => {
    return request.get('/api/article/drafts')
}

// 获取文章详情用于编辑 (回显)
export const getArticleForEdit = (id) => {
    return request.get(`/api/article/edit/${id}`)
}

// 获取某用户的文章列表 (已发布)
export const getUserArticles = (userId, page, size) => {
    return request.get(`/api/article/user/${userId}`, {
        params: { page, size }
    })
}

// 获取回收站
export const getRecycleBin = () => {
    return request.get('/api/article/recycle-bin')
}
// 恢复文章
export const restoreArticle = (id) => {
    return request.post(`/api/article/restore/${id}`)
}
// 彻底删除
export const hardDeleteArticle = (id) => {
    return request.delete(`/api/article/hard/${id}`)
}

// 【新增】获取草稿数量
export const getDraftCount = () => {
    return request.get('/api/article/draft-count')
}

// 【新增】获取7天热榜
export const getHotFeed = () => {
    return request.get('/api/article/hot-feed')
}

// 【新增】获取关注流
export const getFollowFeed = (page) => {
    return request.get('/api/article/follow-feed', {
        params: { page }
    })
}

// 【新增】搜索文章
export const searchArticles = (keyword, page) => {
    return request.get('/api/article/search', {
        params: { keyword, page }
    })
}

// 【修改后】调用新的查询“我的全部文章”接口
export const getMyArticles = (page) => {
    return request.get('/article/my/list', { params: page })
}