package cumt.zongzuo.community.account;

/** 账号注销状态机；状态名会直接持久化，禁止随意改名。 */
public enum AccountState {
    ACTIVE,
    PENDING_DELETE,
    DELETED
}
