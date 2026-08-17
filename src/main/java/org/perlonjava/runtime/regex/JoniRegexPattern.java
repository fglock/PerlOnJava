package org.perlonjava.runtime.regex;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.CalloutHandler;
import org.joni.CalloutResult;
import org.joni.DynamicPatternResult;
import org.joni.MatchView;
import org.joni.NameEntry;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Region;
import org.joni.Syntax;

import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.joni.constants.SyntaxProperties.OP2_PLUS_POSSESSIVE_INTERVAL;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import org.perlonjava.runtime.runtimetypes.*;

/**
 * Stack-based regex backend for Perl constructs that require matcher semantics
 * beyond the Java Pattern fast path.
 */
final class JoniRegexPattern {
    // Ruby syntax defaults \w to ASCII even for a Unicode encoding. Perl's
    // default and /u modes use Unicode character classes; /a adds ASCII_RANGE
    // explicitly in toJoniOptions(). Keep the richer Ruby parser surface used
    // by callouts and control verbs while changing only that default policy.
    private static final Syntax PERLONJAVA_SYNTAX = new Syntax(
            "PERLONJAVA", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL | OP2_PLUS_POSSESSIVE_INTERVAL,
            Syntax.RUBY.op3,
            Syntax.RUBY.behavior, Syntax.RUBY.options & ~Option.ASCII_RANGE,
            Syntax.RUBY.metaCharTable);

    private final Regex regex;
    private final String sourcePattern;
    private final Map<String, Integer> namedGroups;
    private final RegexFlags flags;
    private final boolean hasControlVerbState;
    private final boolean hasDeferredUserDefinedUnicodeProperty;

    JoniRegexPattern(String perlPattern, RegexFlags flags) {
        this(perlPattern, flags, 0);
    }

    JoniRegexPattern(String perlPattern, RegexFlags flags, int trustedCalloutCount) {
        this.flags = flags;
        hasControlVerbState = hasControlVerbState(perlPattern);
        UserPropertyTranslation userProperties = translateUserDefinedProperties(perlPattern, flags);
        hasDeferredUserDefinedUnicodeProperty = userProperties.deferred();
        sourcePattern = translatePattern(userProperties.pattern(), flags, trustedCalloutCount);
        byte[] bytes = sourcePattern.getBytes(StandardCharsets.UTF_8);
        regex = new Regex(bytes, 0, bytes.length, toJoniOptions(flags),
                UTF8Encoding.INSTANCE, PERLONJAVA_SYNTAX);
        namedGroups = collectNamedGroups(regex);
    }

    RegexMatcher matcher(String input, List<RuntimeRegexCallback> callbacks) {
        return new JoniRegexMatcher(regex, sourcePattern, namedGroups, flags,
                hasControlVerbState, input, callbacks);
    }

    String patternDescription() {
        return sourcePattern;
    }

    Regex engineRegex() {
        return regex;
    }

    boolean hasDeferredUserDefinedUnicodeProperty() {
        return hasDeferredUserDefinedUnicodeProperty;
    }

    private record UserPropertyTranslation(String pattern, boolean deferred) {}

    private static UserPropertyTranslation translateUserDefinedProperties(
            String pattern, RegexFlags flags) {
        StringBuilder translated = new StringBuilder(pattern.length());
        boolean deferred = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '\\' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '\\') {
                translated.append("\\\\");
                i++;
                continue;
            }
            if (ch != '\\' || i + 3 >= pattern.length()
                    || (pattern.charAt(i + 1) != 'p' && pattern.charAt(i + 1) != 'P')
                    || pattern.charAt(i + 2) != '{') {
                translated.append(ch);
                continue;
            }
            int end = pattern.indexOf('}', i + 3);
            if (end < 0) {
                translated.append(ch);
                continue;
            }
            String property = pattern.substring(i + 3, end).trim();
            String unnegated = property.startsWith("^")
                    ? property.substring(1).trim() : property;
            boolean userDefined = unnegated.matches("^(.*::)?([Ii][sSNn]).+");
            boolean scriptExtensions = unnegated.matches(
                    "(?i)^(?:scx|script[_ ]?extensions)\\s*=.*");
            if (!userDefined && !scriptExtensions) {
                translated.append(pattern, i, end + 1);
                i = end;
                continue;
            }
            try {
                String propertyClass = UnicodeResolver.translateUnicodeProperty(
                        property, pattern.charAt(i + 1) == 'P', flags.isCaseInsensitive());
                translated.append("(?-i:").append(propertyClass).append(')');
            } catch (IllegalArgumentException error) {
                String message = error.getMessage();
                if (!userDefined || message != null && message.contains("in expansion of")) {
                    throw error;
                }
                translated.append("[\\s\\S]");
                deferred = true;
            }
            i = end;
        }
        return new UserPropertyTranslation(translated.toString(), deferred);
    }

    private static int toJoniOptions(RegexFlags flags) {
        int options = Option.NONE;
        if (flags.isCaseInsensitive()) options |= Option.IGNORECASE;
        if (flags.isExtended()) options |= Option.EXTEND;
        // Oniguruma's MULTILINE option controls whether dot matches newline.
        if (flags.isDotAll()) options |= Option.MULTILINE;
        if (!flags.isMultiLine()) options |= Option.SINGLELINE;
        if (flags.isAscii()) options |= Option.ASCII_RANGE;
        // Ruby/Oniguruma syntax implicitly makes unnamed groups non-capturing
        // when a pattern also contains named groups. Perl keeps both kinds of
        // captures numbered. Force that behavior unless /n explicitly disables
        // unnamed captures.
        if (flags.isNonCapturing()) options |= Option.DONT_CAPTURE_GROUP;
        else options |= Option.CAPTURE_GROUP;
        return options;
    }

    static boolean requiresJoniBackend(String pattern) {
        if (pattern == null) return false;
        return pattern.contains("(?{=CALL:")
                || pattern.contains("(?{=DYNAMIC:")
                || pattern.contains("(*ACCEPT)")
                || pattern.contains("(*PRUNE")
                || pattern.contains("(*SKIP")
                || pattern.contains("(*THEN")
                || pattern.contains("(*COMMIT")
                || pattern.contains("(*MARK")
                || pattern.contains("(?(DEFINE)")
                || pattern.contains("(?(?{=CALL:")
                || pattern.contains("(?(R")
                || pattern.contains("(?(<")
                || pattern.contains("(?('")
                || pattern.matches("(?s).*\\(\\?[+-]?\\d+\\).*" )
                || pattern.contains("(?&")
                || pattern.contains("(?P>");
    }

    private static boolean hasControlVerbState(String pattern) {
        return pattern.contains("(*MARK") || pattern.contains("(*PRUNE")
                || pattern.contains("(*SKIP") || pattern.contains("(*THEN")
                || pattern.contains("(*COMMIT");
    }

    static String translatePattern(String pattern) {
        return translatePattern(pattern, RegexFlags.fromModifiers("", pattern), 0);
    }

    private static String translatePattern(String pattern, RegexFlags flags,
                                           int trustedCalloutCount) {
        pattern = translateDefineBlocks(pattern);
        StringBuilder out = new StringBuilder(pattern.length() + 16);
        boolean escaped = false;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                if (pattern.startsWith("\\N{", i)) {
                    int end = pattern.indexOf('}', i + 3);
                    if (end > i + 3) {
                        int codePoint = UnicodeResolver.getCodePointFromName(
                                pattern.substring(i + 3, end));
                        out.appendCodePoint(codePoint);
                        i = end;
                        continue;
                    }
                }
                // In Perl, \g{name} is a backreference.  Ruby/Oniguruma uses
                // \g<name> for a subexpression call and \k<name> for the
                // backreference, so passing the brace form through makes Joni
                // diagnose it as an invalid subexpression call.  Numeric and
                // relative brace forms follow the same translation.
                if (!inClass && pattern.startsWith("\\g{", i)) {
                    int end = pattern.indexOf('}', i + 3);
                    if (end > i + 3) {
                        out.append("\\k<").append(pattern, i + 3, end).append('>');
                        i = end;
                        continue;
                    }
                }
                out.append(ch);
                escaped = true;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                out.append(ch);
                continue;
            }
            if (inClass && flags.isExtendedWhitespace() && Character.isWhitespace(ch)) {
                continue;
            }
            if (ch == ']' && inClass) {
                inClass = false;
                out.append(ch);
                continue;
            }
            if (!inClass && pattern.startsWith("(?{", i)
                    && !isTrustedCallout(pattern, i, trustedCalloutCount)) {
                // A callback introduced as text by runtime interpolation has
                // no parser-created lexical closure to invoke. Preserve the
                // historical compatibility behavior for that unsupported
                // case: treat it as a zero-width no-op. Structured callbacks
                // remain match-time Joni callouts and are handled below.
                int end = findCodeBlockEnd(pattern, i);
                if (end >= 0) {
                    out.append("(?:)");
                    i = end;
                    continue;
                }
            }
            if (!inClass && pattern.startsWith("(?[", i)) {
                StringBuilder translatedClass = new StringBuilder();
                int end = ExtendedCharClass.handleExtendedCharacterClass(
                        pattern, i, translatedClass, flags);
                String sourceClass = pattern.substring(i, Math.min(pattern.length(), end + 1));
                if (sourceClass.toLowerCase(java.util.Locale.ROOT).contains("[:ascii:]")) {
                    appendAsciiClassForJoni(out, translatedClass.toString());
                } else {
                    out.append(translatedClass);
                }
                i = end;
                continue;
            }
            if (!inClass && pattern.startsWith("(?)", i)) {
                out.append("(?:)");
                i += 2;
                continue;
            }
            if (!inClass && pattern.startsWith("(?&", i)) {
                int end = pattern.indexOf(')', i + 3);
                if (end > i) {
                    out.append("\\g<").append(pattern, i + 3, end).append('>');
                    i = end;
                    continue;
                }
            }
            if (!inClass && pattern.startsWith("(?P>", i)) {
                int end = pattern.indexOf(')', i + 4);
                if (end > i) {
                    out.append("\\g<").append(pattern, i + 4, end).append('>');
                    i = end;
                    continue;
                }
            }
            if (!inClass && ch == '(' && i + 3 < pattern.length() && pattern.charAt(i + 1) == '?') {
                int p = i + 2;
                if (pattern.charAt(p) == '+' || pattern.charAt(p) == '-') p++;
                int digits = p;
                while (p < pattern.length() && Character.isDigit(pattern.charAt(p))) p++;
                if (p > digits && p < pattern.length() && pattern.charAt(p) == ')') {
                    out.append("\\g<").append(pattern, i + 2, p).append('>');
                    i = p;
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }


    private static boolean isTrustedCallout(String pattern, int offset, int callbackCount) {
        String prefix;
        if (pattern.startsWith("(?{=CALL:", offset)) prefix = "(?{=CALL:";
        else if (pattern.startsWith("(?{=DYNAMIC:", offset)) prefix = "(?{=DYNAMIC:";
        else return false;

        int idStart = offset + prefix.length();
        int idEnd = idStart;
        while (idEnd < pattern.length() && Character.isDigit(pattern.charAt(idEnd))) idEnd++;
        if (idEnd == idStart || !pattern.startsWith("})", idEnd)) return false;
        try {
            int id = Integer.parseInt(pattern.substring(idStart, idEnd));
            return id >= 0 && id < callbackCount;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int findCodeBlockEnd(String pattern, int offset) {
        int depth = 1;
        for (int i = offset + 3; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '\\' && i + 1 < pattern.length()) {
                i++;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return i + 1 < pattern.length() && pattern.charAt(i + 1) == ')'
                        ? i + 1 : -1;
            }
        }
        return -1;
    }

    /**
     * Joni's Ruby syntax does not understand Java's {@code &&} character-class
     * intersection.  For an explicitly ASCII-bounded Perl extended class,
     * evaluate the already-translated Java class and emit the exact byte set.
     */
    private static void appendAsciiClassForJoni(StringBuilder out, String javaClass) {
        // Java requires literal closing/opening brackets to be escaped even in
        // the leading position accepted by Perl's bracket syntax.
        javaClass = javaClass.replace("[^][", "[^\\]\\[");
        java.util.regex.Pattern predicate = java.util.regex.Pattern.compile(javaClass);
        out.append('[');
        for (int value = 0; value < 128; value++) {
            if (predicate.matcher(Character.toString((char) value)).matches()) {
                out.append(String.format("\\x%02X", value));
            }
        }
        out.append(']');
    }

    /**
     * Ruby/Oniguruma syntax supports named subexpression calls but not PCRE's
     * {@code (?(DEFINE) ...)} container. Keep the definitions in the compiled
     * graph inside a negative lookahead whose body is forced to fail; the
     * lookahead therefore always succeeds without consuming input, while the
     * named groups remain available to later {@code (?&name)} calls.
     */
    private static String translateDefineBlocks(String pattern) {
        StringBuilder out = new StringBuilder(pattern.length() + 16);
        boolean escaped = false;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                out.append(ch);
                escaped = true;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                out.append(ch);
                continue;
            }
            if (ch == ']' && inClass) {
                inClass = false;
                out.append(ch);
                continue;
            }
            if (!inClass && pattern.startsWith("(?(DEFINE)", i)) {
                int end = findGroupEnd(pattern, i);
                if (end > i) {
                    String definitions = pattern.substring(i + 10, end);
                    out.append("(?!(?:")
                            .append(translateDefineBlocks(definitions))
                            .append(")(?!))");
                    i = end;
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static int findGroupEnd(String pattern, int start) {
        int depth = 0;
        boolean escaped = false;
        boolean inClass = false;
        for (int i = start; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                continue;
            }
            if (ch == ']' && inClass) {
                inClass = false;
                continue;
            }
            if (inClass) continue;
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static Map<String, Integer> collectNamedGroups(Regex regex) {
        Map<String, Integer> names = new LinkedHashMap<>();
        Iterator<NameEntry> iterator = regex.namedBackrefIterator();
        while (iterator.hasNext()) {
            NameEntry entry = iterator.next();
            String name = new String(entry.name, entry.nameP, entry.nameEnd - entry.nameP,
                    StandardCharsets.UTF_8);
            int[] refs = entry.getBackRefs();
            for (int i = 0; i < refs.length; i++) {
                String key = i == 0 ? name
                        : name + CaptureNameEncoder.DUPLICATE_MARKER + (i - 1);
                names.put(key, refs[i]);
            }
        }
        return names;
    }

    private static final class JoniRegexMatcher implements RegexMatcher {
        private final Regex regex;
        private final String sourcePattern;
        private final Map<String, Integer> namedGroups;
        private final RegexFlags flags;
        private final String input;
        private final byte[] bytes;
        private final int[] charToByte;
        private final int[] byteToChar;
        private Matcher matcher;
        private Region captures;
        private int regionStart;
        private int regionEnd;
        private int nextStart;
        private boolean matched;
        private final boolean hasControlVerbState;
        private final List<RuntimeRegexCallback> callbacks;
        private PerlCalloutHandler calloutHandler;

        JoniRegexMatcher(Regex regex, String sourcePattern, Map<String, Integer> namedGroups,
                         RegexFlags flags, boolean hasControlVerbState, String input,
                         List<RuntimeRegexCallback> callbacks) {
            this.regex = regex;
            this.sourcePattern = sourcePattern;
            this.namedGroups = namedGroups;
            this.flags = flags;
            this.hasControlVerbState = hasControlVerbState;
            this.input = input;
            this.callbacks = callbacks;
            this.bytes = input.getBytes(StandardCharsets.UTF_8);
            this.charToByte = buildCharToByte(input);
            this.byteToChar = buildByteToChar(input, bytes.length, charToByte);
            region(0, input.length());
        }

        @Override
        public boolean find() {
            if (nextStart > regionEnd) {
                matched = false;
                if (hasControlVerbState) RuntimeRegex.updateControlVerbVariables(null, null);
                return false;
            }
            matcher = regex.matcher(bytes);
            if (!callbacks.isEmpty()) {
                calloutHandler = new PerlCalloutHandler(input, byteToChar, callbacks, flags);
                matcher.setCalloutHandler(calloutHandler);
            }
            int result;
            try {
                result = matcher.search(charToByte[nextStart], charToByte[regionEnd], Option.NONE);
            } catch (RuntimeException | Error failure) {
                if (calloutHandler != null) calloutHandler.abort();
                throw failure;
            }
            matched = result >= 0;
            if (hasControlVerbState) {
                RuntimeRegex.updateControlVerbVariables(
                        matcher.getControlMark(), matcher.getControlError());
            }
            if (calloutHandler != null) calloutHandler.finish(matched);
            if (!matched) return false;
            captures = matcher.getEagerRegion();
            int start = start();
            int end = end();
            nextStart = end > start ? end : advanceCodePoint(end);
            return true;
        }

        @Override
        public void region(int start, int end) {
            regionStart = Math.max(0, Math.min(start, input.length()));
            regionEnd = Math.max(regionStart, Math.min(end, input.length()));
            nextStart = regionStart;
            matched = false;
        }

        @Override public void useAnchoringBounds(boolean enabled) { }
        @Override public void useTransparentBounds(boolean enabled) { }
        @Override public int start() { return toCharOffset(matcher.getBegin()); }
        @Override public int end() { return toCharOffset(matcher.getEnd()); }
        @Override public int start(int index) { return groupOffset(index, true); }
        @Override public int end(int index) { return groupOffset(index, false); }
        @Override public int start(String name) { return groupOffset(name, true); }
        @Override public int end(String name) { return groupOffset(name, false); }

        @Override
        public String group(int index) {
            requireMatch();
            int begin = index == 0 ? matcher.getBegin() : captures.getBeg(index);
            int end = index == 0 ? matcher.getEnd() : captures.getEnd(index);
            if (begin < 0 || end < 0) return null;
            return input.substring(toCharOffset(begin), toCharOffset(end));
        }

        @Override
        public String group(String name) {
            int group = namedGroupNumber(name);
            return group(group);
        }

        @Override public int groupCount() { return regex.numberOfCaptures(); }
        @Override public int lastClosedCapture() { return matcher.lastClosedCapture(); }
        @Override public String controlMark() { return matcher.getControlMark(); }
        @Override public String controlError() { return matcher.getControlError(); }
        @Override public Map<String, Integer> namedGroups() { return namedGroups; }
        @Override public String patternDescription() { return sourcePattern; }

        private int groupOffset(String name, boolean begin) {
            requireMatch();
            int group = namedGroupNumber(name);
            return groupOffset(group, begin);
        }

        private int groupOffset(int group, boolean begin) {
            requireMatch();
            if (group == 0) return begin ? start() : end();
            int offset = begin ? captures.getBeg(group) : captures.getEnd(group);
            return offset < 0 ? -1 : toCharOffset(offset);
        }

        private int namedGroupNumber(String name) {
            Integer knownGroup = namedGroups.get(name);
            if (knownGroup != null) return knownGroup;
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            return regex.nameToBackrefNumber(nameBytes, 0, nameBytes.length,
                    UTF8Encoding.INSTANCE, captures);
        }

        private int advanceCodePoint(int offset) {
            return offset >= regionEnd ? regionEnd + 1
                    : offset + Character.charCount(input.codePointAt(offset));
        }

        private int toCharOffset(int byteOffset) {
            if (byteOffset < 0 || byteOffset >= byteToChar.length) return -1;
            return byteToChar[byteOffset];
        }

        private void requireMatch() {
            if (!matched) throw new IllegalStateException("No successful match");
        }

        private static int[] buildCharToByte(String input) {
            int[] offsets = new int[input.length() + 1];
            int byteOffset = 0;
            for (int i = 0; i < input.length();) {
                offsets[i] = byteOffset;
                int cp = input.codePointAt(i);
                int chars = Character.charCount(cp);
                int bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
                if (chars == 2) offsets[i + 1] = byteOffset;
                i += chars;
                byteOffset += bytes;
                offsets[i] = byteOffset;
            }
            return offsets;
        }

        private static int[] buildByteToChar(String input, int byteLength, int[] charToByte) {
            int[] offsets = new int[byteLength + 1];
            int charOffset = 0;
            for (int b = 0; b <= byteLength; b++) {
                while (charOffset + 1 < charToByte.length && charToByte[charOffset + 1] <= b) {
                    charOffset++;
                }
                offsets[b] = charOffset;
            }
            return offsets;
        }
    }

    private static final class PerlCalloutHandler implements CalloutHandler {
        private record Token(int localLevel, RegexState regexState, RuntimeScalar previousR,
                             RuntimeScalar result, boolean block) {}

        private final String input;
        private final int[] byteToChar;
        private final List<RuntimeRegexCallback> callbacks;
        private final RegexFlags outerFlags;
        private final int initialLocalLevel;
        private RuntimeScalar completedResult;

        PerlCalloutHandler(String input, int[] byteToChar, List<RuntimeRegexCallback> callbacks,
                           RegexFlags outerFlags) {
            this.input = input;
            this.byteToChar = byteToChar;
            this.callbacks = callbacks;
            this.outerFlags = outerFlags;
            this.initialLocalLevel = DynamicVariableManager.getLocalLevel();
        }

        @Override
        public CalloutResult execute(int id, MatchView match) {
            RuntimeRegexCallback callback = callbacks.get(id);
            if (callback.kind == RuntimeRegexCallback.Kind.DYNAMIC) {
                throw new IllegalStateException("dynamic callback used as a plain callout");
            }
            Evaluation evaluation = evaluate(callback, match);
            RuntimeScalar result = evaluation.result();
            Token token = evaluation.token();
            return callback.kind == RuntimeRegexCallback.Kind.CONDITION && !result.getBoolean()
                    ? CalloutResult.failWith(token) : CalloutResult.continueWith(token);
        }

        @Override
        public DynamicPatternResult executeDynamic(int id, MatchView match) {
            RuntimeRegexCallback callback = callbacks.get(id);
            if (callback.kind != RuntimeRegexCallback.Kind.DYNAMIC) {
                throw new IllegalStateException("plain callback used as a dynamic callout");
            }
            Evaluation evaluation = evaluate(callback, match);
            RuntimeScalar value = evaluation.result();
            JoniRegexPattern nestedPattern;
            List<RuntimeRegexCallback> nestedCallbacks = List.of();
            if (value.value instanceof RuntimeRegex runtimeRegex) {
                RegexFlags nestedFlags = runtimeRegex.getRegexFlags() == null
                        ? outerFlags : runtimeRegex.getRegexFlags();
                nestedPattern = new JoniRegexPattern(runtimeRegex.patternString, nestedFlags,
                        runtimeRegex.executableCallbacks.size());
                nestedCallbacks = runtimeRegex.executableCallbacks;
            } else if (value.value instanceof RuntimeRegexTemplate template) {
                nestedPattern = new JoniRegexPattern(template.pattern(), outerFlags,
                        template.callbacks().size());
                nestedCallbacks = template.callbacks();
            } else {
                String dynamicSource = value.toString();
                if (RuntimeRegex.containsExecutableSource(
                        dynamicSource, outerFlags.isExtended())) {
                    if (!outerFlags.allowEvalGroup()) {
                        throw new PerlCompilerException(
                                "Eval-group not allowed at runtime, use re 'eval'");
                    }
                    String modifiers = outerFlags.toFlagString() + "E";
                    RuntimeScalar compiled = RuntimeRegex.getQuotedRegex(
                            value, new RuntimeScalar(modifiers));
                    RuntimeRegex runtimeRegex = (RuntimeRegex) compiled.value;
                    nestedPattern = new JoniRegexPattern(runtimeRegex.patternString,
                            runtimeRegex.getRegexFlags(),
                            runtimeRegex.executableCallbacks.size());
                    nestedCallbacks = runtimeRegex.executableCallbacks;
                } else {
                    nestedPattern = new JoniRegexPattern(dynamicSource, outerFlags);
                }
            }
            CalloutHandler nestedHandler = nestedCallbacks.isEmpty() ? null
                    : new PerlCalloutHandler(input, byteToChar, nestedCallbacks,
                            value.value instanceof RuntimeRegex runtimeRegex
                                    && runtimeRegex.getRegexFlags() != null
                                    ? runtimeRegex.getRegexFlags() : outerFlags);
            return new DynamicPatternResult(nestedPattern.engineRegex(), nestedHandler,
                    evaluation.token());
        }

        private record Evaluation(RuntimeScalar result, Token token) {}

        private Evaluation evaluate(RuntimeRegexCallback callback, MatchView match) {
            if (System.getenv("DEBUG_REGEX") != null) {
                System.err.println("REGEX_CALLOUT package=" + callback.lexicalPackage
                        + " codePackage=" + callback.code.packageName
                        + " source=" + callback.sourceLocation);
            }
            int localLevel = DynamicVariableManager.getLocalLevel();
            RegexState savedRegex = new RegexState();
            RuntimeScalar rVariable = GlobalVariable.getGlobalVariable(
                    GlobalContext.encodeSpecialVar("R"));
            RuntimeScalar previousR = rVariable.clone();
            RuntimeScalar previousSelf = callback.code.__SUB__;
            if (callback.code.isQuotedRegexCallback) {
                RuntimeCode enclosing = RuntimeCode.getActiveCodeAt(0);
                if (enclosing != null) {
                    RuntimeScalar enclosingSelf = enclosing.__SUB__ != null
                            ? enclosing.__SUB__ : new RuntimeScalar(enclosing);
                    RuntimeCode.inheritSelfReference(
                            new RuntimeScalar(callback.code), enclosingSelf);
                }
            }
            publishProvisional(match);
            var callbackLocations = PerlRuntime.current().executionState()
                    .activeRegexCallbackLocations;
            callbackLocations.push(callback.sourceLocation == null ? "" : callback.sourceLocation);
            var callbackPackages = PerlRuntime.current().executionState()
                    .activeRegexCallbackPackages;
            callbackPackages.push(callback.lexicalPackage == null ? "main" : callback.lexicalPackage);

            try {
                DynamicVariableManager.CapturedFrame<RuntimeList> frame =
                        DynamicVariableManager.captureFrameLocals(() -> RuntimeCode.apply(
                                new RuntimeScalar(callback.code), new RuntimeArray(),
                                RuntimeContextType.SCALAR));
                // Joni's complete() notification is delayed until the candidate
                // path commits. Resume now so a later (?{ ... }) on that same
                // path observes local() values; unwind() still owns the token's
                // pre-callback level and rolls the frame back on backtracking.
                DynamicVariableManager.resumeSuspended(frame.states());
                rejectEscapedControlFlow(callback, frame.result());
                RuntimeScalar result = frame.result().scalar();
                boolean block = callback.kind == RuntimeRegexCallback.Kind.BLOCK;
                if (block) rVariable.set(result);
                Token token = new Token(localLevel, savedRegex, previousR,
                        result.clone(), block);
                return new Evaluation(result, token);
            } catch (RuntimeException | Error failure) {
                // The matcher cannot register an unwind token when the callout
                // itself throws. Restore the provisional match and dynamic
                // scope here before the exception crosses an eval boundary.
                restoreCallbackScope(localLevel, savedRegex, previousR);
                throw failure;
            } finally {
                if (!callbackLocations.isEmpty()) callbackLocations.pop();
                if (!callbackPackages.isEmpty()) callbackPackages.pop();
                if (callback.code.isQuotedRegexCallback) {
                    RuntimeCode.inheritSelfReference(
                            new RuntimeScalar(callback.code), previousSelf);
                }
            }
        }

        @Override
        public void unwind(Object value) {
            restore((Token) value, false);
        }

        @Override
        public void complete(Object value) {
            restore((Token) value, true);
        }

        void finish(boolean matched) {
            try {
                if (matched && completedResult != null) {
                    GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("R"))
                            .set(completedResult);
                }
            } finally {
                DynamicVariableManager.popToLocalLevel(initialLocalLevel);
            }
        }

        void abort() {
            DynamicVariableManager.popToLocalLevel(initialLocalLevel);
        }

        private void restore(Token token, boolean completed) {
            if (!completed) {
                DynamicVariableManager.popToLocalLevel(token.localLevel());
            }
            token.regexState().restore();
            GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("R"))
                    .set(token.previousR());
            if (completed && token.block() && completedResult == null) {
                completedResult = token.result();
            }
        }

        private static void restoreCallbackScope(int localLevel, RegexState regexState,
                                                 RuntimeScalar previousR) {
            try {
                DynamicVariableManager.popToLocalLevel(localLevel);
            } finally {
                regexState.restore();
                GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("R"))
                        .set(previousR);
            }
        }

        private static void rejectEscapedControlFlow(RuntimeRegexCallback callback,
                                                     RuntimeList result) {
            if (!(result instanceof RuntimeControlFlowList flow)) return;
            ControlFlowMarker marker = flow.marker;
            if (marker.type == ControlFlowType.GOTO
                    || marker.type == ControlFlowType.TAILCALL) {
                String file = callback.code.cvStartFile != null
                        ? callback.code.cvStartFile : marker.fileName;
                int line = callback.code.cvStartLine > 0
                        ? callback.code.cvStartLine : marker.lineNumber;
                throw new PerlCompilerException("Can't \"goto\" out of a pseudo block at "
                        + file + " line " + line + ".\n");
            }
            // Preserve the control op's own location. The terminating newline
            // tells PerlCompilerException this is already fully formatted.
            throw new PerlCompilerException(marker.buildErrorMessage() + ".\n");
        }

        private void publishProvisional(MatchView match) {
            RuntimeRegexState state = PerlRuntime.current().regexState;
            int count = match.captureCount();
            state.globalMatchString = input;
            state.lastMatchedString = input;
            state.lastMatchStart = charOffset(match.captureBegin(0));
            state.lastMatchEnd = charOffset(match.captureEnd(0));
            state.lastCaptureGroups = new String[count];
            state.manualCaptureStarts = new int[count];
            state.manualCaptureEnds = new int[count];
            for (int group = 1; group <= count; group++) {
                int begin = charOffset(match.captureBegin(group));
                int end = charOffset(match.captureEnd(group));
                if (begin < 0 || end < begin) {
                    state.manualCaptureStarts[group - 1] = -1;
                    state.manualCaptureEnds[group - 1] = -1;
                    state.lastCaptureGroups[group - 1] = null;
                } else {
                    state.manualCaptureStarts[group - 1] = begin;
                    state.manualCaptureEnds[group - 1] = end;
                    state.lastCaptureGroups[group - 1] = input.substring(begin, end);
                }
            }
            int lastClosed = match.lastClosedCapture();
            state.lastClosedCapture = lastClosed > 0 && lastClosed <= count
                    ? state.lastCaptureGroups[lastClosed - 1] : null;
            RuntimeRegex.updateControlVerbVariables(match.controlMark(), null);
        }

        private int charOffset(int byteOffset) {
            return byteOffset < 0 || byteOffset >= byteToChar.length ? -1 : byteToChar[byteOffset];
        }
    }
}
