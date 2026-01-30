import request from '../utils/request'

// 上传文件
export function uploadFile(formData) {
    return request({
        url: '/api/file/upload',
        method: 'post',
        data: formData,
        headers: {
            'Content-Type': 'multipart/form-data'
        }
    })
}