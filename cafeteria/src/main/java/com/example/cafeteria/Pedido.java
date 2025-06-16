/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.example.cafeteria;

import java.util.concurrent.CountDownLatch;

/**
 *
 * @author guillermo
 */
public class Pedido {
    private final int clienteId;
    private final int tiempoBebida;
    private final int tiempoAcompañamiento;
    private final CountDownLatch latch;

    public Pedido(int clienteId, int tiempoBebida, int tiempoAcompañamiento, CountDownLatch latch) {
        this.clienteId = clienteId;
        this.tiempoBebida = tiempoBebida;
        this.tiempoAcompañamiento = tiempoAcompañamiento;
        this.latch = latch;
    }

    public int getClienteId() {
        return clienteId;
    }

    public int getTiempoBebida() {
        return tiempoBebida;
    }

    public int getTiempoAcompañamiento() {
        return tiempoAcompañamiento;
    }

    public CountDownLatch getLatch() {
        return latch;
    }
}