package cumt.zongzuo.community.ai.provider;

public class ProviderHttpStatusException extends RuntimeException {

    private final int status;

    public ProviderHttpStatusException(int status) {
        super("AI provider returned HTTP status " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
