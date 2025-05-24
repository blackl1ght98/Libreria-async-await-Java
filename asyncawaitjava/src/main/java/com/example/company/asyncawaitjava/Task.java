package com.example.company.asyncawaitjava;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class Task<T> implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(Task.class.getName());
    final CompletableFuture<T> future;

    private Task(CompletableFuture<T> future) {
        this.future = future;
    }

    // Interfaz funcional para Runnable que permite excepciones comprobadas
    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    public static <T> Task<T> of(T value) {
        return new Task<>(CompletableFuture.completedFuture(value));
    }

    public static <T> Task<T> run(Supplier<T> supplier) {
        return new Task<>(CompletableFuture.supplyAsync(supplier, AsyncAwaitConfig.getDefaultExecutor()));
    }

    public static Task<Void> run(CheckedRunnable runnable) {
        return new Task<>(CompletableFuture.runAsync(() -> {
            try {
                runnable.run();
            } catch (Exception e) {
                throw new RuntimeException("Error en tarea asíncrona", e);
            }
        }, AsyncAwaitConfig.getDefaultExecutor()));
    }

    public static <T> Task<T> runAsyncFuture(Supplier<CompletableFuture<T>> supplier) {
        return new Task<>(supplier.get());
    }

    public static <T> Task<T> runAsync(Supplier<T> action) {
        return new Task<>(CompletableFuture.supplyAsync(() -> {
            try {
                return action.get();
            } catch (Exception e) {
                throw new RuntimeException("Error en tarea asíncrona", e);
            }
        }, AsyncAwaitConfig.getDefaultExecutor()));
    }

    public static Task<Void> runAsync(CheckedRunnable runnable) {
        return new Task<>(CompletableFuture.runAsync(() -> {
            try {
                runnable.run();
            } catch (Exception e) {
                throw new RuntimeException("Error en tarea asíncrona", e);
            }
        }, AsyncAwaitConfig.getDefaultExecutor()));
    }

    public static Task<Void> delay(long millis) {
        return new Task<>(CompletableFuture.runAsync(() -> {}, 
            CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS, AsyncAwaitConfig.getDefaultExecutor())));
    }

    public <U> Task<U> thenApply(Function<? super T, ? extends U> fn) {
        return new Task<>(future.thenApply(fn));
    }

    public T await() throws Exception {
        try {
            return future.get();
        } catch (Exception e) {
            LOGGER.severe("Error en await: " + e.getMessage());
            throw e;
        }
    }

    public boolean isDone() {
        return future.isDone();
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean cancelled = future.cancel(mayInterruptIfRunning);
      
        return cancelled;
    }

    @Override
    public void close() {
        if (!future.isDone()) {
            cancel(true);
            LOGGER.info("Cerrando tarea no completada: " + this);
        }
    }
}