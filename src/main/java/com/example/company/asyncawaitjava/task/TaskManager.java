package com.example.company.asyncawaitjava.task;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Un administrador de tareas robusto y seguro para subprocesos que permite
 * programar y ejecutar tareas asíncronas con prioridades, dependencias,
 * cancelaciones automáticas, reintentos y callbacks de estado.
 *
 * @param <T> El tipo de datos asociado con las tareas.
 */
public class TaskManager<T> {

    private static final Logger LOGGER = Logger.getLogger(TaskManager.class.getName());

    // Constantes para configuraciones predeterminadas
    private static final int DEFAULT_SCHEDULER_POOL_SIZE = Math.min(4, Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_TASK_EXECUTOR_POOL_SIZE = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
    private static final int DEFAULT_CALLBACK_EXECUTOR_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final long DEFAULT_BACKOFF_BASE_MS = 100L;
    private static final int DEFAULT_MAX_BACKOFF_EXPONENT = 3;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;
    private static final long AWAIT_ALL_TIMEOUT_SECONDS = 30L;

    private static final long MAX_BACKOFF_MS = 10000L;
    private static final long TASK_TIMEOUT_MS = 30000L; // 30 segundos por defecto

    private final ConcurrentSkipListSet<TaskEntry<T>> tasks = new ConcurrentSkipListSet<>((a, b) -> Integer.compare(b.priority, a.priority));
    private final Map<Task<?>, TaskEntry<T>> taskData = new ConcurrentHashMap<>();
    private final Consumer<TaskStatus<T>> onTaskComplete;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService taskExecutor;
    private ExecutorService callbackExecutor;
    private final Set<ScheduledFuture<?>> scheduledCancellations = ConcurrentHashMap.newKeySet();
    private final Map<Task<?>, ScheduledFuture<?>> taskToCancellation = new ConcurrentHashMap<>();
    private final ReentrantLock closeLock = new ReentrantLock();
    private volatile boolean isClosed = false;
    private final Map<Task<?>, Set<TaskEntry<T>>> dependentTasks = new ConcurrentHashMap<>();
    private final Map<Task<?>, Long> taskStartTimes = new ConcurrentHashMap<>();

    private final long taskTimeoutMs;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final Lock writeLock = lock.writeLock();
//private final Map<Task<?>, TaskExpiry<T>> expiryMap = new ConcurrentHashMap<>();

    // Métricas
    private final AtomicLong completedTasksCount = new AtomicLong();
    private final AtomicLong cancelledTasksCount = new AtomicLong();
    private final AtomicLong failedTasksCount = new AtomicLong();
    private final AtomicLong timedOutTasksCount = new AtomicLong();

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
                Executors.newFixedThreadPool(DEFAULT_TASK_EXECUTOR_POOL_SIZE, new NamedThreadFactory("TaskManager-Executor")),
                Executors.newFixedThreadPool(DEFAULT_CALLBACK_EXECUTOR_POOL_SIZE, new NamedThreadFactory("TaskManager-Callback")), TASK_TIMEOUT_MS);
    }

    /**
     * Construye un TaskManager con un programador y ejecutor personalizados.
     */
    public TaskManager(Consumer<TaskStatus<T>> onTaskComplete, ScheduledExecutorService scheduler, ExecutorService taskExecutor) {
        this(onTaskComplete, scheduler, taskExecutor, Executors.newFixedThreadPool(DEFAULT_CALLBACK_EXECUTOR_POOL_SIZE, new NamedThreadFactory("TaskManager-Callback")), TASK_TIMEOUT_MS);
    }

    /**
     * Construye un TaskManager con todos los ejecutores personalizados.
     */
    public TaskManager(Consumer<TaskStatus<T>> onTaskComplete, ScheduledExecutorService scheduler, ExecutorService taskExecutor, ExecutorService callbackExecutor, long taskTimeoutMs) {
        this.onTaskComplete = Objects.requireNonNull(onTaskComplete, "El callback de finalización no puede ser nulo");
        this.scheduler = Objects.requireNonNull(scheduler, "El programador no puede ser nulo");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "El ejecutor de tareas no puede ser nulo");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "El ejecutor de callbacks no puede ser nulo");
        if (taskTimeoutMs <= 0) {
            throw new IllegalArgumentException("taskTimeoutMs debe ser positivo");
        }
        this.taskTimeoutMs = taskTimeoutMs;

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

    public WhenAllBuilder<T> whenAll(Task<?>... tasks) {
        return new WhenAllBuilder<>(this, tasks);
    }

  public class WhenAllBuilder<T> {
    private final TaskManager<T> taskManager;
    private final Task<?>[] tasks;

    WhenAllBuilder(TaskManager<T> taskManager, Task<?>[] tasks) {
        this.taskManager = Objects.requireNonNull(taskManager, "TaskManager cannot be null");
        this.tasks = Objects.requireNonNull(tasks, "Tasks cannot be null");
        if (tasks.length == 0) {
            throw new IllegalArgumentException("At least one task must be provided");
        }
        for (Task<?> task : tasks) {
            Objects.requireNonNull(task, "Task cannot be null");
        }
    }

    /**
     * Ejecuta la acción especificada cuando todas las tareas finalicen.
     *
     * @param action La acción a ejecutar.
     * @return Un Task<Void> que representa la ejecución de la acción.
     */
    public Task<Void> thenRun(Runnable action) {
        Objects.requireNonNull(action, "Action cannot be null");
        CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(
                Arrays.stream(tasks)
                        .map(Task::getFuture)
                        .toArray(CompletableFuture[]::new)
        );
        return Task.execute(() -> {
            try {
                combinedFuture.get(AWAIT_ALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TaskManagerException("Interrupted while waiting for tasks", e);
            } catch (TimeoutException e) {
                throw new TaskManagerException("Timed out waiting for tasks", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof CancellationException) {
                    // Cancelación intencional (e.g., withAutoCancel), ejecutar acción
                    action.run();
                } else {
                    throw new TaskManagerException("Error in one or more tasks", e.getCause());
                }
            }
            return null;
        }, taskManager.taskExecutor);
    }
}

    /**
     * Programa una tarea asíncrona.
     */
    public <R> Task<R> scheduleTask(Supplier<R> action, T data, int priority, Set<Task<?>> dependsOn, int autoCancelAfterMs, int maxRetries) {
        return scheduleTask(action, data, priority, dependsOn, autoCancelAfterMs, maxRetries, RetryConfig.defaultConfig());
    }

    /**
     * Programa una tarea asíncrona con configuración de reintentos
     * personalizada.
     */
    public <R> Task<R> scheduleTask(Supplier<R> action, T data, int priority, Set<Task<?>> dependsOn, int autoCancelAfterMs, int maxRetries, RetryConfig retryConfig) {
        validateParameters(action, autoCancelAfterMs, maxRetries, retryConfig);
        closeLock.lock();
        try {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }
            boolean hasAutoCancel = autoCancelAfterMs > 0;
            Supplier<R> supplier = () -> executeWithRetries(action, null, maxRetries, retryConfig);
            Task<R> task = Task.execute(supplier, taskExecutor);
            TaskEntry<T> entry = new TaskEntry<>(task, data, priority, dependsOn, hasAutoCancel);
            checkCircularDependencies(task, dependsOn);
            addTask(task, data, priority, dependsOn, hasAutoCancel);
            if (hasAutoCancel) {
                ScheduledFuture<?> future = scheduler.schedule(() -> cancelTask(task, data, true), autoCancelAfterMs, TimeUnit.MILLISECONDS);
                scheduledCancellations.add(future);
                taskToCancellation.compute(task, (k, existing) -> {
                    if (existing != null) {
                        existing.cancel(false);
                    }
                    return future;
                });
            }
            return task;
        } finally {
            closeLock.unlock();
        }
    }

    /**
     * Ejecuta una acción con reintentos asíncronos usando el scheduler.
     */
    private <R> R executeWithRetries(Supplier<R> action, Task<?> task, int maxRetries, RetryConfig retryConfig) {
        CompletableFuture<R> future = new CompletableFuture<>();
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicLong totalRetryTimeMs = new AtomicLong(0);
        final long maxTotalRetryTimeMs = 30_000; // 30 segundos máximo para reintentos
        long startTime = System.currentTimeMillis();

        // Clase interna para encapsular la lógica de reintento
        class RetryAttempt {

            private void attempt() {
                // Verificar cancelación usando task si está disponible
                if (isClosed || (task != null && task.future.isCancelled())) {
                    future.completeExceptionally(new TaskManagerException("Tarea cancelada o TaskManager cerrado", null));
                    return;
                }
                if (attempts.get() > maxRetries) {
                    future.completeExceptionally(new TaskManagerException(
                            "Máximo de reintentos excedido tras %d intentos".formatted(attempts.get()), null));
                    return;
                }
                if (totalRetryTimeMs.get() + (System.currentTimeMillis() - startTime) > maxTotalRetryTimeMs) {
                    future.completeExceptionally(new TaskManagerException(
                            "Tiempo total de reintentos excedido tras %d intentos".formatted(attempts.get()), null));
                    return;
                }

                try {
                    R result = action.get();
                    LOGGER.fine("Tarea ejecutada exitosamente en intento %d, reintentos=%d"
                            .formatted(attempts.get() + 1, attempts.get()));
                    future.complete(result);
                } catch (Throwable t) {
                    if (!retryConfig.retryableException.test(t) || attempts.incrementAndGet() > maxRetries) {
                        LOGGER.warning("Error tras %d intentos, reintentos=%d: %s"
                                .formatted(attempts.get(), attempts.get() - 1, t.getMessage()));
                        future.completeExceptionally(new TaskManagerException(
                                "Error tras %d intentos".formatted(attempts.get()), t));
                        return;
                    }

                    long delay = Math.min(
                            retryConfig.backoffBaseMs * (long) Math.pow(2, Math.min(attempts.get() - 1, retryConfig.maxBackoffExponent)),
                            MAX_BACKOFF_MS
                    );
                    totalRetryTimeMs.addAndGet(delay);

                    LOGGER.fine("Programando reintento tras fallo en intento %d, retraso=%dms, reintentos=%d"
                            .formatted(attempts.get(), delay, attempts.get()));
                    ScheduledFuture<?> retryFuture = scheduler.schedule(
                            this::attempt, delay, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        taskToCancellation.compute(task, (k, existing) -> {
                            if (existing != null) {
                                existing.cancel(false);
                            }
                            return retryFuture;
                        });
                    }
                }
            }
        }

        // Iniciar el primer intento de forma asíncrona
        scheduler.execute(new RetryAttempt()::attempt);

        try {
            return future.get(taskTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Interrumpido durante la ejecución de la tarea, reintentos=%d".formatted(attempts.get()));
            throw new TaskManagerException("Interrumpido durante la ejecución de la tarea", e);
        } catch (TimeoutException e) {
            future.cancel(true);
            LOGGER.warning("Tarea excedió el tiempo de espera de %dms, reintentos=%d".formatted(taskTimeoutMs, attempts.get()));
            throw new TaskManagerException("Tarea excedió el tiempo de espera", e);
        } catch (ExecutionException e) {
            throw new TaskManagerException("Error ejecutando la tarea tras %d intentos".formatted(attempts.get()), e.getCause());
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
        actions.forEach(action -> validateParameters(action, autoCancelAfterMs, maxRetries, retryConfig));

        closeLock.lock();
        try {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }
            List<Task<?>> createdTasks = new ArrayList<>();
            Task<?> previousTask = null;

            for (int i = 0; i < actions.size(); i++) {
                Supplier<?> action = actions.get(i);
                boolean isLast = i == actions.size() - 1;
                Supplier<?> supplier = () -> {
                    try {
                        return action.get();
                    } catch (Throwable t) {
                        throw new TaskManagerException("Error ejecutando acción en tarea dependiente: " + data, t);
                    }
                };
                Set<Task<?>> dependsOn = previousTask != null ? Set.of(previousTask) : null;
                int autoCancel = isLast ? autoCancelAfterMs : 0;

                // Usar scheduleTask para aprovechar addTask optimizado
                Task<?> task = scheduleTask(supplier, data, priority, dependsOn, autoCancel, maxRetries, retryConfig);
                createdTasks.add(task);
                LOGGER.fine("Tarea dependiente configurada: tarea=%s, data=%s, dependsOn=%s".formatted(task, data, dependsOn));
                previousTask = task;
            }

            LOGGER.info("Programadas %d tareas dependientes: data=%s, priority=%d".formatted(createdTasks.size(), data, priority));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error programando tareas dependientes: data=%s".formatted(data), e);
            throw new TaskManagerException("Error programando tareas dependientes", e);
        } finally {
            closeLock.unlock();
        }
    }

    /**
     * Agrega una tarea con configuración completa.
     */
    public void addTask(Task<?> task, T data, int priority, Set<Task<?>> dependsOn, boolean hasAutoCancel) {
        if (task == null) {
            throw new IllegalArgumentException("La tarea no puede ser nula");
        }
        writeLock.lock();
        try {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }
            checkCircularDependencies(task, dependsOn);
            TaskEntry<T> entry = new TaskEntry<>(task, data, priority, dependsOn, hasAutoCancel);
            tasks.add(entry);
            if (data != null) {
                taskData.put(task, entry);
            }
            long startTime = System.currentTimeMillis();
            taskStartTimes.put(task, startTime);
            // Programar cancelación por timeout
            ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> {
                if (!isClosed) {
                    try {
                        if (cancelTask(task, data)) {
                            timedOutTasksCount.incrementAndGet();
                            LOGGER.warning("Tarea vencida: tarea=%s, datos=%s".formatted(task, data));
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error al cancelar tarea vencida: %s".formatted(task), e);
                    }
                }
            }, taskTimeoutMs, TimeUnit.MILLISECONDS);
            taskToCancellation.put(task, timeoutFuture);
            scheduledCancellations.add(timeoutFuture);
            if (dependsOn != null) {
                for (Task<?> dep : dependsOn) {
                    dependentTasks.computeIfAbsent(dep, k -> ConcurrentHashMap.newKeySet()).add(entry);
                }
            }
            task.future.whenCompleteAsync((result, ex) -> processTaskCompletion(task, entry, result, ex), taskExecutor);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Procesa la finalización de una tarea.
     */
    private void processTaskCompletion(Task<?> task, TaskEntry<T> entry, Object result, Throwable ex) {
        // Verificación inicial sin bloqueo
        if (isClosed || entry.isProcessed) {
            LOGGER.fine("Skipping completion for task=%s, data=%s, reason=closed or processed".formatted(task, entry.data));
            return;
        }

        // Verificar dependencias fuera del bloqueo
        if (!areDependenciesCompleted(entry)) {
            LOGGER.fine("Task=%s not processed, dependencies not completed".formatted(task));
            return;
        }

        // Declarar dependents fuera del bloque
        Set<TaskEntry<T>> dependents = Collections.emptySet();

        writeLock.lock();
        try {
            // Verificar nuevamente dentro del bloqueo para evitar condiciones de carrera
            if (entry.isProcessed || entry.isCancelled) {
                LOGGER.fine("Skipping completion for task=%s, data=%s, reason=processed or cancelled".formatted(task, entry.data));
                return;
            }
            entry.isProcessed = true;

            // Obtener dependientes dentro del bloqueo
            dependents = dependentTasks.getOrDefault(task, Collections.emptySet());
            boolean isFailed = ex != null;

            if (entry.isCancelled) {
                processTask(entry, true, false, null);
                cleanupTask(task, entry);
                cancelledTasksCount.incrementAndGet();
                return;
            }

            if (isFailed) {
                Exception exception = ex instanceof Exception ? (Exception) ex : new TaskManagerException("Error en tarea", ex);
                processTask(entry, false, true, exception);
                failedTasksCount.incrementAndGet();
                // Cancelar dependientes bajo el mismo writeLock
                for (TaskEntry<T> dep : new ArrayList<>(dependents)) {
                    cancelTask(dep.task, dep.data);
                }
                cleanupTask(task, entry);
                dependentTasks.remove(task);
                return;
            }

            // Tarea exitosa
            processTask(entry, false, false, null);
            completedTasksCount.incrementAndGet();
            cleanupTask(task, entry);
            dependentTasks.remove(task);
        } finally {
            writeLock.unlock();
        }

        // Procesar dependientes fuera del bloqueo
        if (!dependents.isEmpty()) {
            processDependents(dependents);
        }
    }

    /**
     * Procesa las tareas dependientes de forma eficiente.
     */
    private void processDependents(Set<TaskEntry<T>> dependents) {
        List<TaskEntry<T>> readyDependents = new ArrayList<>();

        // Identificar dependientes listos para procesar
        for (TaskEntry<T> dependent : dependents) {
            if (areDependenciesCompleted(dependent) && !dependent.isProcessed && !dependent.isCancelled) {
                readyDependents.add(dependent);
            }
        }

        // Procesar dependientes en un solo envío a taskExecutor
        if (!readyDependents.isEmpty()) {
            taskExecutor.submit(() -> {
                for (TaskEntry<T> dependent : readyDependents) {
                    writeLock.lock();
                    try {
                        if (dependent.isProcessed || dependent.isCancelled) {
                            LOGGER.fine("Skipping dependent task=%s, reason=processed or cancelled".formatted(dependent.task));
                            continue;
                        }
                        dependent.isProcessed = true;
                    } finally {
                        writeLock.unlock();
                    }

                    if (dependent.task.isDone()) {
                        try {
                            processTask(dependent, false, false, null);
                            completedTasksCount.incrementAndGet();
                            cleanupTask(dependent.task, dependent);
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "Error processing dependent task=%s".formatted(dependent.task), e);
                        }
                    } else {
                        dependent.task.future.whenCompleteAsync(
                                (res, err) -> processTaskCompletion(dependent.task, dependent, res, err),
                                taskExecutor
                        );
                    }
                }
            });
        }
    }

    /**
     * Verifica si todas las dependencias de una tarea están completadas.
     */
    private boolean areDependenciesCompleted(TaskEntry<T> entry) {
        if (entry.dependsOn.isEmpty()) {
            return true;
        }
        // Optimizar acceso a taskData usando una sola iteración
        for (Task<?> dep : entry.dependsOn) {
            TaskEntry<T> depEntry = taskData.get(dep);
            if (depEntry != null && !depEntry.task.isDone() && !depEntry.isCancelled && !depEntry.isProcessed) {
                return false;
            }
        }
        return true;
    }

    /**
     * Limpia los recursos asociados a una tarea.
     */
    private void cleanupTask(Task<?> task, TaskEntry<T> entry) {
        writeLock.lock();
        try {
            tasks.remove(entry);
            taskData.remove(task);
            taskStartTimes.remove(task);
            ScheduledFuture<?> cancellation = taskToCancellation.remove(task);
            if (cancellation != null) {
                cancellation.cancel(false);
                scheduledCancellations.remove(cancellation);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Cancela una tarea e invoca el callback de finalización.
     *
     * @param task La tarea a cancelar.
     * @param dataForCallback Los datos a pasar al callback de finalización.
     * @param isAutoCancel Indica si la cancelación es automática (timeout).
     * @return true si la tarea fue cancelada exitosamente o el callback fue
     * invocado, false si no.
     */
    public boolean cancelTask(Task<?> task, T dataForCallback, boolean isAutoCancel) {
        writeLock.lock();
        try {
            if (isClosed) {
                LOGGER.fine("Intento de cancelar tarea fallido: TaskManager cerrado, tarea=%s".formatted(task));
                return false;
            }

            TaskEntry<T> entry = taskData.get(task);
            boolean shouldInvokeCallback = isAutoCancel || (entry != null && !entry.isProcessed && !entry.isCancelled);
            boolean wasCancelled = false;

            if (entry != null && !entry.isProcessed && !entry.isCancelled) {
                entry.isCancelled = true;
                entry.isProcessed = true;
                entry.task.cancel(true);
                wasCancelled = true;
                cleanupTask(task, entry);
            }

            // Incrementar métrica si se invoca el callback o si la tarea fue cancelada activamente
            if (shouldInvokeCallback || wasCancelled) {
                cancelledTasksCount.incrementAndGet();
            }

            if (shouldInvokeCallback && dataForCallback != null && onTaskComplete != null) {
                LOGGER.info("Ejecutando callback de cancelación para tarea=%s, datos=%s, autoCancel=%b"
                        .formatted(task, dataForCallback, isAutoCancel));
                callbackExecutor.submit(() -> {
                    try {
                        onTaskComplete.accept(new TaskStatus<>(dataForCallback, true, false, null));
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error en callback para la tarea con datos: %s".formatted(dataForCallback), e);
                    }
                });
            }

            if (shouldInvokeCallback || wasCancelled) {
                LOGGER.info("Cancelación de tarea exitosa: tarea=%s, datos=%s, autoCancel=%b, métricas=%s"
                        .formatted(task, dataForCallback, isAutoCancel, getMetrics()));
                return true;
            }
            LOGGER.fine("Tarea ya procesada o cancelada: tarea=%s, datos=%s".formatted(task, dataForCallback));
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Sobrecarga para cancelaciones manuales.
     */
    public boolean cancelTask(Task<?> task, T dataForCallback) {
        return cancelTask(task, dataForCallback, false);
    }

    /**
     * Verifica dependencias circulares con un algoritmo DFS optimizado.
     */
    private void checkCircularDependencies(Task<?> task, Set<Task<?>> dependsOn) {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return;
        }
        Set<Task<?>> visited = new HashSet<>();
        Set<Task<?>> path = new HashSet<>();
        Deque<Task<?>> stack = new ArrayDeque<>(dependsOn);
        while (!stack.isEmpty()) {
            Task<?> current = stack.pop();
            if (!path.add(current)) {
                throw new IllegalArgumentException("Dependencia circular detectada para la tarea: " + current);
            }
            if (visited.add(current)) {
                TaskEntry<?> entry = taskData.get(current);
                if (entry != null && entry.dependsOn != null) {
                    stack.addAll(entry.dependsOn);
                }
            }
            path.remove(current);
        }
    }

    /**
     * Procesa una tarea y ejecuta el callback de manera asíncrona con manejo de
     * errores.
     */
    private void processTask(TaskEntry<T> entry, boolean isCancelled, boolean isFailed, Exception exception) {
        if (onTaskComplete != null && entry.data != null) {
            callbackExecutor.submit(() -> {
                try {
                    onTaskComplete.accept(new TaskStatus<>(entry.data, isCancelled, isFailed, exception));
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error en callback para la tarea con datos: " + entry.data, e);
                }
            });
        }
    }

    /**
     * Espera a que todas las tareas se completen o se agote el tiempo de
     * espera.
     */
    public void awaitAll() throws InterruptedException {
        writeLock.lock();
        try {
            if (isClosed) {
                return;
            }
        } finally {
            writeLock.unlock();
        }

        LOGGER.info("Iniciando awaitAll, tareas activas=%d, métricas=%s".formatted(tasks.size(), getMetrics()));
        CompletableFuture<Void> allTasks = CompletableFuture.allOf(
                tasks.stream().map(entry -> entry.task.future).toArray(CompletableFuture[]::new)
        );
        try {
            allTasks.get(AWAIT_ALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception ex) {
            LOGGER.warning("Error esperando tareas: %s. Tareas afectadas: %s"
                    .formatted(ex.getMessage(), tasks.stream().map(entry -> entry.data).toList()));
        }

        // Esperar a que los callbacks se completen
        callbackExecutor.shutdown();
        try {
            if (!callbackExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warning("Callbacks no terminaron en 5 segundos, forzando cierre");
                callbackExecutor.shutdownNow();
                if (!callbackExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    LOGGER.severe("No se pudo cerrar completamente el callbackExecutor");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Interrumpido mientras se esperaba callbacks, restaurando estado");
        } finally {
            callbackExecutor = Executors.newFixedThreadPool(DEFAULT_CALLBACK_EXECUTOR_POOL_SIZE,
                    new NamedThreadFactory("TaskManager-Callback"));
        }

        // Limpiar cancelaciones programadas
        writeLock.lock();
        try {
            for (ScheduledFuture<?> future : new ArrayList<>(scheduledCancellations)) {
                if (!future.isDone() && !future.isCancelled()) {
                    try {
                        future.cancel(true); // Cancelar activamente
                        future.get(1, TimeUnit.SECONDS);
                    } catch (TimeoutException | ExecutionException e) {
                        LOGGER.fine("Cancelación automática completada: %s".formatted(e.getMessage()));
                    } catch (Exception e) {
                        LOGGER.warning("Error esperando cancelación automática: %s".formatted(e.getMessage()));
                    }
                }
            }
            scheduledCancellations.clear();
            taskToCancellation.clear();
        } finally {
            writeLock.unlock();
        }

        LOGGER.info("Finalizado awaitAll, métricas=%s".formatted(getMetrics()));
    }

    /**
     * Verifica si hay tareas activas.
     */
    public boolean hasTasks() {
        return !tasks.isEmpty();
    }

    /**
     * Devuelve el número de tareas activas.
     */
    public int getActiveTaskCount() {
        return tasks.size();
    }

    /**
     * Cierra el TaskManager, cancelando todas las tareas pendientes y liberando
     * recursos.
     */
    public void close() {
        closeLock.lock();
        try {
            if (isClosed) {
                return;
            }
            isClosed = true;
            for (TaskEntry<T> entry : tasks) {
                if (!entry.isCancelled && !entry.task.isDone()) {
                    entry.isCancelled = true;
                    entry.isProcessed = true;
                    entry.task.cancel(true);
                    processTask(entry, true, false, null);
                    cancelledTasksCount.incrementAndGet();
                }
            }
            tasks.clear();
            taskData.clear();
            taskStartTimes.clear();
            taskToCancellation.values().forEach(future -> future.cancel(false));
            taskToCancellation.clear();
            scheduledCancellations.clear();
            shutdownExecutors();
            LOGGER.info("TaskManager cerrado, tareas activas: %d, completadas: %d, canceladas: %d, fallidas: %d, timedOut: %d"
                    .formatted(tasks.size(), completedTasksCount.get(), cancelledTasksCount.get(), failedTasksCount.get(), timedOutTasksCount.get()));
        } finally {
            closeLock.unlock();
        }
    }

    /**
     * Cierra los ejecutores de manera robusta.
     */
    private void shutdownExecutors() {
        // Cancelar todas las tareas programadas
        writeLock.lock();
        try {
            tasks.forEach(entry -> {
                if (!entry.isCancelled && !entry.task.isDone()) {
                    entry.isCancelled = true;
                    entry.isProcessed = true;
                    entry.task.cancel(true);
                    cancelledTasksCount.incrementAndGet();
                }
            });
            taskToCancellation.values().forEach(future -> future.cancel(true)); // Usar true para interrumpir
            scheduledCancellations.forEach(future -> future.cancel(true));
            taskToCancellation.clear();
            scheduledCancellations.clear();
            tasks.clear();
            taskData.clear();
            taskStartTimes.clear();
        } finally {
            writeLock.unlock();
        }

        List<ExecutorService> executors = List.of(scheduler, taskExecutor, callbackExecutor);
        for (ExecutorService executor : executors) {
            int attempts = 0;
            final int maxAttempts = 3;
            boolean terminated = false;

            while (!terminated && attempts < maxAttempts) {
                attempts++;
                try {
                    executor.shutdown();
                    if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        LOGGER.warning("Ejecutor %s no terminó tras %d intento(s), forzando cierre".formatted(executor, attempts));
                        executor.shutdownNow();
                        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                            LOGGER.severe("Ejecutor %s no se cerró completamente tras %d intento(s)".formatted(executor, attempts));
                        } else {
                            terminated = true;
                        }
                    } else {
                        terminated = true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.warning("Interrumpido durante el cierre del ejecutor %s (intento %d), restaurando estado".formatted(executor, attempts));
                    executor.shutdownNow();
                    try {
                        if (attempts < maxAttempts) {
                            Thread.sleep(100); // Breve espera antes de reintentar
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LOGGER.severe("Segunda interrupción durante el cierre del ejecutor %s (intento %d)".formatted(executor, attempts));
                    }
                } catch (Exception e) {
                    LOGGER.severe("Error inesperado al cerrar el ejecutor %s (intento %d): %s".formatted(executor, attempts, e.getMessage()));
                }
            }

            if (!terminated) {
                LOGGER.severe("Fallo al cerrar el ejecutor %s tras %d intentos".formatted(executor, maxAttempts));
            } else {
                LOGGER.info("Ejecutor %s cerrado exitosamente tras %d intento(s)".formatted(executor, attempts));
            }
        }
    }
 
     /**
     * Creates a new task with a TaskAction that returns no value.
     *
     * @param action The action to execute.
     * @return A TaskBuilder for further configuration.
     */
    public TaskBuilder<T, Void> newTask(TaskAction action) {
        Objects.requireNonNull(action, "Action cannot be null");
        return newTask(() -> {
            try {
                action.execute();
            } catch (Exception ex) {
                Logger.getLogger(TaskManager.class.getName()).log(Level.SEVERE, null, ex);
            }
            return null;
        });
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
    public <R1, R2> void addChainedTasks(Supplier<R1> firstAction, Supplier<R2> secondAction, T data, int priority, int autoCancelAfterMs, RetryConfig retryConfig) {
        validateParameters(firstAction, autoCancelAfterMs, 0, retryConfig);
        validateParameters(secondAction, autoCancelAfterMs, 0, retryConfig);
        closeLock.lock();
        try {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }
            TaskBuilder<T, R1> firstTaskBuilder = newTask(firstAction).withPriority(priority).withRetryConfig(retryConfig);
            Task<R1> firstTask = firstTaskBuilder.schedule();
            newTask(secondAction)
                    .withData(data)
                    .withPriority(priority)
                    .withDependency(firstTask)
                    .withAutoCancel(autoCancelAfterMs)
                    .withRetryConfig(retryConfig)
                    .schedule();
        } finally {
            closeLock.unlock();
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
                "timedOutTasks", timedOutTasksCount.get(),
                "activeTasks", (long) getActiveTaskCount()
        );
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
