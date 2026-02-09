package cumt.zongzuo.community.controller;

import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.utils.OssUtils; // 【关键】使用你项目里真实存在的工具类
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/file") // 确保路径是这个
public class FileController {

    @Autowired
    private OssUtils ossUtils; // 注入你的 OssUtils

    @PostMapping("/upload")
    public Result<String> upload(MultipartFile file) {
        try {
            // 调用你原本的方法 uploadFile
            String url = ossUtils.uploadFile(file);
            return Result.success(url);
        } catch (IOException e) {
            e.printStackTrace();
            return Result.error("文件上传失败");
        }
    }
}