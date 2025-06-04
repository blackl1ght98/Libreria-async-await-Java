/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.example.company.asyncawaitjava.task;

/**
 *
 * @author guillermo
 */
 /**
     * Representa el estado de una tarea completada.
     */
    public class TaskStatus<T> {
        public final T data;
        public final boolean isCancelled;
        public final boolean isFailed;
        public final Exception exception;
        public final boolean isAutoCancel;

        TaskStatus(T data, boolean isCancelled, boolean isFailed, Exception exception, boolean isAutoCancel ) {
            this.data = data;
            this.isCancelled = isCancelled;
            this.isFailed = isFailed;
            this.exception = exception;
            this.isAutoCancel=isAutoCancel;
        }
    }
