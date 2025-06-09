
package com.example.company.asyncawaitjava.task;

/**
 * A Runnable wrapper that supports prioritization for task execution.
 * Tasks with higher priority values are executed before those with lower values.
 *
 * Example usage:
 * ```java
 * PriorityRunnable task1 = new PriorityRunnable(() -> System.out.println("Task 1"), 2);
 * PriorityRunnable task2 = new PriorityRunnable(() -> System.out.println("Task 2"), 1);
 * System.out.println(task1.compareTo(task2)); // Prints positive value, indicating task1 has higher priority
 * ```
 *
 * @author guillermo
 */
public class PriorityRunnable implements Runnable, Comparable<PriorityRunnable> {
    private final Runnable delegate;
    private final int priority;
 /**
     * Constructs a PriorityRunnable with a delegate Runnable and priority.
     *
     * Example usage:
     * ```java
     * PriorityRunnable task = new PriorityRunnable(() -> System.out.println("Task"), 1);
     * System.out.println(task.getPriority()); // Prints 1
     * ```
     *
     * @param delegate The Runnable to execute.
     * @param priority The priority of the task (higher values indicate higher priority).
     */
    PriorityRunnable(Runnable delegate, int priority) {
        this.delegate = delegate;
        this.priority = priority;
    }
  /**
     * Executes the delegate Runnable.
     *
     * Example usage:
     * ```java
     * PriorityRunnable task = new PriorityRunnable(() -> System.out.println("Running task"), 1);
     * task.run(); // Prints "Running task"
     * ```
     */
    @Override
    public void run() {
        delegate.run();
    }
 /**
     * Compares this PriorityRunnable to another based on priority.
     * Higher priority tasks are ordered before lower priority ones.
     *
     * Example usage:
     * ```java
     * PriorityRunnable task1 = new PriorityRunnable(() -> {}, 2);
     * PriorityRunnable task2 = new PriorityRunnable(() -> {}, 1);
     * System.out.println(task1.compareTo(task2)); // Prints positive value, indicating task1 > task2
     * ```
     *
     * @param other The other PriorityRunnable to compare to.
     * @return A negative integer, zero, or a positive integer if this task has lower,
     *         equal, or higher priority than the other.
     */
    @Override
    public int compareTo(PriorityRunnable other) {
      
        return Integer.compare(other.priority, this.priority);
    }
      /**
     * Returns the priority of this task.
     *
     * Example usage:
     * ```java
     * PriorityRunnable task = new PriorityRunnable(() -> {}, 3);
     * System.out.println(task.getPriority()); // Prints 3
     * ```
     *
     * @return The priority value.
     */
     public int getPriority() {
            return priority;
        }
}