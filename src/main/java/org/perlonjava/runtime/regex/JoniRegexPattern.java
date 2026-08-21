package org.perlonjava.runtime.regex;

import org.jcodings.Encoding;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.CalloutHandler;
import org.joni.CalloutResult;
import org.joni.CharacterPropertyResolver;
import org.joni.DynamicPatternResult;
import org.joni.MatchView;
import org.joni.NameEntry;
import org.joni.NamedCharacterResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Region;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.WideScalarCodec;
import org.joni.exception.SyntaxException;

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_ESC_H_HORIZONTAL_WHITESPACE;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.joni.constants.SyntaxProperties.OP2_PLUS_POSSESSIVE_INTERVAL;
import static org.joni.constants.SyntaxProperties.OP3_PERL_LITERAL_OPEN_IN_CC;
import static org.joni.constants.SyntaxProperties.OP_ESC_C_CONTROL;
import static org.joni.constants.SyntaxProperties.OP_POSIX_BRACKET;

import org.joni.constants.internal.AnchorType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.WeakHashMap;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.operators.PerlUtfString;
import org.perlonjava.runtime.NamedCharacterExpansion;
import org.perlonjava.runtime.NamedCharacterExpansionMap;
import org.perlonjava.runtime.runtimetypes.*;

/** Sole production adapter from Perl regex operations to the vendored Joni fork. */
final class JoniRegexPattern {
    private static final String NAMED_SEQUENCE_CLASS_WARNING =
            "Using just the first character returned by \\N{} in character class";
    private static final String MIXED_NAMED_RANGE_WARNING =
            "Both or neither range ends should be Unicode";
    private static final Map<String, InputEncoding> INPUT_ENCODINGS = new WeakHashMap<>();
    private static final Map<String, InputEncoding> BYTE_INPUT_ENCODINGS = new WeakHashMap<>();
    private static final Map<RuntimeScalar, SubjectInputEncodings> SUBJECT_INPUT_ENCODINGS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final WideScalarCodec PERL_SCALAR_CODEC = new WideScalarCodec() {
        @Override
        public byte[] encode(long value, Encoding encoding) {
            return PerlUtfString.encodeInternalCodePoint(value)
                    .getBytes(encoding == ISO8859_1Encoding.INSTANCE
                            ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        }

        @Override
        public Decoded decode(byte[] bytes, int p, int end, Encoding encoding) {
            int cursor;
            if (encoding == UTF8Encoding.INSTANCE && p + 6 <= end
                    && (bytes[p] & 0xff) == 0xef
                    && (bytes[p + 1] & 0xff) == 0xbf
                    && (bytes[p + 2] & 0xff) == 0xbd
                    && bytes[p + 3] == '<') {
                cursor = p + 4;
            } else if (encoding == ISO8859_1Encoding.INSTANCE && p + 4 <= end
                    && bytes[p] == '?' && bytes[p + 1] == '<') {
                cursor = p + 2;
            } else {
                return null;
            }
            long value = 0;
            int digits = 0;
            while (cursor < end && bytes[cursor] != '>') {
                int digit = Character.digit((char)(bytes[cursor] & 0xff), 16);
                if (digit < 0 || digits == 16
                        || value > (Long.MAX_VALUE - digit) / 16) {
                    return null;
                }
                value = value * 16 + digit;
                digits++;
                cursor++;
            }
            if (digits == 0 || cursor >= end || bytes[cursor] != '>') return null;
            return new Decoded(value, cursor + 1);
        }
    };

    // Ruby syntax defaults \w to ASCII even for a Unicode encoding. Perl's
    // default and /u modes use Unicode character classes; /a adds ASCII_RANGE
    // explicitly in toJoniOptions(). Keep the richer Ruby parser surface used
    // by callouts and control verbs while changing only that default policy.
    private static final Syntax PERLONJAVA_SYNTAX = new Syntax(
            "PERLONJAVA", Syntax.RUBY.op | OP_POSIX_BRACKET | OP_ESC_C_CONTROL,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL
                    | OP2_PLUS_POSSESSIVE_INTERVAL | OP2_ESC_H_HORIZONTAL_WHITESPACE,
            Syntax.RUBY.op3 | OP3_PERL_LITERAL_OPEN_IN_CC,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options & ~(Option.ASCII_RANGE
                    | Option.POSIX_BRACKET_ALL_RANGE | Option.WORD_BOUND_ALL_RANGE),
            Syntax.RUBY.metaCharTable,
            null,
            propertyResolver(new DeferredPropertyState()),
            PERL_SCALAR_CODEC);

    static final class NamedCharacterCache {
        private final Map<NamedCharacterExpansionMap.Key, NamedCharacterExpansion> expansions =
                new LinkedHashMap<>();
        private final RuntimeScalar translator;

        NamedCharacterCache() {
            this((RuntimeScalar) null);
        }

        NamedCharacterCache(RuntimeScalar translator) {
            this.translator = translator == null ? null : new RuntimeScalar(translator);
        }

        NamedCharacterCache(NamedCharacterExpansionMap preResolved) {
            this.translator = null;
            if (preResolved != null) expansions.putAll(preResolved.expansions());
        }

        NamedCharacterExpansion resolve(
                String name, NamedCharacterExpansion.SourceMode sourceMode) {
            return expansions.computeIfAbsent(new NamedCharacterExpansionMap.Key(name, sourceMode),
                    ignored -> translator == null
                            ? NamedCharacterExpansion.resolve(name, sourceMode)
                            : NamedCharacterExpansion.resolve(name, translator, sourceMode));
        }


        NamedCharacterExpansionMap snapshot(
                NamedCharacterExpansionMap.LiteralIdentity literalIdentity,
                NamedCharacterExpansionMap.CallableIdentity callableIdentity) {
            return new NamedCharacterExpansionMap(
                    literalIdentity, callableIdentity, expansions);
        }
    }

    private static Syntax syntaxForNamedCharacters(
            NamedCharacterCache cache, NamedCharacterExpansion.SourceMode sourceMode,
            DeferredPropertyState deferredPropertyState) {
        NamedCharacterResolver resolver = new NamedCharacterResolver() {
            @Override
            public int resolve(byte[] bytes, int p, int end, Encoding encoding) {
                int[] sequence = resolveSequence(bytes, p, end, encoding);
                if (sequence.length != 1) {
                    throw new IllegalArgumentException(
                            "named character resolver requires one code point");
                }
                return sequence[0];
            }

            @Override
            public int[] resolveSequence(byte[] bytes, int p, int end, Encoding encoding) {
                String name = new String(bytes, p, end - p,
                        encoding == ISO8859_1Encoding.INSTANCE
                                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
                String trimmed = name.strip();
                if (trimmed.regionMatches(true, 0, "U+", 0, 2)) {
                    name = trimmed;
                }
                NamedCharacterExpansion expansion = cache.resolve(name, sourceMode);
                if (!expansion.resolved()) {
                    throw new IllegalArgumentException(expansion.diagnostic());
                }
                return expansion.sequence().codePoints().toArray();
            }
        };
        CharacterPropertyResolver propertyResolver = propertyResolver(deferredPropertyState);
        return new Syntax(PERLONJAVA_SYNTAX.name, PERLONJAVA_SYNTAX.op,
                PERLONJAVA_SYNTAX.op2, PERLONJAVA_SYNTAX.op3,
                PERLONJAVA_SYNTAX.behavior, PERLONJAVA_SYNTAX.options,
                PERLONJAVA_SYNTAX.metaCharTable, resolver,
                propertyResolver,
                PERLONJAVA_SYNTAX.wideScalarCodec);
    }

    private static final class DeferredPropertyState {
        private boolean deferred;
        private boolean userDefined;
    }

    private static CharacterPropertyResolver propertyResolver(
            DeferredPropertyState deferredPropertyState) {
        return new CharacterPropertyResolver() {
            @Override
            public Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                                  boolean inCharacterClass) {
                return resolve(bytes, p, end, encoding,
                        inCharacterClass
                                ? CharacterPropertyResolver.Context.STANDARD_CHARACTER_CLASS
                                : CharacterPropertyResolver.Context.OUTSIDE_CHARACTER_CLASS,
                        Option.NONE);
            }

            @Override
            public Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                                  boolean inCharacterClass, int option) {
                return resolve(bytes, p, end, encoding,
                        inCharacterClass
                                ? CharacterPropertyResolver.Context.STANDARD_CHARACTER_CLASS
                                : CharacterPropertyResolver.Context.OUTSIDE_CHARACTER_CLASS,
                        option);
            }

            @Override
            public Result resolve(byte[] bytes, int p, int end, Encoding encoding,
                                  CharacterPropertyResolver.Context context, int option) {
                String property = new String(bytes, p, end - p,
                        encoding == ISO8859_1Encoding.INSTANCE
                                ? StandardCharsets.ISO_8859_1
                                : StandardCharsets.UTF_8).trim();
                if (property.startsWith("^")) property = property.substring(1).trim();
                boolean userDefined = UnicodeResolver.isUserDefinedPropertyName(property);
                if (userDefined) deferredPropertyState.userDefined = true;

                if (context == CharacterPropertyResolver.Context.PERL_EXTENDED_CHARACTER_CLASS
                        && UnicodeResolver.isPerlStringProperty(property)) {
                    throw new CharacterPropertyResolver.ResolutionException(
                            "Unicode string properties are not implemented in (?[...])");
                }

                CharacterPropertyResolver.Result resolved = resolveCharacterProperty(
                        bytes, p, end, encoding,
                        context == CharacterPropertyResolver.Context.STANDARD_CHARACTER_CLASS,
                        Option.isIgnoreCase(option));
                if (resolved != null) return resolved;

                boolean caseInsensitive = Option.isIgnoreCase(option);
                if (userDefined
                        && UnicodeResolver.mustDeferPotentialUserDefinedProperty(
                                property, caseInsensitive)) {
                    if (context
                            == CharacterPropertyResolver.Context.PERL_EXTENDED_CHARACTER_CLASS) {
                        throw new CharacterPropertyResolver.ResolutionException(
                                "Unknown user-defined property name \"" + property + "\"");
                    }
                    deferredPropertyState.deferred = true;
                    return new Result(new int[] {1, 0, 0x10ffff},
                            new long[] {1, 0, Long.MAX_VALUE}, false);
                }
                return null;
            }

            @Override
            public boolean isScriptRun(byte[] bytes, int p, int end, Encoding encoding,
                                       WideScalarCodec wideScalarCodec) {
                return UnicodeResolver.isPerlScriptRun(
                        bytes, p, end, encoding, wideScalarCodec);
            }
        };
    }

    private static CharacterPropertyResolver.Result resolveCharacterProperty(
            byte[] bytes, int p, int end, Encoding encoding,
            boolean inCharacterClass, boolean caseInsensitive) {
        String property = new String(bytes, p, end - p,
                encoding == ISO8859_1Encoding.INSTANCE
                        ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        return UnicodeResolver.resolveJoniProperty(
                property, inCharacterClass, caseInsensitive);
    }

    private final Regex regex;
    private final String sourcePattern;
    private final String compatibilityPatternDescription;
    private final Map<String, Integer> namedGroups;
    private final Map<String, Integer> physicalNamedGroups;
    private final RegexFlags flags;
    private final boolean hasControlVerbState;
    private final boolean hasDeferredUserDefinedUnicodeProperty;
    private final boolean hasUserDefinedUnicodeProperty;
    private final boolean byteMode;
    private final List<String> compileWarnings;

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
        this(perlPattern, flags, trustedCalloutCount, forceAsciiClasses, byteMode,
                byteBackedPattern, new NamedCharacterCache());
    }

    JoniRegexPattern(String perlPattern, RegexFlags flags, int trustedCalloutCount,
                     boolean forceAsciiClasses, boolean byteMode,
                     boolean byteBackedPattern, NamedCharacterCache namedCharacterCache) {
        this(perlPattern, flags, trustedCalloutCount, forceAsciiClasses,
                byteMode, byteBackedPattern, namedCharacterCache,
                forceAsciiClasses || byteMode && byteBackedPattern
                        ? NamedCharacterExpansion.SourceMode.BYTE
                        : NamedCharacterExpansion.SourceMode.UNICODE,
                false);
    }

    JoniRegexPattern(String perlPattern, RegexFlags flags, int trustedCalloutCount,
                     boolean forceAsciiClasses, boolean byteMode,
                     boolean byteBackedPattern, NamedCharacterCache namedCharacterCache,
                     boolean perlReStrict) {
        this(perlPattern, flags, trustedCalloutCount, forceAsciiClasses,
                byteMode, byteBackedPattern, namedCharacterCache,
                forceAsciiClasses || byteMode && byteBackedPattern
                        ? NamedCharacterExpansion.SourceMode.BYTE
                        : NamedCharacterExpansion.SourceMode.UNICODE,
                perlReStrict);
    }

    JoniRegexPattern(String perlPattern, RegexFlags flags, int trustedCalloutCount,
                     boolean forceAsciiClasses, boolean byteMode,
                     boolean byteBackedPattern, NamedCharacterCache namedCharacterCache,
                     NamedCharacterExpansion.SourceMode namedCharacterSourceMode) {
        this(perlPattern, flags, trustedCalloutCount, forceAsciiClasses,
                byteMode, byteBackedPattern, namedCharacterCache,
                namedCharacterSourceMode, false);
    }

    JoniRegexPattern(String perlPattern, RegexFlags flags, int trustedCalloutCount,
                     boolean forceAsciiClasses, boolean byteMode,
                     boolean byteBackedPattern, NamedCharacterCache namedCharacterCache,
                     NamedCharacterExpansion.SourceMode namedCharacterSourceMode,
                     boolean perlReStrict) {
        this.flags = flags;
        this.byteMode = byteMode;
        sourcePattern = RuntimeRegexTemplate.materializeTrustedCallouts(
                perlPattern, trustedCalloutCount);
        compatibilityPatternDescription = legacyCompatibilityDescription(
                sourcePattern, flags, trustedCalloutCount);
        compileWarnings = new ArrayList<>();
        java.nio.charset.Charset sourceCharset = byteMode && byteBackedPattern
                ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
        byte[] bytes = sourcePattern.getBytes(sourceCharset);
        WarnCallback warningCollector = new WarnCallback() {
            @Override
            public void warn(String message) {
                compileWarnings.add(message);
            }

            @Override
            public void warn(String message, int bytePosition) {
                int bounded = Math.max(0, Math.min(bytePosition, bytes.length));
                int characterOffset = new String(bytes, 0, bounded, sourceCharset).length();
                WarningDisplay display = message.equals(NAMED_SEQUENCE_CLASS_WARNING)
                        ? canonicalNamedSequenceWarningDisplay(
                                sourcePattern, characterOffset,
                                namedCharacterCache, namedCharacterSourceMode)
                        : message.equals(MIXED_NAMED_RANGE_WARNING)
                        ? canonicalNamedRangeWarningDisplay(
                                sourcePattern, characterOffset,
                                namedCharacterCache, namedCharacterSourceMode)
                        : new WarningDisplay(sourcePattern, characterOffset);
                compileWarnings.add(RegexDiagnosticFormatter.markedPerl(
                        display.pattern(), display.offset(), message));
            }

            @Override
            public boolean supportsPositions() {
                return true;
            }
        };
        DeferredPropertyState deferredPropertyState = new DeferredPropertyState();
        Syntax syntax = syntaxForNamedCharacters(
                namedCharacterCache, namedCharacterSourceMode,
                deferredPropertyState);
        int options = toJoniOptions(flags, forceAsciiClasses, perlReStrict);
        if (byteMode && byteBackedPattern) options |= Option.PERL_BYTE_PATTERN;
        regex = new Regex(bytes, 0, bytes.length, options,
                byteMode ? ISO8859_1Encoding.INSTANCE : UTF8Encoding.INSTANCE,
                syntax, warningCollector);
        hasControlVerbState = regex.hasControlVerbs();
        hasDeferredUserDefinedUnicodeProperty = deferredPropertyState.deferred;
        hasUserDefinedUnicodeProperty = deferredPropertyState.userDefined;
        NamedGroupMaps groupMaps = collectNamedGroups(regex);
        namedGroups = groupMaps.logical();
        physicalNamedGroups = groupMaps.physical();
    }

    private record WarningDisplay(String pattern, int offset) {}

    /**
     * Perl renders a named sequence as its canonical U+ list when a character
     * class forces the sequence into a single-code-point context.  Joni parses
     * the source spelling so its warning byte position still refers to the
     * original text; replace only the escape ending at that position and move
     * the marker by the corresponding character delta.
     */
    private static WarningDisplay canonicalNamedSequenceWarningDisplay(
            String pattern, int offset, NamedCharacterCache cache,
            NamedCharacterExpansion.SourceMode sourceMode) {
        int escapeStart = pattern.lastIndexOf("\\N{", Math.max(0, offset - 1));
        if (escapeStart < 0) return new WarningDisplay(pattern, offset);
        int nameStart = escapeStart + 3;
        int close = pattern.indexOf('}', nameStart);
        if (close < 0 || close + 1 != offset) {
            return new WarningDisplay(pattern, offset);
        }

        String name = pattern.substring(nameStart, close);
        NamedCharacterExpansion expansion = cache.resolve(name, sourceMode);
        if (!expansion.resolved()
                || expansion.sequence().codePointCount(
                        0, expansion.sequence().length()) <= 1) {
            return new WarningDisplay(pattern, offset);
        }

        String canonical = canonicalNamedCharacterEscape(expansion.sequence());
        String displayPattern = pattern.substring(0, escapeStart)
                + canonical + pattern.substring(close + 1);
        return new WarningDisplay(displayPattern,
                escapeStart + canonical.length());
    }

    /** Canonicalizes the named endpoint in Perl's mixed Unicode-range warning. */
    private static WarningDisplay canonicalNamedRangeWarningDisplay(
            String pattern, int offset, NamedCharacterCache cache,
            NamedCharacterExpansion.SourceMode sourceMode) {
        int escapeStart = pattern.lastIndexOf("\\N{", Math.max(0, offset - 1));
        if (escapeStart < 0) return new WarningDisplay(pattern, offset);
        int nameStart = escapeStart + 3;
        int close = pattern.indexOf('}', nameStart);
        if (close < 0 || close >= offset) return new WarningDisplay(pattern, offset);

        NamedCharacterExpansion expansion = cache.resolve(
                pattern.substring(nameStart, close), sourceMode);
        if (!expansion.resolved() || expansion.sequence().isEmpty()) {
            return new WarningDisplay(pattern, offset);
        }

        String canonical = canonicalNamedCharacterEscape(expansion.sequence());
        String displayPattern = pattern.substring(0, escapeStart)
                + canonical + pattern.substring(close + 1);
        return new WarningDisplay(displayPattern,
                offset + canonical.length() - (close + 1 - escapeStart));
    }

    private static String canonicalNamedCharacterEscape(String sequence) {
        StringBuilder canonical = new StringBuilder("\\N{U+");
        boolean first = true;
        for (int codePoint : sequence.codePoints().toArray()) {
            if (!first) canonical.append('.');
            if (codePoint < 0x10) canonical.append('0');
            canonical.append(Integer.toHexString(codePoint).toUpperCase());
            first = false;
        }
        return canonical.append('}').toString();
    }

    RegexMatcher matcher(String input, List<RuntimeRegexCallback> callbacks) {
        return matcher(input, callbacks, new RuntimeScalar(input));
    }

    RegexMatcher matcher(String input, List<RuntimeRegexCallback> callbacks,
                         RuntimeScalar subject) {
        return new JoniRegexMatcher(regex, sourcePattern, namedGroups, physicalNamedGroups, flags,
                hasControlVerbState, byteMode, input, callbacks, subject);
    }

    Map<String, Integer> namedGroups() {
        return namedGroups;
    }

    record InputEncoding(byte[] bytes, int[] charToByte, int[] byteToChar) {}

    private record SubjectInputEncodings(Object value, int type, boolean uncheckedOctets,
                                        InputEncoding unicode, InputEncoding bytes) {}

    static InputEncoding inputEncoding(String input, RuntimeScalar subject, boolean byteMode) {
        boolean directImmutableString = subject != null
                && (subject.type == RuntimeScalarType.STRING
                        || subject.type == RuntimeScalarType.BYTE_STRING)
                && subject.value == input;
        if (!directImmutableString) {
            return byteMode ? byteInputEncoding(input) : inputEncoding(input);
        }
        Object value = subject.value;

        synchronized (SUBJECT_INPUT_ENCODINGS) {
            SubjectInputEncodings cached = SUBJECT_INPUT_ENCODINGS.get(subject);
            if (cached != null && cached.value == value && cached.type == subject.type
                    && cached.uncheckedOctets == subject.utf8UncheckedOctets) {
                InputEncoding encoding = byteMode ? cached.bytes : cached.unicode;
                if (encoding != null) return encoding;
            }

            InputEncoding unicode = cached != null && cached.value == value
                    && cached.type == subject.type
                    && cached.uncheckedOctets == subject.utf8UncheckedOctets
                    ? cached.unicode : null;
            InputEncoding bytes = cached != null && cached.value == value
                    && cached.type == subject.type
                    && cached.uncheckedOctets == subject.utf8UncheckedOctets
                    ? cached.bytes : null;
            if (byteMode) {
                bytes = buildByteInputEncoding(input);
            } else {
                unicode = buildInputEncoding(input);
            }
            SUBJECT_INPUT_ENCODINGS.put(subject, new SubjectInputEncodings(
                    value, subject.type, subject.utf8UncheckedOctets, unicode, bytes));
            return byteMode ? bytes : unicode;
        }
    }

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
        return compatibilityPatternDescription;
    }

    Regex engineRegex() {
        return regex;
    }

    String optimizerDebugDescription() {
        org.joni.Regex.OptimizationInfo info = regex.getOptimizationInfo();
        StringBuilder description = new StringBuilder("optimizer search=")
                .append(info.searchAlgorithm())
                .append(" minlen=").append(info.minimumLength());
        if (info.exact() != null) {
            description.append(" exact=<").append(info.exact()).append('>')
                    .append(" offset=").append(info.minimumOffset()).append("..");
            description.append(info.maximumOffset() == null
                    ? "infinity" : info.maximumOffset());
        }
        int anchor = regex.getAnchor();
        if ((anchor & AnchorType.ANYCHAR_STAR_ML) != 0) {
            description.append(" anchored(SBOL) implicit");
        } else if ((anchor & AnchorType.ANYCHAR_STAR) != 0) {
            description.append(" anchored(MBOL) implicit");
        }
        return description.toString();
    }

    String nativeCompileDebugDescription() {
        return regex.byteCodeDebugDescription();
    }

    boolean hasOnlyAuthoritativeWideCharacterClasses() {
        return regex.hasOnlyAuthoritativeWideCharacterClasses();
    }

    boolean hasUnicodeCharsetModifier() {
        return regex.hasUnicodeCharsetModifier();
    }

    boolean hasDeferredUserDefinedUnicodeProperty() {
        return hasDeferredUserDefinedUnicodeProperty;
    }

    boolean hasUserDefinedUnicodeProperty() {
        return hasUserDefinedUnicodeProperty;
    }

    List<String> compileWarnings() {
        return List.copyOf(compileWarnings);
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

    private static int toJoniOptions(RegexFlags flags, boolean forceAsciiClasses,
                                     boolean perlReStrict) {
        int options = Option.NONE;
        if (flags.isCaseInsensitive()) options |= Option.IGNORECASE;
        if (flags.isExtended()) options |= Option.EXTEND;
        if (flags.isExtendedWhitespace()) options |= Option.EXTEND | Option.PERL_EXTEND_MORE;
        // Oniguruma's MULTILINE option controls whether dot matches newline.
        if (flags.isDotAll()) options |= Option.MULTILINE;
        if (!flags.isMultiLine()) options |= Option.SINGLELINE;
        if (flags.isAscii() || forceAsciiClasses) options |= Option.ASCII_RANGE;
        if (flags.isAscii()) options |= Option.PERL_EXPLICIT_ASCII;
        if (flags.isAsciiStrict()) options |= Option.PERL_ASCII_STRICT;
        if (flags.isLocale()) options |= Option.PERL_LOCALE;
        if (perlReStrict) options |= Option.PERL_RE_STRICT;
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
        boolean hasSubroutineCall = pattern.matches("(?s).*\\(\\?[+-]?\\d+\\).*")
                || pattern.contains("(?&")
                || pattern.contains("(?P>");
        PerlSyntaxFeatures syntaxFeatures = analyzePerlSyntax(
                pattern, flags != null && flags.isExtended());
        return flags != null && flags.isAsciiStrict()
                || syntaxFeatures.asciiStrictPresent()
                || syntaxFeatures.keepPresent()
                || syntaxFeatures.lookbehindPresent()
                || syntaxFeatures.nativeExtendedClassPresent()
                || syntaxFeatures.branchResetPresent()
                || syntaxFeatures.conditionalPresent()
                || syntaxFeatures.alphaAssertionPresent()
                || pattern.contains("(?{=CALL:")
                || pattern.contains("(?{=DYNAMIC:")
                || containsNamedCharacterEscape(pattern)
                || pattern.contains("(*ACCEPT)")
                || pattern.contains("(*ACCEPT:")
                || pattern.contains("(*FAIL")
                || pattern.contains("(*F)")
                || pattern.contains("(*F:")
                || pattern.contains("(*PRUNE")
                || pattern.contains("(*SKIP")
                || pattern.contains("(*THEN")
                || pattern.contains("(*COMMIT")
                || pattern.contains("(*MARK")
                || pattern.contains("(*:")
                || containsPerlEmptyCharacterClass(pattern)
                || hasSubroutineCall;
    }

    private static boolean containsPerlEmptyCharacterClass(String pattern) {
        boolean quoted = false;
        for (int i = 0; i + 1 < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '\\') {
                char next = pattern.charAt(i + 1);
                if (quoted && next == 'E') quoted = false;
                else if (!quoted && next == 'Q') quoted = true;
                i++;
                continue;
            }
            if (!quoted && ch == '[' && pattern.charAt(i + 1) == ']') return true;
        }
        return false;
    }

    static boolean containsNamedCharacterEscape(String pattern) {
        if (pattern == null) return false;
        for (int i = 0; i + 2 < pattern.length(); i++) {
            if (pattern.charAt(i) != '\\') continue;
            int endSlashes = i;
            while (endSlashes < pattern.length()
                    && pattern.charAt(endSlashes) == '\\') {
                endSlashes++;
            }
            if (((endSlashes - i) & 1) != 0 && endSlashes + 1 < pattern.length()
                    && pattern.charAt(endSlashes) == 'N'
                    && pattern.charAt(endSlashes + 1) == '{') {
                return true;
            }
            i = endSlashes - 1;
        }
        return false;
    }

    private record PerlSyntaxFeatures(boolean keepPresent,
                                      boolean keepInLookaround,
                                      boolean lookbehindPresent,
                                      boolean nativeExtendedClassPresent,
                                      boolean branchResetPresent,
                                      boolean conditionalPresent,
                                      boolean alphaAssertionPresent,
                                      boolean asciiStrictPresent) {}

    private static PerlSyntaxFeatures analyzePerlSyntax(String pattern, boolean extended) {
        boolean quoted = false;
        boolean inClass = false;
        boolean classStart = false;
        int extendedClassDepth = 0;
        int lookaroundDepth = 0;
        java.util.ArrayDeque<Boolean> groups = new java.util.ArrayDeque<>();
        boolean keepPresent = false;
        boolean lookbehindPresent = false;
        boolean nativeExtendedClassPresent = false;
        boolean branchResetPresent = false;
        boolean conditionalPresent = false;
        boolean alphaAssertionPresent = false;
        boolean asciiStrictPresent = false;

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
                    if (pattern.charAt(i + 1) == 'p' || pattern.charAt(i + 1) == 'P') {
                        nativeExtendedClassPresent = true;
                    }
                    i++;
                } else if (ch == '[' && i + 1 < pattern.length()
                        && pattern.charAt(i + 1) == ':') {
                    nativeExtendedClassPresent = true;
                    extendedClassDepth++;
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
                        return new PerlSyntaxFeatures(true, true, lookbehindPresent, nativeExtendedClassPresent, branchResetPresent, conditionalPresent,
                                alphaAssertionPresent, asciiStrictPresent);
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
                if (pattern.startsWith("(?", i)) {
                    int positiveAsciiModifiers = 0;
                    boolean negativeModifiers = false;
                    for (int j = i + 2; j < pattern.length(); j++) {
                        char modifier = pattern.charAt(j);
                        if (modifier == ':' || modifier == ')') {
                            asciiStrictPresent |= positiveAsciiModifiers >= 2;
                            break;
                        }
                        if (modifier == '-') {
                            negativeModifiers = true;
                            continue;
                        }
                        if (modifier == '^') continue;
                        if (modifier < 'a' || modifier > 'z') break;
                        if (modifier == 'a' && !negativeModifiers) {
                            positiveAsciiModifiers++;
                        }
                    }
                }
                if (pattern.startsWith("(*", i)) {
                    int nameEnd = i + 2;
                    while (nameEnd < pattern.length()) {
                        char nameChar = pattern.charAt(nameEnd);
                        if (!Character.isLetter(nameChar) && nameChar != '_') break;
                        nameEnd++;
                    }
                    String name = pattern.substring(i + 2, nameEnd);
                    alphaAssertionPresent |= name.equals("pla")
                            || name.equals("positive_lookahead")
                            || name.equals("plb")
                            || name.equals("positive_lookbehind")
                            || name.equals("nla")
                            || name.equals("negative_lookahead")
                            || name.equals("nlb")
                            || name.equals("negative_lookbehind")
                            || name.equals("atomic")
                            || name.equals("script_run")
                            || name.equals("sr")
                            || name.equals("atomic_script_run")
                            || name.equals("asr");
                }
                boolean lookaround = pattern.startsWith("(?=", i)
                        || pattern.startsWith("(?!", i)
                        || pattern.startsWith("(?<=", i)
                        || pattern.startsWith("(?<!", i);
                lookbehindPresent |= pattern.startsWith("(?<=", i)
                        || pattern.startsWith("(?<!", i);
                branchResetPresent |= pattern.startsWith("(?|", i);
                groups.push(lookaround);
                if (lookaround) lookaroundDepth++;
            } else if (ch == ')' && !groups.isEmpty()) {
                if (groups.pop()) lookaroundDepth--;
            }
        }
        return new PerlSyntaxFeatures(keepPresent, false, lookbehindPresent, nativeExtendedClassPresent, branchResetPresent, conditionalPresent,
                alphaAssertionPresent, asciiStrictPresent);
    }

    /**
     * Test-facing compatibility normalizer. Production patterns retain raw
     * {@code \N{...}} source for Joni's resolver; this helper keeps its
     * historical one-code-point assertion independent of that native path.
     */
    static String translatePattern(String pattern) {
        RegexFlags flags = RegexFlags.fromModifiers("", pattern);
        return legacyCompatibilityDescription(
                translateCompatibilityPattern(pattern, flags), flags, 0);
    }

    /**
     * Preserves the package-private description contract used by legacy unit
     * tests and debug output. Production compilation, matching, warnings, and
     * diagnostics use {@link #sourcePattern} unchanged, so this must never feed
     * Joni or runtime regex reconstruction.
     */
    private static String legacyCompatibilityDescription(String pattern,
                                                         RegexFlags flags,
                                                         int trustedCalloutCount) {
        StringBuilder description = new StringBuilder(pattern.length() + 8);
        boolean escaped = false;
        boolean inClass = false;
        boolean inlineExtendedOption = hasInlineExtendedOption(pattern);
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                description.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\' && i + 3 < pattern.length()
                    && (pattern.charAt(i + 1) == 'p' || pattern.charAt(i + 1) == 'P')
                    && pattern.charAt(i + 2) == '{') {
                int end = pattern.indexOf('}', i + 3);
                if (end > i + 3) {
                    String property = pattern.substring(i + 3, end).trim();
                    if (isLegacyAgeWildcard(property)) {
                        String propertyClass = UnicodeResolver.translateUnicodeProperty(
                                property, pattern.charAt(i + 1) == 'P',
                                flags.isCaseInsensitive());
                        description.append("(?-i:")
                                .append(normalizeGeneratedPropertyClassForJoni(propertyClass))
                                .append(')');
                        i = end;
                        continue;
                    }
                }
            }
            if (ch == '\\') {
                description.append(ch);
                escaped = true;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                description.append(ch);
                continue;
            }
            if (ch == ']' && inClass) {
                inClass = false;
                description.append(ch);
                continue;
            }
            if (!inClass && pattern.startsWith("(*:", i)) {
                description.append("(*MARK:");
                i += 2;
                continue;
            }
            if (inClass && flags.isExtendedWhitespace() && !inlineExtendedOption
                    && Character.isWhitespace(ch)) {
                continue;
            }
            if (!inClass && pattern.startsWith("(?)", i)) {
                description.append("(?:)");
                i += 2;
                continue;
            }
            if (!inClass && pattern.startsWith("(?{", i)
                    && !isTrustedCallout(pattern, i, trustedCalloutCount)) {
                int end = findCodeBlockEnd(pattern, i);
                if (end >= 0) {
                    description.append("(?:)");
                    i = end;
                    continue;
                }
            }
            description.append(ch);
        }
        return description.toString();
    }

    private static boolean isLegacyAgeWildcard(String property) {
        return property.matches(
                "(?is)^(?:age|in|present[-_ ]?in)\\s*(?:=|:(?!:))\\s*:\\\\A.*\\\\z:$");
    }

    private static boolean hasInlineExtendedOption(String pattern) {
        boolean escaped = false;
        boolean inClass = false;
        for (int i = 0; i + 2 < pattern.length(); i++) {
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
            if (inClass || ch != '(' || pattern.charAt(i + 1) != '?') continue;
            for (int j = i + 2; j < pattern.length(); j++) {
                char option = pattern.charAt(j);
                if (option == ':' || option == ')') break;
                if (option == 'x') return true;
                if (option == '-' || option == '^'
                        || option >= 'a' && option <= 'z') continue;
                break;
            }
        }
        return false;
    }

    private static String translateCompatibilityPattern(String pattern, RegexFlags flags) {
        StringBuilder out = new StringBuilder(pattern.length() + 16);
        boolean escaped = false;
        boolean inClass = false;
        boolean atClassStart = false;
        boolean classAllowsLeadingClose = false;
        int posixClassDepth = 0;
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
                if (pattern.startsWith("\\N{", i)) {
                    int end = pattern.indexOf('}', i + 3);
                    if (end > i + 3) {
                        int codePoint = UnicodeResolver.getCodePointFromName(
                                pattern.substring(i + 3, end));
                        appendResolvedNamedCharacter(out, codePoint, flags);
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
                                    || pattern.charAt(i + 1) == '=')
                            && !(i + 2 < pattern.length() && pattern.charAt(i + 2) == ']');
                    if (posixClass) {
                        posixClassDepth++;
                        atClassStart = false;
                        classAllowsLeadingClose = false;
                        out.append(ch);
                        continue;
                    }
                    out.append(ch);
                    atClassStart = false;
                    classAllowsLeadingClose = false;
                    continue;
                }
                inClass = true;
                atClassStart = true;
                classAllowsLeadingClose = true;
                out.append(ch);
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
                continue;
            }
            if (inClass) {
                atClassStart = false;
                classAllowsLeadingClose = false;
            }
            if (!inClass && pattern.startsWith("(?[", i)) {
                int bracketDepth = 0;
                boolean comment = false;
                int end = i + 3;
                for (; end < pattern.length(); end++) {
                    char extended = pattern.charAt(end);
                    if (comment) {
                        if (extended == '\n' || extended == '\r') comment = false;
                        continue;
                    }
                    if (extended == '#' && bracketDepth == 0) {
                        comment = true;
                        continue;
                    }
                    if (extended == '\\') {
                        if (end + 1 < pattern.length()) end++;
                        continue;
                    }
                    if (extended == '[') {
                        bracketDepth++;
                        continue;
                    }
                    if (extended != ']') continue;
                    if (bracketDepth > 0) {
                        bracketDepth--;
                        continue;
                    }
                    if (end + 1 < pattern.length() && pattern.charAt(end + 1) == ')') {
                        end++;
                        break;
                    }
                }
                out.append(pattern, i, Math.min(end + 1, pattern.length()));
                i = Math.min(end, pattern.length() - 1);
                continue;
            }
            out.append(ch);
        }
        return out.toString();
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
        private int consumedStart = -1;
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
            InputEncoding encoding = inputEncoding(input, subject, byteMode);
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
                return false;
            }
            matcher = regex.matcher(bytes);
            if (!callbacks.isEmpty()) {
                calloutHandler = new PerlCalloutHandler(
                        input, byteToChar, callbacks, flags, hasControlVerbState, byteMode, subject);
                matcher.setCalloutHandler(calloutHandler);
            }
            int result;
            boolean directMatch = globalPosition < 0 && anchored;
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
            boolean encounteredControlVerb = matcher.hasEncounteredControlVerb();
            if ((matched && hasControlVerbState) || encounteredControlVerb) {
                String mark = matcher.getControlMark();
                if (matched && mark == null) mark = "1";
                RuntimeRegex.updateControlVerbVariables(
                        mark, matcher.getControlError());
            }
            if (calloutHandler != null) calloutHandler.finish(matched);
            if (!matched) {
                consumedStart = -1;
                committedLastClosedCapture = -1;
                return false;
            }
            consumedStart = directMatch ? nextStart : toCharOffset(result);
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
            nextStart = end > consumedStart ? end : advanceCodePoint(end);
            return true;
        }

        @Override
        public void region(int start, int end) {
            regionStart = Math.max(0, Math.min(start, input.length()));
            regionEnd = Math.max(regionStart, Math.min(end, input.length()));
            nextStart = regionStart;
            consumedStart = -1;
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
        @Override public int consumedStart() { return consumedStart; }
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
            if (index == 0 && begin > end) return null;
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
        private final boolean byteMode;
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
        private final ArrayDeque<RegexCallbackMutationSnapshot> callbackMutations =
                new ArrayDeque<>();
        private boolean preserveCallbackMutations;

        PerlCalloutHandler(String input, int[] byteToChar, List<RuntimeRegexCallback> callbacks,
                           RegexFlags outerFlags, boolean publishesControlVerbState,
                           boolean byteMode, RuntimeScalar subject) {
            this(input, byteToChar, callbacks, outerFlags, publishesControlVerbState,
                    byteMode, subject, null);
        }

        private PerlCalloutHandler(
                String input, int[] byteToChar, List<RuntimeRegexCallback> callbacks,
                RegexFlags outerFlags, boolean publishesControlVerbState,
                boolean byteMode, RuntimeScalar subject, PerlCalloutHandler parent) {
            this.input = input;
            this.byteToChar = byteToChar;
            this.callbacks = callbacks;
            this.outerFlags = outerFlags;
            this.publishesControlVerbState = publishesControlVerbState;
            this.byteMode = byteMode;
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
            if (value.type == RuntimeScalarType.UNDEF
                    && callback.uninitializedWarningsEnabled) {
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
                        template.callbacks().size(), false,
                        byteMode && template.byteBackedPattern(), template.byteBackedPattern());
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
                    try {
                        boolean byteBackedDynamic = value.type == RuntimeScalarType.BYTE_STRING;
                        boolean compileAsBytes = byteMode && byteBackedDynamic;
                        nestedPattern = new JoniRegexPattern(dynamicSource, outerFlags, 0,
                                compileAsBytes, compileAsBytes, byteBackedDynamic);
                    } catch (SyntaxException exception) {
                        String message = exception.getMessage();
                        if (message != null && (message.contains("premature end of char-class")
                                || message.contains("Unclosed character class"))) {
                            int open = dynamicSource.indexOf('[');
                            throw new PerlCompilerException(RegexDiagnosticFormatter.markedPerl(
                                    dynamicSource, open < 0 ? 0 : open + 1, "Unmatched ["));
                        }
                        throw exception;
                    }
                }
            }
            CalloutHandler nestedHandler = nestedCallbacks.isEmpty() ? null
                    : new PerlCalloutHandler(input, byteToChar, nestedCallbacks,
                            value.value instanceof RuntimeRegex runtimeRegex
                                    && runtimeRegex.getRegexFlags() != null
                                    ? runtimeRegex.getRegexFlags() : outerFlags,
                            nestedPattern.hasControlVerbState,
                            byteMode,
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
            if (callback.kind == RuntimeRegexCallback.Kind.BLOCK && parent == null) {
                callbackMutations.addLast(RegexCallbackMutationSnapshot.capture(callback.code));
            }
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
            Token token = (Token) value;
            if (token.block() && parent == null) preserveCallbackMutations = true;
            restore(token, true);
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
                    if (!preserveCallbackMutations) restoreCallbackMutations();
                    initialRegexState.restore();
                    if (hasFailedNestedCaptureState) {
                        state.lastClosedCapture = failedNestedLastClosedCapture;
                        state.lastParenMatchOverrideActive = true;
                        state.lastParenMatchOverride = failedNestedLastParenMatch;
                    }
                }
            } finally {
                callbackMutations.clear();
                DynamicVariableManager.popToLocalLevel(initialLocalLevel);
            }
        }

        private void recordFailedNestedCaptureState(String lastClosed, String lastParen) {
            hasFailedNestedCaptureState = true;
            failedNestedLastClosedCapture = lastClosed;
            failedNestedLastParenMatch = lastParen;
        }

        void abort() {
            // Perl retains ordinary assignments performed before a callback
            // exception; only dynamic local() scope is unwound here.
            callbackMutations.clear();
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

        private void restoreCallbackMutations() {
            while (!callbackMutations.isEmpty()) {
                callbackMutations.removeLast().restore();
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
