package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.regex.RuntimeRegex;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;
import static org.perlonjava.frontend.parser.SpecialBlockParser.getCompileTimeMutationScope;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalCodeRef;

/**
 * The Re class provides functionalities similar to the Perl re module.
 * 
 * <p>Currently implemented features:
 * <ul>
 *   <li>Lexical defaults for {@code /a}, {@code /aa}, {@code /d}, {@code /l},
 *       {@code /u}, {@code /i}, {@code /m}, {@code /s}, {@code /x},
 *       {@code /xx}, {@code /n}, and {@code /p}</li>
 *   <li>Lexical cancellation and block restoration with {@code no re '/flags'}</li>
 *   <li>{@code use re 'strict'} - Enables experimental regex warnings</li>
 *   <li>{@code use re 'debug'} - Lexically enables regex compilation/execution tracing</li>
 *   <li>{@code use re 'debugcolor'} - Enables colorized lexical regex tracing</li>
 *   <li>{@code re::is_regexp($ref)} - Check if reference is a compiled regex</li>
 *   <li>{@code re::regexp_pattern($ref)} - Return pattern and modifiers from qr//</li>
 *   <li>{@code re::optimization($ref)} - Inspect optimizer-selected regex facts</li>
 * </ul>
 * 
 * <p>Features not yet implemented (see {@code perldoc re}):
 * <ul>
 *   <li>{@code use re 'taint'} - Taint mode for regex</li>
 * </ul>
 */
public class Re extends PerlModuleBase {

    private record LexicalRegexOption(String ordinaryModifiers,
                                      List<String> charsetModifiers) {
    }

    private static void propagatePragmaFlags(ScopedSymbolTable source) {
        ScopedSymbolTable mutationScope = getCompileTimeMutationScope();
        if (source != null && mutationScope != null && mutationScope != source) {
            mutationScope.copyFlagsFrom(source);
        }
    }

    private static void warnRegexPragma(String message) {
        WarnDie.warn(new RuntimeScalar(message), new RuntimeScalar());
    }

    /**
     * Parse the slash-prefixed option accepted by Perl's re pragma.
     *
     * <p>The repeated {@code a} handling intentionally follows re.pm rather
     * than treating it as an ordinary run: all occurrences select {@code aa}
     * when there are exactly two, while three or more warn and select
     * {@code a}. Unknown characters invalidate the whole option, so callers
     * can parse before mutating lexical state.</p>
     */
    private static LexicalRegexOption lexicalRegexOption(String option,
                                                          boolean importing) {
        if (option == null || option.length() < 2 || option.charAt(0) != '/') {
            return null;
        }
        String flags = option.substring(1);
        int aCount = 0;
        for (int i = 0; i < flags.length(); i++) {
            if (flags.charAt(i) == 'a') aCount++;
        }

        boolean consumedA = false;
        int xCount = 0;
        StringBuilder ordinary = new StringBuilder();
        List<String> charsets = new ArrayList<>();
        String seenCharset = null;
        for (int i = 0; i < flags.length(); i++) {
            char flag = flags.charAt(i);
            if (flag == 'a') {
                if (consumedA) continue;
                consumedA = true;
                String charset;
                if (aCount > 2) {
                    warnRegexPragma("The \"a\" flag may only appear a maximum of twice");
                    charset = "a";
                } else {
                    charset = aCount == 2 ? "aa" : "a";
                }
                if (importing && seenCharset != null) {
                    warnCharsetConflict(seenCharset, charset);
                }
                charsets.add(charset);
                seenCharset = charset;
            } else if (flag == 'd' || flag == 'l' || flag == 'u') {
                String charset = String.valueOf(flag);
                if (importing && seenCharset != null) {
                    warnCharsetConflict(seenCharset, charset);
                }
                charsets.add(charset);
                seenCharset = charset;
            } else if (flag == 'x') {
                xCount++;
                if (xCount > 2) {
                    warnRegexPragma("The \"x\" flag may only appear a maximum of twice");
                }
            } else if ("imsnp".indexOf(flag) >= 0) {
                if (ordinary.indexOf(String.valueOf(flag)) < 0) {
                    ordinary.append(flag);
                }
            } else {
                warnRegexPragma("Unknown regular expression flag \"" + flag + "\"");
                return null;
            }
        }
        if (xCount > 0) {
            ordinary.append(xCount >= 2 ? "xx" : "x");
        }
        return new LexicalRegexOption(ordinary.toString(), charsets);
    }

    private static void warnCharsetConflict(String first, String second) {
        if (first.equals(second)) {
            warnRegexPragma("The \"" + first + "\" flag may not appear twice");
        } else {
            warnRegexPragma("The \"" + first + "\" and \"" + second
                    + "\" flags are exclusive");
        }
    }

    private static void clearLexicalCharset(ScopedSymbolTable symbolTable) {
        symbolTable.disableStrictOption(Strict.HINT_RE_ASCII
                | Strict.HINT_RE_ASCII_AA | Strict.HINT_RE_UNICODE);
        symbolTable.disableLexicalRegexModifiers("dlu");
    }

    private static String currentLexicalCharset(ScopedSymbolTable symbolTable) {
        if (symbolTable.isStrictOptionEnabled(Strict.HINT_RE_ASCII_AA)) return "aa";
        if (symbolTable.isStrictOptionEnabled(Strict.HINT_RE_ASCII)) return "a";
        if (symbolTable.isStrictOptionEnabled(Strict.HINT_RE_UNICODE)) return "u";
        String lexical = symbolTable.getLexicalRegexModifiers();
        if (lexical.contains("l")) return "l";
        if (lexical.contains("d")) return "d";
        return "";
    }

    private static void enableLexicalCharset(ScopedSymbolTable symbolTable,
                                             String charset) {
        clearLexicalCharset(symbolTable);
        switch (charset) {
            case "a" -> symbolTable.enableStrictOption(Strict.HINT_RE_ASCII);
            case "aa" -> symbolTable.enableStrictOption(
                    Strict.HINT_RE_ASCII | Strict.HINT_RE_ASCII_AA);
            case "u" -> symbolTable.enableStrictOption(Strict.HINT_RE_UNICODE);
            case "d", "l" -> symbolTable.enableLexicalRegexModifiers(charset);
            default -> throw new IllegalArgumentException(
                    "Unknown lexical regex charset: " + charset);
        }
    }

    private static void disableLexicalCharset(ScopedSymbolTable symbolTable,
                                              String charset) {
        if (charset.equals(currentLexicalCharset(symbolTable))) {
            clearLexicalCharset(symbolTable);
        }
    }

    private static int namedDebugFlag(String option) {
        return switch (option) {
            case "COMPILE", "OPTIMISE", "TRIEC", "DUMP", "FLAGS",
                    "TEST", "EXTRA", "DUMP_PRE_OPTIMIZE", "WILDCARD" ->
                    RuntimeRegex.LEXICAL_DEBUG_COMPILE;
            case "PARSE" -> RuntimeRegex.LEXICAL_DEBUG_COMPILE
                    | RuntimeRegex.LEXICAL_DEBUG_PARSE;
            case "EXECUTE", "INTUIT", "MATCH", "TRIEE", "TRIEM", "OFFSETS",
                    "OFFSETSDBG", "STATE", "OPTIMISEM", "STACK", "BUFFERS",
                    "GPOS" -> RuntimeRegex.LEXICAL_DEBUG_EXECUTE;
            case "ALL", "All", "all", "Extra", "More", "MORE", "State", "TRIE" ->
                    RuntimeRegex.LEXICAL_DEBUG_COMPILE
                            | RuntimeRegex.LEXICAL_DEBUG_EXECUTE
                            | RuntimeRegex.LEXICAL_DEBUG_PARSE;
            default -> -1;
        };
    }

    private static void warnUnknownDebugFlag(String option) {
        WarnDie.warn(new RuntimeScalar("Unknown \"re\" Debug flag '" + option
                + "', possible flags: ALL, All, BUFFERS, COMPILE, DUMP, "
                + "DUMP_PRE_OPTIMIZE, EXECUTE, EXTRA, Extra, FLAGS, GPOS, "
                + "INTUIT, MATCH, MORE, More, OFFSETS, OFFSETSDBG, OPTIMISE, "
                + "OPTIMISEM, PARSE, STACK, STATE, State, TEST, TRIE, TRIEC, "
                + "TRIEE, TRIEM, WILDCARD, all"), new RuntimeScalar());
    }

    private static void setDebugFlags(ScopedSymbolTable symbolTable, int flags) {
        symbolTable.setLexicalRegexDebugFlags(flags);
        if ((flags & (RuntimeRegex.LEXICAL_DEBUG_COMPILE
                | RuntimeRegex.LEXICAL_DEBUG_EXECUTE)) == 0) {
            symbolTable.disableStrictOption(Strict.HINT_RE_DEBUG | Strict.HINT_RE_DEBUGCOLOR);
            return;
        }
        symbolTable.enableStrictOption(Strict.HINT_RE_DEBUG);
        if ((flags & RuntimeRegex.LEXICAL_DEBUG_COLOR) != 0) {
            symbolTable.enableStrictOption(Strict.HINT_RE_DEBUGCOLOR);
        } else {
            symbolTable.disableStrictOption(Strict.HINT_RE_DEBUGCOLOR);
        }
    }

    /**
     * Constructor initializes the module.
     */
    public Re() {
        // Register Java-backed re::* functions without pre-populating %INC.
        // Real Perl does not consider re.pm loaded until `use/require re`.
        super("re", false);
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        Re re = new Re();
        try {
            re.registerMethod("is_regexp", "isRegexp", "$");
            re.registerMethod("regexp_pattern", "regexpPattern", "$");
            re.registerMethod("optimization", "optimization", "$");
            re.registerMethod("import", "importRe", null);
            re.registerMethod("unimport", "unimportRe", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing re method: " + e.getMessage());
        }
    }

    /**
     * Method to check if the given argument is a regular expression.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A scalar indicating whether the argument is a regex.
     */
    public static RuntimeList isRegexp(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for isRegexp() method");
        }
        return new RuntimeList(
                new RuntimeScalar(args.get(0).type == RuntimeScalarType.REGEX)
        );
    }

    /**
     * Implements re::regexp_pattern($ref).
     * In list context, returns (pattern, modifiers) if arg is a compiled regex, or (undef, undef).
     * In scalar context, returns (?^flags:pattern) if arg is a compiled regex, or "".
     *
     * @param args The arguments (expects one argument).
     * @param ctx  The context (SCALAR or LIST).
     * @return Pattern/modifiers in list context, stringified regex in scalar context.
     */
    public static RuntimeList regexpPattern(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for regexp_pattern() method");
        }

        RuntimeScalar arg = args.get(0);

        // Dereference if it's a reference to a regex
        if (arg.type == RuntimeScalarType.REFERENCE || arg.type == RuntimeScalarType.ARRAYREFERENCE
                || arg.type == RuntimeScalarType.HASHREFERENCE) {
            RuntimeScalar deref = (RuntimeScalar) arg.value;
            if (deref.type == RuntimeScalarType.REGEX) {
                arg = deref;
            }
        }

        if (arg.type == RuntimeScalarType.REGEX) {
            RuntimeRegex regex = (RuntimeRegex) arg.value;
            String pattern = regex.patternString != null ? regex.patternString : "";
            String flags = regex.getRegexFlags() != null ? regex.getRegexFlags().toModifierString() : "";

            if (ctx == RuntimeContextType.SCALAR) {
                // Scalar context: return stringified form (?^flags:pattern)
                return new RuntimeList(new RuntimeScalar(regex.toString()));
            } else {
                // List context: return (pattern, modifiers)
                RuntimeList result = new RuntimeList();
                result.add(new RuntimeScalar(pattern));
                result.add(new RuntimeScalar(flags));
                return result;
            }
        }

        // Not a regex
        if (ctx == RuntimeContextType.SCALAR) {
            return new RuntimeList(new RuntimeScalar(""));
        } else {
            RuntimeList result = new RuntimeList();
            result.add(RuntimeScalar.undef());
            result.add(RuntimeScalar.undef());
            return result;
        }
    }

    /** Implements re::optimization($regex) using facts selected by Joni itself. */
    public static RuntimeList optimization(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for optimization() method");
        }

        RuntimeScalar arg = args.get(0);
        if (arg.type == RuntimeScalarType.REFERENCE && arg.value instanceof RuntimeScalar deref) {
            arg = deref;
        }
        if (arg.type != RuntimeScalarType.REGEX) {
            return new RuntimeList(RuntimeScalar.undef());
        }

        RuntimeRegex regex = (RuntimeRegex) arg.value;
        org.joni.Regex.OptimizationInfo info = regex.getOptimizationInfo();
        if (info == null) {
            return new RuntimeList(RuntimeScalar.undef());
        }

        RuntimeHash result = new RuntimeHash();
        result.put("minlen", new RuntimeScalar(info.minimumLength()));
        result.put("minlenret", new RuntimeScalar(info.minimumLength()));
        result.put("gofs", new RuntimeScalar(0));
        result.put("noscan", perlBoolean(
                info.beginBufferAnchored() || info.beginPositionAnchored()));
        result.put("isall", perlBoolean(false));
        result.put("skip", perlBoolean(false));
        result.put("implicit", perlBoolean(
                info.implicitSingleLineAnchor() || info.implicitMultiLineAnchor()));
        result.put("anchor SBOL", perlBoolean(info.implicitMultiLineAnchor()));
        result.put("anchor MBOL", perlBoolean(info.implicitSingleLineAnchor()));
        result.put("anchor GPOS", perlBoolean(info.beginPositionAnchored()));
        result.put("joni search", new RuntimeScalar(info.searchAlgorithm()));

        String exact = info.exact();
        if (exact == null) {
            result.put("checking", new RuntimeScalar("none"));
        } else {
            boolean anchored = info.minimumOffset() == 0
                    && info.maximumOffset() != null && info.maximumOffset() == 0;
            String kind = anchored ? "anchored" : "floating";
            result.put(kind, new RuntimeScalar(exact));
            result.put(kind + " min offset", new RuntimeScalar(info.minimumOffset()));
            if (info.maximumOffset() != null) {
                result.put(kind + " max offset", new RuntimeScalar(info.maximumOffset()));
            }
            result.put("checking", new RuntimeScalar(kind));
            boolean isAll = anchored && info.minimumOffset() == 0
                    && info.exactReachEnd() && !info.hasCaptures()
                    && exact.codePointCount(0, exact.length()) == info.minimumLength();
            result.put("isall", perlBoolean(isAll));
        }
        return new RuntimeList(result.createReferenceWithTrackedElements());
    }

    private static RuntimeScalar perlBoolean(boolean value) {
        return new RuntimeScalar(value ? 1 : 0);
    }

    /** Handle {@code use re ...}, including Perl's complete lexical flag set. */
    public static RuntimeList importRe(RuntimeArray args, int ctx) {
        ScopedSymbolTable symbolTable = getCurrentScope();
        
        for (int i = 0; i < args.size(); i++) {
            String opt = args.get(i).toString();
            // Normalize quotes if present
            opt = opt.replace("\"", "").replace("'", "").trim();
            
            if (opt.equals("is_regexp")) {
                // Export re::is_regexp to caller's namespace
                // Determine caller package
                RuntimeList callerList = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.SCALAR);
                String caller = callerList.scalar().toString();
                RuntimeScalar sourceCode = getGlobalCodeRef("re::is_regexp");
                RuntimeScalar targetCode = getGlobalCodeRef(caller + "::is_regexp");
                targetCode.set(sourceCode);
            } else if (opt.equals("regexp_pattern")) {
                // Export re::regexp_pattern to caller's namespace
                RuntimeList callerList = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.SCALAR);
                String caller = callerList.scalar().toString();
                RuntimeScalar sourceCode = getGlobalCodeRef("re::regexp_pattern");
                RuntimeScalar targetCode = getGlobalCodeRef(caller + "::regexp_pattern");
                targetCode.set(sourceCode);
            } else if (opt.equalsIgnoreCase("strict")) {
                symbolTable.enableStrictOption(Strict.HINT_RE_STRICT);
                // Enable categories used by native regex compiler warnings.
                Warnings.warningManager.enableWarning("experimental::re_strict");
                Warnings.warningManager.enableWarning("experimental::uniprop_wildcards");
                Warnings.warningManager.enableWarning("experimental::vlb");
            } else if (opt.equalsIgnoreCase("eval")) {
                symbolTable.enableStrictOption(Strict.HINT_RE_EVAL);
            } else if (opt.equalsIgnoreCase("taint")) {
                symbolTable.enableStrictOption(Strict.HINT_RE_TAINT);
            } else if (opt.equals("Debug") || opt.equals("Debugcolor")) {
                int flags = opt.equals("Debugcolor")
                        ? RuntimeRegex.LEXICAL_DEBUG_COLOR : 0;
                for (int j = i + 1; j < args.size(); j++) {
                    String namedFlag = args.get(j).toString()
                            .replace("\"", "").replace("'", "").trim();
                    int debugFlag = namedDebugFlag(namedFlag);
                    if (debugFlag < 0) {
                        warnUnknownDebugFlag(namedFlag);
                    } else {
                        flags |= debugFlag;
                    }
                }
                setDebugFlags(symbolTable, flags);
                break;
            } else if (opt.equals("debug")) {
                setDebugFlags(symbolTable, RuntimeRegex.LEXICAL_DEBUG_COMPILE
                        | RuntimeRegex.LEXICAL_DEBUG_EXECUTE);
            } else if (opt.equals("debugcolor")) {
                setDebugFlags(symbolTable, RuntimeRegex.LEXICAL_DEBUG_COMPILE
                        | RuntimeRegex.LEXICAL_DEBUG_EXECUTE
                        | RuntimeRegex.LEXICAL_DEBUG_COLOR);
            } else {
                LexicalRegexOption lexical = lexicalRegexOption(opt, true);
                if (lexical != null) {
                    symbolTable.enableLexicalRegexModifiers(
                            lexical.ordinaryModifiers());
                    for (String charset : lexical.charsetModifiers()) {
                        enableLexicalCharset(symbolTable, charset);
                    }
                }
            }
        }
        propagatePragmaFlags(symbolTable);
        return new RuntimeList();
    }

    /** Handle {@code no re ...}, including selective lexical cancellation. */
    public static RuntimeList unimportRe(RuntimeArray args, int ctx) {
        ScopedSymbolTable symbolTable = getCurrentScope();
        
        for (int i = 0; i < args.size(); i++) {
            String opt = args.get(i).toString();
            opt = opt.replace("\"", "").replace("'", "").trim();
            
            if (opt.equalsIgnoreCase("strict")) {
                symbolTable.disableStrictOption(Strict.HINT_RE_STRICT);
                Warnings.warningManager.disableWarning("experimental::re_strict");
                Warnings.warningManager.disableWarning("experimental::uniprop_wildcards");
                Warnings.warningManager.disableWarning("experimental::vlb");
            } else if (opt.equalsIgnoreCase("eval")) {
                symbolTable.disableStrictOption(Strict.HINT_RE_EVAL);
            } else if (opt.equalsIgnoreCase("taint")) {
                symbolTable.disableStrictOption(Strict.HINT_RE_TAINT);
            } else if (opt.equals("Debug") || opt.equals("Debugcolor")) {
                // re.pm resets ${^RE_DEBUG_FLAGS} before processing a named
                // unimport. Removing any named Debug selection therefore turns
                // the engine hook off for this lexical scope.
                setDebugFlags(symbolTable, 0);
                break;
            } else if (opt.equals("debug") || opt.equals("debugcolor")) {
                setDebugFlags(symbolTable, 0);
            } else {
                LexicalRegexOption lexical = lexicalRegexOption(opt, false);
                if (lexical != null) {
                    symbolTable.disableLexicalRegexModifiers(
                            lexical.ordinaryModifiers());
                    for (String charset : lexical.charsetModifiers()) {
                        disableLexicalCharset(symbolTable, charset);
                    }
                }
            }
        }
        propagatePragmaFlags(symbolTable);
        return new RuntimeList();
    }
}
