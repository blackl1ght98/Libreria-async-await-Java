package com.example.company.asyncawaitjava.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.ExecutorService;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Async {
    String methodSuffix() default "Async"; // Sufijo para el método generado
    Class<? extends ExecutorService> executor() default DefaultExecutor.class; // Ejecutor personalizado

    // Clase auxiliar para el ejecutor por defecto
    interface DefaultExecutor extends ExecutorService {}
}