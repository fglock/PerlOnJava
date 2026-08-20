package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.WarningFlags;

import java.util.ArrayList;
import java.util.List;

public class RegexQuoteMeta {
    private static final ThreadLocal<List<String>> WARNINGS_ON_USE = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Integer> CALL_SITE_WARNING_STATE = new ThreadLocal<>();
    private static final ThreadLocal<String> CALL_SITE_WARNING_BITS = new ThreadLocal<>();
    private static final ThreadLocal<String> PARSER_WARNING_BITS = new ThreadLocal<>();
    private static final ThreadLocal<String> MATCH_TARGET_NAME = new ThreadLocal<>();

    public static void setCallSiteWarningState(int state) {
        CALL_SITE_WARNING_STATE.set(state);
    }

    public static void setCallSiteWarningBits(String bits) {
        CALL_SITE_WARNING_BITS.set(bits);
    }

    public static String getCallSiteWarningBits() {
        return CALL_SITE_WARNING_BITS.get();
    }

    public static void setParserWarningBits(String bits) {
        if (bits == null) PARSER_WARNING_BITS.remove();
        else PARSER_WARNING_BITS.set(bits);
    }

    public static String getParserWarningBits() {
        return PARSER_WARNING_BITS.get();
    }

    public static void setMatchTargetName(String name) {
        MATCH_TARGET_NAME.set(name);
    }

    public static String getMatchTargetName() {
        return MATCH_TARGET_NAME.get();
    }

    public static String escapeQ(String s) {
        WARNINGS_ON_USE.get().clear();
        StringBuilder sb = new StringBuilder();
        int len = s.length();
        int offset = 0;
        boolean inCharClass = false;
        boolean charClassFirst = false;
        boolean escaped = false;

        // Predefined set of regex metacharacters
        final String regexMetacharacters = "-.+*?[](){}^$|\\";

        while (offset < len) {
            char c = s.charAt(offset);
            if (escaped) {
                if (inCharClass && (c == 'Q' || c == 'E')) {
                    warnUnrecognizedCharClassEscape(c);
                    sb.append(c);
                    if (charClassFirst && c != '^') {
                        charClassFirst = false;
                    }
                    escaped = false;
                    offset++;
                    continue;
                }
                sb.append('\\');
                sb.append(c);
                escaped = false;
                offset++;
                continue;
            }

            if (c == '\\' && offset + 1 < len && s.charAt(offset + 1) == 'Q') {
                if (inCharClass) {
                    warnUnrecognizedCharClassEscape('Q');
                    sb.append('Q');
                    if (charClassFirst) {
                        charClassFirst = false;
                    }
                    offset += 2;
                    continue;
                }
                // Skip past \Q
                offset += 2;

                // Process characters until \E or end of string
                while (offset < len) {
                    if (s.charAt(offset) == '\\' && offset + 1 < len && s.charAt(offset + 1) == 'E') {
                        // Skip past \E and stop quoting
                        offset += 2;
                        break;
                    }

                    // Escape regex metacharacters
                    char currentChar = s.charAt(offset);
                    if (regexMetacharacters.indexOf(currentChar) != -1) {
                        sb.append('\\'); // Escape the metacharacter
                    }
                    sb.append(currentChar);
                    offset++;
                }
            } else {
                if (c == '\\') {
                    escaped = true;
                    offset++;
                    continue;
                }
                if (c == '[' && !inCharClass) {
                    inCharClass = true;
                    charClassFirst = true;
                } else if (c == ']' && inCharClass && !charClassFirst) {
                    inCharClass = false;
                } else if (inCharClass && charClassFirst && c != '^') {
                    charClassFirst = false;
                }
                sb.append(c);
                offset++;
            }
        }
        if (escaped) {
            sb.append('\\');
        }

        return sb.toString();
    }

    public static List<String> getWarningsOnUse() {
        return new ArrayList<>(WARNINGS_ON_USE.get());
    }

    private static void warnUnrecognizedCharClassEscape(char c) {
        String message = "Unrecognized escape \\" + c + " in character class passed through in regex";
        warnAtConstruction(message);
    }

    /** Emit a regex-construction warning using the lexical state captured at the quote site. */
    public static void warnAtConstruction(String message) {
        // This warning belongs to construction of the interpolated pattern.
        // Retaining it on the cached RuntimeRegex re-emits it for every match
        // and can leak a warning-enabled compilation into a no-warnings use.
        Integer state = CALL_SITE_WARNING_STATE.get();
        RuntimeScalar warning = new RuntimeScalar(message);
        RuntimeScalar where = new RuntimeScalar("");
        String warningBits = CALL_SITE_WARNING_BITS.get();
        if (warningBits == null) {
            // Synthetic/interpolated regex wrappers do not always have a
            // dedicated quote opcode. Use the active statement mask rather
            // than a stale warning-state value from an earlier qr//.
            warningBits = WarningBitsRegistry.getRuntimeWarningBits();
        }
        if (warningBits != null) {
            String category = warningCategory(message);
            if (!WarningFlags.isEnabledInBits(warningBits, category)) {
                return;
            }
            if (WarningFlags.isFatalInBits(warningBits, category)) {
                WarnDie.die(warning, where);
            } else {
                WarnDie.warn(warning, where);
            }
            return;
        }
        if (state != null && state == 0) {
            return;
        }
        if (state != null && state == 2) {
            WarnDie.die(warning, where);
        } else if (state != null) {
            WarnDie.warn(warning, where);
        } else {
            WarnDie.warnWithCategory(warning, where, "regexp");
        }
    }

    /** Perl assigns a few lexer diagnostics to broader warning categories. */
    static String warningCategory(String message) {
        if (message != null && message.startsWith("Useless use of \\E")) {
            return "misc";
        }
        if (message != null && message.contains("is more clearly written simply as")) {
            return "syntax";
        }
        return "regexp";
    }
}
