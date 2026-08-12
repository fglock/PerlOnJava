package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Java implementation of Convert::Bencode_XS. */
public final class ConvertBencode_XS extends PerlModuleBase {
    public ConvertBencode_XS() {
        super("Convert::Bencode_XS", false);
    }

    public static void initialize() {
        ConvertBencode_XS module = new ConvertBencode_XS();
        try {
            module.registerMethod("bencode", "bencode", "$");
            module.registerMethod("bdecode", "bdecode", "$");
            module.registerMethod("cleanse", "cleanse", "$");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Convert::Bencode_XS method", e);
        }
    }

    public static RuntimeList bencode(RuntimeArray args, int ctx) {
        StringBuilder encoded = new StringBuilder();
        encode(encoded, args.get(0), coerce());
        return new RuntimeScalar(encoded.toString()).getList();
    }

    private static void encode(StringBuilder out, RuntimeScalar value, boolean coerce) {
        if (value.type == RuntimeScalarType.ARRAYREFERENCE) {
            out.append('l');
            RuntimeArray array = (RuntimeArray) value.value;
            for (RuntimeScalar element : array.elements) encode(out, element, coerce);
            out.append('e');
            return;
        }
        if (value.type == RuntimeScalarType.HASHREFERENCE) {
            out.append('d');
            RuntimeHash hash = (RuntimeHash) value.value;
            List<String> keys = new ArrayList<>(hash.elements.keySet());
            keys.sort(Comparator.comparing(ConvertBencode_XS::bytes,
                    ConvertBencode_XS::compareUnsigned));
            for (String key : keys) {
                appendString(out, key);
                encode(out, hash.elements.get(key), coerce);
            }
            out.append('e');
            return;
        }
        if (value.value instanceof RuntimeBase) {
            throw new IllegalArgumentException("Cannot serialize this kind of reference: " + value);
        }

        String text = value.toString();
        boolean nativeInteger = value.type == RuntimeScalarType.INTEGER;
        if (nativeInteger || (coerce && isCanonicalInteger(text))) {
            if (text.startsWith("+")) text = text.substring(1);
            out.append('i').append(text).append('e');
        } else {
            appendString(out, text);
        }
    }

    private static void appendString(StringBuilder out, String value) {
        out.append(bytes(value).length).append(':').append(value);
    }

    private static boolean isCanonicalInteger(String value) {
        if (!value.matches("[+-]?[0-9]+")) return false;
        int start = value.startsWith("+") || value.startsWith("-") ? 1 : 0;
        String digits = value.substring(start);
        return digits.equals("0") || !digits.startsWith("0");
    }

    private static boolean coerce() {
        return GlobalVariable.getGlobalVariable("Convert::Bencode_XS::COERCE").getBoolean();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int cmp = Integer.compare(left[i] & 0xff, right[i] & 0xff);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(left.length, right.length);
    }

    public static RuntimeList bdecode(RuntimeArray args, int ctx) {
        RuntimeScalar input = args.get(0);
        if (input.type != RuntimeScalarType.STRING && input.type != RuntimeScalarType.BYTE_STRING) {
            throw new IllegalArgumentException("bdecode only accepts scalar strings");
        }
        Decoder decoder = new Decoder(input.toString());
        RuntimeScalar result = decoder.value();
        if (!decoder.atEnd()) decoder.fail("bad format");
        return result.getList();
    }

    public static RuntimeList cleanse(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.get(0);
        try {
            scalar.set(new RuntimeScalar(new BigInteger(scalar.toString())));
        } catch (NumberFormatException e) {
            scalar.set(scalar.getLong());
        }
        return new RuntimeList();
    }

    private static final class Decoder {
        private final String input;
        private int position;

        private Decoder(String input) { this.input = input; }
        private boolean atEnd() { return position == input.length(); }

        private RuntimeScalar value() {
            if (atEnd()) return fail("overflow");
            char token = input.charAt(position);
            if (token == 'i') return integer();
            if (token == 'l') return list();
            if (token == 'd') return dictionary();
            if (token >= '0' && token <= '9') return string();
            return fail("bad format");
        }

        private RuntimeScalar integer() {
            int start = ++position;
            int end = input.indexOf('e', position);
            if (end < 0) return fail("overflow");
            String number = input.substring(start, end);
            if (!number.matches("[+-]?[0-9]+")) return fail("invalid number");
            position = end + 1;
            return coerce() ? new RuntimeScalar(number) : numeric(number);
        }

        private RuntimeScalar numeric(String number) {
            try { return new RuntimeScalar(new BigInteger(number)); }
            catch (NumberFormatException e) { return fail("invalid number"); }
        }

        private RuntimeScalar string() {
            int colon = input.indexOf(':', position);
            if (colon < 0) return fail("overflow");
            String lengthText = input.substring(position, colon);
            if (!lengthText.matches("[0-9]+")) return fail("invalid number");
            int length;
            try { length = Integer.parseInt(lengthText); }
            catch (NumberFormatException e) { return fail("invalid number"); }
            position = colon + 1;
            if (position + length > input.length()) return fail("overflow");
            String result = input.substring(position, position + length);
            position += length;
            return new RuntimeScalar(result);
        }

        private RuntimeScalar list() {
            position++;
            RuntimeArray result = new RuntimeArray();
            while (!atEnd() && input.charAt(position) != 'e') result.push(value());
            if (atEnd()) return fail("bad format");
            position++;
            return result.createReference();
        }

        private RuntimeScalar dictionary() {
            position++;
            RuntimeHash result = new RuntimeHash();
            while (!atEnd() && input.charAt(position) != 'e') {
                if (!Character.isDigit(input.charAt(position))) return fail("dictionary keys must be strings");
                String key = string().toString();
                if (atEnd() || input.charAt(position) == 'e') return fail("dictionary key with no value");
                result.put(key, value());
            }
            if (atEnd()) return fail("bad format");
            position++;
            return result.createReference();
        }

        private <T> T fail(String message) {
            throw new IllegalArgumentException("bdecode error: " + message + ": pos "
                    + position + ", " + input);
        }
    }
}
