package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.config.RetryConfig;
import com.example.company.asyncawaitjava.task.interfaces.Step;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * A builder class for creating a pipeline of asynchronous tasks with flexible execution modes.
 * Supports SEQUENTIAL, SEQUENTIAL_STRICT, PARALLEL, and CUSTOM modes with a fluent syntax.
 *
 * Example usage:
 * ```java
 * TaskManager<String> taskManager = TaskManager.of(System.out::println);
 * PipelineBuilder<String> builder = new PipelineBuilder<>("PipelineData", taskManager);
 * Task<?> pipeline = builder.withExecutionMode(TaskExecutionMode.SEQUENTIAL)
 *     .step(() -> CompletableFuture.completedFuture("Step 1"), String.class)
 *     .step(() -> CompletableFuture.completedFuture("Step 2"), String.class)
 *     .execute();
 * System.out.println(pipeline.isDone()); // Prints false initially
 * ```
 *
 * @param <T> The type of data associated with the pipeline.
 * @author guillermo
 */
public class PipelineBuilder<T> {
    private static final Logger LOGGER = Logger.getLogger(PipelineBuilder.class.getName());

    private final T data;
    private final TaskManager<T> taskManager;
    private final List<Step<?>> steps = new ArrayList<>();
    private final Map<String, Integer> stepNameToIndex = new HashMap<>();
    private final Map<Integer, Set<Integer>> dependencies = new HashMap<>();
    private int currentIndex = 0;
    private int priority = 10;
    private int autoCancelAfterMs = 0;
    private int maxRetries = 0;
    private RetryConfig retryConfig = RetryConfig.defaultConfig();
    private TaskExecutionMode executionMode = TaskExecutionMode.PARALLEL;

    public PipelineBuilder(T data, TaskManager<T> taskManager) {
        this.data = data;
        this.taskManager = taskManager;
    }

    /**
     * Constructs a PipelineBuilder with associated data and TaskManager.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * PipelineBuilder<String> builder = new PipelineBuilder<>("PipelineData", taskManager);
     * System.out.println(builder != null); // Prints true
     * ```
     *
     * @param data The data associated with the pipeline.
     * @param taskManager The TaskManager to manage the tasks.
     */
    public PipelineBuilder<T> withExecutionMode(TaskExecutionMode mode) {
        this.executionMode = mode != null ? mode : TaskExecutionMode.PARALLEL;
        return this;
    }

     /**
     * Adds a step with a name and an action that returns a CompletableFuture (for CUSTOM mode).
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * PipelineBuilder<String> builder = new PipelineBuilder<>("Data", taskManager)
     *     .withExecutionMode(TaskExecutionMode.CUSTOM);
     * builder.step("Step1", () -> CompletableFuture.completedFuture("Result"), String.class);
     * Task<?> pipeline = builder.execute();
     * System.out.println(pipeline.isDone()); // Prints false initially
     * ```
     *
     * @param name The name of the step (required for CUSTOM mode).
     * @param action The action returning a CompletableFuture.
     * @param returnType The expected return type of the CompletableFuture.
     * @param <R> The result type of the action.
     * @return This builder.
     * @throws IllegalStateException If name is null or empty in CUSTOM mode.
     */
    public <R> PipelineBuilder<T> step(String name, Supplier<CompletableFuture<R>> action, Class<R> returnType) {
        if (executionMode == TaskExecutionMode.CUSTOM && (name == null || name.isEmpty())) {
            throw new IllegalStateException("El nombre del paso es obligatorio en modo CUSTOM");
        }
        if (executionMode == TaskExecutionMode.CUSTOM && stepNameToIndex.containsKey(name)) {
            throw new IllegalArgumentException("Nombre de paso duplicado: " + name);
        }
        String stepName = name != null ? name : "Step-" + currentIndex;
        stepNameToIndex.put(stepName, currentIndex);
        steps.add(Step.fromFuture(action, returnType));
        currentIndex++;
        return this;
    }

    /**
     * Adds a step with an action that returns a CompletableFuture (for non-CUSTOM modes).
     *
     * @param action     The action returning a CompletableFuture.
     * @param returnType The expected return type of the CompletableFuture.
     * @param <R>        The result type of the action.
     * @return This builder.
     * @throws IllegalStateException If used in CUSTOM mode.
     */
    public <R> PipelineBuilder<T> step(Supplier<CompletableFuture<R>> action, Class<R> returnType) {
        if (executionMode == TaskExecutionMode.CUSTOM) {
            throw new IllegalStateException("En modo CUSTOM, los pasos deben tener un nombre");
        }
        return step(null, action, returnType);
    }

    /**
     * Adds a step with a name and a simple action that returns a CompletableFuture<Void> (for CUSTOM mode).
     *
     * @param name   The name of the step (required for CUSTOM mode).
     * @param action The action returning a CompletableFuture<Void>.
     * @return This builder.
     * @throws IllegalStateException If name is null or empty in CUSTOM mode.
     */
    public PipelineBuilder<T> step(String name, Supplier<CompletableFuture<Void>> action) {
        return step(name, action, Void.class);
    }

    /**
     * Adds a step with a simple action that returns a CompletableFuture<Void> (for non-CUSTOM modes).
     *
     * @param action The action returning a CompletableFuture<Void>.
     * @return This builder.
     * @throws IllegalStateException If used in CUSTOM mode.
     */
    public PipelineBuilder<T> step(Supplier<CompletableFuture<Void>> action) {
        return step(null, action, Void.class);
    }

    /**
     * Adds a step with a name and a simple action with no return value (for CUSTOM mode).
     *
     * @param name   The name of the step (required for CUSTOM mode).
     * @param action The action to execute.
     * @return This builder.
     * @throws IllegalStateException If name is null or empty in CUSTOM mode.
     */
    public PipelineBuilder<T> step(String name, Task.CheckedRunnable action) {
        if (executionMode == TaskExecutionMode.CUSTOM && (name == null || name.isEmpty())) {
            throw new IllegalStateException("El nombre del paso es obligatorio en modo CUSTOM");
        }
        if (executionMode == TaskExecutionMode.CUSTOM && stepNameToIndex.containsKey(name)) {
            throw new IllegalArgumentException("Nombre de paso duplicado: " + name);
        }
        String stepName = name != null ? name : "Step-" + currentIndex;
        stepNameToIndex.put(stepName, currentIndex);
        steps.add(Step.executing(() -> action.run()));
        currentIndex++;
        return this;
    }

      /**
     * Adds a step with a simple action with no return value (for non-CUSTOM modes).
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * PipelineBuilder<String> builder = new PipelineBuilder<>("Data", taskManager);
     * builder.step(() -> System.out.println("Action"));
     * Task<?> pipeline = builder.execute();
     * System.out.println(pipeline.isDone()); // Prints false initially
     * ```
     *
     * @param action The action to execute.
     * @return This builder.
     * @throws IllegalStateException If used in CUSTOM mode.
     */
    public PipelineBuilder<T> step(Task.CheckedRunnable action) {
        if (executionMode == TaskExecutionMode.CUSTOM) {
            throw new IllegalStateException("En modo CUSTOM, los pasos deben tener un nombre");
        }
        return step(null, action);
    }

    /**
     * Specifies dependencies for the last added step (only for CUSTOM mode).
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * PipelineBuilder<String> builder = new PipelineBuilder<>("Data", taskManager)
     *     .withExecutionMode(TaskExecutionMode.CUSTOM)
     *     .step("Step1", () -> CompletableFuture.completedFuture("Result"), String.class)
     *     .step("Step2", () -> CompletableFuture.completedFuture("Result2"), String.class)
     *     .after("Step1");
     * Task<?> pipeline = builder.execute();
     * System.out.println(pipeline.isDone()); // Prints false initially
     * ```
     *
     * @param dependsOn The names of the steps this step depends on.
     * @return This builder.
     * @throws IllegalStateException If called when execution mode is not CUSTOM.
     */
    public PipelineBuilder<T> after(String... dependsOn) {
        if (executionMode != TaskExecutionMode.CUSTOM) {
            throw new IllegalStateException("El método 'after' solo se puede usar con el modo CUSTOM");
        }
        if (currentIndex == 0) {
            throw new IllegalStateException("No hay pasos definidos para asignar dependencias");
        }
        int stepIndex = currentIndex - 1;
        Set<Integer> deps = new HashSet<>();
        for (String depName : dependsOn) {
            Integer depIndex = stepNameToIndex.get(depName);
            if (depIndex == null) {
                throw new IllegalArgumentException("Paso no encontrado: " + depName);
            }
            deps.add(depIndex);
        }
        dependencies.put(stepIndex, deps);
        return this;
    }
  /**
     * Sets the priority for all tasks in the pipeline.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * PipelineBuilder<String> builder = new PipelineBuilder<>("Data", taskManager)
     *     .withPriority(5)
     *     .step(() -> CompletableFuture.completedFuture("Step"), String.class);
     * Task<?> pipeline = builder.execute();
     * System.out.println(pipeline.isDone()); // Prints false initially
     * ```
     *
     * @param priority The priority value for the pipeline tasks.
     * @return This builder.
     */
    public PipelineBuilder<T> withPriority(int priority) {
        this.priority = priority;
        return this;
    }
/**
     * Sets the auto-cancellation timeout for all tasks in the pipeline.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * PipelineBuilder<String> builder = new PipelineBuilder<>("Data", taskManager)
     *     .withAutoCancel(5000)
     *     .step(() -> CompletableFuture.completedFuture("Step"), String.class);
     * Task<?> pipeline = builder.execute();
     * System.out.println(pipeline.isDone()); // Prints false initially
     * ```
     *
     * @param autoCancelAfterMs The timeout after which tasks are cancelled (0 to disable).
     * @return This builder.
     * @throws IllegalArgumentException If autoCancelAfterMs is negative.
     */
    public PipelineBuilder<T> withAutoCancel(int autoCancelAfterMs) {
        if (autoCancelAfterMs < 0) {
            throw new IllegalArgumentException("autoCancelAfterMs debe ser no negativo");
        }
        this.autoCancelAfterMs = autoCancelAfterMs;
        return this;
    }

    /**
     * Sets the maximum number of retries for all tasks in the pipeline.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * PipelineBuilder<String> builder = new PipelineBuilder<>("Data", taskManager)
     *     .withMaxRetries(3)
     *     .step(() -> CompletableFuture.completedFuture("Step"), String.class);
     * Task<?> pipeline = builder.execute();
     * System.out.println(pipeline.isDone()); // Prints false initially
     * ```
     *
     * @param maxRetries The maximum number of retries for pipeline tasks.
     * @return This builder.
     * @throws IllegalArgumentException If maxRetries is negative.
     */
    public PipelineBuilder<T> withMaxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries debe ser no negativo");
        }
        this.maxRetries = maxRetries;
        return this;
    }
 /**
     * Sets the retry configuration for all tasks in the pipeline.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * PipelineBuilder<String> builder = new PipelineBuilder<>("Data", taskManager)
     *     .withRetryConfig(RetryConfig.defaultConfig())
     *     .step(() -> CompletableFuture.completedFuture("Step"), String.class);
     * Task<?> pipeline = builder.execute();
     * System.out.println(pipeline.isDone()); // Prints false initially
     * ```
     *
     * @param retryConfig The retry configuration for pipeline tasks.
     * @return This builder.
     */
    public PipelineBuilder<T> withRetryConfig(RetryConfig retryConfig) {
        this.retryConfig = retryConfig != null ? retryConfig : RetryConfig.defaultConfig();
        return this;
    }
  /**
     * Executes the pipeline with the configured steps and settings.
     *
     * Example usage:
     * ```java
     * TaskManager<String> taskManager = TaskManager.of(System.out::println);
     * PipelineBuilder<String> builder = new PipelineBuilder<>("Data", taskManager)
     *     .withExecutionMode(TaskExecutionMode.PARALLEL)
     *     .step(() -> CompletableFuture.completedFuture("Step 1"), String.class)
     *     .step(() -> CompletableFuture.completedFuture("Step 2"), String.class);
     * Task<?> pipeline = builder.execute();
     * System.out.println(pipeline.isDone()); // Prints false initially
     * ```
     *
     * @return The last Task in the pipeline.
     * @throws IllegalArgumentException If TaskManager is null or no steps are defined.
     */
    public Task<?> execute() {
        if (taskManager == null) {
            throw new IllegalArgumentException("TaskManager no puede ser nulo");
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("No se han definido pasos para el pipeline");
        }
        if (executionMode == TaskExecutionMode.CUSTOM && dependencies.isEmpty() && steps.size() > 1) {
            LOGGER.warning("Modo CUSTOM seleccionado pero no se definieron dependencias. Usando modo PARALLEL.");
            executionMode = TaskExecutionMode.PARALLEL;
        }
        return taskManager.addTasks(
            steps,
            data,
            priority,
            autoCancelAfterMs,
            maxRetries,
            retryConfig,
            executionMode,
            executionMode == TaskExecutionMode.CUSTOM ? dependencies : null
        );
    }
}