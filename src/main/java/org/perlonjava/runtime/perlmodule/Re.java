package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalCodeRef;

/**
 * The Re class provides functionalities similar to the Perl re module.
 * 
 * <p>Currently implemented features:
 * <ul>
 *   <li>{@code use re '/a'} - ASCII-restrict \w, \d, \s, \b</li>
 *   <li>{@code use re '/aa'} - ASCII-restrict including case folding</li>
 *   <li>{@code use re '/u'} - Unicode semantics for character classes</li>
 *   <li>{@code use re 'strict'} - Enables experimental regex warnings</li>
 *   <li>{@code re::is_regexp($ref)} - Check if reference is a compiled regex</li>
 * </ul>
 * 
 * <p>TODO: Features not yet implemented (see {@code perldoc re}):
 * <ul>
 *   <li>{@code use re '/l'} - Locale-aware matching</li>
 *   <li>{@code use re '/d'} - Default/legacy semantics</li>
 *   <li>{@code use re 'eval'} - Allow (?{}) in interpolated patterns without 'use re eval'</li>
 *   <li>{@code use re 'debug'} - Regex debugging output</li>
 *   <li>{@code use re 'debugcolor'} - Colorized regex debugging</li>
 *   <li>{@code use re 'taint'} - Taint mode for regex</li>
 *   <li>{@code re::regexp_pattern($ref)} - Return pattern and modifiers from qr//</li>
 *   <li>Combining multiple flags: {@code use re '/xms'}</li>
 *   <li>Scoped flag restoration with {@code no re '/flags'}</li>
 * </ul>
 */
public class Re extends PerlModuleBase {

    /**
     * Constructor initializes the module.
     */
    public Re() {
        super("re");
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        Re re = new Re();
        try {
            re.registerMethod("is_regexp", "isRegexp", "$");
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
     * @return Empty list
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
     * Handle `use re ...` import. Recognizes: 'strict', '/a', '/u', '/aa'.
     * Enables appropriate experimental warning categories so our regex preprocessor can emit them.
     */
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
            } else if (opt.equalsIgnoreCase("strict")) {
                // Enable categories used by our preprocessor warnings
                Warnings.warningManager.enableWarning("experimental::re_strict");
                Warnings.warningManager.enableWarning("experimental::uniprop_wildcards");
                Warnings.warningManager.enableWarning("experimental::vlb");
            } else if (opt.equals("/a")) {
                // use re '/a' - ASCII-restrict regex character classes
                symbolTable.enableStrictOption(Strict.HINT_RE_ASCII);
                symbolTable.disableStrictOption(Strict.HINT_RE_UNICODE | Strict.HINT_RE_ASCII_AA);
            } else if (opt.equals("/aa")) {
                // use re '/aa' - Strict ASCII-restrict (also affects case folding)
                symbolTable.enableStrictOption(Strict.HINT_RE_ASCII | Strict.HINT_RE_ASCII_AA);
                symbolTable.disableStrictOption(Strict.HINT_RE_UNICODE);
            } else if (opt.equals("/u")) {
                // use re '/u' - Unicode semantics for regex
                symbolTable.enableStrictOption(Strict.HINT_RE_UNICODE);
                symbolTable.disableStrictOption(Strict.HINT_RE_ASCII | Strict.HINT_RE_ASCII_AA);
            }
        }
        return new RuntimeList();
    }

    /**
     * Handle `no re ...` unimport. Recognizes: 'strict', '/a', '/u', '/aa'.
     */
    public static RuntimeList unimportRe(RuntimeArray args, int ctx) {
        ScopedSymbolTable symbolTable = getCurrentScope();
        
        for (int i = 0; i < args.size(); i++) {
            String opt = args.get(i).toString();
            opt = opt.replace("\"", "").replace("'", "").trim();
            
            if (opt.equalsIgnoreCase("strict")) {
                Warnings.warningManager.disableWarning("experimental::re_strict");
                Warnings.warningManager.disableWarning("experimental::uniprop_wildcards");
                Warnings.warningManager.disableWarning("experimental::vlb");
            } else if (opt.equals("/a") || opt.equals("/aa")) {
                symbolTable.disableStrictOption(Strict.HINT_RE_ASCII | Strict.HINT_RE_ASCII_AA);
            } else if (opt.equals("/u")) {
                symbolTable.disableStrictOption(Strict.HINT_RE_UNICODE);
            }
        }
        return new RuntimeList();
    }
}
