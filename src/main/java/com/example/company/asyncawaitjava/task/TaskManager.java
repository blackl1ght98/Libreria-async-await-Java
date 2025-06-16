package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.config.RetryConfig;
import com.example.company.asyncawaitjava.exceptions.customizedException.TaskException;
import com.example.company.asyncawaitjava.task.interfaces.TaskAction;
import com.example.company.asyncawaitjava.task.interfaces.Step;
import com.example.company.asyncawaitjava.exceptions.customizedException.TaskManagerException;

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
 * A robust, thread-safe task manager for scheduling and executing asynchronous
 * tasks with support for priorities, dependencies, automatic cancellations,
 * retries, and status callbacks.
 *
 * Example usage: ```java // Create a TaskManager with a callback for task
 * completion TaskManager<String> taskManager = TaskManager.of(data ->
 * System.out.println("Task completed with data: " + data));
 *
 * // Define a step Step<String> step = () -> "Processed data";
 *
 * // Schedule a single task Task<String> task = taskManager.scheduleTask( ()
 * -> "Task result", "Task1", 1, null, 5000, 2);
 *
 * // Schedule sequential tasks with strict cancellation List<Step<?>> steps =
 * List.of(() -> "Step 1", () -> "Step 2");
 * taskManager.addSequentialTasksWithStrict(steps, "SequentialTask", 2, 5000, 2,
 * RetryConfig.defaultConfig());
 *
 * // Wait for all tasks to complete try { taskManager.awaitAll(); } catch
 * (InterruptedException e) { e.printStackTrace(); }
 *
 * // Close the TaskManager taskManager.close(); ```
 *
 * @param <T> The type of data associated with the tasks.
 */
public class TaskManager<T> {

    private static final Logger LOGGER = Logger.getLogger(TaskManager.class.getName());

    // Default configuration constants
    private static final int DEFAULT_SCHEDULER_POOL_SIZE = Math.min(4, Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_TASK_EXECUTOR_POOL_SIZE = Math.max(16, Runtime.getRuntime().availableProcessors() * 2);
    private static final int DEFAULT_CALLBACK_EXECUTOR_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;
    private static final long AWAIT_ALL_TIMEOUT_SECONDS = 30L;
    private static final long MAX_BACKOFF_MS = 10000L;
    private static final long TASK_TIMEOUT_MS = 30000L;

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

    // Metrics for tracking task execution
    private final AtomicLong completedTasksCount = new AtomicLong();
    private final AtomicLong cancelledTasksCount = new AtomicLong();
    private final AtomicLong failedTasksCount = new AtomicLong();
    private final AtomicLong timedOutTasksCount = new AtomicLong();

    /**
     * Constructs a TaskManager with a completion callback and default
     * executors.
     *
     * @param onTaskComplete The callback to invoke when a task completes.
     */
    public TaskManager(Consumer<TaskStatus<T>> onTaskComplete) {
        this(onTaskComplete, Executors.newScheduledThreadPool(DEFAULT_SCHEDULER_POOL_SIZE, new NamedThreadFactory("TaskManager-Scheduler")),
                Executors.newFixedThreadPool(DEFAULT_TASK_EXECUTOR_POOL_SIZE, new NamedThreadFactory("TaskManager-Executor")),
                Executors.newFixedThreadPool(DEFAULT_CALLBACK_EXECUTOR_POOL_SIZE, new NamedThreadFactory("TaskManager-Callback")), TASK_TIMEOUT_MS);
    }

    /**
     * Constructs a TaskManager with custom scheduler and task executor.
     *
     * @param onTaskComplete The callback to invoke when a task completes.
     * @param scheduler The scheduler for task cancellations and retries.
     * @param taskExecutor The executor for task execution.
     */
    public TaskManager(Consumer<TaskStatus<T>> onTaskComplete, ScheduledExecutorService scheduler, ExecutorService taskExecutor) {
        this(onTaskComplete, scheduler, taskExecutor, Executors.newFixedThreadPool(DEFAULT_CALLBACK_EXECUTOR_POOL_SIZE, new NamedThreadFactory("TaskManager-Callback")), TASK_TIMEOUT_MS);
    }

    /**
     * Constructs a TaskManager with all custom executors and timeout.
     *
     * @param onTaskComplete The callback to invoke when a task completes.
     * @param scheduler The scheduler for task cancellations and retries.
     * @param taskExecutor The executor for task execution.
     * @param callbackExecutor The executor for completion callbacks.
     * @param taskTimeoutMs The timeout for tasks in milliseconds.
     */
    public TaskManager(Consumer<TaskStatus<T>> onTaskComplete, ScheduledExecutorService scheduler, ExecutorService taskExecutor, ExecutorService callbackExecutor, long taskTimeoutMs) {
        this.onTaskComplete = Objects.requireNonNull(onTaskComplete, "El callback de finalización no puede ser nulo");
        this.scheduler = Objects.requireNonNull(scheduler, "El programador no puede ser nulo");
        this.taskExecutor = new ThreadPoolExecutor(
                DEFAULT_TASK_EXECUTOR_POOL_SIZE, DEFAULT_TASK_EXECUTOR_POOL_SIZE,
                0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(11, (r1, r2) -> {
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
     * Creates a TaskManager with a simple data consumer.
     *
     * @param onTaskComplete The consumer to process task result data.
     * @return A new TaskManager instance.
     */
    public static <T> TaskManager<T> of(Consumer<T> onTaskComplete) {
        return new TaskManager<>(status -> {
            if (onTaskComplete != null && status.data != null) {
                onTaskComplete.accept(status.data);
            }
        });
    }

    /**
     * Converts an array of actions into a list of suppliers.
     *
     * @param actions The actions to convert.
     * @return A list of suppliers.
     * @throws IllegalArgumentException If no actions are provided.
     */
    @SafeVarargs
    public static <R> List<Supplier<R>> actions(Supplier<R>... actions) {
        if (actions == null || actions.length == 0) {
            throw new IllegalArgumentException("Se requiere al menos una acción");
        }
        return Arrays.asList(actions);
    }

    /**
     * Creates a builder for executing an action after all specified tasks
     * complete.
     *
     * @param tasks The tasks to wait for.
     * @return A WhenAllBuilder instance.
     */
    public WhenAllBuilder<T> whenAll(Task<?>... tasks) {
        return new WhenAllBuilder<>(this, tasks);
    }

    /**
     * Builder for executing an action after all specified tasks complete.
     */
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
     * Executes the provided action after all tasks complete.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * Task<String> task1 = taskManager.scheduleTask(() -> "Task 1", "Data1", 1, null, 5000, 2);
     * Task<String> task2 = taskManager.scheduleTask(() -> "Task 2", "Data2", 1, null, 5000, 2);
     * Task<Void> finalTask = taskManager.whenAll(task1, task2).thenRun(() -> System.out.println("All tasks done"));
     * System.out.println(finalTask.isDone()); // Prints false initially
     * ```
     *
     * @param action The action to execute.
     * @return A Task representing the action.
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
     * Schedules a task with the specified parameters.
     *
     * @param action The task action.
     * @param data The associated data.
     * @param priority The task priority.
     * @param dependsOn The tasks this task depends on.
     * @param autoCancelAfterMs The auto-cancel timeout in milliseconds.
     * @param maxRetries The maximum number of retries.
     * @return The scheduled Task.
     */
    public <R> Task<R> scheduleTask(Supplier<R> action, T data, int priority, Set<Task<?>> dependsOn, int autoCancelAfterMs, int maxRetries) {
        return scheduleTask(action, data, priority, dependsOn, autoCancelAfterMs, maxRetries, RetryConfig.defaultConfig());
    }

  /**
 * Schedules a task with custom retry configuration.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * Task<String> task = taskManager.scheduleTask(
 *     () -> "Task result", "TaskData", 1, null, 5000, 2, RetryConfig.defaultConfig()
 * );
 * System.out.println(task.isDone()); // Prints false initially
 * ```
 *
 * @param action The task action.
 * @param data The associated data.
 * @param priority The task priority.
 * @param dependsOn The tasks this task depends on.
 * @param autoCancelAfterMs The auto-cancel timeout in milliseconds.
 * @param maxRetries The maximum number of retries.
 * @param retryConfig The retry configuration.
 * @return The scheduled Task.
 */
    public <R> Task<R> scheduleTask(Supplier<R> action, T data, int priority, Set<Task<?>> dependsOn, int autoCancelAfterMs, int maxRetries, RetryConfig retryConfig) {
        validateParameters(action, autoCancelAfterMs, maxRetries, retryConfig);
        closeLock.lock();
        try {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }
            boolean hasAutoCancel = autoCancelAfterMs > 0;
            Supplier<R> wrappedAction = () -> executeWithRetries(action, null, maxRetries, retryConfig);
            Task<R> task = Task.execute(wrappedAction, taskExecutor, priority);
            TaskEntry<T> entry = new TaskEntry<>(task, data, priority, dependsOn, hasAutoCancel);
            checkCircularDependencies(task, dependsOn);

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

    /**
     * Executes an action with retries according to the retry configuration.
     *
     * @param action The action to execute.
     * @param task The associated task, if any.
     * @param maxRetries The maximum number of retries.
     * @param retryConfig The retry configuration.
     * @return The result of the action.
     * @throws TaskManagerException If the task fails after retries.
     */
 private <R> R executeWithRetries(Supplier<R> action, Task<?> task, int maxRetries, RetryConfig retryConfig) {
    CompletableFuture<R> future = new CompletableFuture<>();
    AtomicInteger attempts = new AtomicInteger(0);
    AtomicLong totalRetryTimeMs = new AtomicLong(0);
    final long maxTotalRetryTimeMs = 30_000;
    long startTime = System.currentTimeMillis();

 
    int effectiveMaxRetries = retryConfig != null && retryConfig.getMaxAttempts() > 0 ? retryConfig.getMaxAttempts() : maxRetries;

    class RetryAttempt {
        private void attempt() {
            int currentAttempt = attempts.incrementAndGet(); 
            if (isClosed || (task != null && task.future.isCancelled())) {
                future.completeExceptionally(new TaskManagerException("Tarea cancelada o TaskManager cerrado", null));
                return;
            }
            if (currentAttempt > effectiveMaxRetries) {
                future.completeExceptionally(new TaskManagerException(
                        "Máximo de reintentos excedido tras %d intentos".formatted(currentAttempt), null));
                return;
            }
            if (totalRetryTimeMs.get() + (System.currentTimeMillis() - startTime) > maxTotalRetryTimeMs) {
                future.completeExceptionally(new TaskManagerException(
                        "Tiempo total de reintentos excedido tras %d intentos".formatted(currentAttempt), null));
                return;
            }

            try {
                R result = action.get();
                LOGGER.fine("Tarea ejecutada exitosamente en intento %d, reintentos=%d"
                        .formatted(currentAttempt, currentAttempt - 1));
                future.complete(result);
            } catch (Throwable t) {
                if (!retryConfig.retryableException.test(t)) {
                    LOGGER.warning("Excepción no reintentable tras %d intentos: %s"
                            .formatted(currentAttempt, t.getMessage()));
                    future.completeExceptionally(new TaskManagerException(
                            "Excepción no reintentable tras %d intentos".formatted(currentAttempt), t));
                    return;
                }

                if (currentAttempt >= effectiveMaxRetries) {
                    LOGGER.warning("Error tras %d intentos, reintentos=%d: %s"
                            .formatted(currentAttempt, currentAttempt - 1, t.getMessage()));
                    future.completeExceptionally(new TaskManagerException(
                            "Error tras %d intentos".formatted(currentAttempt), t));
                    return;
                }

                long delay = Math.min(
                        retryConfig.backoffBaseMs * (long) Math.pow(2, Math.min(currentAttempt - 1, retryConfig.maxBackoffExponent)),
                        MAX_BACKOFF_MS
                );
                totalRetryTimeMs.addAndGet(delay);

                LOGGER.fine("Programando reintento tras fallo en intento %d, retraso=%dms, reintentos=%d"
                        .formatted(currentAttempt, delay, currentAttempt));
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
        LOGGER.warning("Interrumpido durante la ejecución de la tarea, intentos=%d".formatted(attempts.get()));
        throw new TaskManagerException("Interrumpido durante la ejecución de la tarea", e);
    } catch (TimeoutException e) {
        future.cancel(true);
        LOGGER.warning("Tarea excedió el tiempo de espera de %dms, intentos=%d".formatted(taskTimeoutMs, attempts.get()));
        throw new TaskManagerException("Tarea excedió el tiempo de espera", e);
    } catch (ExecutionException e) {
        throw new TaskManagerException("Error ejecutando la tarea tras %d intentos".formatted(attempts.get()), e.getCause());
    }
}

    /**
     * Adds a group of tasks with the specified execution mode.
     *
     * @param steps The list of steps to execute.
     * @param data The associated data.
     * @param priority The task priority.
     * @param autoCancelAfterMs The auto-cancel timeout in milliseconds.
     * @param maxRetries The maximum number of retries.
     * @param retryConfig The retry configuration.
     * @param executionMode The execution mode (SEQUENTIAL_STRICT, SEQUENTIAL,
     * PARALLEL, CUSTOM).
     * @param dependencies The dependency map for CUSTOM mode.
     * @return The last scheduled Task, or null if no tasks.
     */
    public Task<?> addTasks(
            List<Step<?>> steps,
            T data,
            int priority,
            int autoCancelAfterMs,
            int maxRetries,
            RetryConfig retryConfig,
            TaskExecutionMode executionMode,
            Map<Integer, Set<Integer>> dependencies
    ) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("La lista de pasos no puede ser nula o vacía");
        }
        steps.forEach(step -> validateParameters(() -> step, autoCancelAfterMs, maxRetries, retryConfig));
        if (executionMode == TaskExecutionMode.CUSTOM && (dependencies == null || dependencies.isEmpty())) {
            throw new IllegalArgumentException("Se requiere un mapa de dependencias no vacío para el modo CUSTOM");
        }
        if (executionMode == TaskExecutionMode.CUSTOM) {
            validateCustomDependencies(steps.size(), dependencies);
        }

        closeLock.lock();
        try {
            if (isClosed) {
                throw new TaskManagerException("TaskManager está cerrado", null);
            }

            AtomicBoolean isGroupCancelled = executionMode == TaskExecutionMode.SEQUENTIAL_STRICT ? new AtomicBoolean(false) : null;
            Set<Task<?>> groupTasks = executionMode == TaskExecutionMode.SEQUENTIAL_STRICT ? ConcurrentHashMap.newKeySet() : null;
            List<Task<?>> taskList = new ArrayList<>();
            Map<Integer, CompletableFuture<Object>> futures = new HashMap<>();

            for (int i = 0; i < steps.size(); i++) {
                Step<?> step = steps.get(i);
                boolean isLastStep = i == steps.size() - 1 && executionMode != TaskExecutionMode.PARALLEL;
                final int stepIndex = i;

                Set<Task<?>> taskDependencies = new HashSet<>();
                CompletableFuture<Object> currentFuture;

                switch (executionMode) {
                    case SEQUENTIAL_STRICT:
                    case SEQUENTIAL:
                        if (i > 0) {
                            taskDependencies.add(taskList.get(i - 1));
                        }
                        currentFuture = createFutureForStep(
                                step, data, priority, maxRetries, retryConfig, taskDependencies, isGroupCancelled, stepIndex
                        );
                        break;

                    case PARALLEL:
                        currentFuture = createFutureForStep(
                                step, data, priority, maxRetries, retryConfig, Set.of(), isGroupCancelled, stepIndex
                        );
                        break;

                    case CUSTOM:
                        Set<Integer> depIndices = dependencies.getOrDefault(i, Set.of());
                        for (Integer depIndex : depIndices) {
                            if (depIndex >= i || depIndex < 0 || depIndex >= steps.size()) {
                                throw new IllegalArgumentException(
                                        "Dependencia inválida para el paso %d: índice %d".formatted(i, depIndex)
                                );
                            }
                            taskDependencies.add(taskList.get(depIndex));
                        }
                        currentFuture = createFutureForStep(
                                step, data, priority, maxRetries, retryConfig, taskDependencies, isGroupCancelled, stepIndex
                        );
                        break;

                    default:
                        throw new IllegalArgumentException("Modo de ejecución no soportado: " + executionMode);
                }

                Task<Object> task = new Task<>(
                        currentFuture,
                        t -> LOGGER.fine("Tarea %s %d iniciada: %s".formatted(executionMode, stepIndex, t)),
                        isLastStep
                );
                taskList.add(task);
                if (executionMode == TaskExecutionMode.SEQUENTIAL_STRICT) {
                    groupTasks.add(task);
                }
                futures.put(i, currentFuture);

                TaskEntry<T> entry = new TaskEntry<>(task, data, priority, taskDependencies, autoCancelAfterMs > 0);
                if (executionMode == TaskExecutionMode.SEQUENTIAL_STRICT) {
                    entry.groupCancellationToken = isGroupCancelled;
                }
                taskData.putIfAbsent(task, entry);

                addTask(task, data, priority, taskDependencies, autoCancelAfterMs > 0);
                LOGGER.fine("Tarea añadida: tarea=%s, data=%s, modo=%s, step=%d, isLast=%b"
                        .formatted(task, data, executionMode, stepIndex, isLastStep));

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

                if (executionMode == TaskExecutionMode.SEQUENTIAL_STRICT) {
                    task.getFuture().whenComplete((result, ex) -> {
                        if (task.isCancelled() || (ex != null && ex.getCause() instanceof CancellationException)) {
                            isGroupCancelled.set(true);
                            groupTasks.forEach(t -> {
                                if (!t.isDone() && !t.isCancelled()) {
                                    t.cancel(true);
                                    LOGGER.fine("Tarea cancelada en grupo SEQUENTIAL_STRICT: tarea=%s".formatted(t));
                                }
                            });
                        } else if (ex != null) {
                            isGroupCancelled.set(true);
                            groupTasks.forEach(t -> {
                                if (!t.isDone() && !t.isCancelled()) {
                                    t.cancel(true);
                                    LOGGER.fine("Tarea cancelada en grupo SEQUENTIAL_STRICT por excepción: tarea=%s, excepción=%s".formatted(t, ex.getMessage()));
                                }
                            });
                        }
                    });
                }
            }

            LOGGER.info("Programadas %d tareas en modo %s: data=%s, priority=%d"
                    .formatted(steps.size(), executionMode, data, priority));
            return taskList.isEmpty() ? null : taskList.get(taskList.size() - 1);
        } finally {
            closeLock.unlock();
        }
    }

    /**
     * Creates a CompletableFuture for a step, considering dependencies and
     * cancellation.
     *
     * @param step The step to execute.
     * @param data The associated data.
     * @param priority The task priority.
     * @param maxRetries The maximum number of retries.
     * @param retryConfig The retry configuration.
     * @param dependencies The task dependencies.
     * @param isGroupCancelled The cancellation token for SEQUENTIAL_STRICT
     * mode.
     * @param stepIndex The index of the step.
     * @return A CompletableFuture for the step.
     */
    private CompletableFuture<Object> createFutureForStep(
            Step<?> step,
            T data,
            int priority,
            int maxRetries,
            RetryConfig retryConfig,
            Set<Task<?>> dependencies,
            AtomicBoolean isGroupCancelled,
            int stepIndex
    ) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        if (!dependencies.isEmpty()) {
            CompletableFuture<Void> allDependencies = CompletableFuture.allOf(
                    dependencies.stream().map(Task::getFuture).toArray(CompletableFuture[]::new)
            );
            allDependencies.thenRunAsync(() -> {
                if (isGroupCancelled != null && isGroupCancelled.get() || Thread.currentThread().isInterrupted()) {
                    future.completeExceptionally(
                            new TaskManagerException("Tarea cancelada antes de ejecutarse: data=" + data, null)
                    );
                    return;
                }
                executeStepWithRetries(step, data, maxRetries, retryConfig, future, isGroupCancelled, stepIndex);
            }, taskExecutor);
        } else {
            executeStepWithRetries(step, data, maxRetries, retryConfig, future, isGroupCancelled, stepIndex);
        }
        return future;
    }

    /**
     * Executes a step with retries and completes the corresponding
     * CompletableFuture.
     *
     * @param step The step to execute.
     * @param data The associated data.
     * @param maxRetries The maximum number of retries.
     * @param retryConfig The retry configuration.
     * @param future The CompletableFuture to complete.
     * @param isGroupCancelled The cancellation token for SEQUENTIAL_STRICT
     * mode.
     * @param stepIndex The index of the step.
     */
    private void executeStepWithRetries(
            Step<?> step,
            T data,
            int maxRetries,
            RetryConfig retryConfig,
            CompletableFuture<Object> future,
            AtomicBoolean isGroupCancelled,
            int stepIndex
    ) {
        taskExecutor.execute(() -> {
            try {
                Object result = executeWithRetries(() -> {
                    if (isGroupCancelled != null && isGroupCancelled.get()) {
                        throw new TaskManagerException("Tarea cancelada antes de ejecutarse: data=" + data, null);
                    }
                    try {
                        return step.execute();
                    } catch (Exception ex) {
                        throw new TaskManagerException("Error ejecutando paso %d: data=%s".formatted(stepIndex, data), ex);
                    }
                }, null, maxRetries, retryConfig);
                future.complete(result);
            } catch (TaskManagerException e) {
                future.completeExceptionally(e);
            } catch (Exception e) {
                future.completeExceptionally(new TaskManagerException(
                        "Error ejecutando paso %d en tarea: data=%s".formatted(stepIndex, data), e
                ));
            }
        });
    }

     /**
     * Validates custom dependencies for CUSTOM execution mode.
     *
     * @param stepCount The number of steps.
     * @param dependencies The dependency map.
     * @throws IllegalArgumentException If dependencies are invalid or cyclic.
     */
    private void validateCustomDependencies(int stepCount, Map<Integer, Set<Integer>> dependencies) {
        for (Map.Entry<Integer, Set<Integer>> entry : dependencies.entrySet()) {
            int stepIndex = entry.getKey();
            if (stepIndex < 0 || stepIndex >= stepCount) {
                throw new IllegalArgumentException("Índice de paso inválido: " + stepIndex);
            }
            for (Integer depIndex : entry.getValue()) {
                if (depIndex < 0 || depIndex >= stepCount) {
                    throw new IllegalArgumentException("Índice de dependencia inválido: " + depIndex);
                }
                if (depIndex >= stepIndex) {
                    throw new IllegalArgumentException(
                            "Las dependencias deben ser pasos anteriores: paso=%d, dependencia=%d".formatted(stepIndex, depIndex)
                    );
                }
            }
        }
        Set<Integer> visited = new HashSet<>();
        Set<Integer> path = new HashSet<>();
        for (int i = 0; i < stepCount; i++) {
            if (!visited.contains(i)) {
                if (hasCycle(i, dependencies, visited, path)) {
                    throw new IllegalArgumentException("Dependencia circular detectada en el paso: " + i);
                }
            }
        }
    }

    /**
     * Checks for cycles in custom dependencies.
     *
     * @param step The current step index.
     * @param dependencies The dependency map.
     * @param visited The set of visited steps.
     * @param path The current path in the dependency graph.
     * @return True if a cycle is detected, false otherwise.
     */
    private boolean hasCycle(int step, Map<Integer, Set<Integer>> dependencies, Set<Integer> visited, Set<Integer> path) {
        if (path.contains(step)) {
            return true;
        }
        if (visited.contains(step)) {
            return false;
        }
        visited.add(step);
        path.add(step);
        Set<Integer> deps = dependencies.getOrDefault(step, Set.of());
        for (Integer dep : deps) {
            if (hasCycle(dep, dependencies, visited, path)) {
                return true;
            }
        }
        path.remove(step);
        return false;
    }

    /**
 * Adds a group of sequential tasks with strict cancellation.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * List<Step<?>> steps = List.of(() -> "Step 1", () -> "Step 2");
 * Task<?> lastTask = taskManager.addSequentialTasksWithStrict(
 *     steps, "SequentialTask", 2, 5000, 2, RetryConfig.defaultConfig()
 * );
 * System.out.println(lastTask.isLastTaskInPipeline()); // Prints true
 * ```
 *
 * @param steps The list of steps to execute.
 * @param data The associated data.
 * @param priority The task priority.
 * @param autoCancelAfterMs The auto-cancel timeout in milliseconds.
 * @param maxRetries The maximum number of retries.
 * @param retryConfig The retry configuration.
 * @return The last scheduled Task.
 */
    public Task<?> addSequentialTasksWithStrict(
            List<Step<?>> steps, T data, int priority, int autoCancelAfterMs, int maxRetries, RetryConfig retryConfig
    ) {
        return addTasks(steps, data, priority, autoCancelAfterMs, maxRetries, retryConfig, TaskExecutionMode.SEQUENTIAL_STRICT, null);
    }
  /**
 * Adds a group of tasks with the specified execution mode.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * List<Step<?>> steps = List.of(() -> "Step 1", () -> "Step 2");
 * Task<?> lastTask = taskManager.addTasks(
 *     steps, "TaskGroup", 1, 5000, 2, RetryConfig.defaultConfig(), TaskExecutionMode.PARALLEL, null
 * );
 * System.out.println(lastTask != null); // Prints true
 * ```
 *
 * @param steps The list of steps to execute.
 * @param data The associated data.
 * @param priority The task priority.
 * @param autoCancelAfterMs The auto-cancel timeout in milliseconds.
 * @param maxRetries The maximum number of retries.
 * @param retryConfig The retry configuration.
 * @param executionMode The execution mode (SEQUENTIAL_STRICT, SEQUENTIAL, PARALLEL, CUSTOM).
 * @param dependencies The dependency map for CUSTOM mode.
 * @return The last scheduled Task, or null if no tasks.
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
/**
     * Processes task completion, updating metrics and handling dependents.
     *
     * @param task The task that completed.
     * @param entry The task entry.
     * @param result The task result.
     * @param ex The exception, if any.
     */
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
                cancelDependents(task, entry.hasAutoCancel);
            } else if (isFailed) {
                Exception exception = ex.getCause() instanceof Exception ? (Exception) ex.getCause() : new TaskException("Error en tarea", ex);
                processTask(entry, false, true, exception, false);
                failedTasksCount.incrementAndGet();
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

        if (!isClosed && !entry.isCancelled && !task.isCancelled() && ex == null) {
            Set<TaskEntry<T>> dependents = dependentTasks.getOrDefault(task, Collections.emptySet());
            if (!dependents.isEmpty()) {
                LOGGER.fine("Procesando %d dependientes para la tarea %s".formatted(dependents.size(), task));
                processDependents(dependents);
            }
        }
    }
    /**
     * Cancels dependent tasks.
     *
     * @param task The task whose dependents should be cancelled.
     * @param isAutoCancel Whether the cancellation is automatic.
     */
    private void cancelDependents(Task<?> task, boolean isAutoCancel) {
        Set<TaskEntry<T>> dependents = dependentTasks.getOrDefault(task, Collections.emptySet());
        for (TaskEntry<T> dep : new ArrayList<>(dependents)) {
            if (!dep.isProcessed && !dep.isCancelled) {
                LOGGER.fine("Cancelando dependiente: tarea=%s, datos=%s, autoCancel=%b".formatted(dep.task, dep.data, isAutoCancel));
                cancelTask(dep.task, dep.data, isAutoCancel);
            }
        }
    }
    /**
     * Processes dependent tasks that are ready to execute.
     *
     * @param dependents The dependent tasks to process.
     */
    private void processDependents(Set<TaskEntry<T>> dependents) {
        List<TaskEntry<T>> readyDependents = new ArrayList<>();

        writeLock.lock();
        try {
            for (TaskEntry<T> dependent : dependents) {
                if (dependent.isProcessed || dependent.isCancelled || dependent.task.isCancelled()) {
                    LOGGER.fine("Omitiendo dependiente: tarea=%s, razon=procesado o cancelado.".formatted(dependent.task));
                    continue;
                }
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
    /**
     * Checks if all dependencies of a task are completed.
     *
     * @param entry The task entry.
     * @return True if all dependencies are completed, false otherwise.
     */
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
            if (!depEntry.task.isDone() || !depEntry.isProcessed || depEntry.isCancelled || depEntry.isFailed) {
                LOGGER.fine("Dependencia no completada: dep=%s, isDone=%b, isProcessed=%b, isCancelled=%b, isFailed=%b, tarea=%s"
                        .formatted(dep, depEntry.task.isDone(), depEntry.isProcessed, depEntry.isCancelled, depEntry.isFailed, entry.task));
                return false;
            }
        }
        LOGGER.fine("Todas las dependencias completadas para la tarea %s".formatted(entry.task));
        return true;
    }
 /**
     * Cleans up task-related resources.
     *
     * @param task The task to clean up.
     * @param entry The task entry.
     */
    private void cleanupTask(Task<?> task, TaskEntry<T> entry) {
        writeLock.lock();
        try {
            LOGGER.info("Limpiando tarea=%s, datos=%s".formatted(task, entry.data));
            tasks.remove(entry);
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
 /**
 * Cancels a specific task.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * Task<String> task = taskManager.scheduleTask(() -> "Task result", "TaskData", 1, null, 5000, 2);
 * boolean cancelled = taskManager.cancelTask(task, "TaskData", true);
 * System.out.println(cancelled); // Prints true if task was cancelled
 * ```
 *
 * @param task The task to cancel.
 * @param dataForCallback The data for the callback.
 * @param isAutoCancel Whether the cancellation is automatic.
 * @return True if the task was cancelled, false otherwise.
 */
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
 /**
 * Cancels a specific task.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * Task<String> task = taskManager.scheduleTask(() -> "Task result", "TaskData", 1, null, 5000, 2);
 * boolean cancelled = taskManager.cancelTask(task, "TaskData", true);
 * System.out.println(cancelled); // Prints true if task was cancelled
 * ```
 *
 * @param task The task to cancel.
 * @param dataForCallback The data for the callback.
 * @param isAutoCancel Whether the cancellation is automatic.
 * @return True if the task was cancelled, false otherwise.
 */
    public boolean cancelTask(Task<?> task, T dataForCallback) {
        return cancelTask(task, dataForCallback, false);
    }
  /**
 * Cancels all tasks associated with specific data.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * taskManager.scheduleTask(() -> "Task result", "TaskData", 1, null, 5000, 2);
 * taskManager.cancelTasksForData("TaskData", true);
 * System.out.println(taskManager.getActiveTaskCount()); // Prints 0 if all tasks were cancelled
 * ```
 *
 * @param data The data associated with tasks to cancel.
 * @param isAutoCancel Whether the cancellation is automatic.
 */
    public void cancelTasksForData(T data, boolean isAutoCancel) {
        writeLock.lock();
        try {
            List<TaskEntry<T>> tasksToCancel = taskData.entrySet().stream()
                    .filter(entry -> entry.getValue().data.equals(data) && !entry.getValue().isProcessed && !entry.getValue().isCancelled)
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toList());
            for (TaskEntry<T> entry : tasksToCancel) {
                if (entry.groupCancellationToken != null) {
                    entry.groupCancellationToken.set(true);
                }
                cancelTask(entry.task, entry.data, isAutoCancel);
                if (entry.task.getFuture() != null) {
                    entry.task.getFuture().cancel(true);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }
 /**
     * Checks for circular dependencies in the task graph.
     *
     * @param task The task to check.
     * @param dependsOn The tasks it depends on.
     * @throws IllegalArgumentException If a circular dependency is detected.
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
     * Processes a task's completion status and invokes the callback.
     *
     * @param entry The task entry.
     * @param isCancelled Whether the task was cancelled.
     * @param isFailed Whether the task failed.
     * @param exception The exception, if any.
     * @param isAutoCancel Whether the cancellation is automatic.
     */
    private void processTask(TaskEntry<T> entry, boolean isCancelled, boolean isFailed, Exception exception, boolean isAutoCancel) {
        processTask(entry, entry.data, isCancelled, isFailed, exception, isAutoCancel);
    }
 /**
     * Processes a task with specific callback data.
     *
     * @param entry The task entry.
     * @param callbackData The data for the callback.
     * @param isCancelled Whether the task was cancelled.
     * @param isFailed Whether the task failed.
     * @param exception The exception, if any.
     * @param isAutoCancel Whether the cancellation is automatic.
     */
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
  /**
 * Waits for all tasks to complete.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * taskManager.scheduleTask(() -> "Task result", "TaskData", 1, null, 5000, 2);
 * try {
 *     taskManager.awaitAll();
 *     System.out.println(taskManager.getActiveTaskCount()); // Prints 0
 * } catch (InterruptedException e) {
 *     e.printStackTrace();
 * }
 * ```
 *
 * @throws InterruptedException If the thread is interrupted.
 */
public void awaitAll() throws InterruptedException {
    writeLock.lock();
    try {
        if (isClosed) {
            LOGGER.info("TaskManager ya está cerrado, omitiendo awaitAll");
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
        // Wait for all task futures to complete
        allTasks.get(AWAIT_ALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.warning("Interrumpido mientras se esperaba la finalización de tareas");
        throw e;
    } catch (Exception ex) {
        LOGGER.warning("Error esperando tareas: %s. Tareas afectadas: %s"
                .formatted(ex.getMessage(), tasks.stream().map(entry -> entry.data).toList()));
    }

    // Wait for tasks to be cleaned up (tasks.isEmpty())
    int maxWaitCycles = 50; // Up to 5 seconds
    while (!tasks.isEmpty() && maxWaitCycles-- > 0) {
        Thread.sleep(100);
        LOGGER.fine("Esperando limpieza de tareas, activas=%d".formatted(tasks.size()));
    }
    if (!tasks.isEmpty()) {
        LOGGER.warning("Tareas aún activas después de awaitAll: %d".formatted(tasks.size()));
    }

    // Flush and wait for taskExecutor to process all completions
    writeLock.lock();
    try {
        // Submit an empty task to ensure all task completions are processed
        CompletableFuture<Void> flushTaskExecutor = CompletableFuture.runAsync(() -> {}, taskExecutor);
        flushTaskExecutor.get(5, TimeUnit.SECONDS);

        // Attempt to shut down taskExecutor
        taskExecutor.shutdown();
        if (!taskExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            LOGGER.warning("taskExecutor no terminó en 5 segundos, forzando cierre");
            taskExecutor.shutdownNow();
            if (!taskExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                LOGGER.severe("No se pudo cerrar completamente el taskExecutor");
            }
        }

        // Submit an empty task to ensure all callbacks are processed
        CompletableFuture<Void> flushCallbackExecutor = CompletableFuture.runAsync(() -> {}, callbackExecutor);
        flushCallbackExecutor.get(5, TimeUnit.SECONDS);

        // Attempt to shut down callbackExecutor
        callbackExecutor.shutdown();
        if (!callbackExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            LOGGER.warning("Callbacks no terminaron en 5 segundos, forzando cierre");
            callbackExecutor.shutdownNow();
            if (!callbackExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                LOGGER.severe("No se pudo cerrar completamente el callbackExecutor");
            }
        }

        // Recreate callbackExecutor for subsequent calls
        callbackExecutor = Executors.newFixedThreadPool(DEFAULT_CALLBACK_EXECUTOR_POOL_SIZE,
                new NamedThreadFactory("TaskManager-Callback"));
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.warning("Interrumpido mientras se esperaba executors, restaurando estado");
        throw e;
    } catch (Exception e) {
        LOGGER.warning("Error esperando executors: %s".formatted(e.getMessage()));
    } finally {
        writeLock.unlock();
    }

    LOGGER.info("Finalizado awaitAll, tareas activas=%d, métricas=%s".formatted(tasks.size(), getMetrics()));
}
  /**
     * Checks if there are any active tasks.
     *
     * @return True if there are active tasks, false otherwise.
     */
    public boolean hasTasks() {
        return !tasks.isEmpty();
    }
/**
     * Returns the number of active tasks.
     *
     * @return The number of active tasks.
     */
    public int getActiveTaskCount() {
        return tasks.size();
    }
 /**
 * Closes the TaskManager and cancels all tasks.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * taskManager.scheduleTask(() -> "Task result", "TaskData", 1, null, 5000, 2);
 * taskManager.close();
 * System.out.println(taskManager.getActiveTaskCount()); // Prints 0
 * ```
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
/**
     * Shuts down all executors gracefully.
     */
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
   /**
 * Creates a TaskBuilder for a task action.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * TaskBuilder<String, Void> builder = taskManager.newTask(() -> System.out.println("Executing task"));
 * Task<Void> task = builder.withData("TaskData").withPriority(1).build();
 * System.out.println(task.isDone()); // Prints false initially
 * ```
 *
 * @param action The task action.
 * @return A TaskBuilder instance.
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
 * Creates a TaskBuilder for a supplier action.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * TaskBuilder<String, String> builder = taskManager.newTask(() -> "Task result");
 * Task<String> task = builder.withData("TaskData").withPriority(1).build();
 * System.out.println(task.isDone()); // Prints false initially
 * ```
 *
 * @param action The supplier action.
 * @return A TaskBuilder instance.
 */
    public <R> TaskBuilder<T, R> newTask(Supplier<R> action) {
        return new TaskBuilder<>(this, action);
    }
 /**
 * Adds two chained tasks with custom retry configuration.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * taskManager.addChainedTasks(
 *     () -> "First task", () -> "Second task", "ChainData", 1, 5000, RetryConfig.defaultConfig()
 * );
 * System.out.println(taskManager.getActiveTaskCount()); // Prints 2 initially
 * ```
 *
 * @param firstAction The first task action.
 * @param secondAction The second task action.
 * @param data The associated data.
 * @param priority The task priority.
 * @param autoCancelAfterMs The auto-cancel timeout in milliseconds.
 * @param retryConfig The retry configuration.
 */
    public <R1, R2> void addChainedTasks(Supplier<R1> firstAction, Supplier<R2> secondAction, T data, int priority, int autoCancelAfterMs) {
        addChainedTasks(firstAction, secondAction, data, priority, autoCancelAfterMs, RetryConfig.defaultConfig());
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
  /**
     * Adds two chained tasks with custom retry configuration.
     *
     * @param firstAction The first task action.
     * @param secondAction The second task action.
     * @param data The associated data.
     * @param priority The task priority.
     * @param autoCancelAfterMs The auto-cancel timeout in milliseconds.
     * @param retryConfig The retry configuration.
     */
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
/**
 * Validates the parameters of a task before scheduling it.
 *
 * @param action The task action to execute.
 * @param autoCancelAfterMs The time in milliseconds after which the task is automatically canceled.
 * @param maxRetries The maximum number of retries allowed for the task.
 * @param retryConfig The retry configuration for the task.
 * @throws IllegalArgumentException If any parameter is invalid.
 */
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
 * Finds the first task matching the given predicate.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * Task<String> task = taskManager.scheduleTask(() -> "Task result", "TaskData", 1, null, 5000, 2);
 * Task<?> foundTask = taskManager.findFirstTask(entry -> entry.data.equals("TaskData"));
 * System.out.println(foundTask != null); // Prints true
 * ```
 *
 * @param predicate The predicate to match tasks.
 * @return The first matching task, or null if none found.
 */
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
 * Initiates the construction of a task pipeline for the specified data.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * PipelineBuilder<String> pipeline = taskManager.pipeline("PipelineData");
 * System.out.println(pipeline != null); // Prints true
 * ```
 *
 * @param data The data associated with the pipeline.
 * @return A PipelineBuilder for defining steps and dependencies.
 */
    public PipelineBuilder<T> pipeline(T data) {
        return new PipelineBuilder<>(data, this);
    }

}
