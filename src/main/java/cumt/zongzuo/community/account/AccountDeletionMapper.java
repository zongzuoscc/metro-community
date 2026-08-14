package cumt.zongzuo.community.account;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 注销状态的唯一写入口。
 *
 * <p>所有写语句同时比较状态与 deletion_version；恢复、到期清理和重复请求即使并发，
 * 也只能有一条路径提交成功。</p>
 */
@Mapper
public interface AccountDeletionMapper {

    @Select("""
            SELECT id AS user_id,account_state,deletion_requested_at,purge_after,deletion_version
            FROM sys_user WHERE id=#{userId} FOR UPDATE
            """)
    AccountDeletionRecord selectForUpdate(@Param("userId") long userId);

    @Select("""
            SELECT id AS user_id,account_state,deletion_requested_at,purge_after,deletion_version
            FROM sys_user WHERE id=#{userId}
            """)
    AccountDeletionRecord select(@Param("userId") long userId);

    @Update("""
            UPDATE sys_user
            SET account_state='PENDING_DELETE',
                deletion_requested_at=CURRENT_TIMESTAMP(6),
                purge_after=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY),
                deletion_version=deletion_version+1
            WHERE id=#{userId} AND deleted=0 AND account_state='ACTIVE'
              AND deletion_version=#{version}
            """)
    int request(@Param("userId") long userId, @Param("version") long version);

    @Update("""
            UPDATE sys_user
            SET account_state='ACTIVE',deletion_requested_at=NULL,purge_after=NULL,
                deletion_version=deletion_version+1
            WHERE id=#{userId} AND deleted=0 AND account_state='PENDING_DELETE'
              AND purge_after>CURRENT_TIMESTAMP(6) AND deletion_version=#{version}
            """)
    int restore(@Param("userId") long userId, @Param("version") long version);

    @Select("""
            SELECT id FROM sys_user FORCE INDEX(idx_sys_user_deletion_due)
            WHERE account_state='PENDING_DELETE' AND deleted=0
              AND purge_after<=CURRENT_TIMESTAMP(6)
            ORDER BY purge_after,id LIMIT #{limit} FOR UPDATE SKIP LOCKED
            """)
    List<Long> selectDueForUpdate(@Param("limit") int limit);

    @Update("""
            UPDATE sys_user
            SET username=CONCAT('deleted-',LEFT(SHA2(CONCAT(id,':',password,':',deletion_requested_at),256),42)),
                email=NULL,password=CONCAT('!deleted-',id),
                avatar=NULL,intro=NULL,status=0,ban_time=NULL,deleted=1,account_state='DELETED',
                deletion_requested_at=NULL,purge_after=NULL,deletion_version=deletion_version+1
            WHERE id=#{userId} AND deleted=0 AND account_state='PENDING_DELETE'
              AND purge_after<=CURRENT_TIMESTAMP(6) AND deletion_version=#{version}
            """)
    int finalizeDeletion(@Param("userId") long userId, @Param("version") long version);
}
