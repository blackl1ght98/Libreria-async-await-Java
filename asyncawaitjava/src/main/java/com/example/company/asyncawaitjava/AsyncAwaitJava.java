package com.example.company.asyncawaitjava;

public class AsyncAwaitJava {
    /**
     * Espera el resultado de una tarea asíncrona y propaga cualquier excepción lanzada.
     * @param <T> Tipo del resultado de la tarea.
     * @param task La tarea a esperar.
     * @return El resultado de la tarea.
     * @throws Exception Si la tarea falla con una excepción.
     */
    public static <T> T await(Task<T> task) throws Exception {
        if (task == null) {
            throw new IllegalArgumentException("Task cannot be null");
        }
        return task.await();
    }
}