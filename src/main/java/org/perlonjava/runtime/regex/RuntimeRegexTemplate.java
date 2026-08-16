package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

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
                appendEmbeddedRegex(pattern, callbacks, regex.toString(), regex.executableCallbacks);
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

    @Override
    public String toString() {
        return pattern;
    }
}
