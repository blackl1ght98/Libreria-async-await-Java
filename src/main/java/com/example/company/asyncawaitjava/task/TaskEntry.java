package com.example.company.asyncawaitjava.task;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Representa una entrada de tarea con metadatos.
 */
public class TaskEntry<T> {

    public final Task<?> task;
    public final T data;
    public final int priority;
    public final Set<Task<?>> dependsOn;
    public volatile boolean isCancelled;
    public volatile boolean isProcessed;
    public final boolean hasAutoCancel;
    public volatile boolean isFailed;
    public volatile AtomicBoolean groupCancellationToken;

    public TaskEntry(Task<?> task, T data, int priority, Set<Task<?>> dependsOn, boolean hasAutoCancel) {
        this.task = task;
        this.data = data;
        this.priority = priority;
        this.dependsOn = dependsOn != null ? new HashSet<>(dependsOn) : new HashSet<>();
        this.isCancelled = false;
        this.isProcessed = false;
        this.isFailed = false;
        this.hasAutoCancel = hasAutoCancel;
        this.groupCancellationToken = null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskEntry)) {
            return false;
        }
        TaskEntry<?> that = (TaskEntry<?>) o;
        return task.equals(that.task);
    }

    @Override
    public int hashCode() {
        return task.hashCode();
    }
}
