
package com.example.company.asyncawaitjava;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class TaskManager<T> {
    private static final Logger LOGGER = Logger.getLogger(TaskManager.class.getName());
    private final PriorityQueue<TaskEntry<T>> tasks = new PriorityQueue<>((a, b) -> Integer.compare(b.priority, a.priority));
    private final Map<Task<?>, TaskEntry<T>> taskData = new HashMap<>();
    private final Consumer<TaskStatus<T>> onTaskComplete;
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Object lock = new Object();
    private volatile boolean isClosed = false;

    static class TaskEntry<T> {
        Task<?> task;
        T data;
        int priority;
        Set<Task<?>> dependsOn;
        boolean isCancelled;

        TaskEntry(Task<?> task, T data, int priority, Set<Task<?>> dependsOn) {
            this.task = task;
            this.data = data;
            this.priority = priority;
            this.dependsOn = dependsOn != null ? new HashSet<>(dependsOn) : new HashSet<>();
            this.isCancelled = false;
        }
    }

    public static class TaskStatus<T> {
        public final T data;
        public final boolean isCancelled;
        public final boolean isFailed;
        public final Exception exception;

        TaskStatus(T data, boolean isCancelled, boolean isFailed, Exception exception) {
            this.data = data;
            this.isCancelled = isCancelled;
            this.isFailed = isFailed;
            this.exception = exception;
        }
    }

    public TaskManager(Consumer<TaskStatus<T>> onTaskComplete) {
        this.onTaskComplete = onTaskComplete;
    }

    public TaskManager() {
        this.onTaskComplete = null;
    }

    public static <T> TaskManager<T> of(Consumer<T> onTaskComplete) {
        return new TaskManager<>(status -> {
            if (onTaskComplete != null && status.data != null) {
                onTaskComplete.accept(status.data);
            }
        });
    }

  public Task<Void> scheduleTask(Supplier<Void> action, T data, int priority, Set<Task<?>> dependsOn, int autoCancelAfterMs, int maxRetries) {
    synchronized (lock) {
        if (isClosed) {
            LOGGER.warning("Intento de programar tarea en TaskManager cerrado");
            throw new IllegalStateException("TaskManager is closed");
        }
        checkCircularDependencies(null, dependsOn); // Null para nueva tarea
        Task<Void> task = Task.runAsync(() -> {
            int attempts = 0;
            while (attempts <= maxRetries) {
                try {
                    action.get();
                    return;
                } catch (Exception e) {
                    if (attempts++ == maxRetries) {
                        throw new RuntimeException("Max retries exceeded", e);
                    }
                    try {
                        Thread.sleep(100 * (long) Math.pow(2, attempts));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                }
            }
        });
        addTask(task, data, priority, dependsOn);
        if (autoCancelAfterMs > 0) {
            scheduler.schedule(() -> cancelTask(task), autoCancelAfterMs, TimeUnit.MILLISECONDS);
        }
        return task;
    }
}

public void addDependentTasks(Supplier<Void> firstAction, Supplier<Void> secondAction, T data, int priority, int autoCancelSecondAfterMs) {
    synchronized (lock) {
        if (isClosed) {
            LOGGER.warning("Intento de añadir tareas dependientes en TaskManager cerrado");
            throw new IllegalStateException("TaskManager is closed");
        }
        Task<Void> firstTask = Task.runAsync(() -> firstAction.get());
        Task<Void> secondTask = Task.runAsync(() -> secondAction.get());
        addTask(firstTask, null, priority, null);
        addTask(secondTask, data, priority, Set.of(firstTask));
        if (autoCancelSecondAfterMs > 0) {
            scheduler.schedule(() -> cancelTask(secondTask), autoCancelSecondAfterMs, TimeUnit.MILLISECONDS);
        }
    }
}
    public void addTask(Task<?> task, T data) {
        addTask(task, data, 0, null);
    }

    public void addTask(Task<?> task, T data, int priority, Set<Task<?>> dependsOn) {
        synchronized (lock) {
            if (isClosed) {
                LOGGER.warning("Intento de añadir tarea en TaskManager cerrado");
                throw new IllegalStateException("TaskManager is closed");
            }
            if (task == null) {
                throw new IllegalArgumentException("Task cannot be null");
            }
            checkCircularDependencies(task, dependsOn);
            TaskEntry<T> entry = new TaskEntry<>(task, data, priority, dependsOn);
            tasks.add(entry);
            if (data != null) {
                taskData.put(task, entry);
            }
            task.future.whenComplete((result, ex) -> {
                synchronized (lock) {
                    if (isClosed) return;
                    if (areDependenciesCompleted(entry) && !entry.isCancelled) {
                        processTask(entry, entry.isCancelled, ex != null, ex != null ? new RuntimeException(ex) : null);
                        tasks.remove(entry);
                        taskData.remove(task);
                    }
                }
            });
        }
    }

    public void addTask(Task<?> task, int priority) {
        addTask(task, null, priority, null);
    }

    public void addTask(Task<?> task, int priority, Set<Task<?>> dependsOn) {
        addTask(task, null, priority, dependsOn);
    }


public boolean cancelTask(Task<?> task) {
    synchronized (lock) {
        if (isClosed) return false;
        TaskEntry<T> entry = taskData.get(task);
        if (entry != null && !entry.isCancelled && !entry.task.isDone()) {
            entry.isCancelled = true;
            tasks.remove(entry);
            taskData.remove(task);
            entry.task.cancel(true); // Cambiar a true para interrumpir la tarea
            tasks.forEach(e -> e.dependsOn.remove(task));
            processTask(entry, true, false, null);
            LOGGER.info("Tarea cancelada: " + task + ", data: " + entry.data);
            return true;
        }
        LOGGER.info("No se pudo cancelar tarea: " + task + ", ya completada o no encontrada");
        return false;
    }
}

public void processCompletedTasks() {
    synchronized (lock) {
        if (isClosed) return;
        Iterator<TaskEntry<T>> iterator = tasks.iterator();
        while (iterator.hasNext()) {
            TaskEntry<T> entry = iterator.next();
            if (entry.isCancelled || (entry.task.isDone() && areDependenciesCompleted(entry))) {
                iterator.remove();
                taskData.remove(entry.task);
                if (!entry.isCancelled && entry.task.future.isCompletedExceptionally()) {
                    try {
                        entry.task.future.getNow(null); // Forzar propagación de excepción
                    } catch (Exception ex) {
                        processTask(entry, false, true, ex);
                    }
                } else if (entry.task.isDone() && !entry.isCancelled) {
                    processTask(entry, false, false, null);
                }
                //LOGGER.info("Tarea procesada y eliminada: " + entry.task + ", data: " + entry.data);
            }
        }
    }
}
    private boolean areDependenciesCompleted(TaskEntry<T> entry) {
        synchronized (lock) {
            return entry.dependsOn.stream().allMatch(dep -> {
                TaskEntry<T> depEntry = taskData.get(dep);
                return depEntry == null || depEntry.task.isDone() || depEntry.isCancelled;
            });
        }
    }

    private void checkCircularDependencies(Task<?> task, Set<Task<?>> dependsOn) {
        synchronized (lock) {
            if (dependsOn == null || dependsOn.isEmpty()) return;
            Set<Task<?>> visited = new HashSet<>();
            Deque<Task<?>> stack = new ArrayDeque<>(dependsOn);
            while (!stack.isEmpty()) {
                Task<?> current = stack.pop();
                if (task != null && current == task) {
                    throw new IllegalArgumentException("Circular dependency detected for task: " + task);
                }
                if (visited.add(current)) {
                    TaskEntry<?> entry = taskData.get(current);
                    if (entry != null && entry.dependsOn != null && !entry.dependsOn.isEmpty()) {
                        stack.addAll(entry.dependsOn);
                    }
                }
            }
        }
    }

    private void processTask(TaskEntry<T> entry, boolean isCancelled, boolean isFailed, Exception exception) {
        synchronized (lock) {
            if (onTaskComplete != null && entry.data != null) {
                onTaskComplete.accept(new TaskStatus<>(entry.data, isCancelled, isFailed, exception));
            }
        }
    }

   public void awaitAll() throws InterruptedException {
    synchronized (lock) {
        if (isClosed) return;
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(
            tasks.stream().map(entry -> entry.task.future).toArray(CompletableFuture[]::new)
        );
        try {
            allTasks.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restaurar estado de interrupción
            throw e; // Propagar la excepción
        } catch (Exception e) {
            LOGGER.severe("Error esperando tareas: " + e.getMessage());
        }
        processCompletedTasks();
    }
}

    public boolean hasTasks() {
        synchronized (lock) {
            return !tasks.isEmpty();
        }
    }

    public int getActiveTaskCount() {
        synchronized (lock) {
            return tasks.size();
        }
    }


  public void close() {
        synchronized (lock) {
            if (isClosed) return;
            isClosed = true;
            // Cancelar todas las tareas
            Iterator<TaskEntry<T>> iterator = tasks.iterator();
            while (iterator.hasNext()) {
                TaskEntry<T> entry = iterator.next();
                if (!entry.isCancelled && !entry.task.isDone()) {
                    entry.isCancelled = true;
                    entry.task.cancel(false); // No interrumpir hilos
                    processTask(entry, true, false, null);
                }
                iterator.remove();
            }
            taskData.clear();
            // Cerrar scheduler
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    LOGGER.warning("Forzando cierre del scheduler después de 5 segundos");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restaurar estado de interrupción
                scheduler.shutdownNow();
                LOGGER.info("Interrumpido al cerrar scheduler, forzando cierre");
            } catch (Exception e) {
                LOGGER.severe("Error inesperado al cerrar scheduler: " + e.getMessage());
            }
            LOGGER.info("TaskManager cerrado, tareas activas: " + tasks.size());
        }
    }

    public class TaskBuilder {
        private final Supplier<Void> action;
        private T data;
        private int priority = 0;
        private Set<Task<?>> dependsOn = new HashSet<>();
        private int autoCancelAfterMs = 0;
        private int maxRetries = 0;

        private TaskBuilder(Supplier<Void> action) {
            this.action = action;
        }

        public TaskBuilder withData(T data) {
            this.data = data;
            return this;
        }

        public TaskBuilder withPriority(int priority) {
            this.priority = priority;
            return this;
        }

        public TaskBuilder withDependencies(Set<Task<?>> dependsOn) {
            this.dependsOn = dependsOn != null ? new HashSet<>(dependsOn) : new HashSet<>();
            return this;
        }

        public TaskBuilder withDependency(Task<?> dependency) {
            this.dependsOn.add(dependency);
            return this;
        }

        public TaskBuilder withAutoCancel(int autoCancelAfterMs) {
            this.autoCancelAfterMs = autoCancelAfterMs;
            return this;
        }

        public TaskBuilder withRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Task<Void> schedule() {
            return TaskManager.this.scheduleTask(action, data, priority, dependsOn, autoCancelAfterMs, maxRetries);
        }
    }

    public TaskBuilder newTask(Supplier<Void> action) {
        return new TaskBuilder(action);
    }

    public void addChainedTasks(Supplier<Void> firstAction, Supplier<Void> secondAction, T data, int priority, int autoCancelSecondAfterMs) {
        synchronized (lock) {
            if (isClosed) {
                LOGGER.warning("Intento de añadir tareas encadenadas en TaskManager cerrado");
                throw new IllegalStateException("TaskManager is closed");
            }
            TaskBuilder firstTaskBuilder = newTask(firstAction).withPriority(priority);
            Task<Void> firstTask = firstTaskBuilder.schedule();
            newTask(secondAction)
                .withData(data)
                .withPriority(priority)
                .withDependency(firstTask)
                .withAutoCancel(autoCancelSecondAfterMs)
                .schedule();
        }
    }
}