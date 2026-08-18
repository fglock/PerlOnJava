package org.perlonjava.runtime.regex;

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
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

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.joni.constants.SyntaxProperties.OP2_PLUS_POSSESSIVE_INTERVAL;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.WeakHashMap;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

/**
 * Stack-based regex backend for Perl constructs that require matcher semantics
 * beyond the Java Pattern fast path.
 */
final class JoniRegexPattern {
    private static final Map<String, InputEncoding> INPUT_ENCODINGS = new WeakHashMap<>();
    private static final Map<String, InputEncoding> BYTE_INPUT_ENCODINGS = new WeakHashMap<>();

    // Ruby syntax defaults \w to ASCII even for a Unicode encoding. Perl's
    // default and /u modes use Unicode character classes; /a adds ASCII_RANGE
    // explicitly in toJoniOptions(). Keep the richer Ruby parser surface used
    // by callouts and control verbs while changing only that default policy.
    private static final Syntax PERLONJAVA_SYNTAX = new Syntax(
            "PERLONJAVA", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL | OP2_PLUS_POSSESSIVE_INTERVAL,
            Syntax.RUBY.op3,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options & ~(Option.ASCII_RANGE
                    | Option.POSIX_BRACKET_ALL_RANGE | Option.WORD_BOUND_ALL_RANGE),
            Syntax.RUBY.metaCharTable,
            JoniRegexPattern::resolveNamedCharacter,
            JoniRegexPattern::resolveCharacterProperty);

    private static int resolveNamedCharacter(byte[] bytes, int p, int end,
                                             Encoding encoding) {
        return UnicodeResolver.getCodePointFromName(new String(bytes, p, end - p,
                encoding == ISO8859_1Encoding.INSTANCE
                        ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8));
    }

    private static int[] resolveCharacterProperty(byte[] bytes, int p, int end,
                                                  Encoding encoding) {
        String property = new String(bytes, p, end - p,
                encoding == ISO8859_1Encoding.INSTANCE
                        ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        return UnicodeResolver.resolveJoniPropertyRanges(property);
    }

    private final Regex regex;
    private final String sourcePattern;
    private final Map<String, Integer> namedGroups;
    private final Map<String, Integer> physicalNamedGroups;
    private final RegexFlags flags;
    private final boolean hasControlVerbState;
    private final boolean hasDeferredUserDefinedUnicodeProperty;
    private final boolean byteMode;

    JoniRegexPattern(String perlPattern, RegexFlags flags) {
        this(perlPattern, flags, 0, false);
    }

    JoniRegexPattern(String perlPattern, RegexFlags flags, int trustedCalloutCount) {
        this(perlPattern, flags, trustedCalloutCount, false);
    }

    JoniRegexPattern(String perlPattern, RegexFlags flags, int trustedCalloutCount,
                     boolean forceAsciiClasses) {
        this(perlPattern, flags, trustedCalloutCount, forceAsciiClasses, false, false);
    }

    JoniRegexPattern(String perlPattern, RegexFlags flags, int trustedCalloutCount,
                     boolean forceAsciiClasses, boolean byteMode,
                     boolean byteBackedPattern) {
        PerlSyntaxFeatures syntaxFeatures = analyzePerlSyntax(perlPattern, flags.isExtended());
        if (syntaxFeatures.keepInLookaround()) {
            throw new PerlCompilerException("\\K not permitted in lookahead/lookbehind in regex");
        }
        this.flags = flags;
        this.byteMode = byteMode;
        hasControlVerbState = hasControlVerbState(perlPattern);
        UserPropertyTranslation userProperties = translateUserDefinedProperties(perlPattern, flags);
        hasDeferredUserDefinedUnicodeProperty = userProperties.deferred();
        sourcePattern = translatePattern(userProperties.pattern(), flags, trustedCalloutCount);
        byte[] bytes = sourcePattern.getBytes(byteMode && byteBackedPattern
                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        regex = new Regex(bytes, 0, bytes.length, toJoniOptions(flags, forceAsciiClasses),
                byteMode ? ISO8859_1Encoding.INSTANCE : UTF8Encoding.INSTANCE,
                PERLONJAVA_SYNTAX);
        NamedGroupMaps groupMaps = collectNamedGroups(regex);
        namedGroups = groupMaps.logical();
        physicalNamedGroups = groupMaps.physical();
    }

    RegexMatcher matcher(String input, List<RuntimeRegexCallback> callbacks) {
        return matcher(input, callbacks, new RuntimeScalar(input));
    }

    RegexMatcher matcher(String input, List<RuntimeRegexCallback> callbacks,
                         RuntimeScalar subject) {
        return new JoniRegexMatcher(regex, sourcePattern, namedGroups, physicalNamedGroups, flags,
                hasControlVerbState, byteMode, input, callbacks, subject);
    }

    record InputEncoding(byte[] bytes, int[] charToByte, int[] byteToChar) {}

    static InputEncoding inputEncoding(String input) {
        synchronized (INPUT_ENCODINGS) {
            return INPUT_ENCODINGS.computeIfAbsent(input, JoniRegexPattern::buildInputEncoding);
        }
    }

    static InputEncoding byteInputEncoding(String input) {
        synchronized (BYTE_INPUT_ENCODINGS) {
            return BYTE_INPUT_ENCODINGS.computeIfAbsent(input,
                    JoniRegexPattern::buildByteInputEncoding);
        }
    }

    private static InputEncoding buildByteInputEncoding(String input) {
        byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);
        int[] identity = new int[input.length() + 1];
        for (int i = 0; i < identity.length; i++) identity[i] = i;
        return new InputEncoding(bytes, identity, identity);
    }

    private static InputEncoding buildInputEncoding(String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        int[] charToByte = JoniRegexMatcher.buildCharToByte(input);
        int[] byteToChar = JoniRegexMatcher.buildByteToChar(input, bytes.length, charToByte);
        return new InputEncoding(bytes, charToByte, byteToChar);
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
        int extendedClassBracketDepth = 0;
        int standardClassBracketDepth = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '\\' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '\\') {
                translated.append("\\\\");
                i++;
                continue;
            }
            if (extendedClassBracketDepth == 0 && pattern.startsWith("(?[", i)) {
                translated.append("(?[");
                extendedClassBracketDepth = 1;
                i += 2;
                continue;
            }
            if (extendedClassBracketDepth > 0 && ch == '[') {
                extendedClassBracketDepth++;
                translated.append(ch);
                continue;
            }
            if (extendedClassBracketDepth > 0 && ch == ']') {
                extendedClassBracketDepth--;
                translated.append(ch);
                continue;
            }
            if (extendedClassBracketDepth == 0 && ch == '[') {
                standardClassBracketDepth++;
                translated.append(ch);
                continue;
            }
            if (extendedClassBracketDepth == 0
                    && standardClassBracketDepth > 0 && ch == ']') {
                standardClassBracketDepth--;
                translated.append(ch);
                continue;
            }
            if (ch != '\\' || i + 3 >= pattern.length()
                    || (pattern.charAt(i + 1) != 'p' && pattern.charAt(i + 1) != 'P')
                    || pattern.charAt(i + 2) != '{') {
                if (ch == '\\' && i + 1 < pattern.length()) {
                    translated.append(pattern, i, i + 2);
                    i++;
                } else {
                    translated.append(ch);
                }
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
            boolean userDefined = UnicodeResolver.isUserDefinedPropertyName(unnegated);
            boolean scriptExtensions = unnegated.matches(
                    "(?i)^(?:scx|script[-_ ]?extensions)\\s*(?:=|:(?!:)).*");
            boolean frontendProperty = unnegated.matches(
                    "(?i)^(?:script|sc|block|blk|age|in|present[_ ]?in)\\s*(?:=|:(?!:)).*");
            boolean perlBuiltInAlias = UnicodeResolver.isPerlBuiltInPropertyAlias(unnegated);
            boolean joniResolvedProperty = UnicodeResolver.resolveJoniPropertyRanges(
                    unnegated) != null;
            if (!userDefined && joniResolvedProperty
                    && (frontendProperty || scriptExtensions || perlBuiltInAlias)) {
                translated.append(pattern, i, end + 1);
                i = end;
                continue;
            }
            if ((frontendProperty || scriptExtensions || perlBuiltInAlias)
                    && extendedClassBracketDepth > 0) {
                translated.append(pattern, i, end + 1);
                i = end;
                continue;
            }
            if ((frontendProperty || scriptExtensions || perlBuiltInAlias)
                    && standardClassBracketDepth > 0) {
                String propertyClass = UnicodeResolver.translateUnicodePropertyForCharClass(
                        property, pattern.charAt(i + 1) == 'P');
                if (propertyClass.startsWith("[") && propertyClass.endsWith("]")) {
                    translated.append(propertyClass, 1, propertyClass.length() - 1);
                } else {
                    translated.append(propertyClass);
                }
                i = end;
                continue;
            }
            if (!userDefined && !scriptExtensions && !frontendProperty && !perlBuiltInAlias) {
                translated.append(pattern, i, end + 1);
                i = end;
                continue;
            }
            try {
                String propertyClass = UnicodeResolver.translateUnicodeProperty(
                        property, pattern.charAt(i + 1) == 'P', flags.isCaseInsensitive());
                translated.append("(?-i:")
                        .append(normalizeGeneratedPropertyClassForJoni(propertyClass))
                        .append(')');
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

    /**
     * UnicodeResolver emits Java-property spellings inside composite classes.
     * Joni accepts the equivalent Unicode aliases without Java's {@code Is}
     * and {@code gc=} prefixes.
     */
    private static String normalizeGeneratedPropertyClassForJoni(String propertyClass) {
        StringBuilder normalized = new StringBuilder(propertyClass.length());
        for (int i = 0; i < propertyClass.length(); i++) {
            if (propertyClass.charAt(i) == '\\' && i + 3 < propertyClass.length()
                    && (propertyClass.charAt(i + 1) == 'p' || propertyClass.charAt(i + 1) == 'P')
                    && propertyClass.charAt(i + 2) == '{') {
                int end = propertyClass.indexOf('}', i + 3);
                if (end > i + 3) {
                    String name = propertyClass.substring(i + 3, end);
                    if (name.startsWith("gc=")) name = name.substring(3);
                    else if (name.startsWith("Is")) name = name.substring(2);
                    normalized.append(propertyClass, i, i + 3).append(name).append('}');
                    i = end;
                    continue;
                }
            }
            normalized.append(propertyClass.charAt(i));
        }
        return normalized.toString();
    }

    private static int toJoniOptions(RegexFlags flags, boolean forceAsciiClasses) {
        int options = Option.NONE;
        if (flags.isCaseInsensitive()) options |= Option.IGNORECASE;
        if (flags.isExtended()) options |= Option.EXTEND;
        // Oniguruma's MULTILINE option controls whether dot matches newline.
        if (flags.isDotAll()) options |= Option.MULTILINE;
        if (!flags.isMultiLine()) options |= Option.SINGLELINE;
        if (flags.isAscii() || forceAsciiClasses) options |= Option.ASCII_RANGE;
        if (flags.isAsciiStrict()) options |= Option.PERL_ASCII_STRICT;
        // Ruby/Oniguruma syntax implicitly makes unnamed groups non-capturing
        // when a pattern also contains named groups. Perl keeps both kinds of
        // captures numbered. Force that behavior unless /n explicitly disables
        // unnamed captures.
        if (flags.isNonCapturing()) options |= Option.DONT_CAPTURE_GROUP;
        else options |= Option.CAPTURE_GROUP;
        return options;
    }

    static boolean requiresJoniBackend(String pattern) {
        return requiresJoniBackend(pattern,
                pattern == null ? null : RegexFlags.fromModifiers("", pattern));
    }

    static boolean requiresJoniBackend(String pattern, RegexFlags flags) {
        if (pattern == null) return false;
        PerlSyntaxFeatures syntaxFeatures = analyzePerlSyntax(
                pattern, flags != null && flags.isExtended());
        return syntaxFeatures.keepPresent()
                || syntaxFeatures.conditionalPresent()
                || pattern.contains("(?{=CALL:")
                || pattern.contains("(?{=DYNAMIC:")
                || pattern.contains("(*ACCEPT)")
                || pattern.contains("(*PRUNE")
                || pattern.contains("(*SKIP")
                || pattern.contains("(*THEN")
                || pattern.contains("(*COMMIT")
                || pattern.contains("(*MARK")
                || pattern.contains("(*:")
                || pattern.matches("(?s).*\\(\\?[+-]?\\d+\\).*" )
                || pattern.contains("(?&")
                || pattern.contains("(?P>");
    }

    private record PerlSyntaxFeatures(boolean keepPresent,
                                      boolean keepInLookaround,
                                      boolean conditionalPresent) {}

    private static PerlSyntaxFeatures analyzePerlSyntax(String pattern, boolean extended) {
        boolean quoted = false;
        boolean inClass = false;
        boolean classStart = false;
        int extendedClassDepth = 0;
        int lookaroundDepth = 0;
        java.util.ArrayDeque<Boolean> groups = new java.util.ArrayDeque<>();
        boolean keepPresent = false;
        boolean conditionalPresent = false;

        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (quoted) {
                if (ch == '\\' && i + 1 < pattern.length()
                        && pattern.charAt(i + 1) == 'E') {
                    quoted = false;
                    i++;
                }
                continue;
            }
            if (extendedClassDepth > 0) {
                if (ch == '\\' && i + 1 < pattern.length()) {
                    i++;
                } else if (ch == '[') {
                    extendedClassDepth++;
                } else if (ch == ']' && --extendedClassDepth == 0) {
                    // The following ')' closes the extended-class construct.
                }
                continue;
            }
            if (inClass) {
                if (ch == '\\' && i + 1 < pattern.length()) {
                    i++;
                    classStart = false;
                    continue;
                }
                if (ch == '[' && i + 1 < pattern.length()
                        && (pattern.charAt(i + 1) == ':'
                                || pattern.charAt(i + 1) == '.'
                                || pattern.charAt(i + 1) == '=')) {
                    char delimiter = pattern.charAt(i + 1);
                    int close = pattern.indexOf("" + delimiter + ']', i + 2);
                    if (close >= 0) i = close + 1;
                    classStart = false;
                    continue;
                }
                if (ch == ']' && !classStart) inClass = false;
                else if (!(classStart && ch == '^')) classStart = false;
                continue;
            }
            if (extended && ch == '#') {
                while (i + 1 < pattern.length()
                        && pattern.charAt(i + 1) != '\n') i++;
                continue;
            }
            if (pattern.startsWith("(?[", i)) {
                extendedClassDepth = 1;
                i += 2;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                classStart = true;
                continue;
            }
            if (ch == '\\' && i + 1 < pattern.length()) {
                char escaped = pattern.charAt(++i);
                if (escaped == 'Q') {
                    quoted = true;
                } else if (escaped == 'K') {
                    keepPresent = true;
                    if (lookaroundDepth > 0) {
                        return new PerlSyntaxFeatures(true, true, conditionalPresent);
                    }
                }
                continue;
            }
            if (ch == '(') {
                if (pattern.startsWith("(?#", i)) {
                    int close = pattern.indexOf(')', i + 3);
                    if (close < 0) break;
                    i = close;
                    continue;
                }
                if (pattern.startsWith("(?(", i)) conditionalPresent = true;
                boolean lookaround = pattern.startsWith("(?=", i)
                        || pattern.startsWith("(?!", i)
                        || pattern.startsWith("(?<=", i)
                        || pattern.startsWith("(?<!", i);
                groups.push(lookaround);
                if (lookaround) lookaroundDepth++;
            } else if (ch == ')' && !groups.isEmpty()) {
                if (groups.pop()) lookaroundDepth--;
            }
        }
        return new PerlSyntaxFeatures(keepPresent, false, conditionalPresent);
    }

    private static boolean hasControlVerbState(String pattern) {
        return pattern.contains("(*MARK") || pattern.contains("(*:")
                || pattern.contains("(*PRUNE")
                || pattern.contains("(*SKIP") || pattern.contains("(*THEN")
                || pattern.contains("(*COMMIT");
    }

    static String translatePattern(String pattern) {
        return translatePattern(pattern, RegexFlags.fromModifiers("", pattern), 0, true);
    }

    private static String translatePattern(String pattern, RegexFlags flags,
                                           int trustedCalloutCount) {
        return translatePattern(pattern, flags, trustedCalloutCount, false);
    }

    private static String translatePattern(String pattern, RegexFlags flags,
                                           int trustedCalloutCount,
                                           boolean resolveNamedCharacters) {
        pattern = translateDefineBlocks(pattern);
        pattern = translateBeyondUnicodeClassMembers(pattern);
        StringBuilder out = new StringBuilder(pattern.length() + 16);
        boolean escaped = false;
        boolean inClass = false;
        boolean atClassStart = false;
        boolean classAllowsLeadingClose = false;
        int posixClassDepth = 0;
        boolean wrapsInternalScalarMarker = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                if (inClass) {
                    atClassStart = false;
                    classAllowsLeadingClose = false;
                }
                if (resolveNamedCharacters && pattern.startsWith("\\N{", i)) {
                    int end = pattern.indexOf('}', i + 3);
                    if (end > i + 3) {
                        int codePoint = UnicodeResolver.getCodePointFromName(
                                pattern.substring(i + 3, end));
                        appendResolvedNamedCharacter(out, codePoint, flags);
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
                if (inClass) {
                    boolean posixClass = i + 1 < pattern.length()
                            && (pattern.charAt(i + 1) == ':'
                                    || pattern.charAt(i + 1) == '.'
                                    || pattern.charAt(i + 1) == '=');
                    if (posixClass) {
                        posixClassDepth++;
                        atClassStart = false;
                        classAllowsLeadingClose = false;
                        out.append(ch);
                        continue;
                    } else {
                        out.append("\\[");
                        atClassStart = false;
                        classAllowsLeadingClose = false;
                        continue;
                    }
                }
                if (!inClass && i + 1 < pattern.length() && pattern.charAt(i + 1) == '^') {
                    // Surrogate and beyond-Unicode Perl scalars use one
                    // Java-safe marker string internally. A negated class
                    // accepts those scalars, but must consume the complete
                    // marker as one Perl character.
                    out.append("(?:\\x{FFFD}<[0-9A-F]+>|");
                    wrapsInternalScalarMarker = true;
                }
                inClass = true;
                atClassStart = true;
                classAllowsLeadingClose = true;
                out.append(ch);
                continue;
            }
            if (!inClass && pattern.startsWith("(*:", i)) {
                // Perl's abbreviated MARK form is (*:NAME). Joni accepts the
                // equivalent long spelling and publishes the mark normally.
                out.append("(*MARK:");
                i += 2;
                continue;
            }
            if (inClass && flags.isExtendedWhitespace() && Character.isWhitespace(ch)) {
                continue;
            }
            if (inClass && atClassStart && ch == '^') {
                out.append(ch);
                atClassStart = false;
                continue;
            }
            if (inClass && classAllowsLeadingClose && ch == ']') {
                out.append(ch);
                atClassStart = false;
                classAllowsLeadingClose = false;
                continue;
            }
            if (ch == ']' && inClass) {
                out.append(ch);
                if (posixClassDepth > 0) {
                    posixClassDepth--;
                    continue;
                }
                inClass = false;
                atClassStart = false;
                classAllowsLeadingClose = false;
                if (wrapsInternalScalarMarker) {
                    out.append(')');
                    wrapsInternalScalarMarker = false;
                }
                continue;
            }
            if (inClass) {
                atClassStart = false;
                classAllowsLeadingClose = false;
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
                    out.append(normalizeGeneratedPropertyClassForJoni(
                            translatedClass.toString()));
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

    /**
     * Perl scalar strings can contain values above Unicode's maximum code point.
     * They are represented internally as {@code U+FFFD<HEX>}, which Joni can
     * match as ordinary text, but Joni rejects the original {@code \\x{...}}
     * class member before matching begins. Lift standalone beyond-Unicode class
     * members into alternatives that match the complete internal marker.
     */
    private static String translateBeyondUnicodeClassMembers(String pattern) {
        StringBuilder translated = new StringBuilder(pattern.length());
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                translated.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                translated.append(ch);
                escaped = true;
                continue;
            }
            if (ch != '[') {
                translated.append(ch);
                continue;
            }

            int close = findStandardClassClose(pattern, i + 1);
            if (close < 0) {
                translated.append(ch);
                continue;
            }
            String replacement = translateBeyondUnicodeClassContent(
                    pattern.substring(i + 1, close));
            if (replacement == null) {
                translated.append(pattern, i, close + 1);
            } else {
                translated.append(replacement);
            }
            i = close;
        }
        return translated.toString();
    }

    private static int findStandardClassClose(String pattern, int start) {
        boolean escaped = false;
        boolean leading = true;
        int posixDepth = 0;
        for (int i = start; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                leading = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                leading = false;
                continue;
            }
            if (ch == '[' && i + 1 < pattern.length()
                    && (pattern.charAt(i + 1) == ':'
                            || pattern.charAt(i + 1) == '.'
                            || pattern.charAt(i + 1) == '=')) {
                posixDepth++;
                leading = false;
                continue;
            }
            if (ch == ']' && posixDepth > 0) {
                posixDepth--;
                continue;
            }
            if (ch == ']' && leading) {
                leading = false;
                continue;
            }
            if (ch == ']') return i;
            if (ch != '^' || !leading) leading = false;
        }
        return -1;
    }

    private static String translateBeyondUnicodeClassContent(String content) {
        if (content.startsWith("^")) return null;

        StringBuilder retained = new StringBuilder(content.length());
        List<String> markers = new ArrayList<>();
        for (int i = 0; i < content.length();) {
            if (content.startsWith("\\\\", i)) {
                retained.append("\\\\");
                i += 2;
                continue;
            }
            if (content.startsWith("\\x{", i)) {
                int close = content.indexOf('}', i + 3);
                if (close > i + 3) {
                    String hex = content.substring(i + 3, close);
                    try {
                        long value = Long.parseUnsignedLong(hex, 16);
                        boolean rangeMember = (i > 0 && content.charAt(i - 1) == '-')
                                || (close + 1 < content.length()
                                        && content.charAt(close + 1) == '-');
                        if (Long.compareUnsigned(value, 0x10FFFFL) > 0 && !rangeMember) {
                            markers.add(Long.toUnsignedString(value, 16).toUpperCase(
                                    java.util.Locale.ROOT));
                            i = close + 1;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {
                        // Let Joni produce the normal malformed-escape diagnostic.
                    }
                }
            }
            retained.append(content.charAt(i++));
        }
        if (markers.isEmpty()) return null;

        StringBuilder replacement = new StringBuilder("(?:");
        for (int i = 0; i < markers.size(); i++) {
            if (i > 0) replacement.append('|');
            replacement.append("\\x{FFFD}<").append(markers.get(i)).append('>');
        }
        if (!retained.isEmpty()) {
            replacement.append("|[").append(retained).append(']');
        }
        return replacement.append(')').toString();
    }

    private static void appendResolvedNamedCharacter(StringBuilder out, int codePoint,
                                                      RegexFlags flags) {
        boolean extendedSyntax = flags.isExtended()
                && (codePoint == '#' || Character.isWhitespace(codePoint));
        boolean regexSyntax = codePoint == '\\' || codePoint == '.' || codePoint == '^'
                || codePoint == '$' || codePoint == '|' || codePoint == '?'
                || codePoint == '*' || codePoint == '+' || codePoint == '('
                || codePoint == ')' || codePoint == '[' || codePoint == ']'
                || codePoint == '{' || codePoint == '}';
        if (extendedSyntax || regexSyntax || Character.isISOControl(codePoint)) {
            out.append("\\x{")
                    .append(Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT))
                    .append('}');
        } else {
            out.appendCodePoint(codePoint);
        }
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

    private record NamedGroupMaps(Map<String, Integer> logical,
                                  Map<String, Integer> physical) {}

    private static NamedGroupMaps collectNamedGroups(Regex regex) {
        Map<String, Integer> names = new LinkedHashMap<>();
        Map<String, Integer> physicalNames = new LinkedHashMap<>();
        Iterator<NameEntry> iterator = regex.namedBackrefIterator();
        while (iterator.hasNext()) {
            NameEntry entry = iterator.next();
            String name = new String(entry.name, entry.nameP, entry.nameEnd - entry.nameP,
                    StandardCharsets.UTF_8);
            int[] refs = entry.getBackRefs();
            int[] physicalRefs = entry.getPhysicalBackRefs();
            for (int i = 0; i < refs.length; i++) {
                String key = i == 0 ? name
                        : name + CaptureNameEncoder.DUPLICATE_MARKER + (i - 1);
                names.put(key, refs[i]);
                physicalNames.put(key, physicalRefs[i]);
            }
        }
        return new NamedGroupMaps(names, physicalNames);
    }

    private static final class JoniRegexMatcher implements RegexMatcher {
        private final Regex regex;
        private final String sourcePattern;
        private final Map<String, Integer> namedGroups;
        private final Map<String, Integer> physicalNamedGroups;
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
        private int globalPosition = -1;
        private boolean matched;
        private int committedLastClosedCapture = -1;
        private final boolean hasControlVerbState;
        private final boolean byteMode;
        private final List<RuntimeRegexCallback> callbacks;
        private final RuntimeScalar subject;
        private PerlCalloutHandler calloutHandler;

        JoniRegexMatcher(Regex regex, String sourcePattern, Map<String, Integer> namedGroups,
                         Map<String, Integer> physicalNamedGroups,
                         RegexFlags flags, boolean hasControlVerbState, boolean byteMode,
                         String input,
                         List<RuntimeRegexCallback> callbacks, RuntimeScalar subject) {
            this.regex = regex;
            this.sourcePattern = sourcePattern;
            this.namedGroups = namedGroups;
            this.physicalNamedGroups = physicalNamedGroups;
            this.flags = flags;
            this.hasControlVerbState = hasControlVerbState;
            this.byteMode = byteMode;
            this.input = input;
            this.callbacks = callbacks;
            this.subject = subject;
            InputEncoding encoding = byteMode
                    ? byteInputEncoding(input) : inputEncoding(input);
            this.bytes = encoding.bytes();
            this.charToByte = encoding.charToByte();
            this.byteToChar = encoding.byteToChar();
            region(0, input.length());
        }

        @Override
        public boolean find() {
            return find(Option.NONE, false);
        }

        @Override
        public boolean findNotEmpty() {
            return find(Option.FIND_NOT_EMPTY, true);
        }

        private boolean find(int option, boolean anchored) {
            if (nextStart > regionEnd) {
                matched = false;
                committedLastClosedCapture = -1;
                if (hasControlVerbState) RuntimeRegex.updateControlVerbVariables(null, null);
                return false;
            }
            matcher = regex.matcher(bytes);
            if (!callbacks.isEmpty()) {
                calloutHandler = new PerlCalloutHandler(
                        input, byteToChar, callbacks, flags, hasControlVerbState, subject);
                matcher.setCalloutHandler(calloutHandler);
            }
            int result;
            try {
                if (globalPosition >= 0) {
                    result = matcher.search(charToByte[globalPosition], charToByte[nextStart],
                            charToByte[regionEnd], option);
                    if (anchored && result != charToByte[nextStart]) result = -1;
                } else {
                    result = anchored
                            ? matcher.match(charToByte[nextStart], charToByte[regionEnd], option)
                            : matcher.search(charToByte[nextStart], charToByte[regionEnd], option);
                }
            } catch (RuntimeException | Error failure) {
                if (calloutHandler != null) calloutHandler.abort();
                throw failure;
            }
            matched = result >= 0;
            if (hasControlVerbState || matcher.hasEncounteredControlVerb()) {
                RuntimeRegex.updateControlVerbVariables(
                        matcher.getControlMark(), matcher.getControlError());
            }
            if (calloutHandler != null) calloutHandler.finish(matched);
            if (!matched) {
                committedLastClosedCapture = -1;
                return false;
            }
            captures = Region.newRegion(regex.numberOfCaptures() + 1);
            for (int group = 0; group <= regex.numberOfCaptures(); group++) {
                captures.setBeg(group, matcher.captureBegin(group));
                captures.setEnd(group, matcher.captureEnd(group));
            }
            committedLastClosedCapture = matcher.lastClosedCapture();
            if (committedLastClosedCapture <= 0
                    || captures.getBeg(committedLastClosedCapture) < 0
                    || captures.getEnd(committedLastClosedCapture) < 0) {
                committedLastClosedCapture = deriveCommittedLastClosedCapture(captures);
            }
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
        @Override
        public boolean setGlobalPosition(int position) {
            if (position < 0 || position > input.length()) return false;
            globalPosition = position;
            return true;
        }
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
            requireMatch();
            Integer physical = physicalNamedGroups.get(name);
            if (physical == null) return group(namedGroupNumber(name));
            int begin = matcher.physicalNamedCaptureBegin(physical);
            int end = matcher.physicalNamedCaptureEnd(physical);
            if (begin < 0 || end < 0) return null;
            return input.substring(toCharOffset(begin), toCharOffset(end));
        }

        @Override public int groupCount() { return regex.numberOfCaptures(); }
        @Override public int lastClosedCapture() { return committedLastClosedCapture; }
        @Override public String controlMark() { return matcher.getControlMark(); }
        @Override public String controlError() { return matcher.getControlError(); }
        @Override public Map<String, Integer> namedGroups() { return namedGroups; }
        @Override public String patternDescription() { return sourcePattern; }

        private static int deriveCommittedLastClosedCapture(Region region) {
            int latestCapture = -1;
            int latestEnd = -1;
            for (int group = 1; group < region.getNumRegs(); group++) {
                int end = region.getEnd(group);
                if (region.getBeg(group) >= 0 && end > latestEnd) {
                    latestCapture = group;
                    latestEnd = end;
                }
            }
            return latestCapture;
        }

        private int groupOffset(String name, boolean begin) {
            requireMatch();
            Integer physical = physicalNamedGroups.get(name);
            if (physical != null) {
                int offset = begin ? matcher.physicalNamedCaptureBegin(physical)
                        : matcher.physicalNamedCaptureEnd(physical);
                return offset < 0 ? -1 : toCharOffset(offset);
            }
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
                    byteMode ? ISO8859_1Encoding.INSTANCE : UTF8Encoding.INSTANCE, captures);
        }

        private int advanceCodePoint(int offset) {
            return offset >= regionEnd ? regionEnd + 1
                    : offset + (byteMode ? 1 : Character.charCount(input.codePointAt(offset)));
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
            for (int charOffset = 0; charOffset < input.length();) {
                int chars = Character.charCount(input.codePointAt(charOffset));
                int nextCharOffset = charOffset + chars;
                int nextByteOffset = charToByte[nextCharOffset];
                for (int byteOffset = charToByte[charOffset];
                     byteOffset < nextByteOffset; byteOffset++) {
                    offsets[byteOffset] = charOffset;
                }
                offsets[nextByteOffset] = nextCharOffset;
                charOffset = nextCharOffset;
            }
            return offsets;
        }
    }

    private static final class PerlCalloutHandler implements CalloutHandler {
        private record Token(int localLevel, RegexState regexState, RuntimeScalar previousR,
                             RuntimeScalar result, boolean block, boolean dynamic,
                             CaptureSnapshot previousDynamicView) {}

        private record CaptureSnapshot(int position, int[] begins, int[] ends,
                                       int lastClosed, String controlMark) implements MatchView {
            static CaptureSnapshot of(MatchView match) {
                int count = match.captureCount();
                int[] begins = new int[count + 1];
                int[] ends = new int[count + 1];
                for (int capture = 0; capture <= count; capture++) {
                    begins[capture] = match.captureBegin(capture);
                    ends[capture] = match.captureEnd(capture);
                }
                return new CaptureSnapshot(match.currentBytePosition(), begins, ends,
                        match.lastClosedCapture(), match.controlMark());
            }

            @Override public int currentBytePosition() { return position; }
            @Override public int captureCount() { return begins.length - 1; }
            @Override public int captureBegin(int capture) { return begins[capture]; }
            @Override public int captureEnd(int capture) { return ends[capture]; }
            @Override public int lastClosedCapture() { return lastClosed; }
        }

        private final String input;
        private final int[] byteToChar;
        private final List<RuntimeRegexCallback> callbacks;
        private final RegexFlags outerFlags;
        private final boolean publishesControlVerbState;
        private final RuntimeScalar subject;
        private final int initialLocalLevel;
        private final RegexState initialRegexState;
        private final PerlCalloutHandler parent;
        private final int nestedDepth;
        private RuntimeScalar completedResult;
        private CaptureSnapshot previousDynamicView;
        private boolean hasFailedNestedCaptureState;
        private boolean executedNestedCallbackPattern;
        private String failedNestedLastClosedCapture;
        private String failedNestedLastParenMatch;

        PerlCalloutHandler(String input, int[] byteToChar, List<RuntimeRegexCallback> callbacks,
                           RegexFlags outerFlags, boolean publishesControlVerbState,
                           RuntimeScalar subject) {
            this(input, byteToChar, callbacks, outerFlags, publishesControlVerbState,
                    subject, null);
        }

        private PerlCalloutHandler(
                String input, int[] byteToChar, List<RuntimeRegexCallback> callbacks,
                RegexFlags outerFlags, boolean publishesControlVerbState,
                RuntimeScalar subject, PerlCalloutHandler parent) {
            this.input = input;
            this.byteToChar = byteToChar;
            this.callbacks = callbacks;
            this.outerFlags = outerFlags;
            this.publishesControlVerbState = publishesControlVerbState;
            this.subject = subject;
            this.parent = parent;
            this.nestedDepth = parent == null ? 0 : parent.nestedDepth + 1;
            this.initialLocalLevel = DynamicVariableManager.getLocalLevel();
            this.initialRegexState = new RegexState();
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
            if (value.type == RuntimeScalarType.UNDEF) {
                WarnDie.warnWithCategory(
                        new RuntimeScalar("Use of uninitialized value"),
                        RuntimeScalarCache.scalarEmptyString, "uninitialized");
            }
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
                                    ? runtimeRegex.getRegexFlags() : outerFlags,
                            nestedPattern.hasControlVerbState,
                            subject,
                            this);
            if (nestedHandler != null) executedNestedCallbackPattern = true;
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
            CaptureSnapshot priorDynamicView = previousDynamicView;
            MatchView provisional = callback.kind == RuntimeRegexCallback.Kind.DYNAMIC
                    ? dynamicCaptureView(match, priorDynamicView) : match;
            publishProvisional(provisional);
            if (callback.kind == RuntimeRegexCallback.Kind.DYNAMIC) {
                previousDynamicView = CaptureSnapshot.of(match);
            }
            var callbackLocations = PerlRuntime.current().executionState()
                    .activeRegexCallbackLocations;
            callbackLocations.push(callback.sourceLocation == null ? "" : callback.sourceLocation);
            var callbackPackages = PerlRuntime.current().executionState()
                    .activeRegexCallbackPackages;
            callbackPackages.push(callback.lexicalPackage == null ? "main" : callback.lexicalPackage);
            RuntimeScalar previousTopic = GlobalVariable.getGlobalVariable("main::_");
            boolean previousTopicWasTemporary =
                    GlobalVariable.isTemporaryGlobalAlias("main::_");
            RuntimeScalar callbackPosition = RuntimePosLvalue.pos(subject);
            int previousPositionType = callbackPosition.type;
            Object previousPositionValue = callbackPosition.value;
            GlobalVariable.aliasTemporaryGlobalVariable("main::_", subject);
            // Exposing the provisional offset is not an assignment to pos().
            // Preserve zero-length /g bookkeeping unless callback code itself
            // explicitly changes pos().
            callbackPosition.type = RuntimeScalarType.INTEGER;
            callbackPosition.value = charOffset(match.currentBytePosition());

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
                        result.clone(), block,
                        callback.kind == RuntimeRegexCallback.Kind.DYNAMIC,
                        priorDynamicView);
                return new Evaluation(result, token);
            } catch (RuntimeException | Error failure) {
                // The matcher cannot register an unwind token when the callout
                // itself throws. Restore the provisional match and dynamic
                // scope here before the exception crosses an eval boundary.
                restoreCallbackScope(localLevel, savedRegex, previousR);
                previousDynamicView = priorDynamicView;
                throw failure;
            } finally {
                callbackPosition.type = previousPositionType;
                callbackPosition.value = previousPositionValue;
                GlobalVariable.restoreTemporaryGlobalVariable(
                        "main::_", previousTopic, previousTopicWasTemporary);
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

        @Override
        public void finish(boolean matched) {
            try {
                RuntimeRegexState state = PerlRuntime.current().regexState;
                if (!matched && parent != null
                        && (nestedDepth >= 2 || executedNestedCallbackPattern)) {
                    parent.recordFailedNestedCaptureState(
                            state.lastClosedCapture, RuntimeRegex.lastCaptureString());
                } else if (hasFailedNestedCaptureState && parent != null) {
                    parent.recordFailedNestedCaptureState(
                            failedNestedLastClosedCapture, failedNestedLastParenMatch);
                }
                if (matched && completedResult != null) {
                    GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("R"))
                            .set(completedResult);
                } else if (!matched) {
                    initialRegexState.restore();
                    if (hasFailedNestedCaptureState) {
                        state.lastClosedCapture = failedNestedLastClosedCapture;
                        state.lastParenMatchOverrideActive = true;
                        state.lastParenMatchOverride = failedNestedLastParenMatch;
                    }
                }
            } finally {
                DynamicVariableManager.popToLocalLevel(initialLocalLevel);
            }
        }

        private void recordFailedNestedCaptureState(String lastClosed, String lastParen) {
            hasFailedNestedCaptureState = true;
            failedNestedLastClosedCapture = lastClosed;
            failedNestedLastParenMatch = lastParen;
        }

        void abort() {
            DynamicVariableManager.popToLocalLevel(initialLocalLevel);
        }

        private void restore(Token token, boolean completed) {
            if (!completed) {
                DynamicVariableManager.popToLocalLevel(token.localLevel());
                previousDynamicView = token.previousDynamicView();
            }
            token.regexState().restore();
            if (!completed || !token.dynamic()) {
                GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("R"))
                        .set(token.previousR());
            }
            if (completed && token.block() && completedResult == null) {
                completedResult = token.result();
            }
        }

        private static MatchView dynamicCaptureView(
                MatchView current, CaptureSnapshot previous) {
            CaptureSnapshot adjusted = CaptureSnapshot.of(current);
            if (previous == null || previous.position() >= adjusted.position()) return adjusted;
            for (int capture = 1; capture <= adjusted.captureCount(); capture++) {
                if (adjusted.begins()[capture] == adjusted.position()
                        && adjusted.ends()[capture] == adjusted.position()
                        && previous.begins()[capture] == previous.position()
                        && (previous.ends()[capture] < 0
                        || previous.ends()[capture] == previous.position())) {
                    adjusted.begins()[capture] = previous.position();
                    adjusted.ends()[capture] = adjusted.position();
                }
            }
            return adjusted;
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
            state.lastParenMatchOverrideActive = false;
            state.lastParenMatchOverride = null;
            int count = match.captureCount();
            state.globalMatchString = input;
            int provisionalStart = charOffset(match.captureBegin(0));
            int provisionalEnd = charOffset(match.captureEnd(0));
            provisionalEnd = provisionalEnd >= 0
                    ? provisionalEnd : charOffset(match.currentBytePosition());
            state.lastMatchStart = provisionalStart;
            state.lastMatchEnd = provisionalEnd;
            state.lastMatchedString = provisionalStart >= 0
                    && provisionalEnd >= provisionalStart
                    ? input.substring(provisionalStart, provisionalEnd) : null;
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
            if (publishesControlVerbState || match.controlMark() != null) {
                RuntimeRegex.updateControlVerbVariables(match.controlMark(), null);
            }
        }

        private int charOffset(int byteOffset) {
            return byteOffset < 0 || byteOffset >= byteToChar.length ? -1 : byteToChar[byteOffset];
        }
    }
}
