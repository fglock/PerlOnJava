package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

/** Matcher-owned save stack for Perl regex callback mutations. */
public final class RegexCallbackMutationSnapshot {
    private final IdentityHashMap<RuntimeScalar, Object> scalars = new IdentityHashMap<>();
    private final IdentityHashMap<RuntimeArray, Object> arrays = new IdentityHashMap<>();
    private final IdentityHashMap<RuntimeHash, Object> hashes = new IdentityHashMap<>();

    private final IdentityHashMap<RuntimeBase, Boolean> seen = new IdentityHashMap<>();

    private RegexCallbackMutationSnapshot() {
        ArrayDeque<RuntimeBase> work = new ArrayDeque<>();
        for (Map.Entry<String, RuntimeScalar> entry : GlobalVariable.globalVariables.entrySet()) {
            if (isOrdinaryPackageScalar(entry.getKey())) work.add(entry.getValue());
        }
        addAll(work, GlobalVariable.globalArrays.values());
        addAll(work, GlobalVariable.globalHashes.values());
        capture(work);
    }

    public void include(RuntimeCode callback) {
        ArrayDeque<RuntimeBase> work = new ArrayDeque<>();
        if (callback.closedOverVariables != null) addAll(work, callback.closedOverVariables.values());
        addAll(work, callback.capturedScalars);
        addAll(work, callback.capturedAggregates);
        capture(work);
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

    public static RegexCallbackMutationSnapshot capture() {
        return new RegexCallbackMutationSnapshot();
    }

    public void restore() {
        for (Map.Entry<RuntimeScalar, Object> entry : scalars.entrySet()) {
            entry.getKey().restoreRegexMutationState(entry.getValue());
        }
        for (Map.Entry<RuntimeArray, Object> entry : arrays.entrySet()) {
            entry.getKey().restoreRegexMutationState(entry.getValue());
        }
        for (Map.Entry<RuntimeHash, Object> entry : hashes.entrySet()) {
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

    private static boolean isOrdinaryPackageScalar(String name) {
        int separator = name.lastIndexOf("::");
        String symbol = separator < 0 ? name : name.substring(separator + 2);
        if (symbol.isEmpty() || !Character.isJavaIdentifierStart(symbol.charAt(0))) return false;
        for (int i = 1; i < symbol.length(); i++) {
            if (!Character.isJavaIdentifierPart(symbol.charAt(i))) return false;
        }
        return true;
    }
}
