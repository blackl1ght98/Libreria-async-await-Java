/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.example.company.asyncawaitjava.task;

/**
 *
 * @author guillermo
 */

public class PriorityRunnable implements Runnable, Comparable<PriorityRunnable> {
    private final Runnable delegate;
    private final int priority;

    PriorityRunnable(Runnable delegate, int priority) {
        this.delegate = delegate;
        this.priority = priority;
    }

    @Override
    public void run() {
        delegate.run();
    }

    @Override
    public int compareTo(PriorityRunnable other) {
        // Mayor prioridad primero (orden descendente)
        return Integer.compare(other.priority, this.priority);
    }
     public int getPriority() {
            return priority;
        }
}