package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.IdentityHashMap;

/**
 * Java implementation of Clone module for PerlOnJava.
 * <p>
 * Provides deep cloning that properly handles tied variables, blessed objects,
 * and circular references — equivalent to the XS Clone module's behavior.
 */
public class Clone extends PerlModuleBase {

    public Clone() {
        super("Clone", false);
    }

    public static void initialize() {
        Clone module = new Clone();
        try {
            module.registerMethod("clone", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Clone method: " + e.getMessage());
        }
        module.defineExport("EXPORT_OK", "clone");
    }

    /**
     * Deep clone a Perl data structure.
     * <p>
     * clone($ref)        - deep clone
     * clone($ref, $depth) - clone with depth limit (0 = shallow)
     */
    public static RuntimeList clone(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar().getList();
        }

        RuntimeScalar source = args.get(0);
        int depth = args.size() > 1 ? args.get(1).getInt() : -1; // -1 = unlimited

        if (source == null || source.type == RuntimeScalarType.UNDEF) {
            return new RuntimeScalar().getList();
        }

        IdentityHashMap<Object, RuntimeScalar> cloned = new IdentityHashMap<>();
        RuntimeScalar result = deepClone(source, cloned, depth);
        return result.getList();
    }

    /**
     * Recursively deep-clones a RuntimeScalar, handling circular references,
     * tied variables, and blessed objects.
     */
    private static RuntimeScalar deepClone(RuntimeScalar scalar, IdentityHashMap<Object, RuntimeScalar> cloned, int depth) {
        if (scalar == null) return new RuntimeScalar();

        // Depth limit: 0 means return as-is (shared)
        if (depth == 0) return scalar;
        int nextDepth = depth > 0 ? depth - 1 : depth;

        // Check for already-cloned references (circular reference handling)
        if (scalar.value != null && cloned.containsKey(scalar.value)) {
            return cloned.get(scalar.value);
        }

        int blessId = RuntimeScalarType.blessedId(scalar);

        return switch (scalar.type) {
            case RuntimeScalarType.HASHREFERENCE -> {
                RuntimeHash origHash = (RuntimeHash) scalar.value;
                RuntimeHash newHash = new RuntimeHash();
                RuntimeScalar newRef = newHash.createReference();
                cloned.put(scalar.value, newRef);

                // Preserve blessing
                if (blessId != 0) {
                    String className = NameNormalizer.getBlessStr(blessId);
                    ReferenceOperators.bless(newRef, new RuntimeScalar(className));
                }

                // Check for tied hash — preserve tie magic
                if (origHash.type == RuntimeHash.TIED_HASH && origHash.elements instanceof TieHash tieHash) {
                    RuntimeScalar clonedSelf = deepClone(tieHash.getSelf(), cloned, nextDepth);
                    RuntimeHash previousValue = new RuntimeHash();
                    newHash.type = RuntimeHash.TIED_HASH;
                    newHash.elements = new TieHash(tieHash.getTiedPackage(), previousValue, clonedSelf);
                    // Copy data through the tied interface
                    RuntimeScalar firstKey = TieHash.tiedFirstKey(origHash);
                    while (firstKey.type != RuntimeScalarType.UNDEF) {
                        RuntimeScalar val = TieHash.tiedFetch(origHash, firstKey);
                        TieHash.tiedStore(newHash, firstKey, deepClone(val, cloned, nextDepth));
                        firstKey = TieHash.tiedNextKey(origHash, firstKey);
                    }
                } else {
                    origHash.elements.forEach((key, value) ->
                            newHash.put(key, deepClone(value, cloned, nextDepth)));
                }
                yield newRef;
            }
            case RuntimeScalarType.ARRAYREFERENCE -> {
                RuntimeArray origArray = (RuntimeArray) scalar.value;
                RuntimeArray newArray = new RuntimeArray();
                RuntimeScalar newRef = newArray.createReference();
                cloned.put(scalar.value, newRef);

                // Preserve blessing
                if (blessId != 0) {
                    String className = NameNormalizer.getBlessStr(blessId);
                    ReferenceOperators.bless(newRef, new RuntimeScalar(className));
                }

                // Check for tied array — preserve tie magic
                if (origArray.type == RuntimeArray.TIED_ARRAY && origArray.elements instanceof TieArray tieArray) {
                    RuntimeScalar clonedSelf = deepClone(tieArray.getSelf(), cloned, nextDepth);
                    RuntimeArray previousValue = new RuntimeArray();
                    newArray.type = RuntimeArray.TIED_ARRAY;
                    newArray.elements = new TieArray(tieArray.getTiedPackage(), previousValue, clonedSelf, newArray);
                    int size = TieArray.tiedFetchSize(origArray).getInt();
                    for (int i = 0; i < size; i++) {
                        RuntimeScalar val = TieArray.tiedFetch(origArray, new RuntimeScalar(i));
                        TieArray.tiedStore(newArray, new RuntimeScalar(i), deepClone(val, cloned, nextDepth));
                    }
                } else {
                    for (RuntimeScalar element : origArray.elements) {
                        newArray.elements.add(deepClone(element, cloned, nextDepth));
                    }
                }
                yield newRef;
            }
            case RuntimeScalarType.REFERENCE -> {
                RuntimeScalar origValue = (RuntimeScalar) scalar.value;
                RuntimeScalar newValue = deepClone(origValue, cloned, nextDepth);
                RuntimeScalar newRef = newValue.createReference();
                cloned.put(scalar.value, newRef);

                if (blessId != 0) {
                    String className = NameNormalizer.getBlessStr(blessId);
                    ReferenceOperators.bless(newRef, new RuntimeScalar(className));
                }
                yield newRef;
            }
            case RuntimeScalarType.CODE -> {
                // CODE refs are shared, not cloned
                yield scalar;
            }
            case RuntimeScalarType.READONLY_SCALAR -> deepClone((RuntimeScalar) scalar.value, cloned, nextDepth);
            case RuntimeScalarType.TIED_SCALAR -> {
                if (scalar.value instanceof TieScalar tieScalar) {
                    RuntimeScalar clonedSelf = deepClone(tieScalar.getSelf(), cloned, nextDepth);
                    RuntimeScalar prevValue = new RuntimeScalar();
                    prevValue.set(tieScalar.tiedFetch());
                    RuntimeScalar copy = new RuntimeScalar();
                    copy.type = RuntimeScalarType.TIED_SCALAR;
                    copy.value = new TieScalar(tieScalar.getTiedPackage(), prevValue, clonedSelf);
                    yield copy;
                } else {
                    RuntimeScalar copy = new RuntimeScalar();
                    copy.set(scalar);
                    yield copy;
                }
            }
            default -> {
                // Scalar values (int, double, string, undef) — just copy
                RuntimeScalar copy = new RuntimeScalar();
                copy.type = scalar.type;
                copy.value = scalar.value;
                yield copy;
            }
        };
    }
}
