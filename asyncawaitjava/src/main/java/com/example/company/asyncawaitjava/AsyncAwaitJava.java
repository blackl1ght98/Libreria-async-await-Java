package com.example.company.asyncawaitjava;

public class AsyncAwaitJava {
    public static <T> T await(Task<T> task) throws Exception {
        return task.await();
    }
}