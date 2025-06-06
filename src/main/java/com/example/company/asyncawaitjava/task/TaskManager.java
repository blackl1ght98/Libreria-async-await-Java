package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.config.RetryConfig;
import com.example.company.asyncawaitjava.task.interfaces.TaskAction;
import com.example.company.asyncawaitjava.task.interfaces.Step;
import com.example.company.asyncawaitjava.exceptions.customizedException.TaskManagerException;
import com.example.company.asyncawaitjava.task.Task.TaskException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

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
    private static final int DEFAULT_TASK_EXECUTOR_POOL_SIZE = Math.max(16, Runtime.getRuntime().availableProcessors() * 2);
    private static final int DEFAULT_CALLBACK_EXECUTOR_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());

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
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    // Métricas
    private final AtomicLong completedTasksCount = new AtomicLong();
    private final AtomicLong cancelledTasksCount = new AtomicLong();
    private final AtomicLong failedTasksCount = new AtomicLong();
    private final AtomicLong timedOutTasksCount = new AtomicLong();

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
        this.taskExecutor = new ThreadPoolExecutor(
                DEFAULT_TASK_EXECUTOR_POOL_SIZE, DEFAULT_TASK_EXECUTOR_POOL_SIZE,
                0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(11, (r1, r2) -> {
                    // Asegurar que las prioridades se comparen correctamente
                    if (r1 instanceof PriorityRunnable pr1 && r2 instanceof PriorityRunnable pr2) {
                        return Integer.compare(pr2.getPriority(), pr1.getPriority()); // Mayor prioridad primero
                    }
                    return 0;
                }),
                new NamedThreadFactory("TaskManager-Executor")
        );
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
                        action.run();
                    } else {
                        throw new TaskManagerException("Error in one or more tasks", e.getCause());
                    }
                }
                return null;
            }, taskManager.taskExecutor);
        }
    }

    public <R> Task<R> scheduleTask(Supplier<R> action, T data, int priority, Set<Task<?>> dependsOn, int autoCancelAfterMs, int maxRetries) {
        return scheduleTask(action, data, priority, dependsOn, autoCancelAfterMs, maxRetries, RetryConfig.defaultConfig());
    }

    public <R> Task<R> scheduleTask(Supplier<R> action, T data, int priority, Set<Task<?>> dependsOn, int autoCancelAfterMs, int maxRetries, RetryConfig retryConfig) {
        validateParameters(action, autoCancelAfterMs, maxRetries, retryConfig);
        closeLock.lock();
        try {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }
            boolean hasAutoCancel = autoCancelAfterMs > 0;
            Supplier<R> wrappedAction = () -> executeWithRetries(action, null, maxRetries, retryConfig);
            Task<R> task = Task.execute(wrappedAction, taskExecutor, priority); // Pasar prioridad
            TaskEntry<T> entry = new TaskEntry<>(task, data, priority, dependsOn, hasAutoCancel);
            checkCircularDependencies(task, dependsOn);

            // Verificar dependencias canceladas antes de añadir la tarea
            if (dependsOn != null && dependsOn.stream()
                    .map(taskData::get)
                    .filter(Objects::nonNull)
                    .anyMatch(depEntry -> depEntry.isCancelled || depEntry.task.isCancelled())) {
                entry.isCancelled = true;
                entry.isProcessed = true;
                task.cancel(true);
                taskData.put(task, entry);
                processTask(entry, true, false, null, false);
                cancelledTasksCount.incrementAndGet();
                LOGGER.fine("Tarea cancelada debido a dependencias canceladas: tarea=%s, datos=%s".formatted(task, data));
                return task;
            }

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

    private <R> R executeWithRetries(Supplier<R> action, Task<?> task, int maxRetries, RetryConfig retryConfig) {
        CompletableFuture<R> future = new CompletableFuture<>();
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicLong totalRetryTimeMs = new AtomicLong(0);
        final long maxTotalRetryTimeMs = 30_000;
        long startTime = System.currentTimeMillis();

        class RetryAttempt {

            private void attempt() {
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

    public Task<?> addDependentTasks(List<Step<?>> steps, T data, int priority, int autoCancelAfterMs, int maxRetries) {
        return addDependentTasks(steps, data, priority, autoCancelAfterMs, maxRetries, RetryConfig.defaultConfig());
    }

    public Task<?> addDependentTasks(List<Step<?>> steps, T data, int priority, int autoCancelAfterMs, int maxRetries, RetryConfig retryConfig) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("La lista de pasos no puede ser nula o vacía");
        }
        steps.forEach(step -> validateParameters(() -> step, autoCancelAfterMs, maxRetries, retryConfig));

        closeLock.lock();
        try {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }
            Task<?> previousTask = null;
            Task<?> lastTask = null;

            for (int i = 0; i < steps.size(); i++) {
                Step<?> step = steps.get(i);
                boolean isLast = i == steps.size() - 1;
                Supplier<?> supplier = () -> {
                    try {
                        return step.execute();
                    } catch (Throwable t) {
                        throw new TaskManagerException("Error ejecutando paso en tarea dependiente: " + data, t);
                    }
                };
                Set<Task<?>> dependsOn = previousTask != null ? Set.of(previousTask) : null;
                Task<?> task = scheduleTask(supplier, data, priority, dependsOn, autoCancelAfterMs, maxRetries, retryConfig); // Aplica autoCancel a todas
                lastTask = task;
                LOGGER.fine("Tarea dependiente configurada: tarea=%s, data=%s, dependsOn=%s".formatted(task, data, dependsOn));
                previousTask = task;
            }

            LOGGER.info("Programadas %d tareas dependientes: data=%s, priority=%d".formatted(steps.size(), data, priority));
            return lastTask;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error programando tareas dependientes: data=%s".formatted(data), e);
            throw new TaskManagerException("Error programando tareas dependientes", e);
        } finally {
            closeLock.unlock();
        }
    }

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

            // Nueva verificación: No añadir tarea si alguna dependencia está cancelada
            if (dependsOn != null && dependsOn.stream()
                    .map(taskData::get)
                    .filter(Objects::nonNull)
                    .anyMatch(depEntry -> depEntry.isCancelled || depEntry.task.isCancelled())) {
                task.cancel(true);
                TaskEntry<T> entry = new TaskEntry<>(task, data, priority, dependsOn, hasAutoCancel);
                entry.isCancelled = true;
                entry.isProcessed = true;
                taskData.put(task, entry);
                processTask(entry, true, false, null, false);
                cancelledTasksCount.incrementAndGet();
                LOGGER.fine("Tarea no añadida debido a dependencias canceladas: tarea=%s, datos=%s".formatted(task, data));
                return;
            }

            TaskEntry<T> entry = new TaskEntry<>(task, data, priority, dependsOn, hasAutoCancel);
            tasks.add(entry);
            if (data != null) {
                taskData.put(task, entry);
            }
            long startTime = System.currentTimeMillis();
            taskStartTimes.put(task, startTime);
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

    private void processTaskCompletion(Task<?> task, TaskEntry<T> entry, Object result, Throwable ex) {
        writeLock.lock();
        try {
            if (entry.isProcessed) {
                LOGGER.fine("Omitiendo la tarea %s, datos=%s, razonamiento=ya procesada".formatted(task, entry.data));
                return;
            }
            LOGGER.info("Procesando tarea: task=%s, datos=%s, isCancelled=%b, isFailed=%b"
                    .formatted(task, entry.data, entry.isCancelled || task.isCancelled(), ex != null));
            entry.isProcessed = true;

            boolean isFailed = ex != null && !entry.isCancelled && !task.isCancelled() && !(ex.getCause() instanceof CancellationException);
            if (entry.isCancelled || task.isCancelled() || (ex != null && ex.getCause() instanceof CancellationException)) {
                entry.isCancelled = true;
                processTask(entry, true, false, null, entry.hasAutoCancel);
                cancelledTasksCount.incrementAndGet();
                tasks.remove(entry);
                // Cancelar dependientes
                cancelDependents(task, entry.hasAutoCancel);
            } else if (isFailed) {
                Exception exception = ex.getCause() instanceof Exception ? (Exception) ex.getCause() : new TaskException("Error en tarea", ex);
                processTask(entry, false, true, exception, false);
                failedTasksCount.incrementAndGet();
                // Cancelar dependientes si la tarea falló
                cancelDependents(task, false);
            } else {
                processTask(entry, false, false, null, false);
                completedTasksCount.incrementAndGet();
                LOGGER.info("Tarea completada exitosamente: task=%s, datos=%s".formatted(task, entry.data));
            }

            cleanupTask(task, entry);
        } finally {
            writeLock.unlock();
        }

        // Procesar dependientes solo si la tarea se completó exitosamente
        if (!isClosed && !entry.isCancelled && !task.isCancelled() && ex == null) {
            Set<TaskEntry<T>> dependents = dependentTasks.getOrDefault(task, Collections.emptySet());
            if (!dependents.isEmpty()) {
                LOGGER.fine("Procesando %d dependientes para la tarea %s".formatted(dependents.size(), task));
                processDependents(dependents);
            }
        }
    }

    private void cancelDependents(Task<?> task, boolean isAutoCancel) {
        Set<TaskEntry<T>> dependents = dependentTasks.getOrDefault(task, Collections.emptySet());
        for (TaskEntry<T> dep : new ArrayList<>(dependents)) {
            if (!dep.isProcessed && !dep.isCancelled) {
                LOGGER.fine("Cancelando dependiente: tarea=%s, datos=%s, autoCancel=%b".formatted(dep.task, dep.data, isAutoCancel));
                cancelTask(dep.task, dep.data, isAutoCancel);
            }
        }
    }

    private void processDependents(Set<TaskEntry<T>> dependents) {
        List<TaskEntry<T>> readyDependents = new ArrayList<>();

        writeLock.lock();
        try {
            for (TaskEntry<T> dependent : dependents) {
                if (dependent.isProcessed || dependent.isCancelled || dependent.task.isCancelled()) {
                    LOGGER.fine("Omitiendo dependiente: tarea=%s, razon=procesado o cancelado.".formatted(dependent.task));
                    continue;
                }
                // Verificar si alguna dependencia está cancelada o fallida
                boolean hasInvalidDependency = dependent.dependsOn.stream()
                        .map(taskData::get)
                        .filter(Objects::nonNull)
                        .anyMatch(depEntry -> depEntry.isCancelled || depEntry.task.isCancelled() || depEntry.isFailed);
                if (hasInvalidDependency) {
                    LOGGER.fine("Cancelando dependiente por dependencia inválida: tarea=%s, datos=%s".formatted(dependent.task, dependent.data));
                    cancelTask(dependent.task, dependent.data, dependent.hasAutoCancel);
                    continue;
                }
                if (areDependenciesCompleted(dependent)) {
                    readyDependents.add(dependent);
                    LOGGER.fine("Dependiente listo para procesar: tarea=%s, datos=%s".formatted(dependent.task, dependent.data));
                }
            }
        } finally {
            writeLock.unlock();
        }

        if (!readyDependents.isEmpty()) {
            LOGGER.info("Enviando %d dependientes al taskExecutor".formatted(readyDependents.size()));
            taskExecutor.submit(() -> {
                for (TaskEntry<T> dependent : readyDependents) {
                    writeLock.lock();
                    try {
                        if (dependent.isProcessed || dependent.isCancelled || dependent.task.isCancelled()) {
                            continue;
                        }
                        // Verificación adicional antes de ejecutar
                        boolean hasInvalidDependency = dependent.dependsOn.stream()
                                .map(taskData::get)
                                .filter(Objects::nonNull)
                                .anyMatch(depEntry -> depEntry.isCancelled || depEntry.task.isCancelled() || depEntry.isFailed);
                        if (hasInvalidDependency) {
                            cancelTask(dependent.task, dependent.data, dependent.hasAutoCancel);
                            continue;
                        }
                        if (!areDependenciesCompleted(dependent)) {
                            continue;
                        }
                        dependent.isProcessed = true;
                    } finally {
                        writeLock.unlock();
                    }

                    if (dependent.task.isDone()) {
                        try {
                            Object result = dependent.task.future.get();
                            processTaskCompletion(dependent.task, dependent, result, null);
                        } catch (Exception e) {
                            processTaskCompletion(dependent.task, dependent, null, e);
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

    private boolean areDependenciesCompleted(TaskEntry<T> entry) {
        if (entry.dependsOn.isEmpty()) {
            LOGGER.fine("No hay dependencias para la tarea %s, datos=%s".formatted(entry.task, entry.data));
            return true;
        }
        for (Task<?> dep : entry.dependsOn) {
            TaskEntry<T> depEntry = taskData.get(dep);
            if (depEntry == null) {
                LOGGER.warning("Dependencia no encontrada en taskData: dep=%s, tarea=%s".formatted(dep, entry.task));
                return false;
            }
            // Requerir que la tarea esté completada Y procesada
            if (!depEntry.task.isDone() || !depEntry.isProcessed || depEntry.isCancelled || depEntry.isFailed) {
                LOGGER.fine("Dependencia no completada: dep=%s, isDone=%b, isProcessed=%b, isCancelled=%b, isFailed=%b, tarea=%s"
                        .formatted(dep, depEntry.task.isDone(), depEntry.isProcessed, depEntry.isCancelled, depEntry.isFailed, entry.task));
                return false;
            }
        }
        LOGGER.fine("Todas las dependencias completadas para la tarea %s".formatted(entry.task));
        return true;
    }

    private void cleanupTask(Task<?> task, TaskEntry<T> entry) {
        writeLock.lock();
        try {
            LOGGER.info("Limpiando tarea=%s, datos=%s".formatted(task, entry.data));
            tasks.remove(entry); // Asegurarse de eliminar la tarea de tasks
            taskData.remove(task);
            taskStartTimes.remove(task);
            dependentTasks.remove(task);
            ScheduledFuture<?> cancellation = taskToCancellation.remove(task);
            if (cancellation != null) {
                cancellation.cancel(false);
                scheduledCancellations.remove(cancellation);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public boolean cancelTask(Task<?> task, T dataForCallback, boolean isAutoCancel) {
        writeLock.lock();
        try {
            if (isClosed) {
                LOGGER.fine("Intento de cancelar tarea fallido: TaskManager cerrado, tarea=%s".formatted(task));
                return false;
            }

            TaskEntry<T> entry = taskData.get(task);
            if (entry == null || entry.isProcessed || entry.isCancelled) {
                LOGGER.fine("Tarea ya procesada o cancelada: tarea=%s, datos=%s".formatted(task, dataForCallback));
                return false;
            }

            entry.isCancelled = true;
            entry.isProcessed = true;
            entry.task.cancel(true);
            tasks.remove(entry);
            processTask(entry, dataForCallback, true, false, null, isAutoCancel);

            // Cancelar dependientes
            Set<TaskEntry<T>> dependents = dependentTasks.getOrDefault(task, Collections.emptySet());
            for (TaskEntry<T> dep : new ArrayList<>(dependents)) {
                if (!dep.isProcessed && !dep.isCancelled) {
                    LOGGER.fine("Cancelando dependiente: tarea=%s, datos=%s".formatted(dep.task, dep.data));
                    cancelTask(dep.task, dep.data, isAutoCancel);
                }
            }

            cancelledTasksCount.incrementAndGet();
            cleanupTask(task, entry);
            LOGGER.info("Cancelación de tarea exitosa: tarea=%s, datos=%s, autoCancel=%b, métricas=%s"
                    .formatted(task, dataForCallback, isAutoCancel, getMetrics()));
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    public boolean cancelTask(Task<?> task, T dataForCallback) {
        return cancelTask(task, dataForCallback, false);
    }

    public void cancelTasksForData(T data, boolean isAutoCancel) {
        writeLock.lock();
        try {
            List<TaskEntry<T>> tasksToCancel = taskData.entrySet().stream()
                    .filter(entry -> entry.getValue().data.equals(data) && !entry.getValue().isProcessed && !entry.getValue().isCancelled)
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toList());
            for (TaskEntry<T> entry : tasksToCancel) {
                if (entry.groupCancellationToken != null) {
                    entry.groupCancellationToken.set(true); // Marcar el grupo como cancelado
                }
                cancelTask(entry.task, entry.data, isAutoCancel);
                if (entry.task.getFuture() != null) {
                    entry.task.getFuture().cancel(true); // Forzar interrupción
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

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

    private void processTask(TaskEntry<T> entry, boolean isCancelled, boolean isFailed, Exception exception, boolean isAutoCancel) {
        processTask(entry, entry.data, isCancelled, isFailed, exception, isAutoCancel); // Usar entry.data por defecto
    }

//    private void processTask(TaskEntry<T> entry, T callbackData, boolean isCancelled, boolean isFailed, Exception exception, boolean isAutoCancel) {
//        if (onTaskComplete != null && callbackData != null) {
//            callbackExecutor.submit(() -> {
//                try {
//                    boolean isLastTaskInPipeline = dependentTasks.getOrDefault(entry.task, Collections.emptySet()).isEmpty();
//                    LOGGER.fine("Ejecutando callback: data=%s, isCancelled=%b, isFailed=%b, isAutoCancel=%b, isLastTaskInPipeline=%b"
//                            .formatted(callbackData, isCancelled, isFailed, isAutoCancel, isLastTaskInPipeline));
//                    onTaskComplete.accept(new TaskStatus<>(callbackData, isCancelled, isFailed, exception, isAutoCancel, isLastTaskInPipeline));
//                } catch (Exception e) {
//                    LOGGER.log(Level.SEVERE, "Error en callback para la tarea con datos: " + callbackData, e);
//                }
//            });
//        }
//    }
    private void processTask(TaskEntry<T> entry, T callbackData, boolean isCancelled, boolean isFailed, Exception exception, boolean isAutoCancel) {
        if (onTaskComplete != null && callbackData != null) {
            LOGGER.fine("Enviando callback para tarea: %s, data=%s, isLastTaskInPipeline=%b".formatted(entry.task, callbackData, entry.task.isLastTaskInPipeline()));
            CompletableFuture.runAsync(() -> {
                try {
                    boolean isLastTaskInPipeline = entry.task.isLastTaskInPipeline();
                    LOGGER.fine("Ejecutando callback: data=%s, isCancelled=%b, isFailed=%b, isAutoCancel=%b, isLastTaskInPipeline=%b"
                            .formatted(callbackData, isCancelled, isFailed, isAutoCancel, isLastTaskInPipeline));
                    onTaskComplete.accept(new TaskStatus<>(callbackData, isCancelled, isFailed, exception, isAutoCancel, isLastTaskInPipeline));
                    LOGGER.fine("Callback ejecutado exitosamente para data=%s".formatted(callbackData));
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error en callback para la tarea con datos: " + callbackData, e);
                }
            }, callbackExecutor).exceptionally(e -> {
                LOGGER.log(Level.SEVERE, "Error al enviar callback para la tarea con datos: " + callbackData, e);
                return null;
            });
        } else {
            LOGGER.fine("Callback no enviado: onTaskComplete=%s, callbackData=%s".formatted(onTaskComplete, callbackData));
        }
    }

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
            // Esperar a que todas las tareas estén procesadas
            while (!tasks.isEmpty()) {
                Thread.sleep(100); // Breve espera para permitir procesamiento
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception ex) {
            LOGGER.warning("Error esperando tareas: %s. Tareas afectadas: %s"
                    .formatted(ex.getMessage(), tasks.stream().map(entry -> entry.data).toList()));
        }

        writeLock.lock();
        try {
            callbackExecutor.shutdown();
            if (!callbackExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warning("Callbacks no terminaron en 5 segundos, forzando cierre");
                callbackExecutor.shutdownNow();
                if (!callbackExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    LOGGER.severe("No se pudo cerrar completamente el callbackExecutor");
                }
            }
            callbackExecutor = Executors.newFixedThreadPool(DEFAULT_CALLBACK_EXECUTOR_POOL_SIZE,
                    new NamedThreadFactory("TaskManager-Callback"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Interrumpido mientras se esperaba callbacks, restaurando estado");
            throw e;
        } finally {
            writeLock.unlock();
        }

        LOGGER.info("Finalizado awaitAll, tareas activas=%d, métricas=%s".formatted(tasks.size(), getMetrics()));
    }

    public boolean hasTasks() {
        return !tasks.isEmpty();
    }

    public int getActiveTaskCount() {
        return tasks.size();
    }

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
                    processTask(entry, true, false, null, false);
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

    private void shutdownExecutors() {
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
            taskToCancellation.values().forEach(future -> future.cancel(true));
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
                            Thread.sleep(100);
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

    public <R> TaskBuilder<T, R> newTask(Supplier<R> action) {
        return new TaskBuilder<>(this, action);
    }

    public <R1, R2> void addChainedTasks(Supplier<R1> firstAction, Supplier<R2> secondAction, T data, int priority, int autoCancelSecondAfterMs) {
        addChainedTasks(firstAction, secondAction, data, priority, autoCancelSecondAfterMs, RetryConfig.defaultConfig());
    }

    public Map<String, Long> getMetrics() {
        return Map.of(
                "completedTasks", completedTasksCount.get(),
                "cancelledTasks", cancelledTasksCount.get(),
                "failedTasks", failedTasksCount.get(),
                "timedOutTasks", timedOutTasksCount.get(),
                "activeTasks", (long) getActiveTaskCount()
        );
    }

    public <R1, R2> void addChainedTasks(Supplier<R1> firstAction, Supplier<R2> secondAction, T data, int priority, int autoCancelAfterMs, RetryConfig retryConfig) {
        validateParameters(firstAction, autoCancelAfterMs, 0, retryConfig);
        validateParameters(secondAction, autoCancelAfterMs, 0, retryConfig);
        closeLock.lock();
        try {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }
            Task<R1> firstTask = scheduleTask(firstAction, data, priority, null, autoCancelAfterMs, 0, retryConfig);
            Task<R2> secondTask = scheduleTask(secondAction, data, priority, Set.of(firstTask), autoCancelAfterMs, 0, retryConfig);
            LOGGER.info("Programadas tareas encadenadas: data=%s, priority=%d".formatted(data, priority));
        } finally {
            closeLock.unlock();
        }
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

    public Task<?> findFirstTask(Predicate<TaskEntry<T>> predicate) {
        readLock.lock();
        try {
            return taskData.entrySet().stream()
                    .filter(entry -> predicate.test(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Añade un grupo de tareas secuenciales con cancelación estricta. Si el
     * dato asociado se cancela, todas las tareas del grupo se detienen
     * inmediatamente.
     *
     * @param steps Lista de pasos a ejecutar en secuencia.
     * @param data Datos asociados a las tareas.
     * @param priority Prioridad de las tareas.
     * @param autoCancelAfterMs Tiempo después del cual se cancelan
     * automáticamente (0 para desactivar).
     * @param maxRetries Número máximo de reintentos.
     * @param retryConfig Configuración de reintentos.
     * @return La última tarea programada.
     */

    public Task<?> addSequentialTasksWithStrict(
            List<Step<?>> steps, T data, int priority, int autoCancelAfterMs, int maxRetries, RetryConfig retryConfig
    ) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("La lista de pasos no puede ser nula o vacía");
        }
        steps.forEach(step -> validateParameters(() -> step, autoCancelAfterMs, maxRetries, retryConfig));

        closeLock.lock();
        try {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }

            AtomicBoolean isGroupCancelled = new AtomicBoolean(false);
            Set<Task<?>> groupTasks = ConcurrentHashMap.newKeySet();
            Task<?> lastTask = null;
            CompletableFuture<Object> previousFuture = CompletableFuture.completedFuture(null);

            for (int i = 0; i < steps.size(); i++) {
                Step<?> step = steps.get(i);
                boolean isLastStep = i == steps.size() - 1;
                final int stepIndex = i;

                // Crear un CompletableFuture para la tarea actual que depende del anterior
                CompletableFuture<Object> currentFuture = previousFuture.thenComposeAsync(v -> {
                    if (isGroupCancelled.get() || Thread.currentThread().isInterrupted()) {
                        return CompletableFuture.failedFuture(
                                new TaskManagerException("Tarea cancelada antes de ejecutarse: data=" + data, null)
                        );
                    }
                    Supplier<Object> wrappedStep = () -> {
                        if (isGroupCancelled.get()) {
                            throw new TaskManagerException("Tarea cancelada antes de ejecutarse: data=" + data, null);
                        }
                        try {
                            return step.execute();
                        } catch (Throwable t) {
                            throw new TaskManagerException("Error ejecutando paso en tarea: " + data, t);
                        }
                    };
                    return CompletableFuture.supplyAsync(
                            () -> executeWithRetries(wrappedStep, null, maxRetries, retryConfig),
                            taskExecutor
                    );
                }, taskExecutor);

                // Crear la tarea usando el constructor correcto
                Task<Object> task = new Task<>(
                        currentFuture,
                        t -> LOGGER.fine("Tarea secuencial %d iniciada: %s".formatted(stepIndex, t)),
                        isLastStep
                );
                groupTasks.add(task);

                // Configurar TaskEntry
                TaskEntry<T> entry = new TaskEntry<>(task, data, priority, Set.of(), autoCancelAfterMs > 0);
                entry.groupCancellationToken = isGroupCancelled;
                taskData.putIfAbsent(task, entry);

                // Añadir la tarea
                addTask(task, data, priority, Set.of(), autoCancelAfterMs > 0);
                LOGGER.fine("Tarea añadida: tarea=%s, data=%s, isLastTaskInPipeline=%b".formatted(task, data, isLastStep));

                // Configurar cancelación automática si es necesario
                if (autoCancelAfterMs > 0) {
                    ScheduledFuture<?> future = scheduler.schedule(
                            () -> cancelTask(task, data, true), autoCancelAfterMs, TimeUnit.MILLISECONDS
                    );
                    scheduledCancellations.add(future);
                    taskToCancellation.compute(task, (k, existing) -> {
                        if (existing != null) {
                            existing.cancel(false);
                        }
                        return future;
                    });
                }

                // Configurar cancelación del grupo si la tarea falla o se cancela
                task.getFuture().whenComplete((result, ex) -> {
                    if (task.isCancelled() || (ex != null && ex.getCause() instanceof CancellationException)) {
                        isGroupCancelled.set(true);
                        groupTasks.forEach(t -> {
                            if (!t.isDone() && !t.isCancelled()) {
                                t.cancel(true);
                            }
                        });
                    } else if (ex != null) {
                        isGroupCancelled.set(true);
                        groupTasks.forEach(t -> {
                            if (!t.isDone() && !t.isCancelled()) {
                                t.cancel(true);
                            }
                        });
                    }
                });

                lastTask = task;
                previousFuture = currentFuture;
                LOGGER.fine("Tarea secuencial configurada: tarea=%s, data=%s, step=%d, isLast=%b".formatted(task, data, i, isLastStep));
            }

            LOGGER.info("Programadas %d tareas secuenciales con cancelación estricta: data=%s, priority=%d".formatted(steps.size(), data, priority));
            return lastTask;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error programando tareas secuenciales: data=%s".formatted(data), e);
            throw new TaskManagerException("Error programando tareas secuenciales", e);
        } finally {
            closeLock.unlock();
        }
    }
}
