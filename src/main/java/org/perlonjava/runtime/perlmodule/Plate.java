package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.DynamicVariableManager;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeGlob;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.util.HashMap;
import java.util.Map;

/** Java replacement for Plate's small XS pad-import helper. */
public class Plate extends PerlModuleBase {

    public Plate() {
        super("Plate", false);
    }

    public static void initialize() {
        Plate module = new Plate();
        try {
            module.registerMethod("_local_vars", "localVars", null);
            module.registerMethod("_local_args", "localArgs", null);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public static RuntimeList localVars(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeList();
        String packageName = args.get(0).toString();
        RuntimeHash variables = args.get(1).hashDerefRaw();
        Map<String, RuntimeGlob> localized = new HashMap<>();

        for (Map.Entry<String, RuntimeScalar> entry : variables.elements.entrySet()) {
            String suppliedName = entry.getKey();
            // Go through FETCH semantics: hash slots may be proxy wrappers even
            // though Perl-level ref() sees the referenced SCALAR/ARRAY value.
            RuntimeScalar value = variables.get(suppliedName);
            boolean unblessedReference = RuntimeScalarType.isReference(value)
                    && RuntimeScalarType.blessedId(value) == 0;
            if (unblessedReference) {
                RuntimeGlob glob = localized.computeIfAbsent(
                        withoutSigil(suppliedName),
                        name -> localizeGlob(packageName, name));
                installReferenceAlias(glob, value);
            } else {
                installLocalizedConstant(packageName, suppliedName, value);
            }
        }
        return new RuntimeList();
    }

    public static RuntimeList localArgs(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeList();
        String packageName = args.get(0).toString();
        RuntimeHash variables = args.get(1).hashDerefRaw();
        Map<String, RuntimeGlob> localized = new HashMap<>();
        for (Map.Entry<String, RuntimeScalar> entry : variables.elements.entrySet()) {
            RuntimeGlob glob = localized.computeIfAbsent(
                    withoutSigil(entry.getKey()),
                    name -> localizeGlob(packageName, name));
            RuntimeScalar value = variables.get(entry.getKey());
            if (value.type == RuntimeScalarType.ARRAYREFERENCE
                    || value.type == RuntimeScalarType.HASHREFERENCE
                    || value.type == RuntimeScalarType.CODE
                    || value.type == RuntimeScalarType.GLOBREFERENCE) {
                installReferenceAlias(glob, value);
            } else {
                installReferenceAlias(glob, value.createReference());
            }
        }
        return new RuntimeList();
    }

    private static RuntimeGlob localizeGlob(String packageName, String suppliedName) {
        String fullName = packageName + "::" + withoutSigil(suppliedName);
        return DynamicVariableManager.pushLocalVariable(GlobalVariable.getGlobalIO(fullName));
    }

    private static void installLocalizedConstant(
            String packageName, String suppliedName, RuntimeScalar value) {
        // Perl retains enough of a localized constant CV for subsequently
        // compiled templates to resolve and inline it. Keep the Java-backed CV
        // in the stash; template constants are immutable snapshots by design.
        String fullName = packageName + "::" + withoutSigil(suppliedName);
        RuntimeCode constant = new RuntimeCode("", null);
        constant.constantValue = value.getList();
        constant.packageName = "constant";
        constant.subName = "__ANON__";
        GlobalVariable.defineGlobalCodeRef(fullName).set(new RuntimeScalar(constant));
        GlobalVariable.aliasGlobalVariable(fullName, value);
        GlobalVariable.setGlobalPseudoConstant(fullName, value);
    }

    private static void installReferenceAlias(RuntimeGlob glob, RuntimeScalar reference) {
        String name = glob.globName;
        switch (reference.type) {
            case RuntimeScalarType.REFERENCE -> {
                    GlobalVariable.aliasGlobalVariable(name, reference.scalarDeref());
                    GlobalVariable.declareGlobalVariable(name);
            }
            case RuntimeScalarType.ARRAYREFERENCE -> {
                    GlobalVariable.aliasGlobalArray(name, reference.arrayDeref());
                    GlobalVariable.declareGlobalArray(name);
            }
            case RuntimeScalarType.HASHREFERENCE -> {
                    GlobalVariable.aliasGlobalHash(name, reference.hashDerefRaw());
                    GlobalVariable.declareGlobalHash(name);
            }
            default -> glob.set(reference);
        }
    }

    private static String withoutSigil(String name) {
        if (name != null && !name.isEmpty() && "$@%&*".indexOf(name.charAt(0)) >= 0) {
            return name.substring(1);
        }
        return name;
    }
}
