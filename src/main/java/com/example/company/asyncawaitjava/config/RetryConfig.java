/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.example.company.asyncawaitjava.config;

import java.util.function.Predicate;

/**
 *
 * @author guillermo
 */
/**
 * Configuración para el comportamiento de reintentos.
 */
public class RetryConfig {

    public final long backoffBaseMs;
    public final int maxBackoffExponent;
    public final Predicate<Throwable> retryableException;
    public static final long DEFAULT_BACKOFF_BASE_MS = 100L;
    public static final int DEFAULT_MAX_BACKOFF_EXPONENT = 3;

    public RetryConfig(long backoffBaseMs, int maxBackoffExponent, Predicate<Throwable> retryableException) {
        this.backoffBaseMs = backoffBaseMs;
        this.maxBackoffExponent = maxBackoffExponent;
        this.retryableException = retryableException != null ? retryableException : t -> t instanceof RuntimeException;
    }

    public static RetryConfig defaultConfig() {
        return new RetryConfig(DEFAULT_BACKOFF_BASE_MS, DEFAULT_MAX_BACKOFF_EXPONENT, null);
    }
}
