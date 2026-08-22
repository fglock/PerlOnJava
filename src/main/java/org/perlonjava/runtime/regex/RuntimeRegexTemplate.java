package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.operators.StringOperators;
import org.perlonjava.runtime.runtimetypes.Overload;
import org.perlonjava.runtime.runtimetypes.OverloadContext;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.perlmodule.Utf8;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/** Runtime interpolation result that keeps executable regex callbacks out of strings. */
public final class RuntimeRegexTemplate {
    private static final char SLOT_START = '\u001e';
    private static final char SLOT_END = '\u001f';
    private final String pattern;
    private final List<RuntimeRegexCallback> callbacks;
    private final boolean byteBackedPattern;

    private record CalloutSlot(int start, int end, char kind, int id) {}

    /**
     * Deferred array interpolation. Keeping the joined operands separate until
     * the enclosing regex template is assembled lets a blessed element's dot
     * overload observe the complete left-hand pattern, as Perl does.
     */
    private record JoinedParts(List<RuntimeScalar> parts) {
        @Override
        public String toString() {
            StringBuilder joined = new StringBuilder();
            for (RuntimeScalar part : parts) joined.append(part);
            return joined.toString();
        }
    }

    record MaskedCallouts(String pattern, String syntheticPrefix,
                          List<String> placeholders, List<String> markers) {
        String restore(String compiledPattern) {
            String restored = compiledPattern;
            if (!syntheticPrefix.isEmpty() && restored.startsWith(syntheticPrefix)) {
                restored = restored.substring(syntheticPrefix.length());
            }
            for (int i = 0; i < placeholders.size(); i++) {
                restored = restored.replace(placeholders.get(i), markers.get(i));
            }
            return restored;
        }
    }

    private RuntimeRegexTemplate(String pattern, List<RuntimeRegexCallback> callbacks,
                                 boolean byteBackedPattern) {
        this.pattern = pattern;
        this.callbacks = List.copyOf(callbacks);
        this.byteBackedPattern = byteBackedPattern;
    }

    public static RuntimeScalar build(RuntimeList parts) {
        parts = materializeTiedParts(flattenJoinedParts(parts));
        if (parts.elements.size() == 1) {
            RuntimeScalar only = parts.elements.getFirst().scalar();
            // A lone interpolation must retain its runtime type so qr
            // overloading and an already-compiled regex remain observable.
            // Only a parser-created callback needs a new template skeleton.
            if (!(only.value instanceof RuntimeRegexCallback)) {
                RuntimeScalar overloaded = resolveLoneRegexOverload(only);
                return overloaded == null ? only : overloaded;
            }
        }
        boolean hasBlessedPart = false;
        for (RuntimeBase part : parts.elements) {
            if (RuntimeScalarType.blessedId(part.scalar()) != 0) {
                hasBlessedPart = true;
                break;
            }
        }
        if (hasBlessedPart) {
            // Overload dispatch is complete after this pass. A dot overload is
            // allowed to return another blessed value; assemble that result
            // without dispatching the same interpolation a second time.
            parts = resolveBlessedParts(parts);
        }
        StringBuilder pattern = new StringBuilder();
        List<RuntimeRegexCallback> callbacks = new ArrayList<>();
        boolean tainted = false;
        boolean byteBackedPattern = true;
        for (RuntimeBase part : parts.elements) {
            RuntimeScalar scalar = part.scalar();
            tainted |= scalar.isTainted();
            byteBackedPattern &= isByteCompatiblePatternPart(scalar);
            if (scalar.value instanceof RuntimeRegexCallback callback) {
                int id = callbacks.size();
                callbacks.add(callback);
                if (callback.kind == RuntimeRegexCallback.Kind.CONDITION) {
                    appendSlot(pattern, 'C', id);
                } else if (callback.kind == RuntimeRegexCallback.Kind.DYNAMIC) {
                    appendSlot(pattern, 'D', id);
                } else {
                    appendSlot(pattern, 'B', id);
                }
            } else if (scalar.value instanceof RuntimeRegex regex
                    && !regex.executableCallbacks.isEmpty()) {
                appendEmbeddedRegex(pattern, callbacks,
                        regex.toExecutableString(), regex.executableCallbacks);
            } else if (scalar.value instanceof RuntimeRegexTemplate template) {
                appendEmbeddedRegex(pattern, callbacks,
                        template.pattern, template.callbacks);
            } else {
                appendUntrusted(pattern, scalar.toString());
            }
        }
        RuntimeScalar result = callbacks.isEmpty()
                ? patternScalar(collapseSlotEscapes(pattern.toString()), byteBackedPattern)
                : new RuntimeScalar(new RuntimeRegexTemplate(
                        pattern.toString(), callbacks, byteBackedPattern));
        result.tainted = tainted;
        return result;
    }

    private static boolean isByteCompatiblePatternPart(RuntimeScalar scalar) {
        if (scalar.value instanceof RuntimeRegexCallback) return true;
        if (scalar.value instanceof RuntimeRegex regex) return regex.isPatternByteBacked();
        if (scalar.value instanceof RuntimeRegexTemplate template) return template.byteBackedPattern;
        return !Utf8.isUtf8(scalar);
    }

    static RuntimeScalar patternScalar(String pattern, boolean byteBackedPattern) {
        return byteBackedPattern
                ? new RuntimeScalar(pattern.getBytes(StandardCharsets.ISO_8859_1))
                : new RuntimeScalar(pattern);
    }

    private static RuntimeScalar resolveLoneRegexOverload(RuntimeScalar scalar) {
        int blessId = RuntimeScalarType.blessedId(scalar);
        if (blessId >= 0) return null;
        OverloadContext overload = OverloadContext.prepare(blessId);
        if (overload == null) return null;

        RuntimeScalar qr = resolveQrOverload(overload, scalar);
        if (qr != null) return qr;
        RuntimeScalar stringified = overload.tryOverload("(\"\"", unaryOverloadArguments(scalar));
        if (stringified != null) return resolveStringificationResult(stringified);
        if (!overload.allowsFallbackAutogen()) {
            throw new PerlCompilerException("Operation \"qr\": no method found,");
        }
        return null;
    }

    private static RuntimeList resolveBlessedParts(RuntimeList parts) {
        RuntimeList resolved = new RuntimeList();
        resolved.add(new RuntimeScalar(""));

        for (RuntimeBase part : parts.elements) {
            RuntimeScalar scalar = part.scalar();
            int blessId = RuntimeScalarType.blessedId(scalar);
            if (blessId >= 0) {
                // A previous dot overload may have returned a blessed scalar
                // that is now the complete left-hand interpolation result.
                // Continue the binary overload chain with the original right
                // operand instead of appending and stringifying it later.  In
                // particular, a compiled qr// must reach the overload as the
                // same REGEXP value, not as its display string.
                if (resolved.elements.size() == 1) {
                    RuntimeScalar left = resolved.elements.getFirst().scalar();
                    int leftBlessId = RuntimeScalarType.blessedId(left);
                    if (leftBlessId < 0) {
                        RuntimeScalar right = operandForConcatOverload(scalar);
                        RuntimeScalar concatenated =
                                OverloadContext.tryTwoArgumentOverloadDirect(
                                        left, right, leftBlessId,
                                        RuntimeScalarType.blessedId(right), "(.");
                        if (concatenated != null) {
                            resolved = new RuntimeList(concatenated);
                            continue;
                        }
                    }
                }
                resolved.add(scalar);
                continue;
            }

            OverloadContext overload = OverloadContext.prepare(blessId);
            RuntimeScalar qr = overload == null ? null : resolveQrOverload(overload, scalar);
            if (qr != null) {
                resolved.add(qr);
                continue;
            }

            RuntimeScalar left = concatenateForOverload(resolved);
            RuntimeScalar concatenated = OverloadContext.tryTwoArgumentOverloadDirect(
                    left, scalar, RuntimeScalarType.blessedId(left), blessId, "(.");
            if (concatenated != null) {
                resolved = new RuntimeList(concatenated);
                continue;
            }

            RuntimeScalar stringified = overload == null ? null
                    : overload.tryOverload("(\"\"", unaryOverloadArguments(scalar));
            if (stringified != null) {
                resolved.add(resolveStringificationResult(stringified));
                continue;
            }

            resolved = new RuntimeList(StringOperators.stringConcat(left, scalar));
        }
        return resolved;
    }

    /**
     * A dot overload receives the textual pattern assembled so far. Executable
     * callbacks on that side consequently become runtime source; only a qr or
     * stringification overload that directly returns REGEXP keeps provenance.
     */
    private static RuntimeScalar concatenateForOverload(RuntimeList parts) {
        RuntimeScalar concatenated = new RuntimeScalar("");
        for (RuntimeBase part : parts.elements) {
            RuntimeScalar scalar = operandForConcatOverload(part.scalar());
            concatenated = StringOperators.stringConcat(concatenated, scalar);
        }
        return concatenated;
    }

    private static RuntimeScalar operandForConcatOverload(RuntimeScalar scalar) {
        if (scalar.value instanceof RuntimeRegexCallback callback) {
            return new RuntimeScalar(callback.source == null ? "" : callback.source);
        }
        return scalar;
    }

    private static RuntimeScalar resolveQrOverload(OverloadContext overload,
                                                    RuntimeScalar scalar) {
        RuntimeScalar qr = overload.tryOverload("(qr", new RuntimeArray(scalar));
        if (qr != null && qr.type != RuntimeScalarType.REGEX) {
            throw new PerlCompilerException("Overloaded qr did not return a REGEXP");
        }
        return qr;
    }

    private static RuntimeScalar resolveStringificationResult(RuntimeScalar result) {
        RuntimeScalar current = result;
        for (int depth = 0; depth < 10 && RuntimeScalarType.blessedId(current) < 0; depth++) {
            RuntimeScalar next = Overload.stringify(current);
            if (next == current) break;
            current = next;
        }
        return current;
    }

    private static RuntimeArray unaryOverloadArguments(RuntimeScalar scalar) {
        return new RuntimeArray(scalar, scalarUndef, new RuntimeScalar(""));
    }

    /** Preserve callback-bearing qr values while an array is joined for interpolation. */
    public static RuntimeScalar buildJoined(RuntimeScalar separator,
                                            List<RuntimeScalar> elements) {
        List<RuntimeScalar> parts = new ArrayList<>();
        boolean tainted = separator.isTainted();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) parts.add(separator);
            RuntimeScalar element = elements.get(i);
            parts.add(element);
            tainted |= element.isTainted();
        }
        RuntimeScalar result = new RuntimeScalar(new JoinedParts(List.copyOf(parts)));
        result.tainted = tainted;
        return result;
    }

    public static boolean hasExecutableValue(RuntimeScalar scalar) {
        return scalar != null && (scalar.value instanceof JoinedParts
                || scalar.value instanceof RuntimeRegexTemplate
                || scalar.value instanceof RuntimeRegex regex
                && !regex.executableCallbacks.isEmpty());
    }

    private static RuntimeList flattenJoinedParts(RuntimeList input) {
        RuntimeList flattened = new RuntimeList();
        for (RuntimeBase part : input.elements) {
            RuntimeScalar scalar = part.scalar();
            if (scalar.value instanceof JoinedParts joined) {
                for (RuntimeScalar joinedPart : joined.parts) flattened.add(joinedPart);
            } else {
                flattened.add(part);
            }
        }
        return flattened;
    }

    /** Resolve each tied interpolation exactly once before any type inspection. */
    private static RuntimeList materializeTiedParts(RuntimeList input) {
        RuntimeList materialized = new RuntimeList();
        for (RuntimeBase part : input.elements) {
            RuntimeScalar scalar = part.scalar();
            materialized.add(scalar.type == RuntimeScalarType.TIED_SCALAR
                    ? scalar.tiedFetch() : part);
        }
        return materialized;
    }

    private static void appendEmbeddedRegex(StringBuilder pattern,
                                            List<RuntimeRegexCallback> callbacks,
                                            String embeddedPattern,
                                            List<RuntimeRegexCallback> embeddedCallbacks) {
        int offset = callbacks.size();
        int cursor = 0;
        for (CalloutSlot slot : calloutSlots(embeddedPattern)) {
            pattern.append(embeddedPattern, cursor, slot.start());
            int oldId = slot.id();
            if (oldId < 0 || oldId >= embeddedCallbacks.size()) {
                throw new IllegalArgumentException("Invalid embedded regex callout ID " + oldId);
            }
            appendSlot(pattern, slot.kind(), offset + oldId);
            cursor = slot.end();
        }
        pattern.append(embeddedPattern, cursor, embeddedPattern.length());
        callbacks.addAll(embeddedCallbacks);
    }

    private static void appendSlot(StringBuilder pattern, char kind, int id) {
        pattern.append(SLOT_START).append(kind).append(id).append(SLOT_END);
    }

    private static void appendUntrusted(StringBuilder pattern, String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            pattern.append(ch);
            if (ch == SLOT_START) pattern.append(ch);
        }
    }

    private static List<CalloutSlot> calloutSlots(String executablePattern) {
        List<CalloutSlot> slots = new ArrayList<>();
        for (int i = 0; i < executablePattern.length(); i++) {
            if (executablePattern.charAt(i) != SLOT_START) continue;
            if (i + 1 < executablePattern.length()
                    && executablePattern.charAt(i + 1) == SLOT_START) {
                i++;
                continue;
            }
            int start = i;
            if (++i >= executablePattern.length()) {
                throw malformedCalloutSlot();
            }
            char kind = executablePattern.charAt(i);
            if (kind != 'B' && kind != 'C' && kind != 'D') {
                throw malformedCalloutSlot();
            }
            int digitStart = ++i;
            while (i < executablePattern.length()
                    && executablePattern.charAt(i) >= '0'
                    && executablePattern.charAt(i) <= '9') i++;
            if (i == digitStart || i >= executablePattern.length()
                    || executablePattern.charAt(i) != SLOT_END) {
                throw malformedCalloutSlot();
            }
            try {
                slots.add(new CalloutSlot(start, i + 1, kind,
                        Integer.parseInt(executablePattern.substring(digitStart, i))));
            } catch (NumberFormatException ignored) {
                throw malformedCalloutSlot();
            }
        }
        return slots;
    }

    private static IllegalArgumentException malformedCalloutSlot() {
        return new IllegalArgumentException("Malformed internal regex callout slot");
    }

    String pattern() {
        return pattern;
    }

    List<RuntimeRegexCallback> callbacks() {
        return callbacks;
    }

    boolean byteBackedPattern() {
        return byteBackedPattern;
    }

    /**
     * Hide parser-created callout markers while runtime-interpolated Perl
     * source is compiled. The source parser must see raw {@code (?{...})} and
     * {@code (??{...})} groups, but must not reinterpret trusted marker IDs as
     * Perl expressions.
     */
    MaskedCallouts maskCallouts() {
        StringBuilder masked = new StringBuilder();
        StringBuilder syntheticPrefix = new StringBuilder();
        List<String> placeholders = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        int cursor = 0;
        int id = 0;
        for (CalloutSlot slot : calloutSlots(pattern)) {
            int maskStart = slot.kind() == 'C' && slot.start() > 0
                    && pattern.charAt(slot.start() - 1) == '('
                    ? slot.start() - 1 : slot.start();
            masked.append(pattern, cursor, maskStart);
            String token = "POJ_INTERNAL_CALLOUT_PLACEHOLDER_" + id++ + "_END";
            while (pattern.contains(token)) token += "_";
            // In (?(?{...})yes|no), the marker itself is the condition. Use a
            // temporary named-capture condition so the runtime regex parser
            // preserves the branches without interpreting the trusted marker
            // as Perl source. The empty capture is removed by restore().
            boolean callbackCondition = slot.kind() == 'C';
            String placeholder = token;
            if (callbackCondition) {
                String name = "POJ_INTERNAL_CALLOUT_CONDITION_" + id;
                while (pattern.contains(name)) name += "_";
                syntheticPrefix.append("(?<").append(name).append(">)");
                placeholder = "(<" + name + ">)";
            }
            placeholders.add(placeholder);
            markers.add(pattern.substring(maskStart, slot.end()));
            masked.append(placeholder);
            cursor = slot.end();
        }
        masked.append(pattern, cursor, pattern.length());
        return new MaskedCallouts(syntheticPrefix + masked.toString(),
                syntheticPrefix.toString(), placeholders, markers);
    }

    boolean containsRuntimeExecutableSource(String modifiers) {
        return RuntimeRegex.containsExecutableSource(
                maskCallouts().pattern(), modifiers.indexOf('x') >= 0);
    }

    static String offsetCalloutIds(String executablePattern, int offset,
                                   int callbackCount) {
        StringBuilder remapped = new StringBuilder();
        int cursor = 0;
        for (CalloutSlot slot : calloutSlots(executablePattern)) {
            remapped.append(executablePattern, cursor, slot.start());
            int oldId = slot.id();
            if (oldId < 0 || oldId >= callbackCount) {
                throw new IllegalArgumentException("Invalid runtime regex callout ID " + oldId);
            }
            appendSlot(remapped, slot.kind(), offset + oldId);
            cursor = slot.end();
        }
        remapped.append(executablePattern, cursor, executablePattern.length());
        return remapped.toString();
    }

    static String materializeTrustedCallouts(String executablePattern, int callbackCount) {
        if (callbackCount == 0 || executablePattern.indexOf(SLOT_START) < 0) {
            return executablePattern;
        }
        StringBuilder materialized = new StringBuilder(executablePattern.length() + 16);
        int cursor = 0;
        for (CalloutSlot slot : calloutSlots(executablePattern)) {
            appendCollapsedSlotEscapes(materialized,
                    executablePattern.substring(cursor, slot.start()));
            if (slot.id() < 0 || slot.id() >= callbackCount) {
                throw new IllegalArgumentException(
                        "Invalid runtime regex callout ID " + slot.id());
            }
            if (slot.kind() == 'C') {
                materialized.append("?{=CALL:").append(slot.id()).append("})");
            } else {
                materialized.append("(?{=")
                        .append(slot.kind() == 'D' ? "DYNAMIC:" : "CALL:")
                        .append(slot.id()).append("})");
            }
            cursor = slot.end();
        }
        appendCollapsedSlotEscapes(materialized,
                executablePattern.substring(cursor));
        return materialized.toString();
    }

    private static void appendCollapsedSlotEscapes(StringBuilder target, String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            target.append(ch);
            if (ch == SLOT_START && i + 1 < text.length()
                    && text.charAt(i + 1) == SLOT_START) i++;
        }
    }

    private static String collapseSlotEscapes(String text) {
        StringBuilder collapsed = new StringBuilder(text.length());
        appendCollapsedSlotEscapes(collapsed, text);
        return collapsed.toString();
    }

    static String displayPattern(String executablePattern,
                                 List<RuntimeRegexCallback> callbacks) {
        if (executablePattern == null || executablePattern.isEmpty()) {
            return executablePattern;
        }
        StringBuilder display = new StringBuilder();
        int cursor = 0;
        for (CalloutSlot slot : calloutSlots(executablePattern)) {
            int textEnd = slot.kind() == 'C' && slot.start() > cursor
                    && executablePattern.charAt(slot.start() - 1) == '('
                    ? slot.start() - 1 : slot.start();
            appendCollapsedSlotEscapes(display,
                    executablePattern.substring(cursor, textEnd));
            int callbackId = slot.id();
            String replacement = callbackId >= 0 && callbackId < callbacks.size()
                    && callbacks.get(callbackId).source != null
                    ? callbacks.get(callbackId).source
                    : slot.kind() == 'D' ? "(??{})" : "(?{})";
            display.append(replacement);
            cursor = slot.end();
        }
        appendCollapsedSlotEscapes(display, executablePattern.substring(cursor));
        return display.toString();
    }

    @Override
    public String toString() {
        return displayPattern(pattern, callbacks);
    }
}
