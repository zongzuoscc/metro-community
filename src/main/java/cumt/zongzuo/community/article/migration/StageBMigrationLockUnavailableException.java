package cumt.zongzuo.community.article.migration;

public class StageBMigrationLockUnavailableException extends IllegalStateException {

    public StageBMigrationLockUnavailableException() {
        super("another stage B article migration runner owns the advisory lock");
    }
}
