/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.example.company.asyncawaitjava.task;

import java.util.HashSet;
import java.util.Set;

/**
 *
 * @author guillermo
 */
 /**
     * Representa una entrada de tarea con metadatos.
     */
  public class TaskEntry<T> {
        final Task<?> task;
        final T data;
        final int priority;
        final Set<Task<?>> dependsOn;
        volatile boolean isCancelled;
        volatile boolean isProcessed;
        final boolean hasAutoCancel;

        TaskEntry(Task<?> task, T data, int priority, Set<Task<?>> dependsOn, boolean hasAutoCancel) {
            this.task = task;
            this.data = data;
            this.priority = priority;
            this.dependsOn = dependsOn != null ? new HashSet<>(dependsOn) : new HashSet<>();
            this.isCancelled = false;
            this.isProcessed = false;
            this.hasAutoCancel = hasAutoCancel;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TaskEntry)) return false;
            TaskEntry<?> that = (TaskEntry<?>) o;
            return task.equals(that.task);
        }

        @Override
        public int hashCode() {
            return task.hashCode();
        }
    }
