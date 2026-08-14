package cumt.zongzuo.community.launcher;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

import java.util.Locale;
import java.util.Objects;

/**
 * 在 Spring 实例化 Controller 以前，按运行角色裁剪不属于当前进程的 HTTP 入口。
 *
 * <p>三个可执行 JAR 暂时仍共享同一套领域服务、Mapper 和消息模型，避免复制业务规则；
 * 但 HTTP 入口必须有明确归属，否则“拆成三个端口”只是表面拆分，任意进程仍能处理
 * 全部请求。这个后处理器只删除 Controller/Advice 的 BeanDefinition，不删除领域服务，
 * 因而 Worker 仍可以复用文章、审核和投影逻辑。</p>
 */
final class CommunityRoleBeanBoundary implements BeanDefinitionRegistryPostProcessor {

    private static final String AGENT_WEB_PACKAGE = "cumt.zongzuo.community.ai.agent.web.";
    private static final String USER_PROVIDER_CONTROLLER =
            "cumt.zongzuo.community.ai.userprovider.UserAiProviderController";
    private static final String AI_ADVICE_PACKAGE = "cumt.zongzuo.community.ai.web.";

    private final CommunityServiceRole role;

    CommunityRoleBeanBoundary(CommunityServiceRole role) {
        this.role = Objects.requireNonNull(role, "role");
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        for (String beanName : registry.getBeanDefinitionNames()) {
            String className = registry.getBeanDefinition(beanName).getBeanClassName();
            if (className != null && !isAllowed(role, className)) {
                registry.removeBeanDefinition(beanName);
            }
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // 所有裁剪都必须在 BeanDefinition 阶段完成；这里不再操作已经创建的 Bean。
    }

    static boolean isAllowed(CommunityServiceRole role, String className) {
        if (!isHttpEntryPoint(className)) {
            return true;
        }
        return switch (role) {
            case BACKEND -> !isAgentEntryPoint(className);
            case AGENT -> isAgentEntryPoint(className) || className.startsWith(AI_ADVICE_PACKAGE);
            case WORKER -> false;
        };
    }

    private static boolean isAgentEntryPoint(String className) {
        return className.startsWith(AGENT_WEB_PACKAGE)
                || className.equals(USER_PROVIDER_CONTROLLER);
    }

    private static boolean isHttpEntryPoint(String className) {
        // Spring Boot、第三方 SDK 也大量使用 `.web.` 包名。角色边界只能处理本项目自己的
        // BeanDefinition，否则会误删 Tomcat、Actuator 或安全框架的自动配置。
        if (!className.startsWith("cumt.zongzuo.community.")) {
            return false;
        }
        String simpleName = className.substring(className.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        return className.contains(".controller.")
                || className.contains(".web.")
                || simpleName.endsWith("controller")
                || simpleName.endsWith("controlleradvice")
                || simpleName.endsWith("exceptionhandler")
                || simpleName.endsWith("problemdetailadvice");
    }
}
