package com.example.company.asyncawaitjava.task.interfaces;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Representa un paso en una cadena de tareas, con un resultado de tipo R.
 */

public interface Step<R> {
    /**
     * Ejecuta el paso y devuelve un resultado (o null para Void).
     * @throws Exception si ocurre un error durante la ejecución.
     */
    R execute() throws Exception;

    /**
     * Devuelve el tipo de retorno esperado.
     * @return La clase del tipo de retorno.
     */
    Class<R> getReturnType();

    /**
     * Crea un paso que devuelve un valor usando un Supplier.
     * @param supplier El proveedor del valor.
     * @param returnType El tipo de retorno esperado.
     * @param <R> El tipo de retorno.
     * @return Un nuevo paso.
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
     * Crea un paso que realiza una acción sin devolver valor (Void).
     * @param runnable La acción a ejecutar.
     * @return Un nuevo paso de tipo Void.
     */
    static Step<Void> doing(Runnable runnable) {
        return new Step<>() {
            @Override
            public Void execute() throws Exception {
                runnable.run();
                return null; // Retorno implícito para Void
            }

            @Override
            public Class<Void> getReturnType() {
                return Void.class;
            }
        };
    }

    /**
     * Crea un paso que ejecuta una acción con excepciones verificadas.
     * @param checkedStep La acción a ejecutar.
     * @return Un nuevo paso de tipo Void.
     */
    static Step<Void> executing(CheckedStep checkedStep) {
        return new Step<>() {
            @Override
            public Void execute() throws Exception {
                checkedStep.execute();
                return null; // Retorno implícito para Void
            }

            @Override
            public Class<Void> getReturnType() {
                return Void.class;
            }
        };
    }
    
    /**
     * Crea un paso que ejecuta una tarea asíncrona representada por un CompletableFuture.
     * @param futureSupplier El proveedor del CompletableFuture.
     * @param returnType El tipo de retorno esperado.
     * @param <T> El tipo de retorno.
     * @return Un nuevo paso.
     */
    static <T> Step<T> fromFuture(Supplier<CompletableFuture<T>> futureSupplier, Class<T> returnType) {
        return new Step<>() {
            @Override
            public T execute() throws Exception {
                return futureSupplier.get().join(); // Usamos join() para esperar el resultado
            }

            @Override
            public Class<T> getReturnType() {
                return returnType;
            }
        };
    }
}