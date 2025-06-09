/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.example.company.asyncawaitjava.exceptions;

/**
 *
 * @author guillermo
 */
public class customizedException {
    /**
     * Excepción personalizada para errores del TaskManager.
     */
    public static class TaskManagerException extends RuntimeException {
        public TaskManagerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
       /**
     * Exception thrown when a task encounters an error during execution or
     * awaiting.
     */
    public static class TaskException extends RuntimeException {
        public TaskException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
