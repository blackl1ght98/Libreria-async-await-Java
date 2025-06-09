package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.config.AsyncAwaitConfig;
import com.example.company.asyncawaitjava.exceptions.customizedException.TaskException;
import com.example.company.asyncawaitjava.task.Task.CheckedRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;


import static org.junit.jupiter.api.Assertions.*;

public class TestTask {

    private ExecutorService testExecutor;

    public TestTask() {
    }

    @BeforeEach
    public void setUp() {
        testExecutor = Executors.newFixedThreadPool(4);
        AsyncAwaitConfig.setDefaultExecutor(testExecutor);
    }

    @AfterEach
    public void tearDown() {
        testExecutor.shutdown();
        try {
            if (!testExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                testExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            testExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void executeSupplierReturnsExpectedResult() {
        Task<String> task = Task.execute(() -> "Hello, Task!");
        assertEquals("Hello, Task!", task.await(1, TimeUnit.SECONDS));
        assertTrue(task.isDone());
        assertFalse(task.isCancelled());
    }

    @Test
    void executeSupplierWithCallbackInvokesCallback() {
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        Task<String> task = Task.execute(
                () -> "Test",
                testExecutor,
                t -> callbackInvoked.set(true)
        );
        assertEquals("Test", task.await(1, TimeUnit.SECONDS));
        assertTrue(callbackInvoked.get(), "Callback should have been invoked");
        assertTrue(task.isStarted());
    }

    @Test
    void executeSupplierHandlesException() {
        Task<String> task = Task.execute(() -> {
            throw new RuntimeException("Test exception");
        }, testExecutor);
        TaskException thrown = assertThrows(TaskException.class,
                () -> task.await(2, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof RuntimeException, "Cause should be a RuntimeException");
        assertEquals("Test exception", thrown.getCause().getMessage(), "Expected cause message to be 'Test exception'");
        assertTrue(task.isDone(), "Task should be done after exception");
    }

    @Test
    void executeRunnableCompletesSuccessfully() {
        AtomicBoolean executed = new AtomicBoolean(false);
        Task<Void> task = Task.execute(() -> executed.set(true));
        task.await(1, TimeUnit.SECONDS);
        assertTrue(executed.get(), "Runnable should have executed");
        assertTrue(task.isDone());
        assertFalse(task.isCancelled());
    }

    @Test
    void executeRunnableWithCallbackInvokesCallback() {
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        Task<Void> task = Task.execute(
                () -> {
                },
                testExecutor,
                t -> callbackInvoked.set(true)
        );
        task.await(1, TimeUnit.SECONDS);
        assertTrue(callbackInvoked.get(), "Callback should have been invoked");
        assertTrue(task.isStarted());
    }

    @Test
    void executeRunnableHandlesException() {
        CheckedRunnable runnable = () -> {
            System.out.println("Executing CheckedRunnable, throwing exception");
            throw new Exception("Test exception");
        };
        Task<Void> task = Task.execute(runnable, testExecutor);
        TaskException thrown = assertThrows(TaskException.class,
                () -> {
                    System.out.println("Awaiting task result");
                    task.await(2, TimeUnit.SECONDS);
                });
        assertTrue(thrown.getCause() instanceof Exception, "Cause should be an Exception");
        assertEquals("Test exception", thrown.getCause().getMessage(), "Expected cause message to be 'Test exception'");
        assertTrue(task.isDone(), "Task should be done after exception");
    }

    @Test
    void executeFutureHandlesNullFuture() {
        assertThrows(NullPointerException.class, () -> Task.executeFuture(() -> null));
    }

    @Test
    void cancelStopsTask() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Task<Void> task = Task.execute(() -> {
            try {
                Thread.sleep(5000);
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                latch.countDown();
            }
        });
        Thread.sleep(100);
        task.cancel(true);
        assertTrue(task.isCancelled());
        assertTrue(task.isDone());
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Task should have been interrupted");
    }

    @Test
    void closeCancelsUncompletedTask() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Task<Void> task = Task.execute(() -> {
            try {
                Thread.sleep(5000);
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                latch.countDown();
            }
        });
        Thread.sleep(100);
        task.close();
        assertTrue(task.isCancelled());
        assertTrue(task.isDone());
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Task should have been interrupted");
    }

    @Test
    void delayIntroducesExpectedDelay() {
        long startTime = System.currentTimeMillis();
        Task<Void> task = Task.delay(500);
        task.await(1, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;
        assertTrue(duration >= 500 && duration < 600, "Delay should be approximately 500ms");
        assertTrue(task.isDone());
    }

    @Test
    void thenApplyTransformsResult() {
        Task<String> task = Task.execute(() -> "Hello")
                .thenApply(String::toUpperCase);
        assertEquals("HELLO", task.await(1, TimeUnit.SECONDS));
        assertTrue(task.isDone());
    }

    @Test
    void awaitWithTimeoutThrowsTimeoutException() {
        Task<Void> task = Task.execute(() -> {
            Thread.sleep(2000);
        });
        TaskException thrown = assertThrows(TaskException.class,
                () -> task.await(100, TimeUnit.MILLISECONDS));
        assertTrue(thrown.getCause() instanceof TimeoutException, "Cause should be TimeoutException");
    }

    @Test
    void ofCreatesCompletedTask() {
        Task<String> task = Task.of("Completed");
        assertEquals("Completed", task.await(1, TimeUnit.SECONDS));
        assertTrue(task.isDone());
        assertFalse(task.isCancelled());
    }

    @Test
    void fromFutureWrapsExistingFuture() {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "Wrapped", testExecutor);
        Task<String> task = Task.fromFuture(future);
        assertEquals("Wrapped", task.await(1, TimeUnit.SECONDS));
        assertTrue(task.isDone());
    }

    @Test
    void executeAsyncAndAwaitCompletesSuccessfully() {
        String result = Task.executeAsyncAndAwait(() -> "Hello, Async!", testExecutor);
        assertEquals("Hello, Async!", result, "Expected supplier result to be returned");
    }

    @Test
    void executeAsyncAndAwaitRespectsDefaultTimeout() {
        TaskException thrown = assertThrows(TaskException.class,
                () -> Task.executeAsyncAndAwait(() -> {
                    try {
                        Thread.sleep(40_000); // Mayor que DEFAULT_AWAIT_TIMEOUT_SECONDS (30s)
                        return "Should not reach here";
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new TaskException("Task interrupted", e);
                    }
                }, testExecutor));

        assertTrue(thrown.getCause() instanceof TimeoutException, "Cause should be a TimeoutException");
    }

//    @Test
//    void executeAsyncAndAwaitHandlesCancellation() throws InterruptedException {
//        CountDownLatch latch = new CountDownLatch(1);
//        Task<String> task = Task.execute(() -> {
//            try {
//                latch.await(5, TimeUnit.SECONDS); // Tiempo suficiente para cancelar
//                return "Should not reach here";
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                throw new TaskException("Task interrupted", e);
//            }
//        }, testExecutor);
//
//        // Ejecutar executeAsyncAndAwait con el mismo latch
//        Thread executeThread = new Thread(() -> {
//            try {
//                Task.executeAsyncAndAwait(() -> {
//                    try {
//                        latch.await(5, TimeUnit.SECONDS); // Mismo latch, se bloqueará
//                        return "Should not reach here";
//                    } catch (InterruptedException e) {
//                        Thread.currentThread().interrupt();
//                        throw new TaskException("Task interrupted", e);
//                    }
//                }, testExecutor);
//            } catch (TaskException e) {
//                // Capturar la excepción para evitar que el hilo termine abruptamente
//            }
//        });
//        executeThread.start();
//
//        // Cancelar la tarea rápidamente
//        Thread.sleep(50); // Esperar a que ambas tareas comiencen
//        task.cancel(true); // Cancelar la tarea principal
//        latch.countDown(); // Liberar el latch para desbloquear
//
//        TaskException thrown = assertThrows(TaskException.class,
//                () -> task.await(2, TimeUnit.SECONDS)); // Esperar el resultado de la tarea principal
//
//        executeThread.join(); // Asegurar que el hilo de executeAsyncAndAwait termine
//        assertTrue(task.isCancelled(), "Task should be cancelled");
//        assertEquals("Task was cancelled", thrown.getMessage(), "Expected cancellation message");
//    }
}
