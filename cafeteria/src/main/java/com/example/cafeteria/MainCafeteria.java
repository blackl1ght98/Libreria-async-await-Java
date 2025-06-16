package com.example.cafeteria;

import com.example.company.asyncawaitjava.task.Task;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainCafeteria {
    private static final String GREEN = "\u001B[32m";
    private static final String RESET = "\u001B[0m";

    public static void main(String[] args) {
        Cafeteria cafeteria = new Cafeteria();
        Scanner scanner = new Scanner(System.in);
        Random random = new Random();
        Task<Void> processingTask = Task.execute(cafeteria::procesarColaPedidosConReintentos);
        int clienteId = 1;

        try {
            boolean running = true;
            while (running) {
                System.out.println(GREEN + "\n=== Cafetería ===" + RESET);
                System.out.println("1. Simular llegada de clientes");
                System.out.println("2. Mostrar estado de la cafetería");
                System.out.println("3. Cerrar cafetería");
                System.out.print("Elige una opción: ");
                String input = scanner.nextLine();

                try {
                    int option = Integer.parseInt(input);
                    switch (option) {
                        case 1:
                            System.out.println("¿Cuántos clientes quieres simular? (1-5)");
                            int numClientes = Integer.parseInt(scanner.nextLine());
                            if (numClientes < 1 || numClientes > 5) {
                                System.out.println(GREEN + "Error: Número de clientes debe estar entre 1 y 5." + RESET);
                                continue;
                            }
                            System.out.println(GREEN + "Simulando " + numClientes + " clientes..." + RESET);
                            for (int i = 0; i < numClientes; i++) {
                                int tiempoBebida = random.nextInt(Cafeteria.TIEMPO_MAXIMO_BEBIDA - Cafeteria.TIEMPO_MINIMO_BEBIDA + 1) + Cafeteria.TIEMPO_MINIMO_BEBIDA;
                                int tiempoAcompañamiento = random.nextInt(Cafeteria.TIEMPO_MAXIMO_ACOMPAÑAMIENTO - Cafeteria.TIEMPO_MINIMO_ACOMPAÑAMIENTO + 1) + Cafeteria.TIEMPO_MINIMO_ACOMPAÑAMIENTO;
                                CountDownLatch latch = new CountDownLatch(1);
                                cafeteria.añadirPedido(clienteId, tiempoBebida, tiempoAcompañamiento, latch);
                                clienteId++;
                                latch.await(10, TimeUnit.SECONDS);
                            }
                            break;
                        case 2:
                            cafeteria.mostrarEstado();
                            break;
                        case 3:
                            cafeteria.cerrar();
                            processingTask.cancel(false);
                            try {
                                processingTask.await(5, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                // Ignorar excepciones de cancelación
                            }
                            System.out.println(GREEN + "Programa terminado." + RESET);
                            running = false;
                            break;
                        default:
                            System.out.println(GREEN + "Opción no válida. Por favor, elige 1, 2 o 3." + RESET);
                    }
                } catch (NumberFormatException e) {
                    System.out.println(GREEN + "Error: Por favor, ingrese un número válido." + RESET);
                } catch (Exception e) {
                    System.out.println(GREEN + "Error: " + (e.getMessage() != null ? e.getMessage() : "null") + RESET);
                }
            }
        } finally {
            scanner.close();
            if (!processingTask.isDone()) {
                processingTask.cancel(true);
                try {
                    processingTask.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Ignorar excepciones
                }
            }
        }
    }
}