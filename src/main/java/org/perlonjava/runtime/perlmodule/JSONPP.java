package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * A deliberately narrow native acceleration for JSON::PP's common canonical
 * JSON subset.  JSON::PP.pm performs the observable-option guard; this class
 * is not a general replacement for JSON::PP.
 */
public final class JSONPP extends PerlModuleBase {
    private static final String MODULE = "JSON::PP";
    private static final String BOOLEAN_CLASS = "JSON::PP::Boolean";

    private JSONPP() { super(MODULE, false); }

    public static void initialize() {
        JSONPP module = new JSONPP();
        try {
            module.registerMethod("_perlonjava_encode", null);
            module.registerMethod("_perlonjava_decode", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + MODULE, e);
        }
    }

    public static RuntimeList _perlonjava_encode(RuntimeArray args, int context) {
        RuntimeScalar self = args.get(0);
        RuntimeScalar value = args.get(1);
        int maxDepth = optionInteger(self, "max_depth", 512);
        StringBuilder out = new StringBuilder(128);
        appendValue(out, value, 0, maxDepth, new IdentityHashMap<>());
        return new RuntimeScalar(out.toString()).getList();
    }

    public static RuntimeList _perlonjava_decode(RuntimeArray args, int context) {
        RuntimeScalar self = args.get(0);
        String source = args.get(1).toString();
        int maxDepth = optionInteger(self, "max_depth", 512);
        JsonReader reader = new JsonReader(source, maxDepth);
        RuntimeScalar result = reader.readValue(0);
        reader.skipWhitespace();
        if (!reader.atEnd()) throw new IllegalArgumentException("garbage after JSON object");
        return result.getList();
    }

    private static int optionInteger(RuntimeScalar self, String key, int fallback) {
        if (self != null && self.value instanceof RuntimeHash hash) {
            RuntimeScalar value = hash.elements.get(key);
            if (value != null && value.getDefinedBoolean()) return value.getInt();
        }
        return fallback;
    }

    private static void appendValue(StringBuilder out, RuntimeScalar value, int depth,
                                    int maxDepth, IdentityHashMap<RuntimeBase, Boolean> ancestors) {
        if (value == null || !value.getDefinedBoolean()) { out.append("null"); return; }
        switch (value.type) {
            case INTEGER, DOUBLE -> out.append(value.toString());
            case BOOLEAN -> out.append(value.getBoolean() ? "true" : "false");
            case STRING, BYTE_STRING, VSTRING, DUALVAR -> appendString(out, value.toString());
            case ARRAYREFERENCE -> appendArray(out, (RuntimeArray) value.value, depth, maxDepth, ancestors);
            case HASHREFERENCE -> appendHash(out, (RuntimeHash) value.value, depth, maxDepth, ancestors);
            case REFERENCE -> {
                if (BOOLEAN_CLASS.equals(NameNormalizer.getBlessStr(RuntimeScalarType.blessedId(value)))
                        && value.value instanceof RuntimeScalar booleanValue) {
                    out.append(booleanValue.getLong() == 1 ? "true" : "false");
                } else {
                    throw new IllegalArgumentException("cannot encode reference to scalar");
                }
            }
            default -> throw new IllegalArgumentException("encountered value which JSON can only represent as arrays or hashes");
        }
    }

    private static void appendArray(StringBuilder out, RuntimeArray array, int depth, int maxDepth,
                                    IdentityHashMap<RuntimeBase, Boolean> ancestors) {
        enter(array, depth, maxDepth, ancestors);
        out.append('[');
        for (int i = 0; i < array.size(); i++) {
            if (i != 0) out.append(',');
            appendValue(out, array.get(i), depth + 1, maxDepth, ancestors);
        }
        out.append(']');
        ancestors.remove(array);
    }

    private static void appendHash(StringBuilder out, RuntimeHash hash, int depth, int maxDepth,
                                   IdentityHashMap<RuntimeBase, Boolean> ancestors) {
        enter(hash, depth, maxDepth, ancestors);
        List<String> keys = new ArrayList<>(hash.elements.keySet());
        Collections.sort(keys);
        out.append('{');
        for (int i = 0; i < keys.size(); i++) {
            if (i != 0) out.append(',');
            String key = keys.get(i);
            appendString(out, key);
            out.append(':');
            appendValue(out, hash.elements.get(key), depth + 1, maxDepth, ancestors);
        }
        out.append('}');
        ancestors.remove(hash);
    }

    private static void enter(RuntimeBase value, int depth, int maxDepth,
                              IdentityHashMap<RuntimeBase, Boolean> ancestors) {
        if (depth >= maxDepth) throw new IllegalArgumentException("json text or perl structure exceeds maximum nesting level (max_depth set too low?)");
        if (ancestors.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("encountered circular reference");
    }

    private static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class JsonReader {
        private final String source;
        private final int maxDepth;
        private int position;

        JsonReader(String source, int maxDepth) { this.source = source; this.maxDepth = maxDepth; }
        boolean atEnd() { return position == source.length(); }
        void skipWhitespace() { while (!atEnd() && Character.isWhitespace(source.charAt(position))) position++; }

        RuntimeScalar readValue(int depth) {
            skipWhitespace();
            if (depth > maxDepth) {
                throw new IllegalArgumentException("json text or perl structure exceeds maximum nesting level (max_depth set too low?)");
            }
            if (atEnd()) throw new IllegalArgumentException("malformed JSON string");
            return switch (source.charAt(position)) {
                case '{' -> readObject(depth + 1);
                case '[' -> readArray(depth + 1);
                case '"' -> new RuntimeScalar(readString());
                case 't' -> { consume("true"); yield booleanValue(true); }
                case 'f' -> { consume("false"); yield booleanValue(false); }
                case 'n' -> { consume("null"); yield new RuntimeScalar(); }
                default -> readNumber();
            };
        }

        RuntimeScalar readObject(int depth) {
            position++;
            RuntimeHash hash = new RuntimeHash();
            skipWhitespace();
            if (consumeIf('}')) return hash.createReference();
            while (true) {
                skipWhitespace();
                if (atEnd() || source.charAt(position) != '"') throw new IllegalArgumentException("malformed JSON string");
                String key = readString();
                skipWhitespace(); require(':');
                hash.put(key, readValue(depth));
                skipWhitespace();
                if (consumeIf('}')) return hash.createReference();
                require(',');
            }
        }

        RuntimeScalar readArray(int depth) {
            position++;
            RuntimeArray array = new RuntimeArray();
            skipWhitespace();
            if (consumeIf(']')) return array.createReference();
            while (true) {
                array.elements.add(readValue(depth));
                skipWhitespace();
                if (consumeIf(']')) return array.createReference();
                require(',');
            }
        }

        String readString() {
            require('"');
            StringBuilder out = new StringBuilder();
            while (!atEnd()) {
                char c = source.charAt(position++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw new IllegalArgumentException("malformed JSON string");
                if (c != '\\') { out.append(c); continue; }
                if (atEnd()) throw new IllegalArgumentException("malformed JSON string");
                char escaped = source.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b'); case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                    case 'u' -> out.append(readUnicodeEscape());
                    default -> throw new IllegalArgumentException("malformed JSON string");
                }
            }
            throw new IllegalArgumentException("malformed JSON string");
        }

        char readUnicodeEscape() {
            if (position + 4 > source.length()) throw new IllegalArgumentException("malformed JSON string");
            int code = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(source.charAt(position++), 16);
                if (digit < 0) throw new IllegalArgumentException("malformed JSON string");
                code = (code << 4) | digit;
            }
            return (char) code;
        }

        RuntimeScalar readNumber() {
            int start = position;
            if (consumeIf('-')) { }
            if (consumeIf('0')) { }
            else { digits(); }
            if (consumeIf('.')) digits();
            if (consumeIf('e') || consumeIf('E')) { consumeIf('+'); consumeIf('-'); digits(); }
            String number = source.substring(start, position);
            try {
                if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) return new RuntimeScalar(Long.parseLong(number));
                return new RuntimeScalar(Double.parseDouble(number));
            } catch (NumberFormatException e) { throw new IllegalArgumentException("malformed JSON number", e); }
        }

        void digits() { int start = position; while (!atEnd() && Character.isDigit(source.charAt(position))) position++; if (position == start) throw new IllegalArgumentException("malformed JSON number"); }
        boolean consumeIf(char c) { if (!atEnd() && source.charAt(position) == c) { position++; return true; } return false; }
        void consume(String text) { if (!source.startsWith(text, position)) throw new IllegalArgumentException("malformed JSON string"); position += text.length(); }
        void require(char c) { skipWhitespace(); if (!consumeIf(c)) throw new IllegalArgumentException("malformed JSON string"); }
    }

    private static RuntimeScalar booleanValue(boolean value) {
        RuntimeScalar scalar = new RuntimeScalar(value ? 1 : 0).createReference();
        return ReferenceOperators.bless(scalar, new RuntimeScalar(BOOLEAN_CLASS));
    }
}
