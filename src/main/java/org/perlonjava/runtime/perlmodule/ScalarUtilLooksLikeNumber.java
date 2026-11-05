package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.runtimetypes.DualVar;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.Overload;
import org.perlonjava.runtime.runtimetypes.OverloadContext;

/** Java implementation of the raw looks_like_number() API result. */
public final class ScalarUtilLooksLikeNumber extends PerlModuleBase {
    public ScalarUtilLooksLikeNumber() { super("Scalar::Util::LooksLikeNumber"); }

    public static void initialize() {
        ScalarUtilLooksLikeNumber module = new ScalarUtilLooksLikeNumber();
        module.initializeExporter();
        GlobalVariable.getGlobalVariable("Scalar::Util::LooksLikeNumber::VERSION").set("1.39.2");
        module.defineExport("EXPORT_OK", "looks_like_number");
        try {
            module.registerMethod("looks_like_number", "looks_like_number", "$");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Scalar::Util::LooksLikeNumber method", e);
        }
    }

    public static RuntimeList looks_like_number(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.get(0);
        while (scalar.type == RuntimeScalarType.READONLY_SCALAR) scalar = (RuntimeScalar) scalar.value;
        if (scalar.type == RuntimeScalarType.TIED_SCALAR) scalar = scalar.tiedFetch();
        if ((scalar.type & 0x8000) != 0 && OverloadContext.hasDirectOverload(scalar, "(0+")) {
            scalar = Overload.numify(scalar);
        }
        int result;
        switch (scalar.type) {
            // Perl's looks_like_number() returns the raw SV IOK/NOK flag
            // values for numeric scalars, unlike grok_number()'s parse flags.
            case RuntimeScalarType.INTEGER -> result = 4352;
            case RuntimeScalarType.DOUBLE -> result = 8704;
            case RuntimeScalarType.BOOLEAN -> result = Boolean.TRUE.equals(scalar.value) ? 4352 : 0;
            case RuntimeScalarType.DUALVAR -> {
                RuntimeScalar numeric = ((DualVar) scalar.value).numericValue();
                result = numeric.type == RuntimeScalarType.DOUBLE ? 8704 : 4352;
            }
            case RuntimeScalarType.STRING, RuntimeScalarType.BYTE_STRING, RuntimeScalarType.VSTRING ->
                    result = ScalarUtilNumeric.numberFlags(scalar);
            default -> result = 0;
        }
        return new RuntimeScalar(result).getList();
    }
}
