package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.HashSpecialVariable;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.util.ArrayList;
import java.util.List;

/** Native implementation of Perl's built-in Tie::Hash::NamedCapture API. */
public final class TieHashNamedCapture extends PerlModuleBase {
    private static final int CAPTURE = 256;
    private static final int CAPTURE_ALL = 512;

    private TieHashNamedCapture() {
        super("Tie::Hash::NamedCapture", false);
    }

    public static void initialize() {
        TieHashNamedCapture module = new TieHashNamedCapture();
        try {
            module.registerMethod("FETCH", null);
            module.registerMethod("STORE", null);
            module.registerMethod("DELETE", null);
            module.registerMethod("CLEAR", null);
            module.registerMethod("EXISTS", null);
            module.registerMethod("FIRSTKEY", null);
            module.registerMethod("NEXTKEY", null);
            module.registerMethod("SCALAR", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Cannot register Tie::Hash::NamedCapture", e);
        }
    }

    public static RuntimeScalar tiedObject(HashSpecialVariable.Id mode) {
        int value = mode == HashSpecialVariable.Id.CAPTURE_ALL ? CAPTURE_ALL : CAPTURE;
        RuntimeScalar reference = new RuntimeScalar(value).createReference();
        ReferenceOperators.bless(reference, new RuntimeScalar("Tie::Hash::NamedCapture"));
        return reference;
    }

    private static HashSpecialVariable.Id mode(RuntimeScalar self) {
        if (self == null || self.type == RuntimeScalarType.UNDEF) return null;
        RuntimeScalar value = self;
        if (value.type == RuntimeScalarType.REFERENCE) value = value.scalarDeref();
        return value.getInt() == CAPTURE_ALL
                ? HashSpecialVariable.Id.CAPTURE_ALL
                : HashSpecialVariable.Id.CAPTURE;
    }

    private static RuntimeHash captureHash(HashSpecialVariable.Id mode) {
        return GlobalVariable.getGlobalHash(
                mode == HashSpecialVariable.Id.CAPTURE_ALL ? "main::-" : "main::+");
    }

    private static void arity(RuntimeArray args, int expected, String method, String signature) {
        if (args.size() != expected) {
            throw new PerlCompilerException(
                    "Usage: Tie::Hash::NamedCapture::" + method + "(" + signature + ")");
        }
    }

    private static RuntimeList undef() {
        return new RuntimeScalar().getList();
    }

    public static RuntimeList FETCH(RuntimeArray args, int ctx) {
        arity(args, 2, "FETCH", "$key");
        HashSpecialVariable.Id mode = mode(args.get(0));
        if (mode == null) return undef();
        return new RuntimeScalar(captureHash(mode).get(args.get(1))).getList();
    }

    public static RuntimeList STORE(RuntimeArray args, int ctx) {
        arity(args, 3, "STORE", "$key, $value");
        throw new PerlCompilerException("Modification of a read-only value attempted");
    }

    public static RuntimeList DELETE(RuntimeArray args, int ctx) {
        arity(args, 2, "DELETE", "$key");
        throw new PerlCompilerException("Modification of a read-only value attempted");
    }

    public static RuntimeList CLEAR(RuntimeArray args, int ctx) {
        arity(args, 1, "CLEAR", "");
        throw new PerlCompilerException("Modification of a read-only value attempted");
    }

    public static RuntimeList EXISTS(RuntimeArray args, int ctx) {
        arity(args, 2, "EXISTS", "$key");
        HashSpecialVariable.Id mode = mode(args.get(0));
        if (mode == null) return undef();
        return new RuntimeScalar(captureHash(mode).exists(args.get(1))).getList();
    }

    private static List<String> keys(HashSpecialVariable.Id mode) {
        return new ArrayList<>(captureHash(mode).elements.keySet());
    }

    public static RuntimeList FIRSTKEY(RuntimeArray args, int ctx) {
        arity(args, 1, "FIRSTKEY", "");
        HashSpecialVariable.Id mode = mode(args.get(0));
        if (mode == null) return undef();
        List<String> keys = keys(mode);
        return keys.isEmpty() ? undef() : new RuntimeScalar(keys.get(0)).getList();
    }

    public static RuntimeList NEXTKEY(RuntimeArray args, int ctx) {
        arity(args, 2, "NEXTKEY", "$lastkey");
        HashSpecialVariable.Id mode = mode(args.get(0));
        if (mode == null) return undef();
        List<String> keys = keys(mode);
        int index = keys.indexOf(args.get(1).toString()) + 1;
        return index <= 0 || index >= keys.size()
                ? undef()
                : new RuntimeScalar(keys.get(index)).getList();
    }

    public static RuntimeList SCALAR(RuntimeArray args, int ctx) {
        arity(args, 1, "SCALAR", "");
        HashSpecialVariable.Id mode = mode(args.get(0));
        if (mode == null) return undef();
        return new RuntimeScalar(captureHash(mode).size()).getList();
    }
}
