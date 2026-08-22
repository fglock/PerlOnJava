package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.StringNode;
import org.perlonjava.runtime.runtimetypes.GlobalContext;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Parser-side support for non-numeric {@code overload::constant} handlers. */
final class ConstantOverloadParser {
    private static final AtomicInteger HANDLER_COUNTER = new AtomicInteger();

    private ConstantOverloadParser() {
    }

    /**
     * Wrap a regex constant segment in its lexically active {@code qr} handler.
     *
     * <p>Perl calls the {@code qr} handler with the raw segment, cooked segment,
     * and the constant kind {@code qq}.  It must execute while parsing, not as
     * part of the emitted expression: ordinary source later in the same scope
     * can change package variables that the handler consults, whereas Perl
     * constant overloading observes their compile-time values.</p>
     */
    static Node wrapRegexSegment(StringNode cooked, String raw, int tokenIndex,
                                 boolean utf8Source) {
        RuntimeScalar handler = findHandler("qr");
        if (handler == null) {
            return cooked;
        }

        RuntimeHash hints = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
        RuntimeScalar saved = hints.elements.remove("qr");
        RuntimeScalar result;
        try {
            RuntimeArray args = new RuntimeArray();
            args.elements.add(materializeRawSource(raw, utf8Source));
            args.elements.add(materializeCooked(cooked, utf8Source));
            args.elements.add(new RuntimeScalar("qq"));
            result = RuntimeCode.apply(handler, args, RuntimeContextType.SCALAR).scalar();
        } finally {
            if (saved != null) {
                hints.elements.put("qr", saved);
            }
        }

        int id = HANDLER_COUNTER.incrementAndGet();
        String varName = "overload::__poj_regex_const_value_" + id;
        GlobalVariable.getGlobalVariable(varName).set(result);
        return new OperatorNode("$", new IdentifierNode(varName, tokenIndex), tokenIndex);
    }

    /** Perl exposes regex source spelling as octets, even under {@code use utf8}. */
    private static RuntimeScalar materializeRawSource(String raw, boolean utf8Source) {
        String value = raw;
        if (utf8Source && raw.codePoints().anyMatch(codePoint -> codePoint > 127)) {
            value = new String(raw.getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.ISO_8859_1);
        }
        RuntimeScalar scalar = new RuntimeScalar(value);
        scalar.type = RuntimeScalarType.BYTE_STRING;
        return scalar;
    }

    /** Mirror literal emitters so the cooked callback argument keeps its UTF-8 flag. */
    private static RuntimeScalar materializeCooked(StringNode cooked,
                                                    boolean utf8Source) {
        RuntimeScalar scalar = new RuntimeScalar(cooked.value);
        boolean asciiOnly = cooked.value.codePoints().allMatch(codePoint -> codePoint <= 127);
        boolean hasWideCharacters = cooked.value.codePoints()
                .anyMatch(codePoint -> codePoint > 255);
        if (!cooked.forceUnicodeString
                && (cooked.forceByteString || asciiOnly
                    || (!hasWideCharacters && !utf8Source))) {
            scalar.type = RuntimeScalarType.BYTE_STRING;
        }
        return scalar;
    }

    private static RuntimeScalar findHandler(String category) {
        RuntimeHash hints = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
        if (hints == null || hints.elements.isEmpty()) {
            return null;
        }
        RuntimeScalar handler = hints.elements.get(category);
        if (handler == null) {
            return null;
        }
        if (handler.type == RuntimeScalarType.CODE) {
            return handler;
        }
        if (handler.type == RuntimeScalarType.REFERENCE
                && handler.value instanceof RuntimeScalar referent
                && referent.type == RuntimeScalarType.CODE) {
            return handler;
        }
        return null;
    }
}
