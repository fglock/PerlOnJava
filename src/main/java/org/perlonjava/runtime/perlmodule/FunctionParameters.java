package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.operators.ReferenceOperators;

import java.util.List;
import java.util.ArrayList;

/** Runtime support for the introspection part of Function::Parameters. */
public final class FunctionParameters extends PerlModuleBase {
    public FunctionParameters() {
        super("Function::Parameters", false);
    }

    public static void initialize() {
        FunctionParameters module = new FunctionParameters();
        try {
            module.registerMethod("info", "info", "$");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Function::Parameters.info implementation", e);
        }
    }

    public static RuntimeList info(RuntimeArray args, int context) {
        if (args.size() == 0) {
            throw new IllegalArgumentException("Usage: Function::Parameters::info(CODEREF)");
        }
        RuntimeScalar codeRef = args.get(0).scalar();
        if (codeRef.type != RuntimeScalarType.CODE || !(codeRef.value instanceof RuntimeCode code)
                || code.signatureSubName == null) {
            throw new IllegalArgumentException("Function::Parameters::info expects a signatured code reference");
        }

        RuntimeHash info = new RuntimeHash();
        List<String> positionalRequired = new ArrayList<>(code.signatureInvocants);
        positionalRequired.addAll(code.signaturePositionalRequired);
        putParameters(info, "_positional_required", positionalRequired);
        putParameters(info, "_positional_optional", code.signaturePositionalOptional);
        putParameters(info, "_named_required", code.signatureNamedRequired);
        putParameters(info, "_named_optional", code.signatureNamedOptional);
        info.put("nshift", new RuntimeScalar(code.signatureInvocants.size()));
        info.put("keyword", new RuntimeScalar(code.signatureKeyword != null
                ? code.signatureKeyword
                : code.signatureInvocants.isEmpty() ? "fun" : "method"));
        RuntimeScalar slurpy = code.signatureSlurpyName == null
                ? new RuntimeScalar()
                : parameter(code.signatureSlurpyName);
        info.put("slurpy", slurpy);
        int min = positionalRequired.size()
                + 2 * code.signatureRequiredNamedParams.size();
        info.put("args_min", new RuntimeScalar(min));
        boolean unbounded = !code.signatureNamedParams.isEmpty() || code.signatureSlurpySigil != null;
        int max = positionalRequired.size() + code.signaturePositionalOptional.size();
        info.put("args_max", new RuntimeScalar(unbounded ? "Inf" : Integer.toString(max)));
        RuntimeScalar object = ReferenceOperators.bless(
                info.createReferenceWithTrackedElements(), new RuntimeScalar("Function::Parameters::Info"));
        return object.getList();
    }

    private static void putParameters(RuntimeHash info, String key, List<String> names) {
        RuntimeArray values = new RuntimeArray();
        for (String name : names) values.elements.add(parameter(name));
        info.put(key, values.createAnonymousReference());
    }

    private static RuntimeScalar parameter(String name) {
        RuntimeHash parameter = new RuntimeHash();
        parameter.put("name", new RuntimeScalar(name));
        parameter.put("type", new RuntimeScalar());
        return ReferenceOperators.bless(parameter.createReferenceWithTrackedElements(),
                new RuntimeScalar("Function::Parameters::Param"));
    }
}
