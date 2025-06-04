
package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.config.RetryConfig;
import com.example.company.asyncawaitjava.task.interfaces.TaskAction;
import com.example.company.asyncawaitjava.task.interfaces.Step;
import com.example.company.asyncawaitjava.exceptions.customizedException.TaskManagerException;
import com.example.company.asyncawaitjava.task.Task.TaskException;
import java.util.*;
import java.util.concurrent.*;
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
            Task<R> task = Task.execute(wrappedAction, taskExecutor);
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
                processTask(entry, true, false, null,false);
                cancelledTasksCount.incrementAndGet();
                LOGGER.fine("Tarea cancelada debido a dependencias canceladas: tarea=%s, datos=%s".formatted(task, data));
                return task;
            }

            addTask(task, data, priority, dependsOn, hasAutoCancel);
            if (hasAutoCancel) {
                ScheduledFuture<?> future = scheduler.schedule(() -> cancelTask(task, data, true), autoCancelAfterMs, TimeUnit.MILLISECONDS);
                scheduledCancellations.add(future);
                taskToCancellation.compute(task, (k, existing) -> {
                    if (existing != null) existing.cancel(false);
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
                            if (existing != null) existing.cancel(false);
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
                int autoCancel = isLast ? autoCancelAfterMs : 0;
                Task<?> task = scheduleTask(supplier, data, priority, dependsOn, autoCancel, maxRetries, retryConfig);
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
                processTask(entry, true, false, null,false);
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
    if (isClosed) {
        LOGGER.fine("Omitiendo la tarea %s, datos=%s, razonamiento=TaskManager cerrado".formatted(task, entry.data));
        return;
    }

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
            processTask(entry, true, false, null, entry.hasAutoCancel); // Usar hasAutoCancel de la entrada
            cancelledTasksCount.incrementAndGet();
            tasks.remove(entry); // Asegurarse de eliminar la tarea de tasks
            // Cancelar dependientes
            Set<TaskEntry<T>> dependents = dependentTasks.getOrDefault(task, Collections.emptySet());
            for (TaskEntry<T> dep : new ArrayList<>(dependents)) {
                if (!dep.isProcessed && !dep.isCancelled) {
                    LOGGER.fine("Cancelando dependiente por tarea cancelada: tarea=%s, datos=%s".formatted(dep.task, dep.data));
                    cancelTask(dep.task, dep.data, entry.hasAutoCancel);
                }
            }
        } else if (isFailed) {
            Exception exception = ex.getCause() instanceof Exception ? (Exception) ex.getCause() : new TaskException("Error en tarea", ex);
            processTask(entry, false, true, exception, false); // Fallos no son auto-cancel
            failedTasksCount.incrementAndGet();
            // Cancelar dependientes si la tarea falló
            Set<TaskEntry<T>> dependents = dependentTasks.getOrDefault(task, Collections.emptySet());
            for (TaskEntry<T> dep : new ArrayList<>(dependents)) {
                if (!dep.isProcessed && !dep.isCancelled) {
                    LOGGER.fine("Cancelando dependiente por tarea fallida: tarea=%s, datos=%s".formatted(dep.task, dep.data));
                    cancelTask(dep.task, dep.data, false);
                }
            }
        } else {
            processTask(entry, false, false, null, false); // Completadas no son auto-cancel
            completedTasksCount.incrementAndGet();
            // No cancelar dependientes si la tarea se completó exitosamente
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
private void processDependents(Set<TaskEntry<T>> dependents) {
    List<TaskEntry<T>> readyDependents = new ArrayList<>();

    writeLock.lock();
    try {
        for (TaskEntry<T> dependent : dependents) {
            if (!dependent.isProcessed && !dependent.isCancelled && !dependent.task.isCancelled()) {
                if (areDependenciesCompleted(dependent)) {
                    readyDependents.add(dependent);
                    LOGGER.fine("Dependiente listo para procesar: tarea=%s, datos=%s".formatted(dependent.task, dependent.data));
                } else {
                    // Solo cancelar si la dependencia está explícitamente cancelada
                    boolean hasCancelledDependency = dependent.dependsOn.stream()
                            .map(taskData::get)
                            .filter(Objects::nonNull)
                            .anyMatch(depEntry -> depEntry.isCancelled);
                    if (hasCancelledDependency) {
                        LOGGER.fine("Cancelando dependiente por dependencia cancelada: tarea=%s, datos=%s".formatted(dependent.task, dependent.data));
                        cancelTask(dependent.task, dependent.data);
                    } else {
                        LOGGER.fine("Dependiente no listo: tarea=%s, datos=%s, depsCompleted=%b, isProcessed=%b, isCancelled=%b"
                                .formatted(dependent.task, dependent.data, areDependenciesCompleted(dependent), dependent.isProcessed, dependent.isCancelled));
                    }
                }
            } else {
                LOGGER.fine("Omitiendo dependiente: tarea=%s, razon=procesado o cancelado".formatted(dependent.task));
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
                        LOGGER.fine("Omitiendo dependiente: tarea=%s, razon=procesado o cancelado".formatted(dependent.task));
                        continue;
                    }
                    // Verificar nuevamente las dependencias
                    boolean hasCancelledDependency = dependent.dependsOn.stream()
                            .map(taskData::get)
                            .filter(Objects::nonNull)
                            .anyMatch(depEntry -> depEntry.isCancelled);
                    if (hasCancelledDependency) {
                        LOGGER.fine("Cancelando dependiente por dependencia cancelada en ejecución: tarea=%s, datos=%s".formatted(dependent.task, dependent.data));
                        cancelTask(dependent.task, dependent.data);
                        continue;
                    }
                    if (!areDependenciesCompleted(dependent)) {
                        LOGGER.fine("Omitiendo dependiente: tarea=%s, dependencias no satisfechas".formatted(dependent.task));
                        continue; // No cancelar, solo omitir
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
                        LOGGER.log(Level.SEVERE, "Error procesando dependiente completado: tarea=%s".formatted(dependent.task), e);
                        processTaskCompletion(dependent.task, dependent, null, e);
                    }
                } else {
                    LOGGER.fine("Programando completitud asíncrona para dependiente: tarea=%s".formatted(dependent.task));
                    dependent.task.future.whenCompleteAsync(
                            (res, err) -> processTaskCompletion(dependent.task, dependent, res, err),
                            taskExecutor
                    );
                }
            }
        });
    } else {
        LOGGER.fine("No hay dependientes listos para procesar");
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
            if (!depEntry.task.isDone() && !depEntry.isCancelled && !depEntry.isProcessed) {
                LOGGER.fine("Dependencia no completada: dep=%s, isDone=%b, isCancelled=%b, isProcessed=%b, tarea=%s"
                        .formatted(dep, depEntry.task.isDone(), depEntry.isCancelled, depEntry.isProcessed, entry.task));
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
        boolean wasCancelled = false;

        if (entry != null && !entry.isProcessed && !entry.isCancelled) {
            entry.isCancelled = true;
            entry.isProcessed = true;
            entry.task.cancel(true);
            wasCancelled = true;
            tasks.remove(entry); // Asegurarse de eliminar la tarea de tasks
            // Usar dataForCallback en lugar de entry.data para el callback
            processTask(entry, dataForCallback, true, false, null, isAutoCancel);
        }

        // Cancelar todas las tareas dependientes inmediatamente
        Set<TaskEntry<T>> dependents = dependentTasks.getOrDefault(task, Collections.emptySet());
        for (TaskEntry<T> dep : new ArrayList<>(dependents)) {
            if (!dep.isProcessed && !dep.isCancelled) {
                LOGGER.fine("Cancelando dependiente por cancelación de tarea padre: tarea=%s, datos=%s".formatted(dep.task, dep.data));
                cancelTask(dep.task, dep.data, isAutoCancel);
            }
        }

        if (wasCancelled) {
            cancelledTasksCount.incrementAndGet();
            cleanupTask(task, entry); // Limpiar después de procesar
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
    public boolean cancelTask(Task<?> task, T dataForCallback) {
        return cancelTask(task, dataForCallback, false);
    }

    private void checkCircularDependencies(Task<?> task, Set<Task<?>> dependsOn) {
        if (dependsOn == null || dependsOn.isEmpty()) return;
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

private void processTask(TaskEntry<T> entry, T callbackData, boolean isCancelled, boolean isFailed, Exception exception, boolean isAutoCancel) {
    if (onTaskComplete != null && callbackData != null) {
        callbackExecutor.submit(() -> {
            try {
                LOGGER.fine("Ejecutando callback: data=%s, isCancelled=%b, isFailed=%b, isAutoCancel=%b".formatted(callbackData, isCancelled, isFailed, isAutoCancel));
                onTaskComplete.accept(new TaskStatus<>(callbackData, isCancelled, isFailed, exception, isAutoCancel));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error en callback para la tarea con datos: " + callbackData, e);
            }
        });
    }
}

    public void awaitAll() throws InterruptedException {
        writeLock.lock();
        try {
            if (isClosed) return;
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

        writeLock.lock();
        try {
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
                throw e;
            } finally {
                callbackExecutor = Executors.newFixedThreadPool(DEFAULT_CALLBACK_EXECUTOR_POOL_SIZE,
                        new NamedThreadFactory("TaskManager-Callback"));
            }

            for (ScheduledFuture<?> future : new ArrayList<>(scheduledCancellations)) {
                if (!future.isDone() && !future.isCancelled()) {
                    try {
                        future.cancel(false);
                        LOGGER.fine("Cancelada future programada: %s".formatted(future));
                    } catch (Exception e) {
                        LOGGER.warning("Error al cancelar future programada: %s".formatted(e.toString()));
                    }
                }
            }
            scheduledCancellations.clear();
            taskToCancellation.clear();
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
            if (isClosed) return;
            isClosed = true;
            for (TaskEntry<T> entry : tasks) {
                if (!entry.isCancelled && !entry.task.isDone()) {
                    entry.isCancelled = true;
                    entry.isProcessed = true;
                    entry.task.cancel(true);
                    processTask(entry, true, false, null,false);
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
                        if (attempts < maxAttempts) Thread.sleep(100);
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
}