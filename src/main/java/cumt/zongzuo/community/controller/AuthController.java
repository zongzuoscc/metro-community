package cumt.zongzuo.community.controller;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.RegisterDTO;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.UserMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送验证码接口
     * GET /api/auth/send-code?email=xxx@qq.com
     */
    @GetMapping("/send-code")
    public Result<String> sendCode(@RequestParam String email) {
        // 1. 简单的防刷校验 (可选：检查 Redis 里是否已有未过期的验证码)
        // if (redisTemplate.hasKey("code:" + email)) { return Result.error("操作太频繁"); }

        // 2. 生成 6 位随机数字验证码
        String code = RandomUtil.randomNumbers(6);

        // 3. 存入 Redis，有效期 5 分钟
        // Key: "verify:email:123@qq.com"  Value: "884822"
        redisTemplate.opsForValue().set("verify:email:" + email, code, 5, TimeUnit.MINUTES);

        // 4. 发送消息到 RabbitMQ (异步处理)
        Map<String, String> map = new HashMap<>();
        map.put("email", email);
        map.put("code", code);
        rabbitTemplate.convertAndSend("mail.queue", map);

        return Result.success("验证码已发送，请注意查收");
    }

    /**
     * 注册接口
     * POST /api/auth/register
     */
    @PostMapping("/register")
    public Result<String> register(@RequestBody RegisterDTO dto) {
        // 1. 校验验证码
        String key = "verify:email:" + dto.getEmail();
        String savedCode = redisTemplate.opsForValue().get(key);

        if (savedCode == null) {
            return Result.error("验证码已过期，请重新发送");
        }
        if (!savedCode.equals(dto.getCode())) {
            return Result.error("验证码错误");
        }

        // 2. 校验邮箱是否已注册
        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("email", dto.getEmail());
        if (userMapper.selectCount(query) > 0) {
            return Result.error("该邮箱已被注册");
        }

        // 3. 校验用户名是否已存在
        QueryWrapper<User> queryName = new QueryWrapper<>();
        queryName.eq("username", dto.getUsername());
        if (userMapper.selectCount(queryName) > 0) {
            return Result.error("用户名已存在，换一个试试");
        }

        // 4. 初始化用户并保存
        User user = new User();
        user.setEmail(dto.getEmail());
        user.setUsername(dto.getUsername());
        user.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + dto.getUsername()); // 随机生成一个头像

        // 【重点】密码千万不能存明文！必须加密
        // 这里使用 Hutool 的 BCrypt 工具，自动加盐
        String hashedPassword = BCrypt.hashpw(dto.getPassword());
        user.setPassword(hashedPassword);

        // 5. 写入数据库
        userMapper.insert(user);

        // 6. 删除 Redis 里的验证码 (防止重复使用)
        redisTemplate.delete(key);

        return Result.success("注册成功！请登录");
    }
}
