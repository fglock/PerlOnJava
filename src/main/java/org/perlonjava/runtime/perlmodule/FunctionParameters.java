package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.operators.ModuleOperators;
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
                || code.signatureKeyword == null) {
            return new RuntimeScalar().getList();
        }

        RuntimeHash info = new RuntimeHash();
        List<String> positionalRequired = new ArrayList<>(code.signatureInvocants);
        positionalRequired.addAll(code.signaturePositionalRequired);
        putParameters(info, "_positional_required", positionalRequired, code);
        putParameters(info, "_positional_optional", code.signaturePositionalOptional, code);
        putParameters(info, "_named_required", code.signatureNamedRequired, code);
        putParameters(info, "_named_optional", code.signatureNamedOptional, code);
        info.put("nshift", new RuntimeScalar(code.signatureInvocants.size()));
        info.put("keyword", new RuntimeScalar(code.signatureKeyword != null
                ? code.signatureKeyword
                : code.signatureInvocants.isEmpty() ? "fun" : "method"));
        RuntimeScalar slurpy = code.signatureSlurpyName == null
                ? new RuntimeScalar()
                : parameter(code.signatureSlurpyName, code);
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

    private static void putParameters(
            RuntimeHash info, String key, List<String> names, RuntimeCode code) {
        RuntimeArray values = new RuntimeArray();
        for (String name : names) values.elements.add(parameter(name, code));
        info.put(key, values.createAnonymousReference());
    }

    private static RuntimeScalar parameter(String name, RuntimeCode code) {
        RuntimeHash parameter = new RuntimeHash();
        parameter.put("name", new RuntimeScalar(name));
        parameter.put("type", resolveParameterType(name, code));
        return ReferenceOperators.bless(parameter.createReferenceWithTrackedElements(),
                new RuntimeScalar("Function::Parameters::Param"));
    }

    private static RuntimeScalar resolveParameterType(String parameterName, RuntimeCode code) {
        RuntimeScalar objectType = code.signatureParameterTypeObjects.get(parameterName);
        if (objectType != null) return objectType;
        String typeName = code.signatureParameterTypes.get(parameterName);
        if (typeName == null) return new RuntimeScalar();
        if (typeName.startsWith("moose:")) {
            ModuleOperators.require(new RuntimeScalar("Moose/Util/TypeConstraints.pm"));
            String reifierName = "Moose::Util::TypeConstraints::find_or_create_isa_type_constraint";
            RuntimeScalar reifier = GlobalVariable.getGlobalCodeRefForFreshLookup(reifierName);
            if (reifier == null || reifier.type != RuntimeScalarType.CODE) return new RuntimeScalar();
            RuntimeList result = RuntimeCode.apply(reifier, reifierName,
                    new org.perlonjava.runtime.runtimetypes.RuntimeBase[]{
                            new RuntimeScalar(typeName.substring("moose:".length()))},
                    org.perlonjava.runtime.runtimetypes.RuntimeContextType.SCALAR);
            return result.scalar();
        }
        String fullName = typeName.startsWith("::") ? typeName.substring(2)
                : typeName.contains("::") ? typeName
                : (code.packageName == null ? "main" : code.packageName) + "::" + typeName;
        RuntimeScalar typeFunction = GlobalVariable.getGlobalCodeRefForFreshLookup(fullName);
        if (typeFunction == null || typeFunction.type != RuntimeScalarType.CODE) {
            return new RuntimeScalar();
        }
        RuntimeList result = RuntimeCode.apply(typeFunction, fullName,
                new org.perlonjava.runtime.runtimetypes.RuntimeBase[0],
                org.perlonjava.runtime.runtimetypes.RuntimeContextType.SCALAR);
        return result.scalar();
    }
}
