package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.math.BigInteger;

/** Java replacement for Scalar::Type's small XS scalar-flag probe. */
public class ScalarType extends PerlModuleBase {
    public ScalarType() {
        super("Scalar::Type", false);
    }

    public static void initialize() {
        ScalarType module = new ScalarType();
        try {
            module.registerMethod("_scalar_type", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Scalar::Type method", e);
        }
    }

    public static RuntimeList _scalar_type(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.get(0);
        if (scalar.type == RuntimeScalarType.READONLY_SCALAR) {
            scalar = (RuntimeScalar) scalar.value;
        }
        String type = switch (scalar.type) {
            case RuntimeScalarType.INTEGER -> "INTEGER";
            case RuntimeScalarType.DOUBLE -> "NUMBER";
            case RuntimeScalarType.STRING, RuntimeScalarType.BYTE_STRING ->
                    numifiedStringType(scalar);
            default -> "SCALAR";
        };
        return new RuntimeScalar(type).getList();
    }

    private static String numifiedStringType(RuntimeScalar scalar) {
        if (!scalar.numericContextSeen) {
            return "SCALAR";
        }
        String text = scalar.toString();
        try {
            BigInteger integer = new BigInteger(text);
            if (integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                    && integer.bitLength() <= 64
                    && integer.toString().equals(text)) {
                return "INTEGER";
            }
        } catch (NumberFormatException ignored) {
            // Try the NV form below.
        }
        try {
            double number = Double.parseDouble(text);
            if (Double.isFinite(number) && canonicalDouble(number).equals(text)) {
                return "NUMBER";
            }
        } catch (NumberFormatException ignored) {
            // Non-numeric strings retain scalar type.
        }
        return "SCALAR";
    }

    private static String canonicalDouble(double value) {
        String text = Double.toString(value);
        if (text.endsWith(".0")) {
            return text.substring(0, text.length() - 2);
        }
        return text;
    }
}
