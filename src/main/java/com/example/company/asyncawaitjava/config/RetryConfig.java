

package com.example.company.asyncawaitjava.config;

import java.util.function.Predicate;

/**
 * Configuración para el comportamiento de reintentos.
 */
public class RetryConfig {
    private final int maxAttempts;
    private final long delayMs;
    private final double backoff;
    private final long jitterMs;
    public final long backoffBaseMs;
    public final int maxBackoffExponent;
    public final Predicate<Throwable> retryableException;

    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final long DEFAULT_DELAY_MS = 100L;
    public static final double DEFAULT_BACKOFF = 1.0;
    public static final long DEFAULT_JITTER_MS = 0L;
    public static final long DEFAULT_BACKOFF_BASE_MS = 100L;
    public static final int DEFAULT_MAX_BACKOFF_EXPONENT = 3;

    private RetryConfig(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.delayMs = builder.delayMs;
        this.backoff = builder.backoff;
        this.jitterMs = builder.jitterMs;
        this.backoffBaseMs = builder.backoffBaseMs;
        this.maxBackoffExponent = builder.maxBackoffExponent;
        this.retryableException = builder.retryableException != null ? builder.retryableException : t -> t instanceof RuntimeException;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getDelayMs() {
        return delayMs;
    }

    public double getBackoff() {
        return backoff;
    }

    public long getJitterMs() {
        return jitterMs;
    }

    public long getBackoffBaseMs() {
        return backoffBaseMs;
    }

    public int getMaxBackoffExponent() {
        return maxBackoffExponent;
    }

    public Predicate<Throwable> getRetryableException() {
        return retryableException;
    }

    public static RetryConfig defaultConfig() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private long delayMs = DEFAULT_DELAY_MS;
        private double backoff = DEFAULT_BACKOFF;
        private long jitterMs = DEFAULT_JITTER_MS;
        private long backoffBaseMs = DEFAULT_BACKOFF_BASE_MS;
        private int maxBackoffExponent = DEFAULT_MAX_BACKOFF_EXPONENT;
        private Predicate<Throwable> retryableException;

        public Builder withMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts > 0 ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
            return this;
        }

        public Builder withDelay(long delayMs) {
            this.delayMs = delayMs >= 0 ? delayMs : DEFAULT_DELAY_MS;
            return this;
        }

        public Builder withBackoff(double backoff) {
            this.backoff = backoff >= 1.0 ? backoff : DEFAULT_BACKOFF;
            return this;
        }

        public Builder withJitter(long jitterMs) {
            this.jitterMs = jitterMs >= 0 ? jitterMs : DEFAULT_JITTER_MS;
            return this;
        }

        public Builder withBackoffBaseMs(long backoffBaseMs) {
            this.backoffBaseMs = backoffBaseMs >= 0 ? backoffBaseMs : DEFAULT_BACKOFF_BASE_MS;
            return this;
        }

        public Builder withMaxBackoffExponent(int maxBackoffExponent) {
            this.maxBackoffExponent = maxBackoffExponent >= 0 ? maxBackoffExponent : DEFAULT_MAX_BACKOFF_EXPONENT;
            return this;
        }

        public Builder withRetryIf(Predicate<Throwable> retryableException) {
            this.retryableException = retryableException;
            return this;
        }

        public RetryConfig build() {
            return new RetryConfig(this);
        }
    }
}