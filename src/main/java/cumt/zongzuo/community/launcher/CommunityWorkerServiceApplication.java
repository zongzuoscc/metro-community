package cumt.zongzuo.community.launcher;

import java.util.Map;

/** 异步任务进程，默认监听 18082，并负责 RabbitMQ 消费和定时调度。 */
public final class CommunityWorkerServiceApplication {

    private CommunityWorkerServiceApplication() {
    }

    public static void main(String[] args) {
        CommunityServiceLauncher.run(CommunityServiceRole.WORKER,
                CommunityWorkerSchedulingConfiguration.class,
                defaultProperties(), args);
    }

    static Map<String, Object> defaultProperties() {
        return CommunityServiceLauncher.defaults(CommunityServiceRole.WORKER, true);
    }
}
