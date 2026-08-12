package cumt.zongzuo.community.ai.agent.temporary;

/** 临时 turn 的异步调度边界，便于 Controller 在线程池拒绝时统一执行补偿。 */
public interface TemporaryTurnRunner {

    /** 仅在 admission 已获得共享 run guard 后提交异步任务。 */
    void submit(TemporaryTurnAdmission admission, long userId, String question);
}
