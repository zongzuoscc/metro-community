package cumt.zongzuo.community.launcher;

import java.util.Map;

/** Agent 与用户模型配置进程，默认监听 18081，不消费通用后台队列。 */
public final class CommunityAgentServiceApplication {

    private CommunityAgentServiceApplication() {
    }

    public static void main(String[] args) {
        CommunityServiceLauncher.run(CommunityServiceRole.AGENT,
                CommunityAgentServiceApplication.class,
                defaultProperties(), args);
    }

    static Map<String, Object> defaultProperties() {
        return CommunityServiceLauncher.defaults(CommunityServiceRole.AGENT, false);
    }
}
