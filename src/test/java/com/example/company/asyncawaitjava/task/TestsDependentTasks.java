/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.task.interfaces.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class TestsDependentTasks {

    private TaskManager<String> taskManager;
    private CountDownLatch callbackLatch;
    private AtomicInteger callbackCount;

    @BeforeEach
    void setUp() {
        callbackCount = new AtomicInteger(0);
        callbackLatch = new CountDownLatch(2); // Esperar 2 callbacks
        taskManager = TaskManager.of(data -> {
            callbackCount.incrementAndGet();
            callbackLatch.countDown();
        });
    }

    @AfterEach
    void tearDown() {
        taskManager.close();
    }

   // En TestsP.java, reemplazar el método testDependentTasksCompletionAndCancellation con esta versión:
@Test
void testDependentTasksCompletionAndCancellation() throws InterruptedException {
    // Configurar tareas dependientes similares a Main.java
    Step<String> descargarArchivo = Step.returning(() -> {
        System.out.println("=== Iniciando descargarArchivo ===");
        try {
            Thread.sleep(100); // Simular trabajo
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        System.out.println("=== Completado descargarArchivo ===");
        return "Archivo descargado";
    }, String.class);

    Step<String> procesarArchivo = Step.returning(() -> {
        System.out.println("=== Iniciando procesarArchivo ===");
        try {
            Thread.sleep(100); // Simular trabajo
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        System.out.println("=== Completado procesarArchivo ===");
        return "Archivo procesado";
    }, String.class);

    // Programar tareas dependientes
    Task<String> task = (Task<String>) taskManager.addDependentTasks(
            List.of(descargarArchivo, procesarArchivo),
            "mi-archivo.txt",
            10,
            0,
            0
    );

    // Esperar el resultado de la última tarea
    String resultado = task.await();
    assertEquals("Archivo procesado", resultado, "El resultado de la última tarea debe ser 'Archivo procesado'");

    // Esperar a que los callbacks se ejecuten
    boolean callbacksCompleted = callbackLatch.await(5, TimeUnit.SECONDS);
    assertTrue(callbacksCompleted, "Ambos callbacks debieron ejecutarse");
    assertEquals(2, callbackCount.get(), "Debieron ejecutarse 2 callbacks");

    // Verificar métricas
    taskManager.awaitAll();
    var metrics = taskManager.getMetrics();
    assertEquals(2L, metrics.get("completedTasks"), "Debieron completarse 2 tareas");
    assertEquals(0L, metrics.get("activeTasks"), "No deberían quedar tareas activas");
    assertEquals(0L, metrics.get("cancelledTasks"), "No deberían haber tareas canceladas");
    assertEquals(0L, metrics.get("failedTasks"), "No deberían haber tareas fallidas");
    assertEquals(0L, metrics.get("timedOutTasks"), "No deberían haber tareas con timeout");
}

 @Test
void testDependentTasksCancellation() throws InterruptedException {
    // Configurar tareas dependientes
    Step<String> descargarArchivo = Step.returning(() -> {
        System.out.println("=== Iniciando descargarArchivo ===");
        try {
            Thread.sleep(500); // Simular trabajo largo para permitir cancelación
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        System.out.println("=== Completado descargarArchivo ===");
        return "Archivo descargado";
    }, String.class);

    Step<String> procesarArchivo = Step.returning(() -> {
        System.out.println("=== Iniciando procesarArchivo ===");
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        System.out.println("=== Completado procesarArchivo ===");
        return "Archivo procesado";
    }, String.class);

    // Programar tareas dependientes
    Task<String> task = (Task<String>) taskManager.addDependentTasks(
            List.of(descargarArchivo, procesarArchivo),
            "mi-archivo.txt",
            10,
            0,
            0
    );

    // Obtener la primera tarea (descargarArchivo) usando findFirstTask
    Task<?> descargarTask = taskManager.findFirstTask(entry -> entry.dependsOn.isEmpty());
    if (descargarTask == null) {
        throw new IllegalStateException("No se encontró la primera tarea");
    }

    // Cancelar la primera tarea inmediatamente
    boolean cancelled = taskManager.cancelTask(descargarTask, "mi-archivo.txt");
    assertTrue(cancelled, "La primera tarea debería cancelarse exitosamente");

    // Dar un breve tiempo para que la cancelación se propague
    Thread.sleep(50);

    // Esperar a que los callbacks de cancelación se ejecuten
    boolean callbacksCompleted = callbackLatch.await(1, TimeUnit.SECONDS);
    assertTrue(callbacksCompleted, "Ambos callbacks de cancelación debieron ejecutarse");
    assertEquals(2, callbackCount.get(), "Debieron ejecutarse 2 callbacks de cancelación");

    // Verificar métricas
    taskManager.awaitAll();
    var metrics = taskManager.getMetrics();
    assertEquals(0L, metrics.get("completedTasks"), "No deberían haberse completado tareas");
    assertEquals(0L, metrics.get("activeTasks"), "No deberían quedar tareas activas");
    assertEquals(2L, metrics.get("cancelledTasks"), "Ambas tareas debieron cancelarse");
    assertEquals(0L, metrics.get("failedTasks"), "No deberían haber tareas fallidas");
    assertEquals(0L, metrics.get("timedOutTasks"), "No deberían haber tareas con timeout");
}
}