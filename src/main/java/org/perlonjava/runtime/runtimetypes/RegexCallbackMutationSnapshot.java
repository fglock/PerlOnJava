package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

/** Backtracking savepoint for mutations made by a plain {@code (?{...})} block. */
public final class RegexCallbackMutationSnapshot {
    private final IdentityHashMap<RuntimeScalar, Object> scalars = new IdentityHashMap<>();
    private final IdentityHashMap<RuntimeArray, Object> arrays = new IdentityHashMap<>();
    private final IdentityHashMap<RuntimeHash, Object> hashes = new IdentityHashMap<>();
    private final IdentityHashMap<RuntimeBase, Boolean> seen = new IdentityHashMap<>();

    private RegexCallbackMutationSnapshot(RuntimeCode callback) {
        ArrayDeque<RuntimeBase> work = new ArrayDeque<>();
        if (callback.closedOverVariables != null) {
            addAll(work, callback.closedOverVariables.values());
        }
        addAll(work, callback.capturedScalars);
        addAll(work, callback.capturedAggregates);
        if (callback.ourVariableRegistry != null) {
            for (Map.Entry<String, String> entry : callback.ourVariableRegistry.entrySet()) {
                String name = entry.getKey();
                String packageName = entry.getValue();
                if (name == null || name.length() < 2 || packageName == null) continue;
                String fullName = packageName + "::" + name.substring(1);
                RuntimeBase cell = switch (name.charAt(0)) {
                    case '@' -> GlobalVariable.getGlobalArray(fullName);
                    case '%' -> GlobalVariable.getGlobalHash(fullName);
                    default -> GlobalVariable.getGlobalVariable(fullName);
                };
                work.add(cell);
            }
        }
        capture(work);
    }

    public static RegexCallbackMutationSnapshot capture(RuntimeCode callback) {
        return new RegexCallbackMutationSnapshot(callback);
    }

    private void capture(ArrayDeque<RuntimeBase> work) {
        while (!work.isEmpty()) {
            RuntimeBase value = work.removeLast();
            if (value == null || seen.put(value, Boolean.TRUE) != null) continue;
            if (value instanceof RuntimeScalar scalar) {
                Object state = scalar.snapshotRegexMutationState();
                if (state != null) scalars.put(scalar, state);
                if (scalar.value instanceof RuntimeArray array) work.add(array);
                else if (scalar.value instanceof RuntimeHash hash) work.add(hash);
                else if (scalar.value instanceof RuntimeScalar nested) work.add(nested);
            } else if (value instanceof RuntimeArray array) {
                Object state = array.snapshotRegexMutationState();
                if (state == null) continue;
                arrays.put(array, state);
                addAll(work, array.elements);
            } else if (value instanceof RuntimeHash hash) {
                Object state = hash.snapshotRegexMutationState();
                if (state == null) continue;
                hashes.put(hash, state);
                addAll(work, hash.elements.values());
            }
        }
    }

    public void restore() {
        for (Map.Entry<RuntimeArray, Object> entry : arrays.entrySet()) {
            entry.getKey().restoreRegexMutationState(entry.getValue());
        }
        for (Map.Entry<RuntimeHash, Object> entry : hashes.entrySet()) {
            entry.getKey().restoreRegexMutationState(entry.getValue());
        }
        for (Map.Entry<RuntimeScalar, Object> entry : scalars.entrySet()) {
            entry.getKey().restoreRegexMutationState(entry.getValue());
        }
        MortalList.flush();
    }

    private static void addAll(ArrayDeque<RuntimeBase> work,
                               Iterable<? extends RuntimeBase> values) {
        if (values == null) return;
        for (RuntimeBase value : values) if (value != null) work.add(value);
    }

    private static void addAll(ArrayDeque<RuntimeBase> work, RuntimeBase[] values) {
        if (values == null) return;
        for (RuntimeBase value : values) if (value != null) work.add(value);
    }
}
