
package com.example.company.asyncawaitjava.task.interfaces;

import com.example.company.asyncawaitjava.task.TaskStatus;

/**
 *
 * @author guillermo
 */
 public interface StatusCallback<T> {
        void onStatus(TaskStatus<T> status);
    }
