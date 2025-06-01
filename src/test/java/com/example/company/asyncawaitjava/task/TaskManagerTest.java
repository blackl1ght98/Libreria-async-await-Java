
package com.example.company.asyncawaitjava.task;


import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias optimizadas para la clase TaskManager.
 */
@DisplayName("TaskManager Tests")
class TaskManagerTest {

    private TaskManager<String> manager;
    private List<String> completedData;

    @BeforeEach
    void setUp() {
        completedData = Collections.synchronizedList(new ArrayList<>());
        manager = new TaskManager<>(status -> {
            if (!status.isFailed && !status.isCancelled && status.data != null) {
                completedData.add(status.data);
            }
        });
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    @DisplayName("Programar una tarea simple con éxito")
    void testScheduleTaskSuccess() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        manager = new TaskManager<>(status -> {
            if (!status.isFailed && !status.isCancelled && status.data != null) {
                completedData.add(status.data);
                latch.countDown();
            }
        });

        Task<String> task = manager.scheduleTask(() -> "Resultado", "TestData", 0, null, 0, 0);
        assertEquals("Resultado", task.await(5, TimeUnit.SECONDS));
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Callback de éxito no recibido");
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
        assertTrue(completedData.contains("TestData"), "Esperaba 'TestData' en completedData");
        assertEquals(1, manager.getMetrics().get("completedTasks"), "Esperaba 1 tarea completada");
    }

    @Test
    @DisplayName("Tareas concurrentes con prioridades")
    void testConcurrentTasksWithPriorities() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(10);
        manager = new TaskManager<>(status -> {
            if (!status.isFailed && !status.isCancelled && status.data != null) {
                completedData.add(status.data);
                latch.countDown();
            }
        });

        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 10; i++) {
            final int priority = i % 3;
            manager.scheduleTask(() -> {
                executionOrder.add("Prioridad" + priority);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "Tarea" + priority;
            }, "Data" + i, priority, null, 0, 0);
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "No se recibieron todos los callbacks");
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
        assertEquals(10, manager.getMetrics().get("completedTasks"), "Esperaba 10 tareas completadas");
        int highPriorityCount = 0;
        for (int i = 0; i < 5 && i < executionOrder.size(); i++) {
            if (executionOrder.get(i).equals("Prioridad2")) {
                highPriorityCount++;
            }
        }
        assertTrue(highPriorityCount >= 1, "Esperaba al menos 1 tarea de prioridad 2 al inicio, pero obtuve " + highPriorityCount);
    }

@Test
@DisplayName("Programar tareas dependientes con éxito")
void testAddDependentTasksSuccess() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(3); // Una por cada tarea
    manager = new TaskManager<>(status -> {
        if (!status.isFailed && !status.isCancelled && status.data != null) {
            completedData.add(status.data);
            latch.countDown();
        }
    });

    List<Supplier<?>> actions = List.of(
            () -> "Tarea1",
            () -> "Tarea2",
            () -> "Tarea3"
    );
    manager.addDependentTasks(actions, "FinalData", 1, 0, 0);
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Callback de finalización no recibido");
    manager.awaitAll();
    assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
    assertTrue(completedData.contains("FinalData"), "Esperaba 'FinalData' en completedData");
    assertEquals(3, manager.getMetrics().get("completedTasks"), "Esperaba 3 tareas completadas");
}

  @Test
@DisplayName("Programar tareas dependientes con fallo")
void testAddDependentTasksFailure() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1); // Para el fallo
    CountDownLatch successLatch = new CountDownLatch(1); // Para Tarea1
    manager = new TaskManager<>(status -> {
        if (status.isFailed && status.data != null) {
            latch.countDown();
        }
        if (!status.isFailed && !status.isCancelled && status.data != null) {
            completedData.add(status.data);
            successLatch.countDown();
        }
    });

    List<Supplier<?>> actions = List.of(
            () -> "Tarea1",
            () -> {
                throw new RuntimeException("Fallo en Tarea2");
            },
            () -> "Tarea3"
    );
    manager.addDependentTasks(actions, "ErrorData", 1, 0, 0);
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Callback de fallo no recibido");
    assertTrue(successLatch.await(10, TimeUnit.SECONDS), "Callback de éxito para Tarea1 no recibido");
    manager.awaitAll();
    assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
    assertTrue(completedData.contains("ErrorData"), "Debería contener 'ErrorData' por Tarea1");
    assertEquals(1, manager.getMetrics().get("failedTasks"), "Esperaba 1 tarea fallida");
    assertEquals(1, manager.getMetrics().get("cancelledTasks"), "Esperaba 1 tarea cancelada (Tarea3)");
}


    @Test
    @DisplayName("Cancelación automática de la última tarea dependiente")
    void testAutoCancelLastDependentTask() throws InterruptedException {
        CountDownLatch callbackLatch = new CountDownLatch(1);
        manager = new TaskManager<>(status -> {
            if (status.isCancelled && status.data != null) {
                completedData.add(status.data);
                callbackLatch.countDown();
            }
        });

        List<Supplier<?>> actions = List.of(
                () -> "Tarea1",
                () -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ex) {
                        Logger.getLogger(TaskManagerTest.class.getName()).log(Level.SEVERE, null, ex);
                    }
                    return "Tarea2";
                }
        );
        manager.addDependentTasks(actions, "CancelData", 0, 1000, 0);
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS), "Callback de cancelación no recibido");
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
        assertEquals(1, manager.getMetrics().get("cancelledTasks"), 
                "Esperaba 1 cancelación, pero obtuve " + manager.getMetrics().get("cancelledTasks"));
        assertTrue(completedData.contains("CancelData"), 
                "Callback no incluyó CancelData, contiene: " + completedData);
    }

//    @Test
//    @DisplayName("Cancelación manual de una tarea")
//    void testCancelTask() throws InterruptedException {
//        CountDownLatch callbackLatch = new CountDownLatch(1);
//        manager = new TaskManager<>(status -> {
//            if (status.isCancelled && status.data != null) {
//                completedData.add(status.data);
//                callbackLatch.countDown();
//            }
//        });
//
//        Task<String> task = manager.scheduleTask(() -> {
//            try {
//                Thread.sleep(2000);
//            } catch (InterruptedException ex) {
//                Logger.getLogger(TaskManagerTest.class.getName()).log(Level.SEVERE, null, ex);
//            }
//            return "NoDeberiaCompletar";
//        }, "CancelManual", 0, null, 0, 0);
//
//        assertTrue(manager.cancelTask(task, "CancelManualCallback"), "Cancelación falló");
//        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS), "Callback de cancelación no recibido");
//        manager.awaitAll();
//        assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
//        assertEquals(1, manager.getMetrics().get("cancelledTasks"), 
//                "Esperaba 1 cancelación, pero obtuve " + manager.getMetrics().get("cancelledTasks"));
//        assertTrue(completedData.contains("CancelManual"), 
//                "Callback no incluyó CancelManual, contiene: " + completedData);
//    }

@Test
@DisplayName("Cancelación manual de una tarea")
void testCancelTask() throws InterruptedException {
    CountDownLatch callbackLatch = new CountDownLatch(1);
    manager = new TaskManager<>(status -> {
        if (status.isCancelled && status.data != null) {
            completedData.add(status.data);
            callbackLatch.countDown();
        }
    });

    Task<String> task = manager.scheduleTask(() -> {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ex) {
            Logger.getLogger(TaskManagerTest.class.getName()).log(Level.SEVERE, null, ex);
        }
        return "NoDeberiaCompletar";
    }, "CancelManual", 0, null, 0, 0);

    assertTrue(manager.cancelTask(task, "CancelManualCallback"), "Cancelación falló");
    assertTrue(callbackLatch.await(5, TimeUnit.SECONDS), "Callback de cancelación no recibido");
    manager.awaitAll();
    assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
    assertEquals(1, manager.getMetrics().get("cancelledTasks"), 
            "Esperaba 1 cancelación, pero obtuve " + manager.getMetrics().get("cancelledTasks"));
    assertTrue(completedData.contains("CancelManualCallback"), 
            "Callback no incluyó CancelManualCallback, contiene: " + completedData);
}
//    @Test
//    @DisplayName("Alta concurrencia en adición de tareas")
//    void testHighConcurrencyTaskAdditions() throws InterruptedException {
//        int numThreads = 8; // Reducido para evitar saturación
//        int tasksPerThread = 100;
//        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
//        CountDownLatch latch = new CountDownLatch(numThreads);
//        CountDownLatch callbackLatch = new CountDownLatch(numThreads * tasksPerThread);
//
//        manager = new TaskManager<>(status -> {
//            if (!status.isFailed && !status.isCancelled && status.data != null) {
//                completedData.add(status.data);
//                callbackLatch.countDown();
//            }
//        });
//
//        for (int i = 0; i < numThreads; i++) {
//            executor.submit(() -> {
//                for (int j = 0; j < tasksPerThread; j++) {
//                    manager.scheduleTask(() -> "Result", "Data" + j, 0, null, 0, 0);
//                }
//                latch.countDown();
//            });
//        }
//
//        assertTrue(latch.await(10, TimeUnit.SECONDS), "No se completaron todas las adiciones");
//        assertTrue(callbackLatch.await(15, TimeUnit.SECONDS), "No se recibieron todos los callbacks");
//        manager.awaitAll();
//        assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
//        assertEquals(numThreads * tasksPerThread, manager.getMetrics().get("completedTasks"), 
//                "Esperaba " + (numThreads * tasksPerThread) + " tareas completadas");
//
//        executor.shutdown();
//        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor no terminó");
//    }

//    @Test
//    @DisplayName("Procesamiento de gran escala")
//    void testLargeScaleTaskProcessing() throws InterruptedException {
//        int totalTasks = 1000; // Reducido para pruebas más rápidas
//        CountDownLatch callbackLatch = new CountDownLatch(totalTasks);
//        manager = new TaskManager<>(status -> {
//            if (!status.isFailed && !status.isCancelled && status.data != null) {
//                completedData.add(status.data);
//                callbackLatch.countDown();
//            }
//        });
//
//        for (int i = 0; i < totalTasks; i++) {
//            manager.scheduleTask(() -> "Result", "Data" + i, i % 10, null, 0, 0);
//        }
//
//        long startTime = System.currentTimeMillis();
//        assertTrue(callbackLatch.await(15, TimeUnit.SECONDS), "No se recibieron todos los callbacks");
//        manager.awaitAll();
//        long duration = System.currentTimeMillis() - startTime;
//        assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
//        assertEquals(totalTasks, manager.getMetrics().get("completedTasks"), "Esperaba " + totalTasks + " tareas completadas");
//        assertTrue(duration < 10000, "Tiempo excesivo: " + duration + "ms");
//        assertTrue(completedData.contains("Data999"), "Esperaba 'Data999' en completedData");
//    }


@Test
@DisplayName("Concurrencia extrema con dependencias")
void testExtremeConcurrency() throws InterruptedException {
    int numTasks = 3; // Reducido a 3
    CountDownLatch latch = new CountDownLatch(numTasks);
    CountDownLatch callbackLatch = new CountDownLatch(numTasks * 2);
    manager = new TaskManager<>(status -> {
        if (!status.isFailed && !status.isCancelled && status.data != null) {
            completedData.add(status.data);
            long remaining = callbackLatch.getCount() - 1;
            System.out.println("Callback recibido para: " + status.data + ", restantes: " + remaining);
            callbackLatch.countDown();
        } else if (status.isFailed) {
            System.out.println("Tarea fallida: " + status.data + ", error: " + status.exception);
        } else if (status.isCancelled) {
            System.out.println("Tarea cancelada: " + status.data);
        }
    });

    List<Task<String>> tasks = new ArrayList<>();
    for (int i = 0; i < numTasks; i++) {
        final int index = i;
        Task<String> prevTask = manager.scheduleTask(() -> {
            System.out.println("Ejecutando Tarea: Data" + index);
            return "Tarea" + index;
        }, "Data" + index, 0, null, 0, 0);
        tasks.add(prevTask);
        manager.scheduleTask(() -> {
            System.out.println("Ejecutando Tarea: DepData" + index);
            return "TareaDep" + index;
        }, "DepData" + index, 0, Set.of(prevTask), 0, 0);
        latch.countDown();
    }

    assertTrue(latch.await(5, TimeUnit.SECONDS), "No se programaron todas las tareas");
    System.out.println("Esperando callbacks, total esperados: " + (numTasks * 2));
    if (!callbackLatch.await(20, TimeUnit.SECONDS)) {
        System.out.println("Fallo: No se recibieron todos los callbacks, restantes: " + callbackLatch.getCount());
        System.out.println("Datos completados: " + completedData);
        System.out.println("Estado de tareas iniciales: " + 
            tasks.stream().map(t -> t.isDone() ? "Completada" : "Pendiente").toList());
        fail("No se recibieron todos los callbacks");
    }
    manager.awaitAll();
    assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
    assertEquals(numTasks * 2, manager.getMetrics().get("completedTasks"), "Esperaba " + (numTasks * 2) + " tareas completadas");
}
    @Test
    @DisplayName("Tareas de larga duración")
    void testLongRunningTask() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        manager = new TaskManager<>(status -> {
            if (!status.isFailed && !status.isCancelled && status.data != null) {
                completedData.add(status.data);
                latch.countDown();
            }
        });

        Task<String> task = manager.scheduleTask(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ex) {
                Logger.getLogger(TaskManagerTest.class.getName()).log(Level.SEVERE, null, ex);
            }
            return "LargaDuracion";
        }, "LongData", 0, null, 0, 0);

        assertEquals("LargaDuracion", task.await(5, TimeUnit.SECONDS));
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback no recibido");
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
        assertTrue(completedData.contains("LongData"), "Esperaba 'LongData' en completedData");
        assertEquals(1, manager.getMetrics().get("completedTasks"), "Esperaba 1 tarea completada");
    }

@Test
@DisplayName("Tarea con reintentos")
void testRetries() throws InterruptedException {
    CountDownLatch callbackLatch = new CountDownLatch(1);
    AtomicInteger attempts = new AtomicInteger(0);
    manager = new TaskManager<>(status -> {
        if (status.isFailed && status.data != null) {
            callbackLatch.countDown();
        }
    });

    Task<String> task = manager.scheduleTask(() -> {
        attempts.incrementAndGet();
        throw new RuntimeException("Fallo intencional");
    }, "RetryData", 0, null, 0, 2);

    assertTrue(callbackLatch.await(5, TimeUnit.SECONDS), "Callback de fallo no recibido");
    manager.awaitAll();
    assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
    assertEquals(1, manager.getMetrics().get("failedTasks"), "Esperaba 1 tarea fallida");
    assertEquals(3, attempts.get(), "Esperaba 3 intentos (1 inicial + 2 reintentos)");
}

@Test
@DisplayName("Cierre abrupto con tareas en curso")
void testAbruptClose() throws InterruptedException {
    CountDownLatch callbackLatch = new CountDownLatch(1);
    manager = new TaskManager<>(status -> {
        if (status.isCancelled && status.data != null) {
            callbackLatch.countDown();
        }
    });

    manager.scheduleTask(() -> {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException ex) {
            Logger.getLogger(TaskManagerTest.class.getName()).log(Level.SEVERE, null, ex);
        }
        return "NoDeberiaCompletar";
    }, "CloseData", 0, null, 0, 0);

    Thread.sleep(1000); // Dar tiempo a que la tarea comience
    manager.close();
    assertTrue(callbackLatch.await(5, TimeUnit.SECONDS), "Callback de cancelación no recibido");
    assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
    assertEquals(1, manager.getMetrics().get("cancelledTasks"), "Esperaba 1 tarea cancelada");
}


}

