package com.example.company.asyncawaitjava;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class Task<T> {

    private final CompletableFuture<T> future;
    private static ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private Task(CompletableFuture<T> future) {
        this.future = future;
    }

    public static <T> Task<T> of(T value) {
        return new Task<>(CompletableFuture.completedFuture(value));
    }

    public static <T> Task<T> run(Supplier<T> supplier) {
        return new Task<>(CompletableFuture.supplyAsync(supplier, executor));
    }

    public static Task<Void> run(Runnable runnable) {
        return new Task<>(CompletableFuture.runAsync(runnable, executor));
    }

    public static <T> Task<T> runAsync(Supplier<CompletableFuture<T>> supplier) {
        return new Task<>(supplier.get());
    }

    public static Task<Void> delay(long millis) {
        return new Task<>(CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during delay", e);
            }
        }, executor));
    }

    public T await() throws Exception {
        try {
            return future.join();
        } catch (Exception e) {
            throw e instanceof RuntimeException ? e : new RuntimeException("Error awaiting task", e);
        }
    }

    public static void configureExecutor(ExecutorService newExecutor) {
        executor = newExecutor;
    }

    public static void shutdown() {
        executor.shutdown();
    }
}
