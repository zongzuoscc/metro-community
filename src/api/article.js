import request from '../utils/request'

// 获取热门文章列表
export function getHotArticles() {
    return request({
        url: '/api/article/hot',
        method: 'get'
    })
}

// 获取右侧全局热榜
export function getHotRank() {
    return request({
        url: '/api/article/hot-rank',
        method: 'get'
    })
}