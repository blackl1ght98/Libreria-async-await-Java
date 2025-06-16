/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.example.cafeteria;

import com.example.company.asyncawaitjava.annotations.Async;





/**
 *
 * @author guillermo
 */


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

    @Async
    public void prepararAcompañamiento(Pedido pedido) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("Preparación de acompañamiento cancelada para Cliente " + pedido.getClienteId());
        }
        Thread.sleep(pedido.getTiempoAcompañamiento());
        System.out.printf("%s[Cliente %d] Acompañamiento preparado en %dms%n%s", GREEN, pedido.getClienteId(), System.currentTimeMillis() - startTime, RESET);
    }

    @Async
    public void entregarPedido(Pedido pedido) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("Entrega cancelada para Cliente " + pedido.getClienteId());
        }
        Thread.sleep(Cafeteria.TIEMPO_ENTREGA);
        System.out.printf("%s[Cliente %d] Pedido entregado en %dms%n%s", GREEN, pedido.getClienteId(), System.currentTimeMillis() - startTime, RESET);
    }
}