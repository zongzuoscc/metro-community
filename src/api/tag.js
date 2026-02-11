import request from '../utils/request'

// 获取全站热门标签
export const getHotTags = () => {
    return request.get('/api/tag/hot')
}