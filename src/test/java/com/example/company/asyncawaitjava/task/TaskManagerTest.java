
package com.example.company.asyncawaitjava.task;

import com.example.company.asyncawaitjava.task.interfaces.Step;
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

    private static final Logger LOGGER = Logger.getLogger(TaskManagerTest.class.getName());

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

        List<Step<?>> actions = List.of(
                Step.returning(() -> {
                    LOGGER.info("Ejecutando Tarea1");
                    return "Tarea1";
                }, Object.class),
                Step.returning(() -> {
                    LOGGER.info("Ejecutando Tarea2");
                    return "Tarea2";
                }, Object.class),
                Step.returning(() -> {
                    LOGGER.info("Ejecutando Tarea3");
                    return "Tarea3";
                }, Object.class)
        );

        Task<?> task = manager.addDependentTasks(actions, "mi1-archivo.txt", 1, 0, 0);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback de completado no recibido para todas las tareas");
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
        assertEquals(3, completedData.size(), "Esperaba 3 callbacks con 'mi1-archivo.txt'");
        assertTrue(completedData.contains("mi1-archivo.txt"), "Esperaba 'mi1-archivo.txt' en completedData");
        assertEquals(3, manager.getMetrics().get("completedTasks"), "Esperaba 3 tareas completadas");
        assertEquals(0, manager.getMetrics().get("cancelledTasks"), "No debería haber tareas canceladas");
        assertEquals(0, manager.getMetrics().get("failedTasks"), "No debería haber tareas fallidas");
    }

    @Test
    @DisplayName("Programar tareas dependientes con fallo")
    void testAddDependentTasksFailure() throws InterruptedException {
        CountDownLatch failedLatch = new CountDownLatch(1);
        CountDownLatch successLatch = new CountDownLatch(1);
        manager = new TaskManager<>(status -> {
            if (status.isFailed && status.data != null) {
                completedData.add("failed:" + status.data);
                failedLatch.countDown();
            } else if (!status.isFailed && !status.isCancelled && status.data != null) {
                completedData.add("success:" + status.data);
                successLatch.countDown();
            } else if (status.isCancelled && status.data != null) {
                completedData.add("cancelled:" + status.data);
            }
        });

        List<Step<?>> steps = List.of(
                Step.returning(() -> {
                    LOGGER.info("Ejecutando Tarea1");
                    return "Tarea1";
                }, Object.class),
                Step.returning(() -> {
                    LOGGER.info("Ejecutando Tarea2");
                    throw new RuntimeException("Fallo en Tarea2");
                }, Object.class),
                Step.returning(() -> {
                    LOGGER.info("Ejecutando Tarea3");
                    return "Tarea3";
                }, Object.class)
        );

        Task<?> task = manager.addDependentTasks(steps, "mi2-archivo.txt", 1, 0, 0);
        assertTrue(successLatch.await(5, TimeUnit.SECONDS), "Callback de éxito para Tarea1 no recibido");
        assertTrue(failedLatch.await(5, TimeUnit.SECONDS), "Callback de fallo para Tarea2 no recibido");
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
        assertTrue(completedData.contains("success:mi2-archivo.txt"), "Esperaba 'success:mi2-archivo.txt' por Tarea1");
        assertTrue(completedData.contains("failed:mi2-archivo.txt"), "Esperaba 'failed:mi2-archivo.txt' por Tarea2");
        assertTrue(completedData.contains("cancelled:mi2-archivo.txt"), "Esperaba 'cancelled:mi2-archivo.txt' por Tarea3");
        assertEquals(1, manager.getMetrics().get("completedTasks"), "Esperaba 1 tarea completada (Tarea1)");
        assertEquals(1, manager.getMetrics().get("failedTasks"), "Esperaba 1 tarea fallida (Tarea2)");
        assertEquals(1, manager.getMetrics().get("cancelledTasks"), "Esperaba 1 tarea cancelada (Tarea3)");
    }

    @Test
    @DisplayName("Cancelación automática de la última tarea dependiente")
    void testAutoCancelLastDependentTask() throws InterruptedException {
        CountDownLatch callbackLatch = new CountDownLatch(2); // Uno para Tarea1 completada, otro para Tarea2 cancelada
        manager = new TaskManager<>(status -> {
            if (status.isCancelled && status.data != null) {
                completedData.add("cancelled:" + status.data);
                callbackLatch.countDown();
            } else if (!status.isFailed && !status.isCancelled && status.data != null) {
                completedData.add("success:" + status.data);
                callbackLatch.countDown();
            }
        });

        List<Step<?>> steps = List.of(
                Step.returning(() -> {
                    LOGGER.info("Ejecutando Tarea1");
                    return "Tarea1";
                }, Object.class),
                Step.returning(() -> {
                    LOGGER.info("Ejecutando Tarea2");
                    try {
                        Thread.sleep(2000); // Simular tarea larga
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        LOGGER.log(Level.SEVERE, "Interrumpido en Tarea2", ex);
                        throw new RuntimeException(ex);
                    }
                    return "Tarea2";
                }, Object.class)
        );

        Task<?> task = manager.addDependentTasks(steps, "mi3-archivo.txt", 0, 1000, 0);
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS), "Callbacks de completado y cancelación no recibidos");
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
        assertTrue(completedData.contains("success:mi3-archivo.txt"), "Esperaba 'success:mi3-archivo.txt' por Tarea1");
        assertTrue(completedData.contains("cancelled:mi3-archivo.txt"), "Esperaba 'cancelled:mi3-archivo.txt' por Tarea2");
        assertEquals(1, manager.getMetrics().get("completedTasks"), "Esperaba 1 tarea completada (Tarea1)");
        assertEquals(1, manager.getMetrics().get("cancelledTasks"), "Esperaba 1 tarea cancelada (Tarea2)");
        assertEquals(0, manager.getMetrics().get("failedTasks"), "No debería haber tareas fallidas");
    }

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
                LOGGER.log(Level.SEVERE, "Interrumpido en tarea", ex);
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
//    @DisplayName("Concurrencia extrema con dependencias")
//    void testExtremeConcurrency() throws InterruptedException {
//        int numTasks = 3; // Reducido a 3
//        CountDownLatch latch = new CountDownLatch(numTasks);
//        CountDownLatch callbackLatch = new CountDownLatch(numTasks * 2);
//        manager = new TaskManager<>(status -> {
//            if (!status.isFailed && !status.isCancelled && status.data != null) {
//                completedData.add(status.data);
//                long remaining = callbackLatch.getCount() - 1;
//                LOGGER.info("Callback recibido para: " + status.data + ", restantes: " + remaining);
//                callbackLatch.countDown();
//            } else if (status.isFailed) {
//                LOGGER.info("Tarea fallida: " + status.data + ", error: " + status.exception);
//            } else if (status.isCancelled) {
//                LOGGER.info("Tarea cancelada: " + status.data);
//            }
//        });
//
//        List<Task<String>> tasks = new ArrayList<>();
//        for (int i = 0; i < numTasks; i++) {
//            final int index = i;
//            Task<String> prevTask = manager.scheduleTask(() -> {
//                LOGGER.info("Ejecutando Tarea: Data" + index);
//                return "Tarea" + index;
//            }, "Data" + index, 0, null, 0, 0);
//            tasks.add(prevTask);
//            manager.scheduleTask(() -> {
//                LOGGER.info("Ejecutando Tarea: DepData" + index);
//                return "TareaDep" + index;
//            }, "DepData" + index, 0, Set.of(prevTask), 0, 0);
//            latch.countDown();
//        }
//
//        assertTrue(latch.await(5, TimeUnit.SECONDS), "No se programaron todas las tareas");
//        LOGGER.info("Esperando callbacks, total esperados: " + (numTasks * 2));
//        if (!callbackLatch.await(20, TimeUnit.SECONDS)) {
//            LOGGER.warning("Fallo: No se recibieron todos los callbacks, restantes: " + callbackLatch.getCount());
//            LOGGER.warning("Datos completados: " + completedData);
//            LOGGER.warning("Estado de tareas iniciales: " + 
//                    tasks.stream().map(t -> t.isDone() ? "Completada" : "Pendiente").toList());
//            fail("No se recibieron todos los callbacks");
//        }
//        manager.awaitAll();
//        assertEquals(0, manager.getActiveTaskCount(), "Esperaba 0 tareas activas");
//        assertEquals(numTasks * 2, manager.getMetrics().get("completedTasks"), "Esperaba " + (numTasks * 2) + " tareas completadas");
//    }

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
                LOGGER.log(Level.SEVERE, "Interrumpido en tarea larga", ex);
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
                LOGGER.log(Level.SEVERE, "Interrumpido en tarea", ex);
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