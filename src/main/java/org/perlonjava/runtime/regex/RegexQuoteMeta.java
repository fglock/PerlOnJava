package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.WarningFlags;

public class RegexQuoteMeta {
    private static final ThreadLocal<Integer> CALL_SITE_WARNING_STATE = new ThreadLocal<>();
    private static final ThreadLocal<String> CALL_SITE_WARNING_BITS = new ThreadLocal<>();
    private static final ThreadLocal<String> PARSER_WARNING_BITS = new ThreadLocal<>();
    private static final ThreadLocal<String> MATCH_TARGET_NAME = new ThreadLocal<>();

    /** Lexical outcome for one regex-construction diagnostic. */
    public record ConstructionWarningDisposition(boolean enabled, boolean fatal) {
    }

    public static void setCallSiteWarningState(int state) {
        CALL_SITE_WARNING_STATE.set(state);
    }

    public static void setCallSiteWarningBits(String bits) {
        CALL_SITE_WARNING_BITS.set(bits);
    }

    public static String getCallSiteWarningBits() {
        return CALL_SITE_WARNING_BITS.get();
    }

    public static boolean isCallSiteWarningExplicitlyDisabled() {
        Integer state = CALL_SITE_WARNING_STATE.get();
        return state != null && state < 0;
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

    /** Emit a regex-construction warning using the lexical state captured at the quote site. */
    public static void warnAtConstruction(String message) {
        warnAtConstruction(message, false);
    }

    static void warnAtConstruction(String message, boolean strictDefault) {
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
        ConstructionWarningDisposition disposition =
                constructionWarningDisposition(message, strictDefault);
        if (!disposition.enabled()) {
            return;
        }
        if (disposition.fatal()) {
            WarnDie.die(warning, new RuntimeScalar(WarnDie.getPerlLocationFromStack()));
        } else if (warningBits != null || state != null) {
            WarnDie.warn(warning, where);
        } else {
            WarnDie.warnWithCategory(warning, where, warningCategory(message));
        }
    }

    /**
     * Resolve warning enablement without dispatching a handler. Fatal regex
     * diagnostics can then be accumulated with a primary compile error while
     * ordinary warnings still flow through {@link #warnAtConstruction}.
     */
    public static ConstructionWarningDisposition constructionWarningDisposition(
            String message, boolean strictDefault) {
        String category = warningCategory(message);
        String warningBits = CALL_SITE_WARNING_BITS.get();
        if (warningBits == null) {
            warningBits = WarningBitsRegistry.getRuntimeWarningBits();
        }
        if (warningBits != null) {
            boolean enabled = WarningFlags.isEnabledInBits(warningBits, category);
            if (!enabled && !isCallSiteWarningExplicitlyDisabled()) {
                enabled = strictDefault
                        || (WarningFlags.isGlobalWarningVariableEnabled()
                            && !WarningFlags.isWarningSuppressedAtRuntime(category));
            }
            return new ConstructionWarningDisposition(enabled,
                    enabled && WarningFlags.isFatalInBits(warningBits, category));
        }

        Integer state = CALL_SITE_WARNING_STATE.get();
        if (state != null) {
            return new ConstructionWarningDisposition(state > 0, state == 2);
        }
        if (WarningFlags.isWarningSuppressedAtRuntime(category)) {
            return new ConstructionWarningDisposition(false, false);
        }
        return new ConstructionWarningDisposition(
                strictDefault || WarningFlags.isGlobalWarningVariableEnabled(), false);
    }

    /** Resolve a construction diagnostic against lexical bits captured by the parser. */
    public static ConstructionWarningDisposition constructionWarningDisposition(
            String message, boolean strictDefault, String warningBits) {
        if (warningBits == null) {
            return constructionWarningDisposition(message, strictDefault);
        }
        String category = warningCategory(message);
        boolean enabled = WarningFlags.isEnabledInBits(warningBits, category)
                || strictDefault;
        return new ConstructionWarningDisposition(enabled,
                enabled && WarningFlags.isFatalInBits(warningBits, category));
    }

    /** Perl assigns a few lexer diagnostics to broader warning categories. */
    static String warningCategory(String message) {
        if (message != null && message.startsWith("Useless use of \\E")) {
            return "misc";
        }
        if (message != null && message.contains("is more clearly written simply as")) {
            return "syntax";
        }
        if (message != null && message.startsWith("Variable length ")
                && message.contains("lookbehind with capturing is experimental")) {
            return "experimental::vlb";
        }
        if (message != null
                && (message.startsWith(
                        "The Unicode property wildcards feature is experimental")
                    || message.startsWith(
                        "Using just the single character results returned by \\p{} in (?[...])"))) {
            return "experimental::uniprop_wildcards";
        }
        return "regexp";
    }
}
