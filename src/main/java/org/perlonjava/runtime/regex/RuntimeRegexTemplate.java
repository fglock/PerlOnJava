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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/** Runtime interpolation result that keeps executable regex callbacks out of strings. */
public final class RuntimeRegexTemplate {
    private static final Pattern CALLOUT_ID = Pattern.compile(
            "\\(\\?\\{=(CALL|DYNAMIC):(\\d+)\\}\\)");
    private final String pattern;
    private final List<RuntimeRegexCallback> callbacks;

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

    private RuntimeRegexTemplate(String pattern, List<RuntimeRegexCallback> callbacks) {
        this.pattern = pattern;
        this.callbacks = List.copyOf(callbacks);
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
        for (RuntimeBase part : parts.elements) {
            RuntimeScalar scalar = part.scalar();
            tainted |= scalar.isTainted();
            if (scalar.value instanceof RuntimeRegexCallback callback) {
                int id = callbacks.size();
                callbacks.add(callback);
                if (callback.kind == RuntimeRegexCallback.Kind.CONDITION) {
                    pattern.append("?{=CALL:").append(id).append("})");
                } else if (callback.kind == RuntimeRegexCallback.Kind.DYNAMIC) {
                    pattern.append("(?{=DYNAMIC:").append(id).append("})");
                } else {
                    pattern.append("(?{=CALL:").append(id).append("})");
                }
            } else if (scalar.value instanceof RuntimeRegex regex
                    && !regex.executableCallbacks.isEmpty()) {
                appendEmbeddedRegex(pattern, callbacks, regex.toExecutableString(), regex.executableCallbacks);
            } else if (scalar.value instanceof RuntimeRegexTemplate template) {
                appendEmbeddedRegex(pattern, callbacks, template.pattern, template.callbacks);
            } else {
                pattern.append(scalar);
            }
        }
        RuntimeScalar result = callbacks.isEmpty()
                ? new RuntimeScalar(pattern.toString())
                : new RuntimeScalar(new RuntimeRegexTemplate(pattern.toString(), callbacks));
        result.tainted = tainted;
        return result;
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
            RuntimeScalar scalar = part.scalar();
            if (scalar.value instanceof RuntimeRegexCallback callback) {
                scalar = new RuntimeScalar(callback.source == null ? "" : callback.source);
            }
            concatenated = StringOperators.stringConcat(concatenated, scalar);
        }
        return concatenated;
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
        Matcher matcher = CALLOUT_ID.matcher(embeddedPattern);
        StringBuilder remapped = new StringBuilder();
        while (matcher.find()) {
            String kind = matcher.group(1);
            int oldId = Integer.parseInt(matcher.group(2));
            if (oldId < 0 || oldId >= embeddedCallbacks.size()) {
                throw new IllegalArgumentException("Invalid embedded regex callout ID " + oldId);
            }
            matcher.appendReplacement(remapped,
                    "(?{=" + kind + ":" + (offset + oldId) + "})");
        }
        matcher.appendTail(remapped);
        pattern.append(remapped);
        callbacks.addAll(embeddedCallbacks);
    }

    String pattern() {
        return pattern;
    }

    List<RuntimeRegexCallback> callbacks() {
        return callbacks;
    }

    /**
     * Hide parser-created callout markers while runtime-interpolated Perl
     * source is compiled. The source parser must see raw {@code (?{...})} and
     * {@code (??{...})} groups, but must not reinterpret trusted marker IDs as
     * Perl expressions.
     */
    MaskedCallouts maskCallouts() {
        Matcher matcher = CALLOUT_ID.matcher(pattern);
        StringBuilder masked = new StringBuilder();
        StringBuilder syntheticPrefix = new StringBuilder();
        List<String> placeholders = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        int id = 0;
        while (matcher.find()) {
            String token = "POJ_INTERNAL_CALLOUT_PLACEHOLDER_" + id++ + "_END";
            while (pattern.contains(token)) token += "_";
            // In (?(?{...})yes|no), the marker itself is the condition. Use a
            // temporary named-capture condition so the runtime regex parser
            // preserves the branches without interpreting the trusted marker
            // as Perl source. The empty capture is removed by restore().
            boolean callbackCondition = matcher.start() >= 2
                    && pattern.startsWith("(?", matcher.start() - 2);
            String placeholder = token;
            if (callbackCondition) {
                String name = "POJ_INTERNAL_CALLOUT_CONDITION_" + id;
                while (pattern.contains(name)) name += "_";
                syntheticPrefix.append("(?<").append(name).append(">)");
                placeholder = "(<" + name + ">)";
            }
            placeholders.add(placeholder);
            markers.add(matcher.group());
            matcher.appendReplacement(masked, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(masked);
        return new MaskedCallouts(syntheticPrefix + masked.toString(),
                syntheticPrefix.toString(), placeholders, markers);
    }

    boolean containsRuntimeExecutableSource(String modifiers) {
        return RuntimeRegex.containsExecutableSource(
                maskCallouts().pattern(), modifiers.indexOf('x') >= 0);
    }

    static String offsetCalloutIds(String executablePattern, int offset,
                                   int callbackCount) {
        if (offset == 0 || callbackCount == 0) return executablePattern;
        Matcher matcher = CALLOUT_ID.matcher(executablePattern);
        StringBuilder remapped = new StringBuilder();
        while (matcher.find()) {
            int oldId = Integer.parseInt(matcher.group(2));
            if (oldId < 0 || oldId >= callbackCount) {
                throw new IllegalArgumentException("Invalid runtime regex callout ID " + oldId);
            }
            matcher.appendReplacement(remapped, Matcher.quoteReplacement(
                    "(?{=" + matcher.group(1) + ":" + (offset + oldId) + "})"));
        }
        matcher.appendTail(remapped);
        return remapped.toString();
    }

    static String displayPattern(String executablePattern,
                                 List<RuntimeRegexCallback> callbacks) {
        if (executablePattern == null || executablePattern.isEmpty()) {
            return executablePattern;
        }
        Matcher matcher = CALLOUT_ID.matcher(executablePattern);
        StringBuilder display = new StringBuilder();
        while (matcher.find()) {
            int callbackId = Integer.parseInt(matcher.group(2));
            String replacement = callbackId >= 0 && callbackId < callbacks.size()
                    && callbacks.get(callbackId).source != null
                    ? callbacks.get(callbackId).source
                    : "DYNAMIC".equals(matcher.group(1)) ? "(??{})" : "(?{})";
            matcher.appendReplacement(display, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(display);
        return display.toString();
    }

    @Override
    public String toString() {
        return pattern;
    }
}
