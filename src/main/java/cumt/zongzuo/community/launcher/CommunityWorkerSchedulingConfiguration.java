package cumt.zongzuo.community.launcher;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 只有 Worker 进程显式开启定时任务，防止三个进程重复训练、恢复或投递。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class CommunityWorkerSchedulingConfiguration {
}
