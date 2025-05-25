
package com.example.company.asyncawaitjava.task;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Un administrador de tareas robusto y seguro para subprocesos que permite programar y ejecutar tareas asíncronas con prioridades,
 * dependencias, cancelaciones automáticas, reintentos y retrollamadas de estado.
 *
 * @param <T> El tipo de datos asociado con las tareas.
 */
public class TaskManager<T> {
    private static final Logger LOGGER = Logger.getLogger(TaskManager.class.getName());

    // Constantes para configuraciones predeterminadas
    private static final int DEFAULT_SCHEDULER_POOL_SIZE = Math.min(4, Runtime.getRuntime().availableProcessors());
    private static final long DEFAULT_BACKOFF_BASE_MS = 100L;
    private static final int DEFAULT_MAX_BACKOFF_EXPONENT = 3;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;
    private static final long AWAIT_ALL_TIMEOUT_SECONDS = 30L;
    private static final long AUTO_CANCEL_TIMEOUT_SECONDS = 1L;

    private final PriorityQueue<TaskEntry<T>> tasks = new PriorityQueue<>((a, b) -> Integer.compare(b.priority, a.priority));
    private final Map<Task<?>, TaskEntry<T>> taskData = new ConcurrentHashMap<>();
    private final BlockingQueue<TaskEntry<T>> completedTasks = new LinkedBlockingQueue<>();
    private final Consumer<TaskStatus<T>> onTaskComplete;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService taskExecutor;
    private final Set<ScheduledFuture<?>> scheduledCancellations = Collections.synchronizedSet(new HashSet<>());
    private final Map<Task<?>, ScheduledFuture<?>> taskToCancellation = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private volatile boolean isClosed = false;

    // Métricas
    private final AtomicLong completedTasksCount = new AtomicLong();
    private final AtomicLong cancelledTasksCount = new AtomicLong();
    private final AtomicLong failedTasksCount = new AtomicLong();

    /**
     * Excepción personalizada para errores del TaskManager.
     */
    public static class TaskManagerException extends RuntimeException {
        public TaskManagerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Representa una entrada de tarea con metadatos.
     */
    static class TaskEntry<T> {
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
    }

    /**
     * Representa el estado de una tarea completada.
     */
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

    /**
     * Configuración para el comportamiento de reintentos.
     */
    public static class RetryConfig {
        final long backoffBaseMs;
        final int maxBackoffExponent;
        final Predicate<Throwable> retryableException;

        public RetryConfig(long backoffBaseMs, int maxBackoffExponent, Predicate<Throwable> retryableException) {
            this.backoffBaseMs = backoffBaseMs;
            this.maxBackoffExponent = maxBackoffExponent;
            this.retryableException = retryableException != null ? retryableException : t -> t instanceof RuntimeException;
        }

        public static RetryConfig defaultConfig() {
            return new RetryConfig(DEFAULT_BACKOFF_BASE_MS, DEFAULT_MAX_BACKOFF_EXPONENT, null);
        }
    }

    /**
     * Construye un TaskManager con una retrollamada de finalización.
     */
    public TaskManager(Consumer<TaskStatus<T>> onTaskComplete) {
        this(onTaskComplete, Executors.newScheduledThreadPool(DEFAULT_SCHEDULER_POOL_SIZE, new NamedThreadFactory("TaskManager-Scheduler")),
             Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), new NamedThreadFactory("TaskManager-Executor")));
    }

    /**
     * Construye un TaskManager con un programador y ejecutor personalizados.
     */
    public TaskManager(Consumer<TaskStatus<T>> onTaskComplete, ScheduledExecutorService scheduler, ExecutorService taskExecutor) {
        this.onTaskComplete = onTaskComplete;
        this.scheduler = Objects.requireNonNull(scheduler, "El programador no puede ser nulo");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "El ejecutor de tareas no puede ser nulo");
        startCompletedTasksProcessor();
    }

    /**
     * Crea un TaskManager con un consumidor de datos simple.
     */
    public static <T> TaskManager<T> of(Consumer<T> onTaskComplete) {
        return new TaskManager<>(status -> {
            if (onTaskComplete != null && status.data != null) {
                onTaskComplete.accept(status.data);
            }
        });
    }

    /**
     * Crea una lista de acciones a partir de proveedores.
     */
    @SafeVarargs
    public static <R> List<Supplier<R>> actions(Supplier<R>... actions) {
        if (actions == null || actions.length == 0) {
            throw new IllegalArgumentException("Se requiere al menos una acción");
        }
        return Arrays.asList(actions);
    }

    /**
     * Programa una tarea asíncrona.
     */
    public <R> Task<R> scheduleTask(Supplier<R> action, T data, int priority, Set<Task<?>> dependsOn, int autoCancelAfterMs, int maxRetries) {
        return scheduleTask(action, data, priority, dependsOn, autoCancelAfterMs, maxRetries, RetryConfig.defaultConfig());
    }

    /**
     * Programa una tarea asíncrona con configuración de reintentos personalizada.
     */
    public <R> Task<R> scheduleTask(Supplier<R> action, T data, int priority, Set<Task<?>> dependsOn, int autoCancelAfterMs, int maxRetries, RetryConfig retryConfig) {
        validateParameters(action, autoCancelAfterMs, maxRetries, retryConfig);
        synchronized (lock) {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }
            checkCircularDependencies(null, dependsOn);
            // Usamos Task.run con taskExecutor y null para onStartCallback
            Task<R> task = Task.run(() -> {
                int attempts = 0;
                while (attempts <= maxRetries) {
                    try {
                        return action.get();
                    } catch (Throwable t) {
                        if (!retryConfig.retryableException.test(t) || attempts++ == maxRetries) {
                            String errorMsg = String.format("Máximo de reintentos excedido para la tarea con datos: %s, intentos: %d", data, attempts);
                            LOGGER.severe(errorMsg);
                            throw new TaskManagerException(errorMsg, t);
                        }
                        long delay = Math.min(retryConfig.backoffBaseMs * (long) Math.pow(2, Math.min(attempts, retryConfig.maxBackoffExponent)), 10000L);
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new TaskManagerException("Interrumpido durante reintento para la tarea con datos: " + data, ie);
                        }
                    }
                }
                return null; // Nunca debería llegar aquí
            }, taskExecutor, null);
            boolean hasAutoCancel = autoCancelAfterMs > 0;
            addTask(task, data, priority, dependsOn, hasAutoCancel);
            if (hasAutoCancel) {
                ScheduledFuture<?> future = scheduler.schedule(() -> cancelTask(task, data), autoCancelAfterMs, TimeUnit.MILLISECONDS);
                scheduledCancellations.add(future);
                taskToCancellation.put(task, future);
            }
            return task;
        }
    }

    /**
     * Agrega tareas dependientes donde cada tarea depende de la anterior.
     */
    public void addDependentTasks(List<Supplier<?>> actions, T data, int priority, int autoCancelAfterMs, int maxRetries) {
        addDependentTasks(actions, data, priority, autoCancelAfterMs, maxRetries, RetryConfig.defaultConfig());
    }

    /**
     * Agrega tareas dependientes con configuración de reintentos personalizada.
     */
  public void addDependentTasks(List<Supplier<?>> actions, T data, int priority, int autoCancelAfterMs, int maxRetries, RetryConfig retryConfig) {
    if (actions == null || actions.isEmpty()) {
        throw new IllegalArgumentException("La lista de acciones no puede ser nula o vacía");
    }
    validateParameters(actions.get(0), autoCancelAfterMs, maxRetries, retryConfig);
    synchronized (lock) {
        if (isClosed) {
            throw new TaskManagerException("TaskManager está cerrado", null);
        }
        Task<?> previousTask = null;
        List<Task<?>> taskChain = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            Supplier<?> action = actions.get(i);
            boolean isLast = i == actions.size() - 1;
            Task<?> task = Task.run(() -> {
                try {
                    return action.get();
                } catch (Throwable t) {
                    throw new TaskManagerException("Error ejecutando la acción en la tarea dependiente: " + data, t);
                }
            }, taskExecutor, null);
            boolean hasAutoCancel = isLast && autoCancelAfterMs > 0;
            Set<Task<?>> dependsOn = previousTask != null ? Set.of(previousTask) : null;
            addTask(task, isLast ? data : null, priority, dependsOn, hasAutoCancel);
            taskChain.add(task);
            if (hasAutoCancel) {
                ScheduledFuture<?> future = scheduler.schedule(() -> cancelTask(task, data), autoCancelAfterMs, TimeUnit.MILLISECONDS);
                scheduledCancellations.add(future);
                taskToCancellation.put(task, future);
            }
            previousTask = task;
        }
        // Cancelar tareas posteriores si una falla
        for (int i = 0; i < taskChain.size() - 1; i++) {
            final Task<?> currentTask = taskChain.get(i); // Variable final
            final int index = i; // Capturar el índice
            currentTask.future.whenComplete((result, ex) -> {
                if (ex != null) {
                    for (int j = index + 1; j < taskChain.size(); j++) {
                        cancelTask(taskChain.get(j), data);
                    }
                }
            });
        }
    }
}

    /**
     * Agrega una tarea con prioridad y dependencias predeterminadas.
     */
    public void addTask(Task<?> task, T data) {
        addTask(task, data, 0, null, false);
    }

    /**
     * Agrega una tarea con prioridad y dependencias especificadas.
     */
    public void addTask(Task<?> task, T data, int priority, Set<Task<?>> dependsOn) {
        addTask(task, data, priority, dependsOn, false);
    }

    /**
     * Agrega una tarea con configuración completa.
     */
    public void addTask(Task<?> task, T data, int priority, Set<Task<?>> dependsOn, boolean hasAutoCancel) {
        if (task == null) {
            throw new IllegalArgumentException("La tarea no puede ser nula");
        }
        synchronized (lock) {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }
            checkCircularDependencies(task, dependsOn);
            TaskEntry<T> entry = new TaskEntry<>(task, data, priority, dependsOn, hasAutoCancel);
            tasks.add(entry);
            if (data != null) {
                taskData.put(task, entry);
            }
            task.future.whenComplete((result, ex) -> {
                if (areDependenciesCompleted(entry) && !entry.isProcessed) {
                    completedTasks.offer(entry);
                }
            });
        }
    }

    /**
     * Agrega una tarea con prioridad especificada.
     */
    public void addTask(Task<?> task, int priority) {
        addTask(task, null, priority, null, false);
    }

    /**
     * Agrega una tarea con dependencias.
     */
    public void addTask(Task<?> task, int priority, Set<Task<?>> dependsOn) {
        addTask(task, null, priority, dependsOn, false);
    }

    /**
     * Cancela una tarea e invoca la retrollamada de finalización.
     */
    public boolean cancelTask(Task<?> task, T dataForCallback) {
        synchronized (lock) {
            if (isClosed) return false;
            TaskEntry<T> entry = taskData.get(task);
            boolean wasCancelled = false;
            if (entry != null) {
                entry.isCancelled = true;
                entry.task.cancel(true);
                tasks.remove(entry);
                taskData.remove(task);
                wasCancelled = true;
                cancelledTasksCount.incrementAndGet();
            }
            ScheduledFuture<?> cancellation = taskToCancellation.remove(task);
            if (cancellation != null) {
                cancellation.cancel(false);
                scheduledCancellations.remove(cancellation);
            }
            if (wasCancelled && !entry.isProcessed) {
                entry.isProcessed = true;
                processTask(entry, true, false, null);
            } else if (dataForCallback != null && onTaskComplete != null) {
                onTaskComplete.accept(new TaskStatus<>(dataForCallback, true, false, null));
                cancelledTasksCount.incrementAndGet();
            }
            LOGGER.info("Cancelación de tarea: tarea=%s, datos=%s, cancelada=%b".formatted(task, dataForCallback, wasCancelled));
            return wasCancelled;
        }
    }

    /**
     * Procesa tareas completadas o canceladas.
     */
    public void processCompletedTasks() {
        TaskEntry<T> entry;
        while ((entry = completedTasks.poll()) != null) {
            synchronized (lock) {
                if (entry.isProcessed || isClosed) continue;
                if (entry.isCancelled || (entry.task.isDone() && areDependenciesCompleted(entry))) {
                    tasks.remove(entry);
                    taskData.remove(entry.task);
                    ScheduledFuture<?> cancellation = taskToCancellation.remove(entry.task);
                    if (cancellation != null) {
                        cancellation.cancel(false);
                        scheduledCancellations.remove(cancellation);
                    }
                    entry.isProcessed = true;
                    if (!entry.isCancelled && entry.task.future.isCompletedExceptionally()) {
                        try {
                            entry.task.future.getNow(null);
                        } catch (Exception ex) {
                            processTask(entry, false, true, ex);
                            failedTasksCount.incrementAndGet();
                        }
                    } else if (entry.task.isDone() && !entry.isCancelled) {
                        processTask(entry, false, false, null);
                        completedTasksCount.incrementAndGet();
                    }
                }
            }
        }
    }

    private boolean areDependenciesCompleted(TaskEntry<T> entry) {
        return entry.dependsOn.stream().allMatch(dep -> {
            TaskEntry<T> depEntry = taskData.get(dep);
            return depEntry == null || depEntry.task.isDone() || depEntry.isCancelled;
        });
    }

    private void checkCircularDependencies(Task<?> task, Set<Task<?>> dependsOn) {
        if (dependsOn == null || dependsOn.isEmpty()) return;
        Set<Task<?>> visited = new HashSet<>();
        Deque<Task<?>> stack = new ArrayDeque<>(dependsOn);
        while (!stack.isEmpty()) {
            Task<?> current = stack.pop();
            if (task != null && current == task) {
                throw new IllegalArgumentException("Dependencia circular detectada para la tarea: " + task);
            }
            if (visited.add(current)) {
                TaskEntry<?> entry = taskData.get(current);
                if (entry != null && entry.dependsOn != null) {
                    stack.addAll(entry.dependsOn);
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

    /**
     * Espera a que todas las tareas se completen o se agote el tiempo de espera.
     */
    public void awaitAll() throws InterruptedException {
        synchronized (lock) {
            if (isClosed) return;
            processCompletedTasks();
            CompletableFuture<Void> allTasks = CompletableFuture.allOf(
                tasks.stream().map(entry -> entry.task.future).toArray(CompletableFuture[]::new)
            );
            try {
                allTasks.get(AWAIT_ALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                String errorMsg = String.format("Error esperando tareas: %s. Tareas afectadas: %s",
                    e.getMessage(), tasks.stream().map(entry -> entry.data).toList());
                LOGGER.severe(errorMsg);
                throw new TaskManagerException(errorMsg, e);
            }
            processCompletedTasks();
            for (ScheduledFuture<?> future : new ArrayList<>(scheduledCancellations)) {
                try {
                    future.get(AUTO_CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.warning("Error esperando la cancelación automática: " + e.getMessage());
                }
            }
            scheduledCancellations.clear();
            taskToCancellation.clear();
            processCompletedTasks();
        }
    }

    /**
     * Verifica si hay tareas activas.
     */
    public boolean hasTasks() {
        synchronized (lock) {
            return !tasks.isEmpty();
        }
    }

    /**
     * Devuelve el número de tareas activas.
     */
    public int getActiveTaskCount() {
        synchronized (lock) {
            return tasks.size();
        }
    }

    /**
     * Cierra el TaskManager, cancelando todas las tareas pendientes y liberando recursos.
     */
    public void close() {
        synchronized (lock) {
            if (isClosed) return;
            isClosed = true;
            for (TaskEntry<T> entry : tasks) {
                if (!entry.isCancelled && !entry.task.isDone()) {
                    entry.isCancelled = true;
                    entry.task.cancel(false);
                    if (!entry.isProcessed) {
                        entry.isProcessed = true;
                        processTask(entry, true, false, null);
                        cancelledTasksCount.incrementAndGet();
                    }
                }
            }
            tasks.clear();
            taskData.clear();
            taskToCancellation.clear();
            scheduledCancellations.forEach(future -> future.cancel(false));
            scheduledCancellations.clear();
            shutdownExecutors();
            LOGGER.info("TaskManager cerrado, tareas activas: %d, completadas: %d, canceladas: %d, fallidas: %d"
                .formatted(tasks.size(), completedTasksCount.get(), cancelledTasksCount.get(), failedTasksCount.get()));
        }
    }

    private void shutdownExecutors() {
        scheduler.shutdown();
        taskExecutor.shutdown();
        int retries = 3;
        while (retries-- > 0) {
            try {
                if (scheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
                    taskExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    return;
                }
                LOGGER.warning("Reintentando el cierre de ejecutores...");
                scheduler.shutdownNow();
                taskExecutor.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
                taskExecutor.shutdownNow();
            }
        }
        LOGGER.severe("No se pudieron cerrar los ejecutores completamente");
    }

    /**
     * Crea un nuevo constructor de tareas.
     */
    public <R> TaskBuilder<T, R> newTask(Supplier<R> action) {
        return new TaskBuilder<>(this, action);
    }

    /**
     * Agrega tareas encadenadas donde la segunda depende de la primera.
     */
    public <R1, R2> void addChainedTasks(Supplier<R1> firstAction, Supplier<R2> secondAction, T data, int priority, int autoCancelSecondAfterMs) {
        addChainedTasks(firstAction, secondAction, data, priority, autoCancelSecondAfterMs, RetryConfig.defaultConfig());
    }

    /**
     * Agrega tareas encadenadas con configuración de reintentos personalizada.
     */
    public <R1, R2> void addChainedTasks(Supplier<R1> firstAction, Supplier<R2> secondAction, T data, int priority, int autoCancelSecondAfterMs, RetryConfig retryConfig) {
        validateParameters(firstAction, autoCancelSecondAfterMs, 0, retryConfig);
        validateParameters(secondAction, autoCancelSecondAfterMs, 0, retryConfig);
        synchronized (lock) {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }
            TaskBuilder<T, R1> firstTaskBuilder = newTask(firstAction).withPriority(priority).withRetryConfig(retryConfig);
            Task<R1> firstTask = firstTaskBuilder.schedule();
            newTask(secondAction)
                .withData(data)
                .withPriority(priority)
                .withDependency(firstTask)
                .withAutoCancel(autoCancelSecondAfterMs)
                .withRetryConfig(retryConfig)
                .schedule();
        }
    }

    /**
     * Devuelve métricas sobre la ejecución de tareas.
     */
    public Map<String, Long> getMetrics() {
        return Map.of(
            "completedTasks", completedTasksCount.get(),
            "cancelledTasks", cancelledTasksCount.get(),
            "failedTasks", failedTasksCount.get(),
            "activeTasks", (long) getActiveTaskCount()
        );
    }

    private void startCompletedTasksProcessor() {
        taskExecutor.submit(() -> {
            while (!isClosed) {
                try {
                    processCompletedTasks();
                    Thread.sleep(10); // Previene espera activa
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void validateParameters(Supplier<?> action, int autoCancelAfterMs, int maxRetries, RetryConfig retryConfig) {
        if (action == null) {
            throw new IllegalArgumentException("La acción no puede ser nula");
        }
        if (autoCancelAfterMs < 0) {
            throw new IllegalArgumentException("autoCancelAfterMs debe ser no negativo");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries debe ser no negativo");
        }
        if (retryConfig == null) {
            throw new IllegalArgumentException("RetryConfig no puede ser nulo");
        }
    }

    /**
     * Fábrica personalizada para nombrar hilos.
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicLong counter = new AtomicLong();

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}