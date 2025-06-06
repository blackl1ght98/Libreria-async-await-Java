
package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.config.AsyncAwaitConfig;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents an asynchronous task backed by a CompletableFuture, supporting
 * execution, cancellation, and result awaiting with customizable execution
 * context.
 *
 * @param <T> The type of the task's result.
 */
public class Task<T> implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(Task.class.getName());
    private static final long DEFAULT_AWAIT_TIMEOUT_SECONDS = 30L;

    final CompletableFuture<T> future;
    private volatile Thread taskThread;
    private volatile boolean isStarted;
    private final Consumer<Task<T>> onStartCallback;
    private final boolean isLastTaskInPipeline; // New field

    /**
     * Exception thrown when a task encounters an error during execution or
     * awaiting.
     */
    public static class TaskException extends RuntimeException {
        public TaskException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Functional interface for runnables that may throw checked exceptions.
     */
    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    // Updated constructor to include isLastTaskInPipeline
    Task(CompletableFuture<T> future, Consumer<Task<T>> onStartCallback, boolean isLastTaskInPipeline) {
        this.future = Objects.requireNonNull(future, "Future cannot be null");
        this.onStartCallback = onStartCallback;
        this.isLastTaskInPipeline = isLastTaskInPipeline;
        if (onStartCallback != null) {
            future.whenComplete((result, ex) -> isStarted = true);
        }
    }

    // Factory methods
    public static <T> Task<T> of(T value) {
        return new Task<>(CompletableFuture.completedFuture(value), null, false);
    }

    public static <T> Task<T> fromFuture(CompletableFuture<T> future) {
        Objects.requireNonNull(future, "Future cannot be null");
        return new Task<>(future, null, false);
    }

    // Unified execute methods for Supplier<T>
    public static <T> Task<T> execute(Supplier<T> supplier) {
        return execute(supplier, AsyncAwaitConfig.getDefaultExecutor(), 0, null, false);
    }

    public static <T> Task<T> execute(Supplier<T> supplier, Executor executor) {
        return execute(supplier, executor, 0, null, false);
    }

    public static <T> Task<T> execute(Supplier<T> supplier, Executor executor, Consumer<Task<T>> onStartCallback) {
        return execute(supplier, executor, 0, onStartCallback, false);
    }

    public static <T> Task<T> execute(Supplier<T> supplier, Executor executor, int priority) {
        return execute(supplier, executor, priority, null, false);
    }

    public static <T> Task<T> execute(Supplier<T> supplier, Executor executor, int priority, Consumer<Task<T>> onStartCallback) {
        return execute(supplier, executor, priority, onStartCallback, false);
    }

    // New execute method with isLastTaskInPipeline
    public static <T> Task<T> execute(
        Supplier<T> supplier, Executor executor, int priority, Consumer<Task<T>> onStartCallback, boolean isLastTaskInPipeline
    ) {
        validateSupplier(supplier);
        Objects.requireNonNull(executor, "Executor cannot be null");
        CompletableFuture<T> future = new CompletableFuture<>();
        Task<T> task = new Task<>(future, onStartCallback, isLastTaskInPipeline);
        executor.execute(new PriorityRunnable(() -> {
            task.taskThread = Thread.currentThread();
            if (onStartCallback != null) {
                onStartCallback.accept(task);
                task.isStarted = true;
            }
            try {
                T result = supplier.get();
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(new TaskException(t.getMessage(), t));
                LOGGER.log(Level.WARNING, "Task execution failed: {0}", t.getMessage());
            } finally {
                task.taskThread = null;
            }
        }, priority));
        return task;
    }

    // Unified execute methods for CheckedRunnable
    public static Task<Void> execute(CheckedRunnable runnable) {
        return execute(runnable, AsyncAwaitConfig.getDefaultExecutor(), 0, null, false);
    }

    public static Task<Void> execute(CheckedRunnable runnable, Executor executor) {
        return execute(runnable, executor, 0, null, false);
    }

    public static Task<Void> execute(CheckedRunnable runnable, Executor executor, Consumer<Task<Void>> onStartCallback) {
        return execute(runnable, executor, 0, onStartCallback, false);
    }

    public static Task<Void> execute(CheckedRunnable runnable, Executor executor, int priority) {
        return execute(runnable, executor, priority, null, false);
    }

    public static Task<Void> execute(CheckedRunnable runnable, Executor executor, int priority, Consumer<Task<Void>> onStartCallback) {
        return execute(runnable, executor, priority, onStartCallback, false);
    }

    // New execute method for CheckedRunnable with isLastTaskInPipeline
    public static Task<Void> execute(
        CheckedRunnable runnable, Executor executor, int priority, Consumer<Task<Void>> onStartCallback, boolean isLastTaskInPipeline
    ) {
        validateRunnable(runnable);
        Objects.requireNonNull(executor, "Executor cannot be null");
        CompletableFuture<Void> future = new CompletableFuture<>();
        Task<Void> task = new Task<>(future, onStartCallback, isLastTaskInPipeline);
        executor.execute(new PriorityRunnable(() -> {
            task.taskThread = Thread.currentThread();
            if (onStartCallback != null) {
                onStartCallback.accept(task);
                task.isStarted = true;
            }
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(new TaskException(e.getMessage(), e));
                LOGGER.log(Level.WARNING, "Task execution failed: {0}", e.getMessage());
            } finally {
                task.taskThread = null;
            }
        }, priority));
        return task;
    }

    // Other methods unchanged
    public static <T> Task<T> executeFuture(Supplier<CompletableFuture<T>> supplier) {
        validateSupplier(supplier);
        CompletableFuture<T> future = supplier.get();
        Objects.requireNonNull(future, "Supplied future cannot be null");
        return new Task<>(future, null, false);
    }

    public static Task<Void> delay(long millis) {
        return delay(millis, AsyncAwaitConfig.getDefaultExecutor());
    }

    public static Task<Void> delay(long millis, Executor executor) {
        if (millis < 0) {
            throw new IllegalArgumentException("Delay must be non-negative");
        }
        Objects.requireNonNull(executor, "Executor cannot be null");
        return new Task<>(CompletableFuture.runAsync(() -> {
        }, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS, executor)), null, false);
    }

    public <U> Task<U> thenApply(Function<? super T, ? extends U> fn) {
        Objects.requireNonNull(fn, "Function cannot be null");
        return new Task<>(future.thenApply(fn), null, false);
    }

    public T await() {
        return await(DEFAULT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public T await(long timeout, TimeUnit unit) {
        try {
            return future.get(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskException("Interrupted while awaiting task", e);
        } catch (CancellationException e) {
            throw e; // Propagate CancellationException directly
        } catch (ExecutionException e) {
            throw new TaskException("Failed to execute task", e.getCause());
        } catch (TimeoutException e) {
            throw new TaskException("Task timed out", e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to await task: " + e.getMessage(), e);
            throw new TaskException("Failed to await task", e);
        }
    }

    public boolean isDone() {
        return future.isDone();
    }

    public boolean isStarted() {
        return isStarted;
    }

    public boolean isCancelled() {
        return future.isCancelled();
    }

    public void cancel(boolean mayInterruptIfRunning) {
        if (future.cancel(mayInterruptIfRunning)) {
            LOGGER.info("Task cancellation: task=" + this + ", cancelled=true");
            if (mayInterruptIfRunning && taskThread != null) {
                LOGGER.info("Interrupted task thread: " + taskThread.getName());
                taskThread.interrupt();
            } else if (mayInterruptIfRunning) {
                LOGGER.fine("No task thread to interrupt for task=" + this);
            }
        } else {
            LOGGER.info("Task cancellation: task=" + this + ", cancelled=false");
        }
    }

    @Override
    public void close() {
        if (!future.isDone()) {
            cancel(true);
            LOGGER.info("Closed uncompleted task: " + this);
        }
    }

    public CompletableFuture<T> getFuture() {
        return future;
    }

    // Getter for isLastTaskInPipeline
    public boolean isLastTaskInPipeline() {
        return isLastTaskInPipeline;
    }

    public static <T> T executeAsyncAndAwait(Supplier<T> supplier) {
        return executeAsyncAndAwait(supplier, AsyncAwaitConfig.getDefaultExecutor(), 0);
    }

    public static <T> T executeAsyncAndAwait(Supplier<T> supplier, Executor executor) {
        return executeAsyncAndAwait(supplier, executor, 0);
    }

    public static <T> T executeAsyncAndAwait(Supplier<T> supplier, Executor executor, int priority) {
        validateSupplier(supplier);
        Objects.requireNonNull(executor, "Executor cannot be null");
        Task<T> task = Task.execute(supplier, executor, priority, t -> LOGGER.fine("Task started: " + t), false);
        try {
            return task.await(DEFAULT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Task.TaskException e) {
            if (task.isCancelled() || e.getCause() instanceof CancellationException) {
                throw new Task.TaskException("Task was cancelled", e);
            }
            throw e;
        }
    }

    private static void validateSupplier(Supplier<?> supplier) {
        Objects.requireNonNull(supplier, "Supplier cannot be null");
    }

    private static void validateRunnable(CheckedRunnable runnable) {
        Objects.requireNonNull(runnable, "Runnable cannot be null");
    }
}