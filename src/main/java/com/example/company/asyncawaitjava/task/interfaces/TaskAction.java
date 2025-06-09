package com.example.company.asyncawaitjava.task.interfaces;

@FunctionalInterface
public interface TaskAction {
    /**
     * Executes a task action that may throw an exception.
     * This interface defines a contract for tasks that perform an action without returning a value.
     * 
     * Example usage:
     * ```java
     * TaskAction action = () -> System.out.println("Performing task");
     * action.execute(); // Prints "Performing task"
     * ```
     * 
     * @throws Exception If an error occurs during execution.
     */
    void execute() throws Exception;
}