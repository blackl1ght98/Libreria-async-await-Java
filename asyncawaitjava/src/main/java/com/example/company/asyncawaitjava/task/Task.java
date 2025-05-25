
package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.config.AsyncAwaitConfig;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * A robust, thread-safe wrapper for asynchronous tasks using CompletableFuture, supporting execution,
 * chaining, cancellation, and completion handling.
 *
 * @param <T> The result type of the task.
 */
public class Task<T> implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(Task.class.getName());
    private static final long DEFAULT_AWAIT_TIMEOUT_SECONDS = 30L;

    final CompletableFuture<T> future;
    private volatile Thread taskThread; // Para rastrear hilos en runAsync
    private volatile boolean isStarted; // Estado de inicio
    private final Consumer<Task<T>> onStartCallback; // Callback opcional para inicio

    /**
     * Custom exception for Task errors.
     */
    public static class TaskException extends RuntimeException {
        public TaskException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Functional interface for runnables that throw checked exceptions.
     */
    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    private Task(CompletableFuture<T> future, Consumer<Task<T>> onStartCallback) {
        this.future = Objects.requireNonNull(future, "Future cannot be null");
        this.onStartCallback = onStartCallback;
        if (onStartCallback != null) {
            future.whenComplete((result, ex) -> isStarted = true);
        }
    }

    /**
     * Creates a completed task with a given value.
     *
     * @param value The value to complete the task with.
     * @param <T>   The type of the value.
     * @return A completed Task.
     */
    public static <T> Task<T> of(T value) {
        return new Task<>(CompletableFuture.completedFuture(value), null);
    }

    /**
     * Creates a task that executes the supplier in the default executor.
     *
     * @param supplier The supplier providing the task result.
     * @param <T>      The result type.
     * @return A new Task.
     * @throws IllegalArgumentException If supplier is null.
     */
    public static <T> Task<T> run(Supplier<T> supplier) {
        return run(supplier, AsyncAwaitConfig.getDefaultExecutor(), null);
    }

    /**
     * Creates a task with a custom executor and optional start callback.
     *
     * @param supplier       The supplier providing the task result.
     * @param executor       The executor to run the task.
     * silenzio       The callback invoked when the task starts.
     * @param <T>            The result type.
     * @return A new Task.
     * @throws IllegalArgumentException If supplier or executor is null.
     */
    public static <T> Task<T> run(Supplier<T> supplier, Executor executor, Consumer<Task<T>> onStartCallback) {
        validateSupplier(supplier);
        Objects.requireNonNull(executor, "Executor cannot be null");
        CompletableFuture<T> future = new CompletableFuture<>();
        Task<T> task = new Task<>(future, onStartCallback);
        CompletableFuture.supplyAsync(() -> {
            if (onStartCallback != null) {
                onStartCallback.accept(task);
                task.isStarted = true;
            }
            return supplier.get();
        }, executor).whenComplete((result, ex) -> {
            if (ex != null) {
                future.completeExceptionally(ex);
            } else {
                future.complete(result);
            }
        });
        return task;
    }

    /**
     * Creates a task that executes a runnable with checked exceptions.
     *
     * @param runnable The runnable to execute.
     * @return A new Task<Void>.
     * @throws IllegalArgumentException If runnable is null.
     */
    public static Task<Void> run(CheckedRunnable runnable) {
        return run(runnable, AsyncAwaitConfig.getDefaultExecutor(), null);
    }

    /**
     * Creates a task with a custom executor and optional start callback.
     *
     * @param runnable       The runnable to execute.
     * @param executor       The executor to run the task.
     * @param silence       Optional callback invoked when the task starts.
     * @return A new Task<Void>.
     * @throws IllegalArgumentException If runnable or executor is null.
     */
 public static Task<Void> run(CheckedRunnable runnable, Executor executor, Consumer<Task<Void>> onStartCallback) {
    validateRunnable(runnable);
    Objects.requireNonNull(executor, "Executor cannot be null");
    CompletableFuture<Void> future = new CompletableFuture<>();
    Task<Void> task = new Task<>(future, onStartCallback);
    CompletableFuture.runAsync(() -> {
        if (onStartCallback != null) {
            onStartCallback.accept(task);
            task.isStarted = true;
        }
        try {
            runnable.run();
        } catch (Exception e) {
            throw new TaskException("Error in async task", e);
        }
    }, executor).whenComplete((result, ex) -> {
        if (ex != null) {
            future.completeExceptionally(ex);
        } else {
            future.complete(null);
        }
    });
    return task;
}
    /**
     * Creates a task from a supplier of CompletableFuture.
     */
    public static <T> Task<T> runAsyncFuture(Supplier<CompletableFuture<T>> supplier) {
        validateSupplier(supplier);
        return new Task<>(supplier.get(), null);
    }

    /**
     * Creates a task that runs in a new thread.
     */
    public static <T> Task<T> runAsync(Supplier<T> supplier) {
        return runAsync(supplier, null);
    }

    /**
     * Creates a task with an optional start callback.
     */
    public static <T> Task<T> runAsync(Supplier<T> supplier, Consumer<Task<T>> onStartCallback) {
        validateSupplier(supplier);
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                if (onStartCallback != null) {
                    Task<T> task = new Task<>(future, onStartCallback);
                    onStartCallback.accept(task);
                    task.isStarted = true;
                }
                T result = supplier.get();
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, "Task-Thread-" + System.nanoTime());
        thread.setDaemon(true);
        thread.start();
        Task<T> task = new Task<>(future, onStartCallback);
        task.taskThread = thread;
        return task;
    }

    /**
     * Creates a task for a runnable in a new thread.
     */
    public static Task<Void> runAsync(CheckedRunnable runnable) {
        return runAsync(runnable, null);
    }

    /**
     * Creates a task for a runnable with an optional start callback.
     */
    public static Task<Void> runAsync(CheckedRunnable runnable, Consumer<Task<Void>> onStartCallback) {
        validateRunnable(runnable);
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                if (onStartCallback != null) {
                    Task<Void> task = new Task<>(future, onStartCallback);
                    onStartCallback.accept(task);
                    task.isStarted = true;
                }
                runnable.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, "Task-Thread-" + System.nanoTime());
        thread.setDaemon(true);
        thread.start();
        Task<Void> task = new Task<>(future, onStartCallback);
        task.taskThread = thread;
        return task;
    }

    /**
     * Creates a task that completes after a delay.
     *
     * @param millis The delay in milliseconds.
     * @return A new Task<Void>.
     * @throws IllegalArgumentException If millis is negative.
     */
    public static Task<Void> delay(long millis) {
        return delay(millis, AsyncAwaitConfig.getDefaultExecutor());
    }

    /**
     * Creates a task that completes after a delay with a custom executor.
     */
    public static Task<Void> delay(long millis, Executor executor) {
        if (millis < 0) {
            throw new IllegalArgumentException("Delay must be non-negative");
        }
        Objects.requireNonNull(executor, "Executor cannot be null");
        return new Task<>(CompletableFuture.runAsync(() -> {}, 
            CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS, executor)), null);
    }

    /**
     * Chains a function to apply to the task result.
     *
     * @param fn The function to apply to the task result.
     * @param <U> The result type of the new task.
     * @return A new Task with the transformed result.
     * @throws IllegalArgumentException If fn is null.
     */
    public <U> Task<U> thenApply(Function<? super T, ? extends U> fn) {
        Objects.requireNonNull(fn, "Function cannot be null");
        return new Task<>(future.thenApply(fn), null); // No propagate onStartCallback
    }

    /**
     * Waits for the task to complete and returns its result.
     *
     * @return The task result.
     * @throws TaskException If the task fails or is interrupted.
     */
    public T await() {
        return await(DEFAULT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Waits for the task to complete with a timeout.
     *
     * @param timeout The timeout value.
     * @param unit    The time unit.
     * @return The task result.
     * @throws TaskException If the task fails, is interrupted, or times out.
     */
    public T await(long timeout, TimeUnit unit) {
        try {
            return future.get(timeout, unit);
        } catch (Exception e) {
            String errorMsg = "Error awaiting task: " + e.getMessage();
            LOGGER.severe(errorMsg);
            throw new TaskException(errorMsg, e);
        }
    }

    /**
     * Checks if the task is done.
     */
    public boolean isDone() {
        return future.isDone();
    }

    /**
     * Checks if the task has started.
     */
    public boolean isStarted() {
        return isStarted;
    }

    /**
     * Cancels the task.
     *
     * @param mayInterruptIfRunning Whether to interrupt the running thread.
     * @return True if the task was cancelled.
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (mayInterruptIfRunning && taskThread != null && taskThread.isAlive()) {
            taskThread.interrupt();
            LOGGER.info("Interrupted task thread: " + taskThread.getName());
        }
        boolean cancelled = future.cancel(mayInterruptIfRunning);
        LOGGER.info("Task cancellation: task=%s, cancelled=%b".formatted(this, cancelled));
        return cancelled;
    }

    /**
     * Closes the task, cancelling it if not completed.
     */
    @Override
    public void close() {
        if (!future.isDone()) {
            cancel(true);
            LOGGER.info("Closed uncompleted task: " + this);
        }
    }

    private static void validateSupplier(Supplier<?> supplier) {
        Objects.requireNonNull(supplier, "Supplier cannot be null");
    }

    private static void validateRunnable(CheckedRunnable runnable) {
        Objects.requireNonNull(runnable, "Runnable cannot be null");
    }
}