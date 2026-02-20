package cumt.zongzuo.community.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义限流注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流的时间窗口 (单位：秒)
     */
    int time() default 60;

    /**
     * 在时间窗口内允许的最大请求次数
     */
    int count() default 5;

    /**
     * 限流的业务名称 (用于区分 Redis Key，如 "publish_article", "like")
     */
    String name() default "default";
}