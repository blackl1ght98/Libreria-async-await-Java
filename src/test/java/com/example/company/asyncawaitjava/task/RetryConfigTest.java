package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.config.RetryConfig;
import org.junit.jupiter.api.Test;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

public class RetryConfigTest {

    @Test
    public void testDefaultConfig() {
        RetryConfig config = RetryConfig.defaultConfig();
        assertEquals(RetryConfig.DEFAULT_MAX_ATTEMPTS, config.getMaxAttempts(), "Default max attempts should be 3");
        assertEquals(RetryConfig.DEFAULT_DELAY_MS, config.getDelayMs(), "Default delay should be 100ms");
        assertEquals(RetryConfig.DEFAULT_BACKOFF, config.getBackoff(), "Default backoff should be 1.0");
        assertEquals(RetryConfig.DEFAULT_JITTER_MS, config.getJitterMs(), "Default jitter should be 0ms");
        assertEquals(RetryConfig.DEFAULT_BACKOFF_BASE_MS, config.getBackoffBaseMs(), "Default backoff base should be 100ms");
        assertEquals(RetryConfig.DEFAULT_MAX_BACKOFF_EXPONENT, config.getMaxBackoffExponent(), "Default max backoff exponent should be 3");
        assertTrue(config.getRetryableException().test(new RuntimeException()), "Default retryable exception should include RuntimeException");
        assertFalse(config.getRetryableException().test(new Error()), "Default retryable exception should exclude Error");
    }

    @Test
    public void testCustomConfig() {
        Predicate<Throwable> customPredicate = t -> t instanceof IllegalStateException;
        RetryConfig config = RetryConfig.builder()
                .withMaxAttempts(5)
                .withDelay(200)
                .withBackoff(2.0)
                .withJitter(50)
                .withBackoffBaseMs(150)
                .withMaxBackoffExponent(4)
                .withRetryIf(customPredicate)
                .build();

        assertEquals(5, config.getMaxAttempts(), "Max attempts should be 5");
        assertEquals(200, config.getDelayMs(), "Delay should be 200ms");
        assertEquals(2.0, config.getBackoff(), "Backoff should be 2.0");
        assertEquals(50, config.getJitterMs(), "Jitter should be 50ms");
        assertEquals(150, config.getBackoffBaseMs(), "Backoff base should be 150ms");
        assertEquals(4, config.getMaxBackoffExponent(), "Max backoff exponent should be 4");
        assertEquals(customPredicate, config.getRetryableException(), "Retryable exception should be custom predicate");
        assertTrue(config.getRetryableException().test(new IllegalStateException()), "Custom predicate should allow IllegalStateException");
        assertFalse(config.getRetryableException().test(new RuntimeException()), "Custom predicate should exclude RuntimeException");
    }

    @Test
    public void testInvalidParameters() {
        RetryConfig.Builder builder = RetryConfig.builder();
        RetryConfig config = builder
                .withMaxAttempts(0) // Should use default
                .withDelay(-100)    // Should use default
                .withBackoff(0.5)   // Should use default
                .withJitter(-50)    // Should use default
                .withBackoffBaseMs(-10) // Should use default
                .withMaxBackoffExponent(-1) // Should use default
                .build();

        assertEquals(RetryConfig.DEFAULT_MAX_ATTEMPTS, config.getMaxAttempts(), "Invalid max attempts should use default");
        assertEquals(RetryConfig.DEFAULT_DELAY_MS, config.getDelayMs(), "Invalid delay should use default");
        assertEquals(RetryConfig.DEFAULT_BACKOFF, config.getBackoff(), "Invalid backoff should use default");
        assertEquals(RetryConfig.DEFAULT_JITTER_MS, config.getJitterMs(), "Invalid jitter should use default");
        assertEquals(RetryConfig.DEFAULT_BACKOFF_BASE_MS, config.getBackoffBaseMs(), "Invalid backoff base should use default");
        assertEquals(RetryConfig.DEFAULT_MAX_BACKOFF_EXPONENT, config.getMaxBackoffExponent(), "Invalid max backoff exponent should use default");
    }
}