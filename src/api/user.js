import request from '../utils/request'

export const updateRemark = (data) => {
    return request.post('/api/follow/remark', null, { params: data })
}