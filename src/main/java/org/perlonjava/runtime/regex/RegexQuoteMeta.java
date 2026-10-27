package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.WarningFlags;

public class RegexQuoteMeta {
    private static final int CALL_SITE_WARNING_UNSET = Integer.MIN_VALUE;

    static final class CallSiteWarningHolder {
        int state = CALL_SITE_WARNING_UNSET;
        String bits;
    }

    private static final ThreadLocal<CallSiteWarningHolder> CALL_SITE_WARNING =
            ThreadLocal.withInitial(CallSiteWarningHolder::new);
    private static final ThreadLocal<String> PARSER_WARNING_BITS = new ThreadLocal<>();
    private static final ThreadLocal<String> MATCH_TARGET_NAME = new ThreadLocal<>();

    /** Lexical outcome for one regex-construction diagnostic. */
    public record ConstructionWarningDisposition(boolean enabled, boolean fatal) {
    }

    public static void setCallSiteWarningState(int state) {
        CallSiteWarningHolder h = CALL_SITE_WARNING.get();
        if (h.state != state) h.state = state;
    }

    public static void setCallSiteWarningBits(String bits) {
        CallSiteWarningHolder h = CALL_SITE_WARNING.get();
        if (h.bits != bits) h.bits = bits;
    }

    public static void setCallSiteWarning(int state, String bits) {
        CallSiteWarningHolder h = CALL_SITE_WARNING.get();
        if (h.state != state) h.state = state;
        if (h.bits != bits) h.bits = bits;
    }

    public static String getCallSiteWarningBits() {
        return CALL_SITE_WARNING.get().bits;
    }

    public static boolean isCallSiteWarningExplicitlyDisabled() {
        int state = CALL_SITE_WARNING.get().state;
        return state != CALL_SITE_WARNING_UNSET && state < 0;
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
        CallSiteWarningHolder h = CALL_SITE_WARNING.get();
        int state = h.state;
        RuntimeScalar warning = new RuntimeScalar(message);
        RuntimeScalar where = new RuntimeScalar("");
        String warningBits = h.bits;
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
        } else if (warningBits != null || (state != CALL_SITE_WARNING_UNSET && state != 0)) {
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
        CallSiteWarningHolder h = CALL_SITE_WARNING.get();
        String warningBits = h.bits;
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

        int state = h.state;
        if (state != CALL_SITE_WARNING_UNSET) {
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
