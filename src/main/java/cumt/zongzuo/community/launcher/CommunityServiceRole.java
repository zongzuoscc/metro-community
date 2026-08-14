package cumt.zongzuo.community.launcher;

/**
 * 社区系统的三个独立运行角色。
 *
 * <p>三个角色暂时复用同一套领域代码和基础设施，但拥有独立进程、端口和启动参数。
 * 这种方式与 KOB 项目中的主业务、匹配和 Bot 执行进程一致：先把运行职责拆开，
 * 不额外引入注册中心、配置中心或服务网关。</p>
 */
public enum CommunityServiceRole {
    BACKEND("backend", 18080),
    AGENT("agent", 18081),
    WORKER("worker", 18082);

    private final String propertyValue;
    private final int defaultPort;

    CommunityServiceRole(String propertyValue, int defaultPort) {
        this.propertyValue = propertyValue;
        this.defaultPort = defaultPort;
    }

    public String propertyValue() {
        return propertyValue;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public String springProfile() {
        return propertyValue + "-service";
    }
}
