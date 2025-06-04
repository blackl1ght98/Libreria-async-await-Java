package com.example.company.asyncawaitjava.task.interfaces;

/**
 * Representa un paso que puede lanzar excepciones verificadas.
 */
@FunctionalInterface
public interface CheckedStep {
    /**
     * Ejecuta el paso.
     * @throws Exception si ocurre un error durante la ejecución.
     */
    void execute() throws Exception;
}