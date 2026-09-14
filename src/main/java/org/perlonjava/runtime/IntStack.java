package org.perlonjava.runtime;

/** A small, allocation-free primitive stack for per-call runtime state. */
final class IntStack {
    private int[] values = new int[8];
    private int size;

    void push(int value) {
        if (size == values.length) {
            int[] expanded = new int[values.length * 2];
            System.arraycopy(values, 0, expanded, 0, values.length);
            values = expanded;
        }
        values[size++] = value;
    }

    void pop() {
        if (size != 0) {
            size--;
        }
    }

    boolean isEmpty() {
        return size == 0;
    }

    int getFromTop(int depth) {
        int index = size - depth - 1;
        return index >= 0 ? values[index] : -1;
    }

    void clear() {
        size = 0;
    }
}
