package cumt.zongzuo.community.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import cumt.zongzuo.community.common.Result;
import cumt.zongzuo.community.dto.LoginDTO;
import cumt.zongzuo.community.dto.RegisterDTO;
import cumt.zongzuo.community.dto.ResetPasswordDTO;
import cumt.zongzuo.community.dto.UpdatePasswordDTO;
import cumt.zongzuo.community.entity.Article;
import cumt.zongzuo.community.entity.Follow;
import cumt.zongzuo.community.entity.User;
import cumt.zongzuo.community.mapper.ArticleMapper;
import cumt.zongzuo.community.mapper.FollowMapper;
import cumt.zongzuo.community.mapper.UserMapper;
import cumt.zongzuo.community.service.UserService;
import cumt.zongzuo.community.utils.JwtUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import cumt.zongzuo.community.service.FavoriteService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service // 标记这是一个业务逻辑组件
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private FollowMapper followMapper;

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

        // 6. 【新增】为新用户创建默认收藏夹
        favoriteService.createDefaultFolder(user.getId());

        // 7. 删除 Redis 验证码
        redisTemplate.delete(key);

        return Result.success("注册成功");
    }

    @Override
    public Result<Map<String, Object>> login(LoginDTO dto) {
        // 1. 查询用户
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

        // 【核心修复】必须把 ID 返回给前端！
        map.put("id", user.getId());

        map.put("username", user.getUsername());
        map.put("avatar", user.getAvatar());

        return Result.success(map);
    }

    @Override
    public User getUserProfile(Long targetUserId, Long currentUserId) {
        // 1. 查基本信息
        User user = getById(targetUserId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        // 脱敏
        user.setPassword(null);

        // 2. 统计文章数
        Long articleCount = articleMapper.selectCount(new QueryWrapper<Article>().eq("author_id", targetUserId));
        user.setArticleCount(articleCount);

        // 3. 统计获赞数 (调用 ArticleMapper 中写好的 SQL)
        Long likeCount = articleMapper.sumLikesByAuthorId(targetUserId);
        user.setLikeCount(likeCount == null ? 0 : likeCount);

        // 4. 统计关注数 (我关注了多少人)
        Long followingCount = followMapper.selectCount(new QueryWrapper<Follow>().eq("follower_id", targetUserId));
        user.setFollowingCount(followingCount);

        // 5. 统计粉丝数 (有多少人关注我)
        Long fanCount = followMapper.selectCount(new QueryWrapper<Follow>().eq("followed_id", targetUserId));
        user.setFanCount(fanCount);

        // 6. 判断我是否关注了他
        if (currentUserId != null && !currentUserId.equals(targetUserId)) {
            Long count = followMapper.selectCount(new QueryWrapper<Follow>()
                    .eq("follower_id", currentUserId)
                    .eq("followed_id", targetUserId));
            user.setIsFollowed(count > 0);
        } else {
            user.setIsFollowed(false);
        }

        return user;
    }

    @Override
    public void updatePassword(Long userId, UpdatePasswordDTO dto) {
        // 1. 查出当前用户
        User user = getById(userId);

        // 2. 校验旧密码
        if (!BCrypt.checkpw(dto.getOldPassword(), user.getPassword())) {
            throw new RuntimeException("旧密码不正确");
        }

        // 3. 校验新密码长度等 (简单校验)
        if (dto.getNewPassword().length() < 6) {
            throw new RuntimeException("新密码长度不能少于6位");
        }

        // 4. 加密并更新
        user.setPassword(BCrypt.hashpw(dto.getNewPassword()));
        updateById(user);
    }

    @Override
    public Result<String> resetPassword(ResetPasswordDTO dto) {
        // 1. 校验验证码
        String key = "verify:email:" + dto.getEmail();
        String savedCode = redisTemplate.opsForValue().get(key);

        if (savedCode == null) return Result.error("验证码已过期，请重新发送");
        if (!savedCode.equals(dto.getCode())) return Result.error("验证码错误");

        // 2. 查询用户是否存在
        User user = getOne(new QueryWrapper<User>().eq("email", dto.getEmail()));
        if (user == null) {
            return Result.error("该邮箱未注册");
        }

        // 3. 校验新密码长度
        if (dto.getNewPassword().length() < 6) {
            return Result.error("密码长度至少6位");
        }

        // 4. 重置密码
        user.setPassword(BCrypt.hashpw(dto.getNewPassword()));
        updateById(user);

        // 5. 删除验证码 (防止被重复使用)
        redisTemplate.delete(key);

        return Result.success("密码重置成功");
    }

    @Override
    public Page<User> searchUsers(String keyword, int page, int size) {
        Page<User> pageInfo = new Page<>(page, size);
        QueryWrapper<User> query = new QueryWrapper<>();

        // 搜索条件：用户名 OR 简介包含关键词
        if (StrUtil.isNotBlank(keyword)) {
            query.and(w -> w.like("username", keyword)
                    .or().like("intro", keyword));
        }

        // 不需要查密码
        query.select(User.class, info -> !info.getColumn().equals("password"));

        return page(pageInfo, query);
    }
}