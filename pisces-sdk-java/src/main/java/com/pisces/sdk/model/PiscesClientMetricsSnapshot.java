package com.pisces.sdk.model;

/**
 * Pisces SDK 本地运行指标快照。
 */
public final class PiscesClientMetricsSnapshot {

    private final long requestAttemptCount;

    private final long requestSuccessCount;

    private final long requestFailureCount;

    private final long retryCount;

    private final long staleExperimentConfigFallbackCount;

    private final long experimentCacheHitCount;

    private final long experimentCacheMissCount;

    private final long experimentVersionCheckCount;

    private PiscesClientMetricsSnapshot(long requestAttemptCount, long requestSuccessCount,
                                        long requestFailureCount, long retryCount,
                                        long staleExperimentConfigFallbackCount,
                                        long experimentCacheHitCount, long experimentCacheMissCount,
                                        long experimentVersionCheckCount) {
        this.requestAttemptCount = requestAttemptCount;
        this.requestSuccessCount = requestSuccessCount;
        this.requestFailureCount = requestFailureCount;
        this.retryCount = retryCount;
        this.staleExperimentConfigFallbackCount = staleExperimentConfigFallbackCount;
        this.experimentCacheHitCount = experimentCacheHitCount;
        this.experimentCacheMissCount = experimentCacheMissCount;
        this.experimentVersionCheckCount = experimentVersionCheckCount;
    }

    public static PiscesClientMetricsSnapshot of(long requestAttemptCount, long requestSuccessCount,
                                                 long requestFailureCount, long retryCount,
                                                 long staleExperimentConfigFallbackCount,
                                                 long experimentCacheHitCount,
                                                 long experimentCacheMissCount,
                                                 long experimentVersionCheckCount) {
        return new PiscesClientMetricsSnapshot(requestAttemptCount, requestSuccessCount, requestFailureCount,
                retryCount, staleExperimentConfigFallbackCount, experimentCacheHitCount, experimentCacheMissCount,
                experimentVersionCheckCount);
    }

    public long getRequestAttemptCount() {
        return requestAttemptCount;
    }

    public long getRequestSuccessCount() {
        return requestSuccessCount;
    }

    public long getRequestFailureCount() {
        return requestFailureCount;
    }

    public long getRetryCount() {
        return retryCount;
    }

    public long getStaleExperimentConfigFallbackCount() {
        return staleExperimentConfigFallbackCount;
    }

    public long getExperimentCacheHitCount() {
        return experimentCacheHitCount;
    }

    public long getExperimentCacheMissCount() {
        return experimentCacheMissCount;
    }

    public long getExperimentVersionCheckCount() {
        return experimentVersionCheckCount;
    }

}
