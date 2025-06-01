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

    private Task(CompletableFuture<T> future, Consumer<Task<T>> onStartCallback) {
        this.future = Objects.requireNonNull(future, "Future cannot be null");
        this.onStartCallback = onStartCallback;
        if (onStartCallback != null) {
            future.whenComplete((result, ex) -> isStarted = true);
        }
    }

    // Factory methods
    /**
     * Creates a completed task with the given value.
     */
    public static <T> Task<T> of(T value) {
        return new Task<>(CompletableFuture.completedFuture(value), null);
    }

    /**
     * Creates a task from an existing CompletableFuture.
     */
    public static <T> Task<T> fromFuture(CompletableFuture<T> future) {
        Objects.requireNonNull(future, "Future cannot be null");
        return new Task<>(future, null);
    }

    // Unified execute methods for Supplier<T>
    /**
     * Executes a task that produces a result using the default executor.
     */
    public static <T> Task<T> execute(Supplier<T> supplier) {
        return execute(supplier, AsyncAwaitConfig.getDefaultExecutor());
    }

    /**
     * Executes a task that produces a result using the specified executor.
     */
    public static <T> Task<T> execute(Supplier<T> supplier, Executor executor) {
        return execute(supplier, executor, null);
    }

    /**
     * Executes a task that produces a result with an optional start callback.
     */

public static <T> Task<T> execute(Supplier<T> supplier, Executor executor, Consumer<Task<T>> onStartCallback) {
    validateSupplier(supplier);
    Objects.requireNonNull(executor, "Executor cannot be null");
    CompletableFuture<T> future = new CompletableFuture<>();
    Task<T> task = new Task<>(future, onStartCallback);
    CompletableFuture.supplyAsync(() -> {
        task.taskThread = Thread.currentThread();
        if (onStartCallback != null) {
            onStartCallback.accept(task);
            task.isStarted = true;
        }
        try {
            return supplier.get();
        } catch (Throwable t) {
            // Usar el mensaje de la excepción original
            throw new TaskException(t.getMessage(), t);
        }
    }, executor).whenComplete((result, ex) -> {
        task.taskThread = null;
        if (ex != null) {
            future.completeExceptionally(ex);
            LOGGER.log(Level.WARNING, "Task execution failed: {0}", ex.getMessage());
        } else {
            future.complete(result);
        }
    });
    return task;
}

    // Unified execute methods for CheckedRunnable
    /**
     * Executes a task without a result using the default executor.
     */
    public static Task<Void> execute(CheckedRunnable runnable) {
        return execute(runnable, AsyncAwaitConfig.getDefaultExecutor());
    }

    /**
     * Executes a task without a result using the specified executor.
     */
    public static Task<Void> execute(CheckedRunnable runnable, Executor executor) {
        return execute(runnable, executor, null);
    }

    /**
     * Executes a task without a result with an optional start callback.
     */

    public static Task<Void> execute(CheckedRunnable runnable, Executor executor, Consumer<Task<Void>> onStartCallback) {
    validateRunnable(runnable);
    Objects.requireNonNull(executor, "Executor cannot be null");
    CompletableFuture<Void> future = new CompletableFuture<>();
    Task<Void> task = new Task<>(future, onStartCallback);
    CompletableFuture.runAsync(() -> {
        task.taskThread = Thread.currentThread();
        if (onStartCallback != null) {
            onStartCallback.accept(task);
            task.isStarted = true;
        }
        try {
            LOGGER.info("Executing CheckedRunnable");
            runnable.run();
            LOGGER.info("CheckedRunnable completed successfully");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Caught exception in CheckedRunnable: {0}", e.getMessage());
            // Usar el mensaje de la excepción original
            throw new TaskException(e.getMessage(), e);
        }
    }, executor).whenComplete((result, ex) -> {
        task.taskThread = null;
        if (ex != null) {
            LOGGER.log(Level.WARNING, "Completing future exceptionally: {0}", ex.getMessage());
            future.completeExceptionally(ex);
        } else {
            LOGGER.info("Completing future normally");
            future.complete(null);
        }
    });
    return task;
}

   
    /**
     * Executes a task that returns a CompletableFuture.
     */
    public static <T> Task<T> executeFuture(Supplier<CompletableFuture<T>> supplier) {
        validateSupplier(supplier);
        CompletableFuture<T> future = supplier.get();
        Objects.requireNonNull(future, "Supplied future cannot be null");
        return new Task<>(future, null);
    }

   
    /**
     * Delays execution for the specified time using the default executor.
     */
    public static Task<Void> delay(long millis) {
        return delay(millis, AsyncAwaitConfig.getDefaultExecutor());
    }

    /**
     * Delays execution for the specified time using the specified executor.
     */
    public static Task<Void> delay(long millis, Executor executor) {
        if (millis < 0) {
            throw new IllegalArgumentException("Delay must be non-negative");
        }
        Objects.requireNonNull(executor, "Executor cannot be null");
        return new Task<>(CompletableFuture.runAsync(() -> {
        },
                CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS, executor)), null);
    }

    /**
     * Applies a function to the task's result, returning a new task.
     */
    public <U> Task<U> thenApply(Function<? super T, ? extends U> fn) {
        Objects.requireNonNull(fn, "Function cannot be null");
        return new Task<>(future.thenApply(fn), null);
    }

    /**
     * Awaits the task's result with the default timeout.
     */
    public T await() {
        return await(DEFAULT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Awaits the task's result with the specified timeout.
     */

    public T await(long timeout, TimeUnit unit) {
    try {
        return future.get(timeout, unit);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new Task.TaskException("Interrupted while awaiting task", e);
    } catch (CancellationException e) {
        throw new Task.TaskException("Task was cancelled", e);
    } catch (ExecutionException e) {
        throw new Task.TaskException("Failed to execute task", e.getCause());
    } catch (TimeoutException e) {
        throw new Task.TaskException("Task timed out", e);
    } catch (Exception e) {
        String errorMsg = "Failed to await task: " + e.getMessage();
        LOGGER.log(Level.SEVERE, errorMsg, e);
        throw new Task.TaskException(errorMsg, e);
    }
}

    /**
     * Checks if the task is completed.
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
     * Checks if the task is cancelled.
     */
    public boolean isCancelled() {
        return future.isCancelled();
    }

    /**
     * Cancels the task, optionally interrupting the running thread.
     */
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

    /**
     * Returns the underlying CompletableFuture.
     */
    public CompletableFuture<T> getFuture() {
        return future;
    }
    
/**
 * Executes a supplier asynchronously and awaits its result synchronously, respecting cancellations.
 *
 * @param <T>      The type of the result produced by the supplier.
 * @param supplier The supplier that produces the result, which may throw exceptions.
 * @return The result of the supplier.
 * @throws Task.TaskException If the task fails, is interrupted, or is cancelled.
 */
public static <T> T executeAsyncAndAwait(Supplier<T> supplier) {
    return executeAsyncAndAwait(supplier, AsyncAwaitConfig.getDefaultExecutor());
}
/**
 * Executes a supplier asynchronously with a specified executor and awaits its result synchronously,
 * respecting cancellations.
 *
 * @param <T>      The type of the result produced by the supplier.
 * @param supplier The supplier that produces the result, which may throw exceptions.
 * @param executor The executor to run the task.
 * @return The result of the supplier.
 * @throws Task.TaskException If the task fails, is interrupted, or is cancelled.
 */
public static <T> T executeAsyncAndAwait(Supplier<T> supplier, Executor executor) {
    validateSupplier(supplier);
    Objects.requireNonNull(executor, "Executor cannot be null");
    
    Task<T> task = Task.execute(supplier, executor, t -> LOGGER.fine("Task started: " + t));
    
    try {
        return task.await(DEFAULT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Task.TaskException e) {
        if (task.isCancelled() || e.getCause() instanceof CancellationException) {
            throw new Task.TaskException("Task was cancelled", e);
        }
        throw e;
    }
}
    // Validation helpers
    private static void validateSupplier(Supplier<?> supplier) {
        Objects.requireNonNull(supplier, "Supplier cannot be null");
    }

    private static void validateRunnable(CheckedRunnable runnable) {
        Objects.requireNonNull(runnable, "Runnable cannot be null");
    }
}
