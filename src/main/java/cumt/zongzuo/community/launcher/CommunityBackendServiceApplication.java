package cumt.zongzuo.community.launcher;

import cumt.zongzuo.community.article.rollout.StageBRolloutOperatorApplication;

import java.util.Map;

/** 社区主业务进程，默认监听 18080，不消费异步 Worker 队列。 */
public final class CommunityBackendServiceApplication {

    private CommunityBackendServiceApplication() {
    }

    public static void main(String[] args) {
        // 既有 Stage B 一次性运维命令仍只从主业务入口执行，避免改变已验证的晋级流程。
        if (StageBRolloutOperatorApplication.isRequested(args)) {
            StageBRolloutOperatorApplication.run(args);
            return;
        }
        CommunityServiceLauncher.run(CommunityServiceRole.BACKEND,
                CommunityBackendServiceApplication.class,
                defaultProperties(), args);
    }

    static Map<String, Object> defaultProperties() {
        return CommunityServiceLauncher.defaults(CommunityServiceRole.BACKEND, false);
    }
}
