package cumt.zongzuo.community.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.LoginDTO;
import cumt.zongzuo.community.dto.RegisterDTO;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.UserMapper;
import cumt.zongzuo.community.service.UserService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service // 标记这是一个业务逻辑组件
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // 注意：因为继承了 ServiceImpl，这里自带了 baseMapper (就是 UserMapper)，
    // 所以不需要再显式注入 UserMapper，直接用 baseMapper 即可，或者用 this.save() 等方法

    @Override
    public Result<String> sendCode(String email) {
        // 1. 生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 2. 存入 Redis (5分钟)
        redisTemplate.opsForValue().set("verify:email:" + email, code, 5, TimeUnit.MINUTES);

        // 3. 发送给 MQ
        Map<String, String> map = new HashMap<>();
        map.put("email", email);
        map.put("code", code);
        rabbitTemplate.convertAndSend("mail.queue", map);

        return Result.success("验证码已发送");
    }

    @Override
    public Result<String> register(RegisterDTO dto) {
        // 1. 校验验证码
        String key = "verify:email:" + dto.getEmail();
        String savedCode = redisTemplate.opsForValue().get(key);
        if (savedCode == null) return Result.error("验证码已过期");
        if (!savedCode.equals(dto.getCode())) return Result.error("验证码错误");

        // 2. 校验邮箱是否已存在 (使用 ServiceImpl 自带的 count 方法)
        if (count(new QueryWrapper<User>().eq("email", dto.getEmail())) > 0) {
            return Result.error("该邮箱已注册");
        }

        // 3. 校验用户名
        if (count(new QueryWrapper<User>().eq("username", dto.getUsername())) > 0) {
            return Result.error("用户名已存在");
        }

        // 4. 封装用户
        User user = new User();
        user.setEmail(dto.getEmail());
        user.setUsername(dto.getUsername());
        user.setPassword(BCrypt.hashpw(dto.getPassword())); // 加密
        user.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + dto.getUsername());

        // 5. 保存到数据库 (使用 ServiceImpl 自带的 save 方法)
        save(user);

        // 6. 删除 Redis 验证码
        redisTemplate.delete(key);

        return Result.success("注册成功");
    }

    @Override
    public Result<Map<String, Object>> login(LoginDTO dto) {
        // 1. 查询用户 (使用 ServiceImpl 自带的 getOne 方法)
        User user = getOne(new QueryWrapper<User>().eq("email", dto.getEmail()));

        if (user == null) return Result.error("用户不存在");

        // 2. 校验密码
        if (!BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            return Result.error("密码错误");
        }

        // 3. 生成 Token
        String token = JwtUtils.generateToken(user.getId());

        // 4. 返回数据
        Map<String, Object> map = new HashMap<>();
        map.put("token", token);
        map.put("username", user.getUsername());
        map.put("avatar", user.getAvatar());

        return Result.success(map);
    }
}