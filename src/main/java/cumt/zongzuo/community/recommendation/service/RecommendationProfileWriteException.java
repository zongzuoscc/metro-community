package cumt.zongzuo.community.recommendation.service;

import org.springframework.dao.DataAccessException;

/**
 * Redis 画像替换失败。事实与持久恢复检查点已经具备补偿价值，因此该异常不会
 * 回滚 MySQL 事务；与之不同，普通 JDBC DataAccessException 仍必须整体回滚。
 */
public class RecommendationProfileWriteException extends DataAccessException {

    public RecommendationProfileWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
