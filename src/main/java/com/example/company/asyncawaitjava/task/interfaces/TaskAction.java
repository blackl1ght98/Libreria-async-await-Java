package com.example.company.asyncawaitjava.task.interfaces;

@FunctionalInterface
public interface TaskAction {
    /**
     * Executes the task action, potentially throwing an exception.
     *
     * @throws Exception If an error occurs during execution.
     */
    void execute() throws Exception;
}