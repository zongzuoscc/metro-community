package cumt.zongzuo.community.article.projection;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("metro.article.projection")
public class ArticleProjectionProperties {

    private Duration leaseDuration = Duration.ofSeconds(30);
    private String indexName = "article";
    private boolean reconcileEnabled;
    private long reconcileStartAfterId;
    private int reconcileBatchSize = 100;
    private int reconcileMaximumBatches = 10_000;
    private final Retry retry = new Retry();

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Retry getRetry() {
        return retry;
    }

    public String getIndexName() {
        return indexName;
    }

    public boolean isReconcileEnabled() {
        return reconcileEnabled;
    }

    public void setReconcileEnabled(boolean reconcileEnabled) {
        this.reconcileEnabled = reconcileEnabled;
    }

    public long getReconcileStartAfterId() {
        return reconcileStartAfterId;
    }

    public void setReconcileStartAfterId(long reconcileStartAfterId) {
        this.reconcileStartAfterId = reconcileStartAfterId;
    }

    public int getReconcileBatchSize() {
        return reconcileBatchSize;
    }

    public void setReconcileBatchSize(int reconcileBatchSize) {
        this.reconcileBatchSize = reconcileBatchSize;
    }

    public int getReconcileMaximumBatches() {
        return reconcileMaximumBatches;
    }

    public void setReconcileMaximumBatches(int reconcileMaximumBatches) {
        this.reconcileMaximumBatches = reconcileMaximumBatches;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public void validate() {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalStateException("article projection lease duration must be positive");
        }
        if (!cumt.zongzuo.community.document.ArticleDoc.INDEX_NAME.equals(indexName)) {
            throw new IllegalStateException("article projection index name must be article");
        }
        if (reconcileStartAfterId < 0 || reconcileBatchSize < 1 || reconcileBatchSize > 1_000
                || reconcileMaximumBatches < 1 || reconcileMaximumBatches > 1_000_000) {
            throw new IllegalStateException("article projection reconcile settings are invalid");
        }
        retry.validate(leaseDuration);
    }

    public static final class Retry {
        private Duration initialInterval = Duration.ofSeconds(10);
        private Duration maxInterval = Duration.ofSeconds(30);
        private double multiplier = 2.0d;
        private int maxAttempts = 5;

        public Duration getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
        }

        public Duration getMaxInterval() {
            return maxInterval;
        }

        public void setMaxInterval(Duration maxInterval) {
            this.maxInterval = maxInterval;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        private void validate(Duration leaseDuration) {
            if (initialInterval == null || initialInterval.isNegative() || initialInterval.isZero()
                    || maxInterval == null || maxInterval.isNegative() || maxInterval.isZero()
                    || multiplier < 1.0d || maxAttempts < 2) {
                throw new IllegalStateException("article projection retry settings are invalid");
            }
            long elapsedMillis = 0L;
            double interval = initialInterval.toMillis();
            for (int attempt = 1; attempt < maxAttempts; attempt++) {
                elapsedMillis = Math.addExact(elapsedMillis,
                        Math.min((long) interval, maxInterval.toMillis()));
                interval = Math.min(interval * multiplier, maxInterval.toMillis());
            }
            if (elapsedMillis <= leaseDuration.toMillis()) {
                throw new IllegalStateException(
                        "article projection retry window must exceed the projection lease");
            }
        }
    }
}
