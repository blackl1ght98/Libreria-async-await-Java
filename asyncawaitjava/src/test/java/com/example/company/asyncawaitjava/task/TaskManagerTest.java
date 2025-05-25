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
 * Pruebas unitarias para la clase TaskManager.
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
        Task<String> task = manager.scheduleTask(() -> "Resultado", "TestData", 0, null, 0, 0);
        assertEquals("Resultado", task.await(5, TimeUnit.SECONDS));
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount());
        assertTrue(completedData.contains("TestData"));
        assertEquals(1, manager.getMetrics().get("completedTasks"));
    }

     @Test
    @DisplayName("Tareas concurrentes con prioridades")
    void testConcurrentTasksWithPriorities() throws InterruptedException {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 10; i++) {
            final int priority = i % 3;
            manager.scheduleTask(() -> {
                executionOrder.add("Prioridad" + priority);
                return "Tarea" + priority;
            }, "Data" + i, priority, null, 0, 0);
        }
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount());
        assertEquals(10, manager.getMetrics().get("completedTasks"));
        assertTrue(manager.getMetrics().get("completedTasks") >= 10);
    }

    @Test
    @DisplayName("Programar tareas dependientes con éxito")
    void testAddDependentTasksSuccess() throws InterruptedException {
        List<Supplier<?>> actions = List.of(
            () -> "Tarea1",
            () -> "Tarea2",
            () -> "Tarea3"
        );
        manager.addDependentTasks(actions, "FinalData", 1, 0, 0);
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount());
        assertTrue(completedData.contains("FinalData"));
        assertEquals(3, manager.getMetrics().get("completedTasks"));
    }

    @Test
    @DisplayName("Programar tareas dependientes con fallo")
    void testAddDependentTasksFailure() throws InterruptedException {
        List<Supplier<?>> actions = List.of(
            () -> "Tarea1",
            () -> { throw new RuntimeException("Fallo en Tarea2"); },
            () -> "Tarea3"
        );
        manager.addDependentTasks(actions, "ErrorData", 1, 0, 0);
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount());
        assertFalse(completedData.contains("ErrorData"));
        assertEquals(1, manager.getMetrics().get("failedTasks"));
        assertEquals(1, manager.getMetrics().get("cancelledTasks")); // Tercera tarea cancelada
    }

    @Test
    @DisplayName("Cancelación automática de la última tarea dependiente")
    void testAutoCancelLastDependentTask() throws InterruptedException {
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
        Thread.sleep(1500);
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount());
        assertEquals(1, manager.getMetrics().get("cancelledTasks"));
        assertFalse(completedData.contains("CancelData"));
    }

    @Test
    @DisplayName("Cancelación manual de una tarea")
    void testCancelTask() throws InterruptedException {
        Task<String> task = manager.scheduleTask(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
                Logger.getLogger(TaskManagerTest.class.getName()).log(Level.SEVERE, null, ex);
            }
            return "NoDeberiaCompletar";
        }, "CancelManual", 0, null, 0, 0);
        Thread.sleep(500);
        assertTrue(manager.cancelTask(task, "CancelManual"));
        manager.awaitAll();
        assertEquals(0, manager.getActiveTaskCount());
        assertEquals(1, manager.getMetrics().get("cancelledTasks"));
        assertFalse(completedData.contains("CancelManual"));
    }

    @Test
    @DisplayName("Manejo de dependencias circulares")
    void testCircularDependencies() {
        Task<String> task1 = manager.scheduleTask(() -> "Tarea1", "Data1", 0, null, 0, 0);
        Task<String> task2 = manager.scheduleTask(() -> "Tarea2", "Data2", 0, Set.of(task1), 0, 0);
        assertThrows(IllegalArgumentException.class, () ->
            manager.addTask(task1, "Data1", 0, Set.of(task2))
        );
    }

   

    @Test
    @DisplayName("Cierre del TaskManager con tareas pendientes")
    void testCloseWithPendingTasks() {
        manager.scheduleTask(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
                Logger.getLogger(TaskManagerTest.class.getName()).log(Level.SEVERE, null, ex);
            }
            return "NoDeberiaCompletar";
        }, "PendingData", 0, null, 0, 0);
        manager.close();
        assertEquals(0, manager.getActiveTaskCount());
        assertEquals(1, manager.getMetrics().get("cancelledTasks"));
        assertFalse(completedData.contains("PendingData"));
    }

    @Test
    @DisplayName("Validación de parámetros inválidos")
    void testInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () ->
            manager.scheduleTask(null, "Data", 0, null, 0, 0)
        );
        assertThrows(IllegalArgumentException.class, () ->
            manager.scheduleTask(() -> "Tarea", "Data", 0, null, -1, 0)
        );
        assertThrows(IllegalArgumentException.class, () ->
            manager.scheduleTask(() -> "Tarea", "Data", 0, null, 0, -1)
        );
        assertThrows(IllegalArgumentException.class, () ->
            manager.addDependentTasks(List.of(), "Data", 0, 0, 0)
        );
    }

@Test
@DisplayName("Manejo de concurrencia con múltiples cadenas de tareas")
void testConcurrentDependentTasks() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(50);
    for (int i = 0; i < 50; i++) {
        final int index = i;
        List<Supplier<?>> actions = List.of(
            () -> "Tarea" + index + "-1",
            () -> "Tarea" + index + "-2"
        );
        manager.addDependentTasks(actions, "Data" + index, 0, 0, 0);
        latch.countDown();
    }
    latch.await(5, TimeUnit.SECONDS);
    manager.awaitAll();
    // Repeatedly process completed tasks until none remain
    int maxRetries = 10;
    while (manager.hasTasks() && maxRetries-- > 0) {
        manager.processCompletedTasks();
        Thread.sleep(50); // Allow background processor to catch up
    }
    assertEquals(0, manager.getActiveTaskCount(), "Expected no active tasks, but found: " + manager.getActiveTaskCount());
    assertEquals(100, manager.getMetrics().get("completedTasks"));
    assertTrue(completedData.contains("Data49"));
}
}