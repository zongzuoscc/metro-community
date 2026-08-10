package cumt.zongzuo.community.ai.moderation.revision;

final class StaleModerationJobException extends RuntimeException {

    StaleModerationJobException() {
        super("moderation job binding or lease is no longer current");
    }
}
