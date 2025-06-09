
package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.config.AsyncAwaitConfig;
import com.example.company.asyncawaitjava.exceptions.customizedException.TaskException;
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
 * Example usage:
 * ```java
 * Task<String> task = Task.execute(() -> "Task result", AsyncAwaitConfig.getDefaultExecutor());
 * try {
 *     String result = task.await();
 *     System.out.println(result); // Prints "Task result"
 * } catch (TaskException e) {
 *     e.printStackTrace();
 * }
 * ```
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
     * Functional interface for runnables that may throw checked exceptions.
     */
    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }


    Task(CompletableFuture<T> future, Consumer<Task<T>> onStartCallback, boolean isLastTaskInPipeline) {
        this.future = Objects.requireNonNull(future, "Future cannot be null");
        this.onStartCallback = onStartCallback;
        this.isLastTaskInPipeline = isLastTaskInPipeline;
        if (onStartCallback != null) {
            future.whenComplete((result, ex) -> isStarted = true);
        }
    }

    /**
     * Creates a Task with a pre-computed value.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.of("Pre-computed value");
     * System.out.println(task.isDone()); // Prints true
     * ```
     *
     * @param value The pre-computed value.
     * @return A completed Task with the given value.
     */
    public static <T> Task<T> of(T value) {
        return new Task<>(CompletableFuture.completedFuture(value), null, false);
    }
 /**
     * Creates a Task from an existing CompletableFuture.
     *
     * Example usage:
     * ```java
     * CompletableFuture<String> future = CompletableFuture.completedFuture("Future result");
     * Task<String> task = Task.fromFuture(future);
     * System.out.println(task.isDone()); // Prints true
     * ```
     *
     * @param future The CompletableFuture to wrap.
     * @return A Task wrapping the provided future.
     */
    public static <T> Task<T> fromFuture(CompletableFuture<T> future) {
        Objects.requireNonNull(future, "Future cannot be null");
        return new Task<>(future, null, false);
    }

    /**
     * Executes a task with a supplier using the default executor.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.execute(() -> "Task result");
     * try {
     *     String result = task.await();
     *     System.out.println(result); // Prints "Task result"
     * } catch (TaskException e) {
     *     e.printStackTrace();
     * }
     * ```
     *
     * @param supplier The supplier to execute.
     * @return The created Task.
     */
    public static <T> Task<T> execute(Supplier<T> supplier) {
        return execute(supplier, AsyncAwaitConfig.getDefaultExecutor(), 0, null, false);
    }
 /**
     * Executes a task with a supplier on the specified executor.
     *
     * Example usage: See {@link #execute(Supplier)}.
     *
     * @param supplier The supplier to execute.
     * @param executor The executor to run the task on.
     * @return The created Task.
     */
    public static <T> Task<T> execute(Supplier<T> supplier, Executor executor) {
        return execute(supplier, executor, 0, null, false);
    }
 /**
     * Executes a task with a supplier, callback, and executor.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.execute(() -> "Task result", AsyncAwaitConfig.getDefaultExecutor(),
     *     t -> System.out.println("Task started"));
     * try {
     *     task.await();
     * } catch (TaskException e) {
     *     e.printStackTrace();
     * }
     * ```
     *
     * @param supplier The supplier to execute.
     * @param executor The executor to run the task on.
     * @param onStartCallback The callback to invoke when the task starts.
     * @return The created Task.
     */
    public static <T> Task<T> execute(Supplier<T> supplier, Executor executor, Consumer<Task<T>> onStartCallback) {
        return execute(supplier, executor, 0, onStartCallback, false);
    }
 /**
     * Executes a task with a supplier, executor, and priority.
     *
     * Example usage: See {@link #execute(Supplier)}.
     *
     * @param supplier The supplier to execute.
     * @param executor The executor to run the task on.
     * @param priority The task priority.
     * @return The created Task.
     */
    public static <T> Task<T> execute(Supplier<T> supplier, Executor executor, int priority) {
        return execute(supplier, executor, priority, null, false);
    }
 /**
     * Executes a task with a supplier, executor, priority, and callback.
     *
     * Example usage: See {@link #execute(Supplier, Executor, Consumer)}.
     *
     * @param supplier The supplier to execute.
     * @param executor The executor to run the task on.
     * @param priority The task priority.
     * @param onStartCallback The callback to invoke when the task starts.
     * @return The created Task.
     */
    public static <T> Task<T> execute(Supplier<T> supplier, Executor executor, int priority, Consumer<Task<T>> onStartCallback) {
        return execute(supplier, executor, priority, onStartCallback, false);
    }

    /**
     * Executes a task with a supplier, executor, priority, callback, and pipeline flag.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.execute(() -> "Pipeline task", AsyncAwaitConfig.getDefaultExecutor(),
     *     1, t -> System.out.println("Task started"), true);
     * System.out.println(task.isLastTaskInPipeline()); // Prints true
     * ```
     *
     * @param supplier The supplier to execute.
     * @param executor The executor to run the task on.
     * @param priority The task priority.
     * @param onStartCallback The callback to invoke when the task starts.
     * @param isLastTaskInPipeline Whether this task is the last in a pipeline.
     * @return The created Task.
     */
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

     /**
     * Executes a task with a checked runnable using the default executor.
     *
     * Example usage:
     * ```java
     * Task<Void> task = Task.execute(() -> System.out.println("Task action"));
     * try {
     *     task.await();
     *     System.out.println(task.isDone()); // Prints true
     * } catch (TaskException e) {
     *     e.printStackTrace();
     * }
     * ```
     * @param runnable Functional interface for runnables that may throw checked exceptions.
     */
    public static Task<Void> execute(CheckedRunnable runnable) {
        return execute(runnable, AsyncAwaitConfig.getDefaultExecutor(), 0, null, false);
    }
   /**
     * Executes a task with a checked runnable and executor.
     *
     * Example usage: See {@link #execute(CheckedRunnable)}.
     *
     * @param runnable The runnable to execute.
     * @param executor The executor to run the task on.
     * @return The created Task.
     */
    public static Task<Void> execute(CheckedRunnable runnable, Executor executor) {
        return execute(runnable, executor, 0, null, false);
    }
/**
     * Executes a task with a checked runnable, executor, and callback.
     *
     * Example usage:
     * ```java
     * Task<Void> task = Task.execute(() -> System.out.println("Task action"),
     *     AsyncAwaitConfig.getDefaultExecutor(), t -> System.out.println("Task started"));
     * try {
     *     task.await();
     * } catch (TaskException e) {
     *     e.printStackTrace();
     * }
     * ```
     *
     * @param runnable The runnable to execute.
     * @param executor The executor to run the task on.
     * @param onStartCallback The callback to invoke when the task starts.
     * @return The created Task.
     */
    public static Task<Void> execute(CheckedRunnable runnable, Executor executor, Consumer<Task<Void>> onStartCallback) {
        return execute(runnable, executor, 0, onStartCallback, false);
    }
 /**
     * Executes a task with a checked runnable, executor, and priority.
     *
     * Example usage: See {@link #execute(CheckedRunnable)}.
     *
     * @param runnable The runnable to execute.
     * @param executor The executor to run the task on.
     * @param priority The task priority.
     * @return The created Task.
     */
    public static Task<Void> execute(CheckedRunnable runnable, Executor executor, int priority) {
        return execute(runnable, executor, priority, null, false);
    }
 /**
     * Executes a task with a checked runnable, executor, priority, and callback.
     *
     * Example usage: See {@link #execute(CheckedRunnable, Executor, Consumer)}.
     *
     * @param runnable The runnable to execute.
     * @param executor The executor to run the task on.
     * @param priority The task priority.
     * @param onStartCallback The callback to invoke when the task starts.
     * @return The created Task.
     */
    public static Task<Void> execute(CheckedRunnable runnable, Executor executor, int priority, Consumer<Task<Void>> onStartCallback) {
        return execute(runnable, executor, priority, onStartCallback, false);
    }

     /**
     * Executes a task with a checked runnable, executor, priority, callback, and pipeline flag.
     *
     * Example usage:
     * ```java
     * Task<Void> task = Task.execute(() -> System.out.println("Pipeline task"),
     *     AsyncAwaitConfig.getDefaultExecutor(), 1, t -> System.out.println("Task started"), true);
     * System.out.println(task.isLastTaskInPipeline()); // Prints true
     * ```
     *
     * @param runnable The runnable to execute.
     * @param executor The executor to run the task on.
     * @param priority The task priority.
     * @param onStartCallback The callback to invoke when the task starts.
     * @param isLastTaskInPipeline Whether this task is the last in a pipeline.
     * @return The created Task.
     */
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

     /**
     * Executes a task that returns a CompletableFuture.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.executeFuture(() -> CompletableFuture.completedFuture("Future result"));
     * try {
     *     String result = task.await();
     *     System.out.println(result); // Prints "Future result"
     * } catch (TaskException e) {
     *     e.printStackTrace();
     * }
     * ```
     *
     * @param supplier The supplier providing a CompletableFuture.
     * @return The created Task.
     */
    public static <T> Task<T> executeFuture(Supplier<CompletableFuture<T>> supplier) {
        validateSupplier(supplier);
        CompletableFuture<T> future = supplier.get();
        Objects.requireNonNull(future, "Supplied future cannot be null");
        return new Task<>(future, null, false);
    }
 /**
     * Creates a task that delays execution for the specified time.
     *
     * Example usage:
     * ```java
     * Task<Void> task = Task.delay(1000);
     * try {
     *     task.await();
     *     System.out.println("Delayed 1 second");
     * } catch (TaskException e) {
     *     e.printStackTrace();
     * }
     * ```
     *
     * @param millis The delay in milliseconds.
     * @return The created Task.
     */
    public static Task<Void> delay(long millis) {
        return delay(millis, AsyncAwaitConfig.getDefaultExecutor());
    }

    /**
     * Creates a task that delays execution for the specified time on a given executor.
     *
     * Example usage: See {@link #delay(long)}.
     *
     * @param millis The delay in milliseconds.
     * @param executor The executor to run the delay on.
     * @return The created Task.
     */
    public static Task<Void> delay(long millis, Executor executor) {
        if (millis < 0) {
            throw new IllegalArgumentException("Delay must be non-negative");
        }
        Objects.requireNonNull(executor, "Executor cannot be null");
        return new Task<>(CompletableFuture.runAsync(() -> {
        }, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS, executor)), null, false);
    }

    /**
     * Applies a function to the task's result, returning a new Task.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.of("Hello");
     * Task<String> transformed = task.thenApply(s -> s + " World");
     * try {
     *     System.out.println(transformed.await()); // Prints "Hello World"
     * } catch (TaskException e) {
     *     e.printStackTrace();
     * }
     * ```
     *
     * @param fn The function to apply to the task's result.
     * @return A new Task with the transformed result.
     */
    public <U> Task<U> thenApply(Function<? super T, ? extends U> fn) {
        Objects.requireNonNull(fn, "Function cannot be null");
        return new Task<>(future.thenApply(fn), null, false);
    }
 /**
     * Awaits the task's completion and returns its result.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.execute(() -> "Task result");
     * try {
     *     String result = task.await();
     *     System.out.println(result); // Prints "Task result"
     * } catch (TaskException e) {
     *     e.printStackTrace();
     * }
     * ```
     *
     * @return The task's result.
     * @throws TaskException If the task fails or is interrupted.
     */
    public T await() {
        return await(DEFAULT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
/**
     * Awaits the task's completion with a timeout and returns its result.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.execute(() -> "Task result");
     * try {
     *     String result = task.await(10, TimeUnit.SECONDS);
     *     System.out.println(result); // Prints "Task result"
     * } catch (TaskException e) {
     *     e.printStackTrace();
     * }
     * ```
     *
     * @param timeout The maximum time to wait.
     * @param unit The time unit of the timeout.
     * @return The task's result.
     * @throws TaskException If the task fails, is interrupted, or times out.
     */
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
 /**
     * Checks if the task is done.
      * Task<String> task = Task.of("Completed");
     * System.out.println(task.isDone()); // Prints true
     * @return True if the task is done, false otherwise.
     */
    public boolean isDone() {
        return future.isDone();
    }
 /**
     * Checks if the task has started.
     *
      * ```java
     * Task<String> task = Task.execute(() -> "Task result", AsyncAwaitConfig.getDefaultExecutor(),
     *     t -> System.out.println("Started"));
     * System.out.println(task.isStarted()); // Prints true after task starts
     *
     * @return True if the task has started, false otherwise.
     */
    public boolean isStarted() {
        return isStarted;
    }
  /**
     * Checks if the task is cancelled.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.execute(() -> "Task result");
     * task.cancel(true);
     * System.out.println(task.isCancelled()); // Prints true
     * ```
     *
     * @return True if the task is cancelled, false otherwise.
     */
    public boolean isCancelled() {
        return future.isCancelled();
    }
 /**
     * Cancels the task.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.execute(() -> "Task result");
     * task.cancel(true);
     * System.out.println(task.isCancelled()); // Prints true
     * ```
     *
     * @param mayInterruptIfRunning Whether to interrupt the running thread.
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
     * Closes the task, cancelling it if not done.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.execute(() -> "Task result");
     * task.close();
     * System.out.println(task.isCancelled()); // Prints true if task was not done
     * ```
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
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.of("Result");
     * CompletableFuture<String> future = task.getFuture();
     * System.out.println(future.isDone()); // Prints true
     * ```
     *
     * @return The CompletableFuture backing this task.
     */
    public CompletableFuture<T> getFuture() {
        return future;
    }

    /**
     * Checks if this task is the last in a pipeline.
     *
     * Example usage:
     * ```java
     * Task<String> task = Task.execute(() -> "Task result", AsyncAwaitConfig.getDefaultExecutor(),
     *     1, null, true);
     * System.out.println(task.isLastTaskInPipeline()); // Prints true
     * ```
     *
     * @return True if this task is the last in a pipeline, false otherwise.
     */
    public boolean isLastTaskInPipeline() {
        return isLastTaskInPipeline;
    }
  /**
     * Executes a supplier asynchronously and awaits its result.
     *
     * Example usage:
     * ```java
     * String result = Task.executeAsyncAndAwait(() -> "Task result");
     * System.out.println(result); // Prints "Task result"
     * ```
     *
     * @param supplier The supplier to execute.
     * @return The result of the task.
     * @throws TaskException If the task fails or is cancelled.
     */
    public static <T> T executeAsyncAndAwait(Supplier<T> supplier) {
        return executeAsyncAndAwait(supplier, AsyncAwaitConfig.getDefaultExecutor(), 0);
    }
 /**
     * Executes a supplier asynchronously with an executor and awaits its result.
     *
     * Example usage: See {@link #executeAsyncAndAwait(Supplier)}.
     *
     * @param supplier The supplier to execute.
     * @param executor The executor to run the task on.
     * @return The result of the task.
     * @throws TaskException If the task fails or is cancelled.
     */
    public static <T> T executeAsyncAndAwait(Supplier<T> supplier, Executor executor) {
        return executeAsyncAndAwait(supplier, executor, 0);
    }
/**
     * Executes a supplier asynchronously with an executor and priority, awaiting its result.
     *
     * Example usage:
     * ```java
     * String result = Task.executeAsyncAndAwait(() -> "Task result",
     *     AsyncAwaitConfig.getDefaultExecutor(), 1);
     * System.out.println(result); // Prints "Task result"
     * ```
     *
     * @param supplier The supplier to execute.
     * @param executor The executor to run the task on.
     * @param priority The task priority.
     * @return The result of the task.
     * @throws TaskException If the task fails or is cancelled.
     */
    public static <T> T executeAsyncAndAwait(Supplier<T> supplier, Executor executor, int priority) {
        validateSupplier(supplier);
        Objects.requireNonNull(executor, "Executor cannot be null");
        Task<T> task = Task.execute(supplier, executor, priority, t -> LOGGER.fine("Task started: " + t), false);
        try {
            return task.await(DEFAULT_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TaskException e) {
            if (task.isCancelled() || e.getCause() instanceof CancellationException) {
                throw new TaskException("Task was cancelled", e);
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