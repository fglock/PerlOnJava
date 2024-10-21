package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.List;

public class CallerStack {
    private static final List<CallerInfo> callerStack = new ArrayList<>();

    public static void push(String packageName, String filename, int line) {
        callerStack.add(new CallerInfo(packageName, filename, line));
    }

    public static CallerInfo peek() {
        if (callerStack.isEmpty()) {
            return null;
        }
        return callerStack.getLast();
    }

    public static CallerInfo pop() {
        if (callerStack.isEmpty()) {
            return null;
        }
        return callerStack.removeLast();
    }

    public static List<CallerInfo> getStack() {
        return new ArrayList<>(callerStack);
    }

    public static class CallerInfo {
        public final String packageName;
        public final String filename;
        public final int line;

        public CallerInfo(String packageName, String filename, int line) {
            this.packageName = packageName;
            this.filename = filename;
            this.line = line;
        }

        @Override
        public String toString() {
            return String.format("(%s, %s, %d)", packageName, filename, line);
        }
    }
}
