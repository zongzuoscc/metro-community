package cumt.zongzuo.community.utils;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

@Component
public class OssUtils {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    /**
     * 上传文件并返回 URL
     */
    public String uploadFile(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.lastIndexOf('.') >= 0
                ? originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase()
                : "";
        LocalDate today = LocalDate.now();
        String fileName = "uploads/%d/%02d/%s%s".formatted(
                today.getYear(), today.getMonthValue(), UUID.randomUUID(), extension);

        // 3. 创建 OSS 客户端
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);

        try {
            // 4. 上传
            ossClient.putObject(bucketName, fileName, file.getInputStream());

            // 5. 拼接返回路径 (假设是公共读 Bucket)
            // 格式: https://bucket-name.endpoint/filename
            // 注意: endpoint 中可能包含 http://，需要处理一下
            String urlEndpoint = endpoint;
            if (!endpoint.startsWith("http")) {
                urlEndpoint = "https://" + endpoint;
            }
            return urlEndpoint.replace("https://", "https://" + bucketName + ".") + "/" + fileName;

        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }
}
