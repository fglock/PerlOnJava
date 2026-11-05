package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java implementation of Scalar::Util::Numeric's grok_number predicates. */
public final class ScalarUtilNumeric extends PerlModuleBase {
    private static final BigInteger UV_MAX = BigInteger.ONE.shiftLeft(Long.SIZE).subtract(BigInteger.ONE);
    private static final Pattern NUMBER = Pattern.compile(
            "^[\\x09-\\x0d ]*[+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|inf(?:inity)?|nan|1\\.#(?:inf|ind))[\\x09-\\x0d ]*$",
            Pattern.CASE_INSENSITIVE);

    public ScalarUtilNumeric() { super("Scalar::Util::Numeric"); }

    public static void initialize() {
        ScalarUtilNumeric module = new ScalarUtilNumeric();
        module.initializeExporter();
        GlobalVariable.getGlobalVariable("Scalar::Util::Numeric::VERSION").set("0.40");
        module.defineExport("EXPORT_OK", "isbig", "isfloat", "isinf", "isint", "isnan", "isneg", "isnum", "isuv");
        module.defineExportTag("all", "isbig", "isfloat", "isinf", "isint", "isnan", "isneg", "isnum", "isuv");
        try {
            module.registerMethod("isnum", "$");
            module.registerMethod("isint", "$");
            module.registerMethod("isuv", "$");
            module.registerMethod("isbig", "$");
            module.registerMethod("isfloat", "$");
            module.registerMethod("isneg", "$");
            module.registerMethod("isinf", "$");
            module.registerMethod("isnan", "$");
            module.registerMethod("uvmax", "");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Scalar::Util::Numeric method", e);
        }
    }

    /** Return the six grok_number classification bits for a scalar's string value. */
    public static int numberFlags(RuntimeScalar scalar) {
        if (scalar == null || scalar.type == org.perlonjava.runtime.runtimetypes.RuntimeScalarType.UNDEF) return 0;
        if (scalar.type == org.perlonjava.runtime.runtimetypes.RuntimeScalarType.TIED_SCALAR) scalar = scalar.tiedFetch();
        String text = scalar.toString();
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.matches()) return 0;
        String value = text.trim();
        boolean negative = value.startsWith("-");
        String unsigned = (value.startsWith("-") || value.startsWith("+")) ? value.substring(1) : value;
        String lower = unsigned.toLowerCase(java.util.Locale.ROOT);
        if (isWindowsSpecialNumber(lower)) {
            return lower.endsWith("ind") || lower.endsWith("nan")
                    ? 36 : 20 | (negative ? 8 : 0);
        }
        if (lower.equals("inf") || lower.equals("infinity")) return 16 | (negative ? 8 : 0) | 4;
        if (lower.equals("nan")) return 32 | 4;

        boolean exponent = value.indexOf('e') >= 0 || value.indexOf('E') >= 0;
        boolean fraction = value.indexOf('.') >= 0;
        boolean notInteger = exponent || fraction;
        int flags = (negative ? 8 : 0) | (notInteger ? 4 : 0);
        if (notInteger && exponent) return flags;
        String digits = unsigned;
        if (fraction) digits = digits.substring(0, digits.indexOf('.'));
        if (digits.isEmpty()) digits = "0";
        try {
            BigInteger integer = new BigInteger(digits);
            if (integer.compareTo(UV_MAX) <= 0) flags |= 1;
            else if (!notInteger) flags |= 2;
        } catch (NumberFormatException ignored) {
            // A syntactically valid floating value can exceed Java's integer range.
        }
        if (notInteger && (flags & 1) == 0) return flags;
        return flags;
    }

    private static boolean isWindowsSpecialNumber(String value) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return false;
        return value.equals("1.#inf") || value.equals("1.#ind");
    }

    private static RuntimeList predicate(RuntimeArray args, int mask, boolean exactInteger) {
        int flags = numberFlags(args.get(0));
        int result = exactInteger ? (flags == 1 ? 1 : flags == 9 ? -1 : 0) : ((flags & mask) != 0 ? 1 : 0);
        return new RuntimeScalar(result).getList();
    }
    public static RuntimeList isnum(RuntimeArray args, int ctx) { return new RuntimeScalar(numberFlags(args.get(0))).getList(); }
    public static RuntimeList isint(RuntimeArray args, int ctx) { return predicate(args, 0, true); }
    public static RuntimeList isuv(RuntimeArray args, int ctx) { return predicate(args, 1, false); }
    public static RuntimeList isbig(RuntimeArray args, int ctx) { return predicate(args, 2, false); }
    public static RuntimeList isfloat(RuntimeArray args, int ctx) { return predicate(args, 4, false); }
    public static RuntimeList isneg(RuntimeArray args, int ctx) { return predicate(args, 8, false); }
    public static RuntimeList isinf(RuntimeArray args, int ctx) { return predicate(args, 16, false); }
    public static RuntimeList isnan(RuntimeArray args, int ctx) { return predicate(args, 32, false); }
    public static RuntimeList uvmax(RuntimeArray args, int ctx) { return new RuntimeScalar(UV_MAX).getList(); }
}
