package com.example.cafeteria;

import com.example.company.asyncawaitjava.config.AsyncAwaitConfig;
import java.util.concurrent.CompletableFuture;

public class AsyncCafeteriaTasks extends CafeteriaTasks {
  public AsyncCafeteriaTasks() {
    super();
  }

  /**
   * Versión asíncrona del método prepararBebida. Devuelve un CompletableFuture<Void>.
   */
  public CompletableFuture<Void> prepararBebidaAsync(Pedido pedido) throws InterruptedException {
    return CompletableFuture.runAsync(() -> {
    try {
      super.prepararBebida(pedido);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    }, AsyncAwaitConfig.getDefaultExecutor());
  }

  /**
   * Versión asíncrona del método prepararAcompañamiento. Devuelve un CompletableFuture<Void>.
   */
  public CompletableFuture<Void> prepararAcompañamientoAsync(Pedido pedido) throws
      InterruptedException {
    return CompletableFuture.runAsync(() -> {
    try {
      super.prepararAcompañamiento(pedido);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    }, AsyncAwaitConfig.getDefaultExecutor());
  }

  /**
   * Versión asíncrona del método entregarPedido. Devuelve un CompletableFuture<Void>.
   */
  public CompletableFuture<Void> entregarPedidoAsync(Pedido pedido) throws InterruptedException {
    return CompletableFuture.runAsync(() -> {
    try {
      super.entregarPedido(pedido);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    }, AsyncAwaitConfig.getDefaultExecutor());
  }
}
