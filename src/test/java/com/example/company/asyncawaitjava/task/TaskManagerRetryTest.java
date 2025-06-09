package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.config.RetryConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

public class TaskManagerRetryTest {
    private static final Logger LOGGER = Logger.getLogger(TaskManagerRetryTest.class.getName());
    private TaskManager<String> taskManager;
    private ScheduledExecutorService scheduler;
    private ExecutorService taskExecutor;
    private ExecutorService callbackExecutor;

    @BeforeEach
    public void setUp() {
        scheduler = Executors.newScheduledThreadPool(4);
        taskExecutor = Executors.newFixedThreadPool(16);
        callbackExecutor = Executors.newFixedThreadPool(4);
        taskManager = new TaskManager<>(
                status -> LOGGER.info("Estado: data=%s, isFailed=%b, excepción=%s".formatted(status.data, status.isFailed(), status.exception)),
                scheduler, taskExecutor, callbackExecutor, 30000L
        );
    }

    @AfterEach
    public void tearDown() {
        taskManager.close();
        scheduler.shutdownNow();
        taskExecutor.shutdownNow();
        callbackExecutor.shutdownNow();
    }

    @Test
    public void testRetrySuccessAfterFailure() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryConfig retryConfig = RetryConfig.builder()
                .withMaxAttempts(3)
                .withDelay(100)
                .withBackoff(2.0)
                .withJitter(50)
                .withRetryIf(t -> t instanceof RuntimeException || t.getClass().getName().contains("TaskManagerException"))
                .build();

        Task<String> task = taskManager.scheduleTask(() -> {
            int attempt = attempts.incrementAndGet();
            LOGGER.info("Intento #" + attempt);
            if (attempt < 3) {
                throw new RuntimeException("Fallo simulado en intento " + attempt);
            }
            return "Éxito";
        }, "TestData", 1, null, 0, retryConfig.getMaxAttempts(), retryConfig); // Alinear con retryConfig

        taskManager.awaitAll();
        assertEquals(3, attempts.get(), "Se esperaban 3 intentos");
        assertEquals("Éxito", task.getFuture().join(), "La tarea debería completarse con éxito");
        assertEquals(1, taskManager.getMetrics().getOrDefault("completedTasks", 0L), "Se esperaba 1 tarea completada");
        assertEquals(0, taskManager.getMetrics().getOrDefault("failedTasks", 0L), "No se esperaban tareas fallidas");
    }

    @Test
    public void testRetryExceedsMaxAttempts() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryConfig retryConfig = RetryConfig.builder()
                .withMaxAttempts(3)
                .withDelay(100)
                .withBackoff(2.0)
                .withJitter(50)
                .withRetryIf(t -> t instanceof RuntimeException || t.getClass().getName().contains("TaskManagerException"))
                .build();

        Task<String> task = taskManager.scheduleTask(() -> {
            attempts.incrementAndGet();
            LOGGER.info("Intento #" + attempts.get());
            throw new RuntimeException("Fallo simulado");
        }, "TestData", 1, null, 0, retryConfig.getMaxAttempts(), retryConfig);

        taskManager.awaitAll();
        //Thread.sleep(100); // Temporal hasta corregir awaitAll
        assertEquals(3, attempts.get(), "Se esperaban 3 intentos");
        assertTrue(task.getFuture().isCompletedExceptionally(), "La tarea debería fallar");
        assertEquals(0, taskManager.getMetrics().getOrDefault("completedTasks", 0L), "No se esperaban tareas completadas");
        assertEquals(1, taskManager.getMetrics().getOrDefault("failedTasks", 0L), "Se esperaba 1 tarea fallida");
    }

    @Test
    public void testNonRetryableException() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryConfig retryConfig = RetryConfig.builder()
                .withMaxAttempts(3)
                .withRetryIf(t -> t instanceof IllegalStateException)
                .build();

        Task<String> task = taskManager.scheduleTask(() -> {
            attempts.incrementAndGet();
            LOGGER.info("Intento #" + attempts.get());
            throw new RuntimeException("Fallo no reintentable");
        }, "TestData", 1, null, 0, retryConfig.getMaxAttempts(), retryConfig); // Alinear con retryConfig

        taskManager.awaitAll();
        assertEquals(1, attempts.get(), "Se esperaba 1 intento");
        assertTrue(task.getFuture().isCompletedExceptionally(), "La tarea debería fallar");
        assertEquals(0, taskManager.getMetrics().getOrDefault("completedTasks", 0L), "No se esperaban tareas completadas");
        assertEquals(1, taskManager.getMetrics().getOrDefault("failedTasks", 0L), "Se esperaba 1 tarea fallida");
    }
}