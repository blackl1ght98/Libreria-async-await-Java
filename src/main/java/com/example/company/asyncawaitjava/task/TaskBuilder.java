package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.config.RetryConfig;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A fluent builder for scheduling tasks in a TaskManager, allowing configuration of task properties
 * such as priority, dependencies, auto-cancellation, retries, and retry policies.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * TaskBuilder<String, String> builder = new TaskBuilder<>(taskManager, () -> "Task result");
 * Task<String> task = builder.withData("TaskData").withPriority(1).schedule();
 * System.out.println(task.isDone()); // Prints false initially
 * ```
 *
 * @param <T> The type of data associated with the task.
 * @param <R> The result type of the task.
 */
public class TaskBuilder<T, R> {
    private final TaskManager<T> taskManager;
    private final Supplier<R> action;
    private T data;
    private int priority = 0;
    private Set<Task<?>> dependsOn = new HashSet<>();
    private int autoCancelAfterMs = 0;
    private int maxRetries = 0;
    private RetryConfig retryConfig = RetryConfig.defaultConfig();

    /**
     * Constructs a TaskBuilder for a given TaskManager and action.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * TaskBuilder<String, String> builder = new TaskBuilder<>(taskManager, () -> "Task result");
     * System.out.println(builder != null); // Prints true
     * ```
     *
     * @param taskManager The TaskManager to schedule the task.
     * @param action      The action to execute.
     * @throws IllegalArgumentException If taskManager or action is null.
     */
    public TaskBuilder(TaskManager<T> taskManager, Supplier<R> action) {
        this.taskManager = Objects.requireNonNull(taskManager, "TaskManager cannot be null");
        this.action = Objects.requireNonNull(action, "Action cannot be null");
    }

    /**
     * Sets the data associated with the task.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * TaskBuilder<String, String> builder = new TaskBuilder<>(taskManager, () -> "Task result");
     * builder.withData("TaskData");
     * Task<String> task = builder.schedule();
     * System.out.println(task.isDone()); // Prints false initially
     * ```
     *
     * @param data The data to associate.
     * @return This builder.
     */
    public TaskBuilder<T, R> withData(T data) {
        this.data = data;
        return this;
    }

    /**
     * Sets the priority of the task (higher value means higher priority).
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * TaskBuilder<String, String> builder = new TaskBuilder<>(taskManager, () -> "Task result");
     * builder.withPriority(2);
     * Task<String> task = builder.schedule();
     * System.out.println(task.isDone()); // Prints false initially
     * ```
     *
     * @param priority The priority value.
     * @return This builder.
     */
    public TaskBuilder<T, R> withPriority(int priority) {
        this.priority = priority;
        return this;
    }

    /**
     * Sets the dependencies for the task.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * Task<String> task1 = taskManager.scheduleTask(() -> "Task 1", "Data1", 1, null, 5000, 2);
     * TaskBuilder<String, String> builder = new TaskBuilder<>(taskManager, () -> "Task 2");
     * builder.withDependencies(Set.of(task1));
     * Task<String> task2 = builder.schedule();
     * ```
     *
     * @param dependsOn The set of tasks this task depends on.
     * @return This builder.
     */
    public TaskBuilder<T, R> withDependencies(Set<Task<?>> dependsOn) {
        this.dependsOn = dependsOn != null ? new HashSet<>(dependsOn) : new HashSet<>();
        return this;
    }

    /**
     * Adds a single dependency for the task.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * Task<String> task1 = taskManager.scheduleTask(() -> "Task 1", "Data1", 1, null, 5000, 2);
     * TaskBuilder<String, String> builder = new TaskBuilder<>(taskManager, () -> "Task 2");
     * builder.withDependency(task1);
     * Task<String> task2 = builder.schedule();
     * System.out.println(task2.isDone()); // Prints false initially
     * ```
     *
     * @param dependency The task this task depends on.
     * @return This builder.
     * @throws IllegalArgumentException If dependency is null.
     */
    public TaskBuilder<T, R> withDependency(Task<?> dependency) {
        Objects.requireNonNull(dependency, "Dependency cannot be null");
        this.dependsOn.add(dependency);
        return this;
    }

    /**
     * Sets the auto-cancellation timeout in milliseconds.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * TaskBuilder<String, String> builder = new TaskBuilder<>(taskManager, () -> "Task result");
     * builder.withAutoCancel(5000);
     * Task<String> task = builder.schedule();
     * System.out.println(task.isDone()); // Prints false initially
     * ```
     *
     * @param autoCancelAfterMs The timeout after which the task is cancelled (0 to disable).
     * @return This builder.
     * @throws IllegalArgumentException If autoCancelAfterMs is negative.
     */
    public TaskBuilder<T, R> withAutoCancel(int autoCancelAfterMs) {
        if (autoCancelAfterMs < 0) {
            throw new IllegalArgumentException("autoCancelAfterMs must be non-negative");
        }
        this.autoCancelAfterMs = autoCancelAfterMs;
        return this;
    }

    /**
     * Sets the maximum number of retries for the task.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * TaskBuilder<String, String> builder = new TaskBuilder<>(taskManager, () -> "Task result");
     * builder.withRetries(3);
     * Task<String> task = builder.schedule();
     * System.out.println(task.isDone()); // Prints false initially
     * ```
     *
     * @param maxRetries The maximum number of retries.
     * @return This builder.
     * @throws IllegalArgumentException If maxRetries is negative.
     */
    public TaskBuilder<T, R> withRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        this.maxRetries = maxRetries;
        return this;
    }

    /**
     * Sets the retry configuration for the task.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * TaskBuilder<String, String> builder = new TaskBuilder<>(taskManager, () -> "Task result");
     * builder.withRetryConfig(RetryConfig.defaultConfig());
     * Task<String> task = builder.schedule();
     * System.out.println(task.isDone()); // Prints false initially
     * ```
     *
     * @param retryConfig The retry configuration.
     * @return This builder.
     * @throws IllegalArgumentException If retryConfig is null.
     */
    public TaskBuilder<T, R> withRetryConfig(RetryConfig retryConfig) {
        this.retryConfig = Objects.requireNonNull(retryConfig, "RetryConfig cannot be null");
        return this;
    }

    /**
     * Schedules the task with the configured properties.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * TaskBuilder<String, String> builder = new TaskBuilder<>(taskManager, () -> "Task result");
     * Task<String> task = builder.withData("TaskData").withPriority(1).withAutoCancel(5000).schedule();
     * System.out.println(task.isDone()); // Prints false initially
     * ```
     *
     * @return The scheduled Task.
     * @throws TaskManager.TaskManagerException If the TaskManager is closed or there are circular dependencies.
     */
    public Task<R> schedule() {
        return taskManager.scheduleTask(action, data, priority, dependsOn, autoCancelAfterMs, maxRetries, retryConfig);
    }
}