# Introduccion

AsyncAwaitJava es una librería Java ligera y potente para gestionar tareas asíncronas con una sintaxis inspirada en el modelo async/await. Construida sobre CompletableFuture, ofrece un procesador de anotaciones (@Async y @Await) y un gestor de tareas (TaskManager) para simplificar la programación concurrente. Soporta pipelines de tareas, prioridades, dependencias, reintentos, cancelaciones automáticas y múltiples modos de ejecución.
Incluye un proyecto de ejemplo, Cafeteria, que demuestra cómo usar la librería para procesar pedidos en una cafetería simulada con concurrencia controlada.
Esta es la versión beta 0.1.0. ¡Pruébala y comparte tus comentarios!
La sintaxis podra sufrir cambios para hacerla mas amigable.

# Caracteristicas

- **Tareas asíncronas simplificadas:** Usa Task para ejecutar y esperar resultados de tareas asíncronas.

- **Pipelines de tareas:** Crea flujos de trabajo con PipelineBuilder en modos secuencial, paralelo o personalizado.

- **Gestión avanzada:** TaskManager soporta prioridades, dependencias, reintentos y cancelaciones automáticas.

- **Anotaciones intuitivas:** @Async y @Await para escribir métodos asíncronos y síncronos con facilidad.

- **Reintentos configurables:** Define políticas de reintentos con backoff y jitter mediante RetryConfig.

- **Métricas integradas:** Monitorea tareas completadas, canceladas, fallidas y vencidas.

- **Concurrencia segura:** Diseñado con estructuras thread-safe para entornos concurrentes.

- **Proyecto de ejemplo:** Simulación de una cafetería que muestra la librería en acción.

# Requisitos

Java 17 o superior

Maven 3.8 o superior

Opcional: Un IDE como IntelliJ IDEA, Eclipse o Netbeans en su ultima version

# Instalación

Clona el repositorio:

```bash
git clone https://github.com/tu-usuario/asyncawaitjava.git
```

Añade la dependencia a tu proyecto pom.xml:

```bash
   <dependency>
            <groupId>com.example.company</groupId>
            <artifactId>asyncawaitjava</artifactId>
            <version>1.0-SNAPSHOT</version>
    </dependency>
      <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.14.0</version>
                <configuration>
                    <release>24</release>
                    <compilerArgs>
                        <arg>--enable-preview</arg>
                    </compilerArgs>
                    <annotationProcessorPaths>
                        <!-- No especificamos procesadores; confiamos en META-INF/services -->
                        <path>
                            <groupId>com.example.company</groupId>
                            <artifactId>asyncawaitjava</artifactId>
                            <version>1.0-SNAPSHOT</version>
                        </path>
                    </annotationProcessorPaths>
                    <generatedSourcesDirectory>${project.build.directory}/generated-sources/annotations</generatedSourcesDirectory>
                </configuration>
            </plugin>
```

# Uso de AsyncAwaitJava

Crea y ejecuta una tarea asíncrona con Task:

```java
import com.example.company.asyncawaitjava.task.Task;

public class Main {
    public static void main(String[] args) {
        Task<String> task = Task.execute(() -> {
            Thread.sleep(1000); // Simula trabajo
            return "¡Tarea completada!";
        });

        try {
            String result = task.await();
            System.out.println(result); // Imprime: ¡Tarea completada!
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

Pipeline de tareas:

```java
import com.example.company.asyncawaitjava.task.*;

public class Main {
    public static void main(String[] args) {
        TaskManager<String> taskManager = TaskManager.of(System.out::println);
        Task<?> pipeline = taskManager.pipeline("Datos")
            .withExecutionMode(TaskExecutionMode.SEQUENTIAL)
            .step(() -> {
                Thread.sleep(500);
                return "Paso 1";
            }, String.class)
            .step(() -> {
                Thread.sleep(500);
                return "Paso 2";
            }, String.class)
            .execute();

        taskManager.awaitAll();
        System.out.println(pipeline.isDone()); // Imprime: true
    }
}
```

Uso con anotaciones:

```java
public class CafeteriaTasks {
    private static final String GREEN = "\u001B[32m";
    private static final String RESET = "\u001B[0m";




    @Async
    public void prepararBebida(Pedido pedido) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("Preparación de bebida cancelada para Cliente " + pedido.getClienteId());
        }
        Thread.sleep(pedido.getTiempoBebida());
        System.out.printf("%s[Cliente %d] Bebida preparada en %dms%n%s", GREEN, pedido.getClienteId(), System.currentTimeMillis() - startTime, RESET);
    }
```

Anotacion @Await en desarrollo pronto estara disponible para su uso

Modos de ejecución:
PipelineBuilder soporta cuatro modos:

- **SEQUENTIAL:** Ejecuta los pasos en orden.

- **SEQUENTIAL_STRICT:** Como SEQUENTIAL, pero un fallo o cancelación detiene el pipeline.

- **PARALLEL:** Ejecuta todos los pasos simultáneamente.

- **CUSTOM:** Define dependencias específicas entre pasos.

# Callback para mejar los posibles estados de las tareas

A continuacion veremos un callback para manejar si una tarea falla se cancela o ocurre algo fuera de lo normal su sintaxis es:

```java
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
```

Este es el nivel de control que puedes tener sobre el manejo de estado de una tarea

Ejemplo con modo CUSTOM:

```java
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
```

Ejemplo con modo SEQUENTIAL:

```java
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
```

Ejemplo con modo SEQUENTIAL_STRICT:

```java
  taskManager.pipeline(pedido)
                            .withExecutionMode(TaskExecutionMode.SEQUENTIAL_STRICT)
                            .step( () -> {
                                try {
                                    return asyncTasks.prepararBebidaAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando bebida", e));
                                }
                            }, Void.class)
                            .step( () -> {
                                try {
                                    return asyncTasks.prepararAcompañamientoAsync(pedido);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return CompletableFuture.failedFuture(new RuntimeException("Error preparando acompañamiento", e));
                                }
                            }, Void.class)
                            .step( () -> {
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
```

Ejemplo con modo PARALLEL:

```java
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
```

Esta ultima sintaxis proporcionada incluye la sintaxis de reintento, si quieres tener el reintento personalizado no tienes que poner al final esto **.withMaxRetries(0)** ya que esto anula los reintentos los desactiva, para usarlo se pone asi:

```java
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

                             .withRetryConfig(RetryConfig.builder()
                    .withMaxAttempts(4)
                    .withDelay(100)
                    .withBackoff(2.0)
                    .withJitter(50)
                    .withRetryIf(e -> e instanceof RuntimeException)
                    .build())
                            .execute();
```

como se puede observar no cambia mucho pero si se quiere algo simple en cuanto a politica de reintento se refiero se puede poner esto **.withMaxRetries(0)** y ponemos el numero de reintentos maximos y estaria listo esta forma es para aquellos que quieran tener control total sobre como se configura los reintentos

### Cancelación de tareas

Cancela tareas asociadas a un dato específico con `TaskManager.cancelTasksForData`:

```java
TaskManager<String> taskManager = TaskManager.of(System.out::println);
Task<?> pipeline = taskManager.pipeline("Datos")
    .step(() -> {
        Thread.sleep(5000); // Tarea larga
        return "Paso";
    }, String.class)
    .execute();

// Cancelar tareas asociadas a "Datos"
taskManager.cancelTasksForData("Datos", true);
```

#### 2. Métricas detalladas

**Por qué incluirlo**: Las métricas son una característica poderosa para monitorear el estado de las tareas, y el código de la cafetería las usa extensivamente.

### Monitoreo de métricas

Accede a métricas de tareas con `TaskManager.getMetrics`:

```java
TaskManager<String> taskManager = TaskManager.of(System.out::println);
taskManager.pipeline("Datos")
    .step(() -> "Paso", String.class)
    .execute();
taskManager.awaitAll();

Map<String, Long> metrics = taskManager.getMetrics();
System.out.println("Tareas completadas: " + metrics.getOrDefault("completedTasks", 0L));
System.out.println("Tareas canceladas: " + metrics.getOrDefault("cancelledTasks", 0L));
```

## 3. Uso de `Task.execute` para procesamiento

Inicia un proceso asíncrono con `Task.execute`:

```java
Task<Void> processingTask = Task.execute(() -> {
    System.out.println("Procesando...");
    Thread.sleep(1000);
});
processingTask.await(); // Esperar finalización
```

## 5. Prioridades y auto-cancelación

Estos metodos afectan a nivel global.

```markdown
- `.withPriority(n)`: Define la prioridad del pipeline (valores más altos tienen preferencia).
- `.withAutoCancel(ms)`: Cancela automáticamente el pipeline si excede el tiempo especificado (0 para desactivar).
```

## Modos de procesamiento

La sintaxis es la siguiente

```java
Task<Void> processingTask = Task.execute(cafeteria::procesarColaPedidosConReintentos);
```
