package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime interpolation result that keeps executable regex callbacks out of strings. */
public final class RuntimeRegexTemplate {
    private static final Pattern CALLOUT_ID = Pattern.compile(
            "\\(\\?\\{=(CALL|DYNAMIC):(\\d+)\\}\\)");
    private final String pattern;
    private final List<RuntimeRegexCallback> callbacks;

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
        if (parts.elements.size() == 1) {
            RuntimeScalar only = parts.elements.getFirst().scalar();
            // A lone interpolation must retain its runtime type so qr
            // overloading and an already-compiled regex remain observable.
            // Only a parser-created callback needs a new template skeleton.
            if (!(only.value instanceof RuntimeRegexCallback)) return only;
        }
        StringBuilder pattern = new StringBuilder();
        List<RuntimeRegexCallback> callbacks = new ArrayList<>();
        boolean tainted = false;
        for (RuntimeBase part : parts.elements) {
            RuntimeScalar scalar = part.scalar();
            // Interpolation is one scalar read. Resolve tied magic once, then
            // use that materialized value for type inspection, taint, and text.
            if (scalar.type == RuntimeScalarType.TIED_SCALAR) {
                scalar = scalar.tiedFetch();
            }
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

    /** Preserve callback-bearing qr values while an array is joined for interpolation. */
    public static RuntimeScalar buildJoined(RuntimeScalar separator,
                                            List<RuntimeScalar> elements) {
        RuntimeList parts = new RuntimeList();
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) parts.add(separator);
            parts.add(elements.get(i));
        }
        return build(parts);
    }

    public static boolean hasExecutableValue(RuntimeScalar scalar) {
        return scalar != null && (scalar.value instanceof RuntimeRegexTemplate
                || scalar.value instanceof RuntimeRegex regex
                && !regex.executableCallbacks.isEmpty());
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
