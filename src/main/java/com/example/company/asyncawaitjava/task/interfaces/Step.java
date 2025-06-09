package com.example.company.asyncawaitjava.task.interfaces;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Represents a step in a task chain, producing a result of type R.
 * This interface defines a single step in a task pipeline, which can be executed synchronously or asynchronously.
 */
public interface Step<R> {
    /**
     * Executes the step and returns a result (or null for Void).
     * 
     * Example usage:
     * ```java
     * Step<String> step = Step.returning(() -> "Hello", String.class);
     * String result = step.execute(); // Returns "Hello"
     * ```
     * 
     * @return The result of the step execution.
     * @throws Exception If an error occurs during execution.
     */
    R execute() throws Exception;

    /**
     * Returns the expected return type of the step.
     * 
     * Example usage:
     * ```java
     * Step<String> step = Step.returning(() -> "Hello", String.class);
     * Class<String> type = step.getReturnType(); // Returns String.class
     * ```
     * 
     * @return The class of the return type.
     */
    Class<R> getReturnType();

    /**
     * Creates a step that returns a value using a Supplier.
     * 
     * Example usage:
     * ```java
     * Step<Integer> step = Step.returning(() -> 42, Integer.class);
     * Integer result = step.execute(); // Returns 42
     * ```
     * 
     * @param supplier The supplier providing the value.
     * @param returnType The expected return type.
     * @param <R> The return type.
     * @return A new step.
     */
    static <R> Step<R> returning(Supplier<R> supplier, Class<R> returnType) {
        return new Step<>() {
            @Override
            public R execute() throws Exception {
                return supplier.get();
            }

            @Override
            public Class<R> getReturnType() {
                return returnType;
            }
        };
    }

    /**
     * Creates a step that performs an action without returning a value (Void).
     * 
     * Example usage:
     * ```java
     * Step<Void> step = Step.doing(() -> System.out.println("Action"));
     * step.execute(); // Prints "Action" and returns null
     * ```
     * 
     * @param runnable The action to execute.
     * @return A new Void step.
     */
    static Step<Void> doing(Runnable runnable) {
        return new Step<>() {
            @Override
            public Void execute() throws Exception {
                runnable.run();
                return null;
            }

            @Override
            public Class<Void> getReturnType() {
                return Void.class;
            }
        };
    }

    /**
     * Creates a step that executes an action with checked exceptions.
     * 
     * Example usage:
     * ```java
     * Step<Void> step = Step.executing(() -> {
     *     throw new IOException("Test");
     * });
     * try {
     *     step.execute(); // Throws IOException
     * } catch (Exception e) {
     *     System.out.println(e.getMessage()); // Prints "Test"
     * }
     * ```
     * 
     * @param checkedStep The action to execute.
     * @return A new Void step.
     */
    static Step<Void> executing(CheckedStep checkedStep) {
        return new Step<>() {
            @Override
            public Void execute() throws Exception {
                checkedStep.execute();
                return null;
            }

            @Override
            public Class<Void> getReturnType() {
                return Void.class;
            }
        };
    }

    /**
     * Creates a step that executes an asynchronous task represented by a CompletableFuture.
     * 
     * Example usage:
     * ```java
     * Step<String> step = Step.fromFuture(
     *     () -> CompletableFuture.supplyAsync(() -> "Async Result"),
     *     String.class
     * );
     * String result = step.execute(); // Returns "Async Result"
     * ```
     * 
     * @param futureSupplier The supplier of the CompletableFuture.
     * @param returnType The expected return type.
     * @param <T> The return type.
     * @return A new step.
     */
    static <T> Step<T> fromFuture(Supplier<CompletableFuture<T>> futureSupplier, Class<T> returnType) {
        return new Step<>() {
            @Override
            public T execute() throws Exception {
                return futureSupplier.get().join();
            }

            @Override
            public Class<T> getReturnType() {
                return returnType;
            }
        };
    }
}