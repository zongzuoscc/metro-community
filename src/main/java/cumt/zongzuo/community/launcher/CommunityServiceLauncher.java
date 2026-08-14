package cumt.zongzuo.community.launcher;

import org.springframework.boot.SpringApplication;

import java.util.LinkedHashMap;
import java.util.Map;

/** 统一构造三个进程的默认属性，环境变量和命令行参数仍可覆盖这些默认值。 */
final class CommunityServiceLauncher {

    private CommunityServiceLauncher() {
    }

    static Map<String, Object> defaults(CommunityServiceRole role, boolean rabbitAutoStartup) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.application.name", "metro-community-" + role.propertyValue());
        defaults.put("metro.service.role", role.propertyValue());
        defaults.put("server.port", Integer.toString(role.defaultPort()));
        defaults.put("spring.rabbitmq.listener.simple.auto-startup",
                Boolean.toString(rabbitAutoStartup));
        return Map.copyOf(defaults);
    }

    static void run(CommunityServiceRole role, Class<?> source,
                    Map<String, Object> defaults, String[] args) {
        SpringApplication application = new SpringApplication(
                CommunityServiceRuntimeConfiguration.class, source);
        // profile 专属配置的优先级高于共享 application.yml，因此即使主服务的
        // .env 定义了 SERVER_PORT，Agent 和 Worker 也不会错误抢占同一端口。
        application.setAdditionalProfiles(role.springProfile());
        application.setDefaultProperties(defaults);
        application.run(args);
    }
}
