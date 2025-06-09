package com.example.company.asyncawaitjava.task;

/**
 * Represents the status of a completed task, including its result, cancellation state, failure state, and associated exception.
 */
public class TaskStatus<T> {

    public final T data;
    private final boolean isCancelled;
    private final boolean isFailed;
    public final Exception exception;
    private final boolean isAutoCancel;
    private final boolean isLastTaskInPipeline;

    /**
     * Constructs a TaskStatus with the given properties.
     * 
     * Example usage:
     * ```java
     * TaskStatus<String> status = new TaskStatus<>("Result", false, false, null, false, true);
     * System.out.println(status.getData()); // Prints "Result"
     * System.out.println(status.isLastTaskInPipeline()); // Prints true
     * ```
     * 
     * @param data The result data of the task.
     * @param isCancelled Whether the task was cancelled.
     * @param isFailed Whether the task failed.
     * @param exception The exception if the task failed.
     * @param isAutoCancel Whether the task was auto-cancelled.
     * @param isLastTaskInPipeline Whether this is the last task in a pipeline.
     */
    public TaskStatus(T data, boolean isCancelled, boolean isFailed, Exception exception, boolean isAutoCancel, boolean isLastTaskInPipeline) {
        this.data = data;
        this.isCancelled = isCancelled;
        this.isFailed = isFailed;
        this.exception = exception;
        this.isAutoCancel = isAutoCancel;
        this.isLastTaskInPipeline = isLastTaskInPipeline;
    }

    /**
     * Gets the result data of the task.
     * 
     * @return The task's result data.
     */
    public T getData() {
        return data;
    }

    /**
     * Checks if the task was cancelled.
     * 
     * @return True if the task was cancelled, false otherwise.
     */
    public boolean isCancelled() {
        return isCancelled;
    }

    /**
     * Checks if the task failed.
     * 
     * @return True if the task failed, false otherwise.
     */
    public boolean isFailed() {
        return isFailed;
    }

    /**
     * Checks if the task was auto-cancelled.
     * 
     * @return True if the task was auto-cancelled, false otherwise.
     */
    public boolean isAutoCancel() {
        return isAutoCancel;
    }

    /**
     * Checks if this is the last task in a pipeline.
     * 
     * @return True if this is the last task in a pipeline, false otherwise.
     */
    public boolean isLastTaskInPipeline() {
        return isLastTaskInPipeline;
    }
}