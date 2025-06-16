package com.example.cafeteria;

import com.example.cafeteria.Pedido;
import com.example.company.asyncawaitjava.annotations.Await;
import com.example.company.asyncawaitjava.config.RetryConfig;
import com.example.company.asyncawaitjava.task.Task;
import com.example.company.asyncawaitjava.task.TaskExecutionMode;
import com.example.company.asyncawaitjava.task.TaskManager;
import com.example.company.asyncawaitjava.task.TaskStatus;
import com.example.company.asyncawaitjava.task.interfaces.Step;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Cafeteria {

    private static final Logger LOGGER = Logger.getLogger(Cafeteria.class.getName());
    public static final int TOTAL_ESTACIONES = 2;
    private static final double PRECIO_PEDIDO = 5.0;
    public static final int TIEMPO_MINIMO_BEBIDA = 1000;
    public static final int TIEMPO_MAXIMO_BEBIDA = 3000;
    public static final int TIEMPO_MINIMO_ACOMPAÑAMIENTO = 1000;
    public static final int TIEMPO_MAXIMO_ACOMPAÑAMIENTO = 2000;
    public static final int TIEMPO_ENTREGA = 500;
    private static final int TASKS_PER_ORDER = 3; // Bebida, Acompañamiento, Entrega

    private final AtomicInteger clientesAtendidos = new AtomicInteger(0);
    private final AtomicInteger ingresosTotales = new AtomicInteger(0);
    private final BlockingQueue<Pedido> colaPedidos = new LinkedBlockingQueue<>();
    private final Semaphore estacionesDisponibles = new Semaphore(TOTAL_ESTACIONES, true);
    private final Map<Integer, Boolean> estacionesOcupadas = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> tareasCompletadasPorPedido = new ConcurrentHashMap<>();
    private volatile boolean aceptandoPedidos = true;
    private volatile TaskManager<Pedido> taskManager;

    private static final String GREEN = "\u001B[32m";
    private static final String RESET = "\u001B[0m";

    private Task<Void> toTask(CompletableFuture<Void> future) {
        return Task.fromFuture(future);
    }

    public void añadirPedido(int clienteId, int tiempoBebida, int tiempoAcompañamiento, CountDownLatch latch) {
        if (aceptandoPedidos) {
            Pedido pedido = new Pedido(clienteId, tiempoBebida, tiempoAcompañamiento, latch);
            colaPedidos.offer(pedido);
            tareasCompletadasPorPedido.put(clienteId, new AtomicInteger(0));
            System.out.println(GREEN + "Cliente " + clienteId + " realizó un pedido y se unió a la cola." + RESET);
        } else {
            System.out.println(GREEN + "La cafetería está cerrada a nuevos pedidos." + RESET);
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    public void procesarColaPedidosLowLevel() {
        taskManager = new TaskManager<>(status -> {
            Pedido pedido = status.data;
            if (pedido == null) {
                LOGGER.warning("Estado recibido con pedido nulo");
                return;
            }
            try {
                int clienteId = pedido.getClienteId();
                LOGGER.fine("Callback para clienteId=%d, isLastTaskInPipeline=%b, isCancelled=%b, isFailed=%b"
                        .formatted(clienteId, status.isLastTaskInPipeline(), status.isCancelled(), status.isFailed()));

                AtomicInteger tareasCompletadas = tareasCompletadasPorPedido.get(clienteId);
                if (tareasCompletadas == null) {
                    LOGGER.warning("No se encontró contador de tareas para clienteId=%d".formatted(clienteId));
                    return;
                }

                if (status.isCancelled()) {
                    LOGGER.info("Pedido del Cliente " + clienteId + " fue cancelado.");
                    System.out.println(GREEN + "Pedido del Cliente " + clienteId + " fue cancelado. Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else if (status.isFailed()) {
                    LOGGER.log(Level.WARNING, "Error en el pedido del Cliente " + clienteId, status.exception);
                    System.out.println(GREEN + "Error en el pedido del Cliente " + clienteId + ": " + status.exception.getMessage() + ". Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else {
                    int tareas = tareasCompletadas.incrementAndGet();
                    LOGGER.fine("Tarea completada para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    if (tareas == TASKS_PER_ORDER) {
                        LOGGER.info("Pedido del Cliente " + clienteId + " completado (todas las tareas finalizadas).");
                        System.out.println(GREEN + "Pedido del Cliente " + clienteId + " completado. Estación liberada." + RESET);
                        clientesAtendidos.incrementAndGet();
                        ingresosTotales.addAndGet((int) (PRECIO_PEDIDO * 100));
                        liberarEstacion(pedido);
                        tareasCompletadasPorPedido.remove(clienteId);
                    } else {
                        LOGGER.fine("Ignorando tarea intermedia para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    }
                }

                if (pedido.getLatch() != null && (status.isCancelled() || status.isFailed() || tareasCompletadas.get() == TASKS_PER_ORDER)) {
                    pedido.getLatch().countDown();
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando el callback para el pedido del Cliente " + pedido.getClienteId(), e);
            }
        });

        AsyncCafeteriaTasks asyncTasks = new AsyncCafeteriaTasks();
        boolean interrupted = false;

        while (aceptandoPedidos || !colaPedidos.isEmpty() || (taskManager != null && taskManager.getActiveTaskCount() > 0)) {
            try {
                if (estacionesDisponibles.availablePermits() > 0) {
                    Pedido pedido = colaPedidos.poll(100, TimeUnit.MILLISECONDS);
                    if (pedido == null) {
                        Thread.sleep(10);
                        continue;
                    }

                    int clienteId = pedido.getClienteId();
                    if (clienteId == 3) {
                        LOGGER.info("Cancelando automáticamente el pedido del Cliente 3 encontrado en la cola.");
                        System.out.println(GREEN + "Cancelando automáticamente el pedido del Cliente 3..." + RESET);
                        tareasCompletadasPorPedido.remove(clienteId);
                        if (pedido.getLatch() != null) {
                            pedido.getLatch().countDown();
                        }
                        System.out.println(GREEN + "Pedido del Cliente 3 cancelado (estaba en la cola)." + RESET);
                        continue; // Saltar al siguiente pedido
                    }

                    estacionesDisponibles.acquire();
                    estacionesOcupadas.put(clienteId, true);

                    List<Step<?>> steps = List.of(
                            Step.fromFuture(() -> {
                                try {
                                    return asyncTasks.prepararBebidaAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando bebida", e));
                                }
                            }, Void.class),
                            Step.fromFuture(() -> {
                                try {
                                    return asyncTasks.prepararAcompañamientoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando acompañamiento", e));
                                }
                            }, Void.class),
                            Step.fromFuture(() -> {
                                try {
                                    return asyncTasks.entregarPedidoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error entregando pedido", e));
                                }
                            }, Void.class)
                    );

                    taskManager.addSequentialTasksWithStrict(
                            steps,
                            pedido,
                            10,
                            0,
                            0,
                            RetryConfig.defaultConfig()
                    );
                } else {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando pedido", e);
                System.out.println(GREEN + "Error procesando pedido: " + e.getMessage() + RESET);
            }
        }

        try {
            if (taskManager != null) {
                taskManager.awaitAll();
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            Map<String, Long> metrics = taskManager != null ? taskManager.getMetricsMap() : new HashMap<>();
            System.out.println(GREEN + "Métricas finales: "
                    + "Completadas=" + metrics.getOrDefault("completedTasks", 0L)
                    + ", Canceladas=" + metrics.getOrDefault("cancelledTasks", 0L)
                    + ", Fallidas=" + metrics.getOrDefault("failedTasks", 0L)
                    + ", TimedOut=" + metrics.getOrDefault("timedOutTasks", 0L)
                    + ", Activas=" + metrics.getOrDefault("activeTasks", 0L) + RESET);
            System.out.println(GREEN + "Clientes atendidos: " + clientesAtendidos.get() + ", Ingresos: $" + (ingresosTotales.get() / 100.0) + RESET);
            if (taskManager != null) {
                taskManager.close();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void procesarColaPedidosLowLevelCustom() {
        taskManager = new TaskManager<>(status -> {
            Pedido pedido = status.data;
            if (pedido == null) {
                LOGGER.warning("Estado recibido con pedido nulo");
                return;
            }
            try {
                int clienteId = pedido.getClienteId();
                LOGGER.fine("Callback para clienteId=%d, isLastTaskInPipeline=%b, isCancelled=%b, isFailed=%b"
                        .formatted(clienteId, status.isLastTaskInPipeline(), status.isCancelled(), status.isFailed()));

                AtomicInteger tareasCompletadas = tareasCompletadasPorPedido.get(clienteId);
                if (tareasCompletadas == null) {
                    LOGGER.warning("No se encontró contador de tareas para clienteId=%d".formatted(clienteId));
                    return;
                }

                if (status.isCancelled()) {
                    LOGGER.info("Pedido del Cliente " + clienteId + " fue cancelado.");
                    System.out.println(GREEN + "Pedido del Cliente " + clienteId + " fue cancelado. Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else if (status.isFailed()) {
                    LOGGER.log(Level.WARNING, "Error en el pedido del Cliente " + clienteId, status.exception);
                    System.out.println(GREEN + "Error en el pedido del Cliente " + clienteId + ": " + status.exception.getMessage() + ". Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else {
                    int tareas = tareasCompletadas.incrementAndGet();
                    LOGGER.fine("Tarea completada para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    if (tareas == TASKS_PER_ORDER) {
                        LOGGER.info("Pedido del Cliente " + clienteId + " completado (todas las tareas finalizadas).");
                        System.out.println(GREEN + "Pedido del Cliente " + clienteId + " completado. Estación liberada." + RESET);
                        clientesAtendidos.incrementAndGet();
                        ingresosTotales.addAndGet((int) (PRECIO_PEDIDO * 100));
                        liberarEstacion(pedido);
                        tareasCompletadasPorPedido.remove(clienteId);
                    } else {
                        LOGGER.fine("Ignorando tarea intermedia para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    }
                }

                if (pedido.getLatch() != null && (status.isCancelled() || status.isFailed() || tareasCompletadas.get() == TASKS_PER_ORDER)) {
                    pedido.getLatch().countDown();
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando el callback para el pedido del Cliente " + pedido.getClienteId(), e);
            }
        });

        AsyncCafeteriaTasks asyncTasks = new AsyncCafeteriaTasks();
        boolean interrupted = false;

        while (aceptandoPedidos || !colaPedidos.isEmpty() || (taskManager != null && taskManager.getActiveTaskCount() > 0)) {
            try {
                if (estacionesDisponibles.availablePermits() > 0) {
                    Pedido pedido = colaPedidos.poll(100, TimeUnit.MILLISECONDS);
                    if (pedido == null) {
                        Thread.sleep(10);
                        continue;
                    }

                    int clienteId = pedido.getClienteId();
                    if (clienteId == 3) {
                        LOGGER.info("Cancelando automáticamente el pedido del Cliente 3 encontrado en la cola.");
                        System.out.println(GREEN + "Cancelando automáticamente el pedido del Cliente 3..." + RESET);
                        tareasCompletadasPorPedido.remove(clienteId);
                        if (pedido.getLatch() != null) {
                            pedido.getLatch().countDown();
                        }
                        System.out.println(GREEN + "Pedido del Cliente 3 cancelado (estaba en la cola)." + RESET);
                        continue;
                    }

                    estacionesDisponibles.acquire();
                    estacionesOcupadas.put(clienteId, true);

                    List<Step<?>> steps = List.of(
                            Step.fromFuture(() -> {
                                try {
                                    return asyncTasks.prepararBebidaAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando bebida", e));
                                }
                            }, Void.class),
                            Step.fromFuture(() -> {
                                try {
                                    return asyncTasks.prepararAcompañamientoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando acompañamiento", e));
                                }
                            }, Void.class),
                            Step.fromFuture(() -> {
                                try {
                                    return asyncTasks.entregarPedidoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error entregando pedido", e));
                                }
                            }, Void.class)
                    );

                    // Definir el mapa de dependencias para modo CUSTOM
                    Map<Integer, Set<Integer>> dependencies = new HashMap<>();
                    dependencies.put(1, Set.of(0)); // Acompañamiento depende de bebida
                    dependencies.put(2, Set.of(0, 1)); // Entrega depende de bebida y acompañamiento

                    taskManager.addTasks(
                            steps,
                            pedido,
                            10,
                            0,
                            0,
                            RetryConfig.defaultConfig(),
                            TaskExecutionMode.CUSTOM,
                            dependencies
                    );
                } else {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando pedido", e);
                System.out.println(GREEN + "Error procesando pedido: " + e.getMessage() + RESET);
            }
        }

        try {
            if (taskManager != null) {
                taskManager.awaitAll();
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            Map<String, Long> metrics = taskManager != null ? taskManager.getMetricsMap() : new HashMap<>();
            System.out.println(GREEN + "Métricas finales: "
                    + "Completadas=" + metrics.getOrDefault("completedTasks", 0L)
                    + ", Canceladas=" + metrics.getOrDefault("cancelledTasks", 0L)
                    + ", Fallidas=" + metrics.getOrDefault("failedTasks", 0L)
                    + ", TimedOut=" + metrics.getOrDefault("timedOutTasks", 0L)
                    + ", Activas=" + metrics.getOrDefault("activeTasks", 0L) + RESET);
            System.out.println(GREEN + "Clientes atendidos: " + clientesAtendidos.get() + ", Ingresos: $" + (ingresosTotales.get() / 100.0) + RESET);
            if (taskManager != null) {
                taskManager.close();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void procesarColaPedidosCustom() {
        taskManager = new TaskManager<>(status -> {
            Pedido pedido = status.data;
            if (pedido == null) {
                LOGGER.warning("Estado recibido con pedido nulo");
                return;
            }
            try {
                int clienteId = pedido.getClienteId();
                LOGGER.fine("Callback para clienteId=%d, isLastTaskInPipeline=%b, isCancelled=%b, isFailed=%b"
                        .formatted(clienteId, status.isLastTaskInPipeline(), status.isCancelled(), status.isFailed()));

                AtomicInteger tareasCompletadas = tareasCompletadasPorPedido.get(clienteId);
                if (tareasCompletadas == null) {
                    LOGGER.warning("No se encontró contador de tareas para clienteId=%d".formatted(clienteId));
                    return;
                }

                if (status.isCancelled()) {
                    LOGGER.info("Pedido del Cliente " + clienteId + " fue cancelado.");
                    System.out.println(GREEN + "Pedido del Cliente " + clienteId + " fue cancelado. Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else if (status.isFailed()) {
                    LOGGER.log(Level.WARNING, "Error en el pedido del Cliente " + clienteId, status.exception);
                    System.out.println(GREEN + "Error en el pedido del Cliente " + clienteId + ": " + status.exception.getMessage() + ". Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else {
                    int tareas = tareasCompletadas.incrementAndGet();
                    LOGGER.fine("Tarea completada para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    if (tareas == TASKS_PER_ORDER) {
                        LOGGER.info("Pedido del Cliente " + clienteId + " completado (todas las tareas finalizadas).");
                        System.out.println(GREEN + "Pedido del Cliente " + clienteId + " completado. Estación liberada." + RESET);
                        clientesAtendidos.incrementAndGet();
                        ingresosTotales.addAndGet((int) (PRECIO_PEDIDO * 100));
                        liberarEstacion(pedido);
                        tareasCompletadasPorPedido.remove(clienteId);
                    } else {
                        LOGGER.fine("Ignorando tarea intermedia para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    }
                }

                if (pedido.getLatch() != null && (status.isCancelled() || status.isFailed() || tareasCompletadas.get() == TASKS_PER_ORDER)) {
                    pedido.getLatch().countDown();
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando el callback para el pedido del Cliente " + pedido.getClienteId(), e);
            }
        });

        AsyncCafeteriaTasks asyncTasks = new AsyncCafeteriaTasks();
        boolean interrupted = false;

        while (aceptandoPedidos || !colaPedidos.isEmpty() || (taskManager != null && taskManager.getActiveTaskCount() > 0)) {
            try {
                if (estacionesDisponibles.availablePermits() > 0) {
                    Pedido pedido = colaPedidos.poll(100, TimeUnit.MILLISECONDS);
                    if (pedido == null) {
                        Thread.sleep(10);
                        continue;
                    }

                    int clienteId = pedido.getClienteId();
                    if (clienteId == 3) {
                        LOGGER.info("Cancelando automáticamente el pedido del Cliente 3 encontrado en la cola.");
                        System.out.println(GREEN + "Cancelando automáticamente el pedido del Cliente 3..." + RESET);
                        tareasCompletadasPorPedido.remove(clienteId);
                        if (pedido.getLatch() != null) {
                            pedido.getLatch().countDown();
                        }
                        System.out.println(GREEN + "Pedido del Cliente 3 cancelado (estaba en la cola)." + RESET);
                        continue;
                    }

                    estacionesDisponibles.acquire();
                    estacionesOcupadas.put(clienteId, true);

                    taskManager.pipeline(pedido)
                            .withExecutionMode(TaskExecutionMode.CUSTOM)
                            .step("BEBIDA", () -> {
                                try {
                                    return asyncTasks.prepararBebidaAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando bebida", e));
                                }
                            }, Void.class) // Especificamos el tipo de retorno
                            .step("ACOMPANAMIENTO", () -> {
                                try {
                                    return asyncTasks.prepararAcompañamientoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando acompañamiento", e));
                                }
                            }, Void.class).after("BEBIDA")
                            .step("ENTREGA", () -> {
                                try {
                                    return asyncTasks.entregarPedidoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error entregando pedido", e));
                                }
                            }, Void.class).after("BEBIDA", "ACOMPANAMIENTO")
                            .withPriority(10)
                            .withAutoCancel(2000)
                            .withMaxRetries(0)
                            .execute();
                } else {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando pedido", e);
                System.out.println(GREEN + "Error procesando pedido: " + e.getMessage() + RESET);
            }
        }

        try {
            if (taskManager != null) {
                taskManager.awaitAll();
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            Map<String, Long> metrics = taskManager != null ? taskManager.getMetricsMap() : new HashMap<>();
            System.out.println(GREEN + "Métricas finales: "
                    + "Completadas=" + metrics.getOrDefault("completedTasks", 0L)
                    + ", Canceladas=" + metrics.getOrDefault("cancelledTasks", 0L)
                    + ", Fallidas=" + metrics.getOrDefault("failedTasks", 0L)
                    + ", TimedOut=" + metrics.getOrDefault("timedOutTasks", 0L)
                    + ", Activas=" + metrics.getOrDefault("activeTasks", 0L) + RESET);
            System.out.println(GREEN + "Clientes atendidos: " + clientesAtendidos.get() + ", Ingresos: $" + (ingresosTotales.get() / 100.0) + RESET);
            if (taskManager != null) {
                taskManager.close();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void procesarColaPedidosSecuencial() {
        taskManager = new TaskManager<>(status -> {
            Pedido pedido = status.data;
            if (pedido == null) {
                LOGGER.warning("Estado recibido con pedido nulo");
                return;
            }
            try {
                int clienteId = pedido.getClienteId();
                LOGGER.fine("Callback para clienteId=%d, isLastTaskInPipeline=%b, isCancelled=%b, isFailed=%b"
                        .formatted(clienteId, status.isLastTaskInPipeline(), status.isCancelled(), status.isFailed()));

                AtomicInteger tareasCompletadas = tareasCompletadasPorPedido.get(clienteId);
                if (tareasCompletadas == null) {
                    LOGGER.warning("No se encontró contador de tareas para clienteId=%d".formatted(clienteId));
                    return;
                }

                if (status.isCancelled()) {
                    LOGGER.info("Pedido del Cliente " + clienteId + " fue cancelado.");
                    System.out.println(GREEN + "Pedido del Cliente " + clienteId + " fue cancelado. Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else if (status.isFailed()) {
                    LOGGER.log(Level.WARNING, "Error en el pedido del Cliente " + clienteId, status.exception);
                    System.out.println(GREEN + "Error en el pedido del Cliente " + clienteId + ": " + status.exception.getMessage() + ". Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else {
                    int tareas = tareasCompletadas.incrementAndGet();
                    LOGGER.fine("Tarea completada para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    if (tareas == TASKS_PER_ORDER) {
                        LOGGER.info("Pedido del Cliente " + clienteId + " completado (todas las tareas finalizadas).");
                        System.out.println(GREEN + "Pedido del Cliente " + clienteId + " completado. Estación liberada." + RESET);
                        clientesAtendidos.incrementAndGet();
                        ingresosTotales.addAndGet((int) (PRECIO_PEDIDO * 100));
                        liberarEstacion(pedido);
                        tareasCompletadasPorPedido.remove(clienteId);
                    } else {
                        LOGGER.fine("Ignorando tarea intermedia para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    }
                }

                if (pedido.getLatch() != null && (status.isCancelled() || status.isFailed() || tareasCompletadas.get() == TASKS_PER_ORDER)) {
                    pedido.getLatch().countDown();
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando el callback para el pedido del Cliente " + pedido.getClienteId(), e);
            }
        });

        AsyncCafeteriaTasks asyncTasks = new AsyncCafeteriaTasks();
        boolean interrupted = false;

        while (aceptandoPedidos || !colaPedidos.isEmpty() || (taskManager != null && taskManager.getActiveTaskCount() > 0)) {
            try {
                if (estacionesDisponibles.availablePermits() > 0) {
                    Pedido pedido = colaPedidos.poll(100, TimeUnit.MILLISECONDS);
                    if (pedido == null) {
                        Thread.sleep(10);
                        continue;
                    }

                    int clienteId = pedido.getClienteId();
                    if (clienteId == 3) {
                        LOGGER.info("Cancelando automáticamente el pedido del Cliente 3 encontrado en la cola.");
                        System.out.println(GREEN + "Cancelando automáticamente el pedido del Cliente 3..." + RESET);
                        tareasCompletadasPorPedido.remove(clienteId);
                        if (pedido.getLatch() != null) {
                            pedido.getLatch().countDown();
                        }
                        System.out.println(GREEN + "Pedido del Cliente 3 cancelado (estaba en la cola)." + RESET);
                        continue;
                    }

                    estacionesDisponibles.acquire();
                    estacionesOcupadas.put(clienteId, true);

                    taskManager.pipeline(pedido)
                            .withExecutionMode(TaskExecutionMode.SEQUENTIAL)
                            .step(() -> {
                                try {
                                    return asyncTasks.prepararBebidaAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando bebida", e));
                                }
                            }) // Sin nombres, sin .after()
                            .step(() -> {
                                try {
                                    return asyncTasks.prepararAcompañamientoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando acompañamiento", e));
                                }
                            })
                            .step(() -> {
                                try {
                                    return asyncTasks.entregarPedidoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error entregando pedido", e));
                                }
                            })
                            .withPriority(10)
                            .withAutoCancel(0)
                            .withMaxRetries(0)
                            .execute();
                } else {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando pedido", e);
                System.out.println(GREEN + "Error procesando pedido: " + e.getMessage() + RESET);
            }
        }

        try {
            if (taskManager != null) {
                taskManager.awaitAll();
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            Map<String, Long> metrics = taskManager != null ? taskManager.getMetricsMap() : new HashMap<>();
            System.out.println(GREEN + "Métricas finales: "
                    + "Completadas=" + metrics.getOrDefault("completedTasks", 0L)
                    + ", Canceladas=" + metrics.getOrDefault("cancelledTasks", 0L)
                    + ", Fallidas=" + metrics.getOrDefault("failedTasks", 0L)
                    + ", TimedOut=" + metrics.getOrDefault("timedOutTasks", 0L)
                    + ", Activas=" + metrics.getOrDefault("activeTasks", 0L) + RESET);
            System.out.println(GREEN + "Clientes atendidos: " + clientesAtendidos.get() + ", Ingresos: $" + (ingresosTotales.get() / 100.0) + RESET);
            if (taskManager != null) {
                taskManager.close();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void procesarColaPedidosStrictSequential() {
        taskManager = new TaskManager<>(status -> {
            Pedido pedido = status.data;
            if (pedido == null) {
                LOGGER.warning("Estado recibido con pedido nulo");
                return;
            }
            try {
                int clienteId = pedido.getClienteId();
                LOGGER.fine("Callback para clienteId=%d, isLastTaskInPipeline=%b, isCancelled=%b, isFailed=%b"
                        .formatted(clienteId, status.isLastTaskInPipeline(), status.isCancelled(), status.isFailed()));

                AtomicInteger tareasCompletadas = tareasCompletadasPorPedido.get(clienteId);
                if (tareasCompletadas == null) {
                    LOGGER.warning("No se encontró contador de tareas para clienteId=%d".formatted(clienteId));
                    return;
                }

                if (status.isCancelled()) {
                    LOGGER.info("Pedido del Cliente " + clienteId + " fue cancelado.");
                    System.out.println(GREEN + "Pedido del Cliente " + clienteId + " fue cancelado. Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else if (status.isFailed()) {
                    LOGGER.log(Level.WARNING, "Error en el pedido del Cliente " + clienteId, status.exception);
                    System.out.println(GREEN + "Error en el pedido del Cliente " + clienteId + ": " + status.exception.getMessage() + ". Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else {
                    int tareas = tareasCompletadas.incrementAndGet();
                    LOGGER.fine("Tarea completada para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    if (tareas == TASKS_PER_ORDER) {
                        LOGGER.info("Pedido del Cliente " + clienteId + " completado (todas las tareas finalizadas).");
                        System.out.println(GREEN + "Pedido del Cliente " + clienteId + " completado. Estación liberada." + RESET);
                        clientesAtendidos.incrementAndGet();
                        ingresosTotales.addAndGet((int) (PRECIO_PEDIDO * 100));
                        liberarEstacion(pedido);
                        tareasCompletadasPorPedido.remove(clienteId);
                    } else {
                        LOGGER.fine("Ignorando tarea intermedia para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    }
                }

                if (pedido.getLatch() != null && (status.isCancelled() || status.isFailed() || tareasCompletadas.get() == TASKS_PER_ORDER)) {
                    pedido.getLatch().countDown();
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando el callback para el pedido del Cliente " + pedido.getClienteId(), e);
            }
        });

        AsyncCafeteriaTasks asyncTasks = new AsyncCafeteriaTasks();
        boolean interrupted = false;

        while (aceptandoPedidos || !colaPedidos.isEmpty() || (taskManager != null && taskManager.getActiveTaskCount() > 0)) {
            try {
                if (estacionesDisponibles.availablePermits() > 0) {
                    Pedido pedido = colaPedidos.poll(100, TimeUnit.MILLISECONDS);
                    if (pedido == null) {
                        Thread.sleep(10);
                        continue;
                    }

                    int clienteId = pedido.getClienteId();
                    if (clienteId == 3) {
                        LOGGER.info("Cancelando automáticamente el pedido del Cliente 3 encontrado en la cola.");
                        System.out.println(GREEN + "Cancelando automáticamente el pedido del Cliente 3..." + RESET);
                        tareasCompletadasPorPedido.remove(clienteId);
                        if (pedido.getLatch() != null) {
                            pedido.getLatch().countDown();
                        }
                        System.out.println(GREEN + "Pedido del Cliente 3 cancelado (estaba en la cola)." + RESET);
                        continue;
                    }

                    estacionesDisponibles.acquire();
                    estacionesOcupadas.put(clienteId, true);

                    taskManager.pipeline(pedido)
                            .withExecutionMode(TaskExecutionMode.SEQUENTIAL_STRICT)
                            .step(() -> {
                                try {
                                    return asyncTasks.prepararBebidaAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando bebida", e));
                                }
                            }, Void.class)
                            .step(() -> {
                                try {
                                    return asyncTasks.prepararAcompañamientoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando acompañamiento", e));
                                }
                            }, Void.class)
                            .step(() -> {
                                try {
                                    return asyncTasks.entregarPedidoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error entregando pedido", e));
                                }
                            }, Void.class)
                            .withPriority(10)
                            .withAutoCancel(0)
                            .withMaxRetries(0)
                            .execute();
                } else {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando pedido", e);
                System.out.println(GREEN + "Error procesando pedido: " + e.getMessage() + RESET);
            }
        }

        try {
            if (taskManager != null) {
                taskManager.awaitAll();
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            Map<String, Long> metrics = taskManager != null ? taskManager.getMetricsMap() : new HashMap<>();
            System.out.println(GREEN + "Métricas finales: "
                    + "Completadas=" + metrics.getOrDefault("completedTasks", 0L)
                    + ", Canceladas=" + metrics.getOrDefault("cancelledTasks", 0L)
                    + ", Fallidas=" + metrics.getOrDefault("failedTasks", 0L)
                    + ", TimedOut=" + metrics.getOrDefault("timedOutTasks", 0L)
                    + ", Activas=" + metrics.getOrDefault("activeTasks", 0L) + RESET);
            System.out.println(GREEN + "Clientes atendidos: " + clientesAtendidos.get() + ", Ingresos: $" + (ingresosTotales.get() / 100.0) + RESET);
            if (taskManager != null) {
                taskManager.close();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void procesarColaPedidosParallel() {
        taskManager = new TaskManager<>(status -> {
            Pedido pedido = status.data;
            if (pedido == null) {
                LOGGER.warning("Estado recibido con pedido nulo");
                return;
            }
            try {
                int clienteId = pedido.getClienteId();
                LOGGER.fine("Callback para clienteId=%d, isLastTaskInPipeline=%b, isCancelled=%b, isFailed=%b"
                        .formatted(clienteId, status.isLastTaskInPipeline(), status.isCancelled(), status.isFailed()));

                AtomicInteger tareasCompletadas = tareasCompletadasPorPedido.get(clienteId);
                if (tareasCompletadas == null) {
                    LOGGER.warning("No se encontró contador de tareas para clienteId=%d".formatted(clienteId));
                    return;
                }

                if (status.isCancelled()) {
                    LOGGER.info("Pedido del Cliente " + clienteId + " fue cancelado.");
                    System.out.println(GREEN + "Pedido del Cliente " + clienteId + " fue cancelado. Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else if (status.isFailed()) {
                    LOGGER.log(Level.WARNING, "Error en el pedido del Cliente " + clienteId, status.exception);
                    System.out.println(GREEN + "Error en el pedido del Cliente " + clienteId + ": " + status.exception.getMessage() + ". Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else {
                    int tareas = tareasCompletadas.incrementAndGet();
                    LOGGER.fine("Tarea completada para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    if (tareas == TASKS_PER_ORDER) {
                        LOGGER.info("Pedido del Cliente " + clienteId + " completado (todas las tareas finalizadas).");
                        System.out.println(GREEN + "Pedido del Cliente " + clienteId + " completado. Estación liberada." + RESET);
                        clientesAtendidos.incrementAndGet();
                        ingresosTotales.addAndGet((int) (PRECIO_PEDIDO * 100));
                        liberarEstacion(pedido);
                        tareasCompletadasPorPedido.remove(clienteId);
                    } else {
                        LOGGER.fine("Ignorando tarea intermedia para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    }
                }

                if (pedido.getLatch() != null && (status.isCancelled() || status.isFailed() || tareasCompletadas.get() == TASKS_PER_ORDER)) {
                    pedido.getLatch().countDown();
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando el callback para el pedido del Cliente " + pedido.getClienteId(), e);
            }
        });

        AsyncCafeteriaTasks asyncTasks = new AsyncCafeteriaTasks();
        boolean interrupted = false;

        while (aceptandoPedidos || !colaPedidos.isEmpty() || (taskManager != null && taskManager.getActiveTaskCount() > 0)) {
            try {
                if (estacionesDisponibles.availablePermits() > 0) {
                    Pedido pedido = colaPedidos.poll(100, TimeUnit.MILLISECONDS);
                    if (pedido == null) {
                        Thread.sleep(10);
                        continue;
                    }

                    int clienteId = pedido.getClienteId();
                    if (clienteId == 3) {
                        LOGGER.info("Cancelando automáticamente el pedido del Cliente 3 encontrado en la cola.");
                        System.out.println(GREEN + "Cancelando automáticamente el pedido del Cliente 3..." + RESET);
                        tareasCompletadasPorPedido.remove(clienteId);
                        if (pedido.getLatch() != null) {
                            pedido.getLatch().countDown();
                        }
                        System.out.println(GREEN + "Pedido del Cliente 3 cancelado (estaba en la cola)." + RESET);
                        continue;
                    }

                    estacionesDisponibles.acquire();
                    estacionesOcupadas.put(clienteId, true);

                    taskManager.pipeline(pedido)
                            .withExecutionMode(TaskExecutionMode.PARALLEL)
                            .step(() -> {
                                try {
                                    return asyncTasks.prepararBebidaAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando bebida", e));
                                }
                            }) // Sin nombres, sin .after()
                            .step(() -> {
                                try {
                                    return asyncTasks.prepararAcompañamientoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando acompañamiento", e));
                                }
                            })
                            .step(() -> {
                                try {
                                    return asyncTasks.entregarPedidoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error entregando pedido", e));
                                }
                            })
                            .withPriority(10)
                            .withAutoCancel(0)
                            .withMaxRetries(0)
                            .withRetryConfig(RetryConfig.builder()
                                    .withMaxAttempts(4)
                                    .withDelay(100)
                                    .withBackoff(2.0)
                                    .withJitter(50)
                                    .withRetryIf(e -> e instanceof RuntimeException)
                                    .build())
                            .execute();
                } else {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando pedido", e);
                System.out.println(GREEN + "Error procesando pedido: " + e.getMessage() + RESET);
            }
        }

        try {
            if (taskManager != null) {
                taskManager.awaitAll();
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            Map<String, Long> metrics = taskManager != null ? taskManager.getMetricsMap() : new HashMap<>();
            System.out.println(GREEN + "Métricas finales: "
                    + "Completadas=" + metrics.getOrDefault("completedTasks", 0L)
                    + ", Canceladas=" + metrics.getOrDefault("cancelledTasks", 0L)
                    + ", Fallidas=" + metrics.getOrDefault("failedTasks", 0L)
                    + ", TimedOut=" + metrics.getOrDefault("timedOutTasks", 0L)
                    + ", Activas=" + metrics.getOrDefault("activeTasks", 0L) + RESET);
            System.out.println(GREEN + "Clientes atendidos: " + clientesAtendidos.get() + ", Ingresos: $" + (ingresosTotales.get() / 100.0) + RESET);
            if (taskManager != null) {
                taskManager.close();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void procesarColaPedidosConReintentos() {
        taskManager = new TaskManager<>(status -> {
            Pedido pedido = status.data;
            if (pedido == null) {
                LOGGER.warning("Estado recibido con pedido nulo");
                return;
            }
            try {
                int clienteId = pedido.getClienteId();
                LOGGER.fine("Callback para clienteId=%d, isLastTaskInPipeline=%b, isCancelled=%b, isFailed=%b"
                        .formatted(clienteId, status.isLastTaskInPipeline(), status.isCancelled(), status.isFailed()));

                AtomicInteger tareasCompletadas = tareasCompletadasPorPedido.get(clienteId);
                if (tareasCompletadas == null) {
                    LOGGER.warning("No se encontró contador de tareas para clienteId=%d".formatted(clienteId));
                    return;
                }

                if (status.isCancelled()) {
                    LOGGER.info("Pedido del Cliente " + clienteId + " fue cancelado.");
                    System.out.println(GREEN + "Pedido del Cliente " + clienteId + " fue cancelado. Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else if (status.isFailed()) {
                    LOGGER.log(Level.WARNING, "Error en el pedido del Cliente " + clienteId, status.exception);
                    System.out.println(GREEN + "Error en el pedido del Cliente " + clienteId + ": " + status.exception.getMessage() + ". Estación liberada." + RESET);
                    liberarEstacion(pedido);
                    tareasCompletadasPorPedido.remove(clienteId);
                } else {
                    int tareas = tareasCompletadas.incrementAndGet();
                    LOGGER.fine("Tarea completada para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    if (tareas == TASKS_PER_ORDER) {
                        LOGGER.info("Pedido del Cliente " + clienteId + " completado (todas las tareas finalizadas).");
                        System.out.println(GREEN + "Pedido del Cliente " + clienteId + " completado. Estación liberada." + RESET);
                        clientesAtendidos.incrementAndGet();
                        ingresosTotales.addAndGet((int) (PRECIO_PEDIDO * 100));
                        liberarEstacion(pedido);
                        tareasCompletadasPorPedido.remove(clienteId);
                    } else {
                        LOGGER.fine("Ignorando tarea intermedia para clienteId=%d, tareas completadas=%d".formatted(clienteId, tareas));
                    }
                }

                if (pedido.getLatch() != null && (status.isCancelled() || status.isFailed() || tareasCompletadas.get() == TASKS_PER_ORDER)) {
                    pedido.getLatch().countDown();
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando el callback para el pedido del Cliente " + pedido.getClienteId(), e);
            }
        });

        AsyncCafeteriaTasks asyncTasks = new AsyncCafeteriaTasks();
        boolean interrupted = false;

        while (aceptandoPedidos || !colaPedidos.isEmpty() || (taskManager != null && taskManager.getActiveTaskCount() > 0)) {
            try {
                if (estacionesDisponibles.availablePermits() > 0) {
                    Pedido pedido = colaPedidos.poll(100, TimeUnit.MILLISECONDS);
                    if (pedido == null) {
                        Thread.sleep(10);
                        continue;
                    }

                    int clienteId = pedido.getClienteId();
                    if (clienteId == 3) {
                        LOGGER.info("Cancelando automáticamente el pedido del Cliente 3 encontrado en la cola.");
                        System.out.println(GREEN + "Cancelando automáticamente el pedido del Cliente 3..." + RESET);
                        tareasCompletadasPorPedido.remove(clienteId);
                        if (pedido.getLatch() != null) {
                            pedido.getLatch().countDown();
                        }
                        System.out.println(GREEN + "Pedido del Cliente 3 cancelado (estaba en la cola)." + RESET);
                        continue;
                    }

                    estacionesDisponibles.acquire();
                    estacionesOcupadas.put(clienteId, true);

                    taskManager.pipeline(pedido)
                            .withExecutionMode(TaskExecutionMode.SEQUENTIAL)
                            .step(() -> {
                                try {
                                    return asyncTasks.prepararBebidaAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando bebida", e));
                                }
                            }) // Sin nombres, sin .after()
                            .step(() -> {
                                try {
                                    return asyncTasks.prepararAcompañamientoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando acompañamiento", e));
                                }
                            })
                            .step(() -> {
                                try {
                                    return asyncTasks.entregarPedidoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error entregando pedido", e));
                                }
                            })
                            .withPriority(10)
                            .withAutoCancel(0)
                            .withRetryConfig(RetryConfig.builder()
                                    .withMaxAttempts(4)
                                    .withDelay(100)
                                    .withBackoff(2.0)
                                    .withJitter(50)
                                    .withRetryIf(e -> e instanceof RuntimeException)
                                    .build())
                            .execute();
                } else {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error procesando pedido", e);
                System.out.println(GREEN + "Error procesando pedido: " + e.getMessage() + RESET);
            }
        }

        try {
            if (taskManager != null) {
                taskManager.awaitAll();
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {

            System.out.println(GREEN + "Clientes atendidos: " + clientesAtendidos.get() + ", Ingresos: $" + (ingresosTotales.get() / 100.0) + RESET);

            if (taskManager != null) {
                taskManager.close();

            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void liberarEstacion(Pedido pedido) {
        Boolean ocupado = estacionesOcupadas.remove(pedido.getClienteId());
        if (ocupado != null && ocupado) {
            try {
                estacionesDisponibles.release();
                LOGGER.fine("Estación liberada para pedido: clienteId=%d".formatted(pedido.getClienteId()));
                System.out.println(GREEN + "Estación liberada para pedido: clienteId=%d" + RESET + pedido.getClienteId());
            } catch (IllegalMonitorStateException e) {
                LOGGER.log(Level.SEVERE, "Error al liberar estación para clienteId=" + pedido.getClienteId(), e);
                System.out.println(GREEN + "Error al liberar estación: " + e.getMessage() + RESET);
            }
        } else {
            LOGGER.fine("Estación ya liberada o no ocupada para clienteId=%d".formatted(pedido.getClienteId()));
        }
    }

    public void cerrar() {
        System.out.println(GREEN + "Cerrando la cafetería, no se aceptan nuevos pedidos" + RESET);
        String metrics1 = taskManager.getMetrics();
       
        System.out.println("LAS MÉTRICAS SON: " + GREEN + metrics1 + RESET);
        Map<String, Long> metrics = taskManager != null ? taskManager.getMetricsMap() : new HashMap<>();
        System.out.println(GREEN + "Métricas finales: "
                + "Completadas=" + metrics.getOrDefault("completedTasks", 0L)
                + ", Canceladas=" + metrics.getOrDefault("cancelledTasks", 0L)
                + ", Fallidas=" + metrics.getOrDefault("failedTasks", 0L)
                + ", TimedOut=" + metrics.getOrDefault("timedOutTasks", 0L)
                + ", Activas=" + metrics.getOrDefault("activeTasks", 0L) + RESET);
        System.out.println("LAS METRICAS SON: " + GREEN + taskManager.getMetrics());
      
        aceptandoPedidos = false;

        Pedido pedido;
        while ((pedido = colaPedidos.poll()) != null) {
            LOGGER.info("Cancelando pedido en cola: clienteId=%d".formatted(pedido.getClienteId()));
            System.out.println(GREEN + "Pedido del Cliente " + pedido.getClienteId() + " cancelado (en cola)." + RESET);
            tareasCompletadasPorPedido.remove(pedido.getClienteId());
            if (pedido.getLatch() != null) {
                pedido.getLatch().countDown();
            }
        }

        if (taskManager != null) {
            taskManager.cancelTasksForData(null, true);
            try {
                taskManager.awaitAll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warning("Interrumpido mientras se esperaba la finalización de tareas");
            }
            taskManager.close();
        }

        System.out.println(GREEN + "Cafetería cerrada." + RESET);
        mostrarEstado();
    }


 

    public void mostrarEstado() {
        System.out.println(GREEN + "\n=== Estado de la cafetería ===" + RESET);
        System.out.println(GREEN + "Clientes atendidos: " + clientesAtendidos.get() + RESET);
        System.out.printf("%sIngresos totales: $%.2f%n%s", GREEN, ingresosTotales.get() / 100.0, RESET);
        int permisos = estacionesDisponibles.availablePermits();
        if (permisos > TOTAL_ESTACIONES) {
            LOGGER.warning("Estaciones disponibles (%d) excede el máximo (%d)".formatted(permisos, TOTAL_ESTACIONES));
            System.out.printf("%sADVERTENCIA: Estaciones disponibles (%d) excede el máximo (%d)%n%s", GREEN, permisos, TOTAL_ESTACIONES, RESET);
        }
        System.out.printf("%sEstaciones disponibles: %d%n%s", GREEN, permisos, RESET);
        System.out.printf("%sEstaciones en uso: %d%n%s", GREEN, Math.max(0, TOTAL_ESTACIONES - permisos), RESET);
    }

    public boolean isAceptandoPedidos() {
        return aceptandoPedidos;
    }

    public boolean isColaVacia() {
        return colaPedidos.isEmpty();
    }
}
