package com.example.company.asyncawaitjava.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class AsyncAwaitConfig {
    private static final Logger LOGGER = Logger.getLogger(AsyncAwaitConfig.class.getName());
    private static ExecutorService defaultExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private static boolean enableLogging = true;

    private AsyncAwaitConfig() {}

    public static void setDefaultExecutor(ExecutorService executor) {
        if (executor == null) {
            throw new IllegalArgumentException("Executor cannot be null");
        }
        defaultExecutor = executor;
        LOGGER.info("ExecutorService configurado: " + executor);
    }

    public static ExecutorService getDefaultExecutor() {
        return defaultExecutor;
    }

    public static void enableLogging(boolean enable) {
        enableLogging = enable;
        LOGGER.info("Logging " + (enable ? "activado" : "desactivado"));
    }

    public static boolean isLoggingEnabled() {
        return enableLogging;
    }

    public static void shutdown() {
        LOGGER.info("Cerrando defaultExecutor");
        defaultExecutor.shutdown();
        try {
            if (!defaultExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                defaultExecutor.shutdownNow();
                LOGGER.warning("Forzando cierre del defaultExecutor");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            defaultExecutor.shutdownNow();
            LOGGER.severe("Interrupción al cerrar defaultExecutor: " + e.getMessage());
        }
    }
}
