package org.perlonjava.runtime;

import org.perlonjava.CompilerOptions;
import org.perlonjava.Configuration;
import org.perlonjava.mro.InheritanceResolver;
import org.perlonjava.perlmodule.*;
import org.perlonjava.regex.RuntimeRegex;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.perlonjava.runtime.RuntimeIO.initStdHandles;

/**
 * The GlobalContext class simulates Perl namespaces.
 * It manages global variables, arrays, hashes, and other global entities.
 */
public class GlobalContext {

    // Special variables internal names
    public static final String GLOBAL_PHASE = encodeSpecialVar("GLOBAL_PHASE"); // $^GLOBAL_PHASE
    public static final String OPEN = encodeSpecialVar("OPEN"); // $^OPEN

    // Internal name of the "./src/main/perl/lib" directory
    public static final String JAR_PERLLIB = "jar:PERL5LIB";

    /**
     * Initializes global variables, arrays, hashes, internal modules, file handles, and other entities.
     *
     * @param compilerOptions The compiler options used for initialization.
     */
    public static void initializeGlobals(CompilerOptions compilerOptions) {

        // Clear package versions from previous runs
        org.perlonjava.symbols.ScopedSymbolTable.clearPackageVersions();

        // Initialize regex state and special variables
        RuntimeRegex.initialize();

        // Initialize scalar variables
        for (char c = 'A'; c <= 'Z'; c++) {
            // Initialize $^A.. $^Z
            String varName = "main::" + Character.toString(c - 'A' + 1);
            GlobalVariable.getGlobalVariable(varName);
        }
        GlobalVariable.getGlobalVariable("main::" + Character.toString('O' - 'A' + 1)).set(SystemUtils.getPerlOsName());    // initialize $^O
        GlobalVariable.getGlobalVariable("main::" + Character.toString('V' - 'A' + 1)).set(Configuration.getPerlVersionVString());    // initialize $^V
        GlobalVariable.getGlobalVariable("main::" + Character.toString('T' - 'A' + 1)).set((int)(System.currentTimeMillis() / 1000));    // initialize $^T to epoch time

        // Initialize $^X - the name used to execute the current copy of Perl
        // PERLONJAVA_EXECUTABLE is set by the `jperl` or `jperl.bat` launcher
        String perlExecutable = System.getenv("PERLONJAVA_EXECUTABLE");
        if (perlExecutable != null && !perlExecutable.isEmpty()) {
            GlobalVariable.getGlobalVariable("main::" + Character.toString('X' - 'A' + 1)).set(perlExecutable);
        } else {
            // Fallback to "jperl" if environment variable is not set
            GlobalVariable.getGlobalVariable("main::" + Character.toString('X' - 'A' + 1)).set("jperl");
        }

        GlobalVariable.getGlobalVariable("main::]").set(Configuration.getPerlVersionOld());    // initialize $] to Perl version
        GlobalVariable.getGlobalVariable("main::@").set("");    // initialize $@ to ""
        GlobalVariable.getGlobalVariable("main::_");    // initialize $_ to "undef"
        GlobalVariable.getGlobalVariable("main::\"").set(" ");    // initialize $" to " "
        GlobalVariable.getGlobalVariable("main::a");    // initialize $a to "undef"
        GlobalVariable.getGlobalVariable("main::b");    // initialize $b to "undef"
        GlobalVariable.getGlobalVariable("main::!");    // initialize $! to "undef"
        GlobalVariable.getGlobalVariable("main::,").set("");    // initialize $, to ""
        GlobalVariable.getGlobalVariable("main::|").set(0);     // initialize $| to 0
        GlobalVariable.getGlobalVariable("main::\\").set(compilerOptions.outputRecordSeparator);    // initialize $\
        GlobalVariable.getGlobalVariable("main::$").set(ProcessHandle.current().pid()); // initialize `$$` to process id
        GlobalVariable.getGlobalVariable("main::?");
        GlobalVariable.getGlobalVariable("main::0").set(compilerOptions.fileName);
        GlobalVariable.getGlobalVariable(GLOBAL_PHASE).set(""); // ${^GLOBAL_PHASE}
        GlobalVariable.globalVariables.put(encodeSpecialVar("TAINT"), RuntimeScalarCache.scalarZero); // ${^TAINT} - read-only, always 0 (taint mode not implemented)
        GlobalVariable.getGlobalVariable("main::>");  // TODO
        GlobalVariable.getGlobalVariable("main::<");  // TODO
        GlobalVariable.getGlobalVariable("main::;").set("\034");  // initialize $; (SUBSEP) to \034
        GlobalVariable.getGlobalVariable("main::(");  // TODO
        GlobalVariable.getGlobalVariable("main::)");  // TODO
        GlobalVariable.getGlobalVariable("main::=");  // TODO
        GlobalVariable.getGlobalVariable("main::^");  // TODO
        GlobalVariable.getGlobalVariable("main:::");  // TODO

        GlobalVariable.globalVariables.put("main::/", new InputRecordSeparator(compilerOptions.inputRecordSeparator)); // initialize $/

        GlobalVariable.globalVariables.put("main::`", new ScalarSpecialVariable(ScalarSpecialVariable.Id.PREMATCH));
        GlobalVariable.globalVariables.put("main::&", new ScalarSpecialVariable(ScalarSpecialVariable.Id.MATCH));
        GlobalVariable.globalVariables.put("main::'", new ScalarSpecialVariable(ScalarSpecialVariable.Id.POSTMATCH));
        GlobalVariable.globalVariables.put("main::.", new ScalarSpecialVariable(ScalarSpecialVariable.Id.INPUT_LINE_NUMBER)); // $.
        GlobalVariable.globalVariables.put("main::+", new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_PAREN_MATCH));
        GlobalVariable.globalVariables.put(encodeSpecialVar("LAST_SUCCESSFUL_PATTERN"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_SUCCESSFUL_PATTERN));
        GlobalVariable.globalVariables.put(encodeSpecialVar("LAST_FH"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_FH)); // $^LAST_FH
        // $^R is writable, not read-only - initialize as regular variable instead of ScalarSpecialVariable
        // GlobalVariable.globalVariables.put(encodeSpecialVar("R"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_REGEXP_CODE_RESULT)); // $^R
        GlobalVariable.getGlobalVariable(encodeSpecialVar("R"));    // initialize $^R to "undef" - writable variable
        GlobalVariable.getGlobalVariable(encodeSpecialVar("A")).set("");    // initialize $^A to "" - format accumulator variable
        GlobalVariable.getGlobalVariable(encodeSpecialVar("P")).set(0);    // initialize $^P to 0 - debugger flags
        GlobalVariable.globalVariables.put(encodeSpecialVar("LAST_SUCCESSFUL_PATTERN"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_SUCCESSFUL_PATTERN));
        GlobalVariable.globalVariables.put(encodeSpecialVar("LAST_FH"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_FH)); // $^LAST_FH

        // Aliases
        GlobalVariable.aliasGlobalVariable(encodeSpecialVar("PREMATCH"), "main::`");
        GlobalVariable.aliasGlobalVariable(encodeSpecialVar("MATCH"), "main::&");
        GlobalVariable.aliasGlobalVariable(encodeSpecialVar("POSTMATCH"), "main::'");

        GlobalVariable.getGlobalVariable(encodeSpecialVar("SAFE_LOCALES"));  // TODO

        // Initialize arrays
        GlobalVariable.getGlobalArray("main::+").elements = new ArraySpecialVariable(ArraySpecialVariable.Id.LAST_MATCH_END);  // regex @+
        GlobalVariable.getGlobalArray("main::-").elements = new ArraySpecialVariable(ArraySpecialVariable.Id.LAST_MATCH_START);  // regex @-
        GlobalVariable.getGlobalArray(encodeSpecialVar("CAPTURE")).elements = new ArraySpecialVariable(ArraySpecialVariable.Id.CAPTURE);  // regex @{^CAPTURE}
        GlobalVariable.getGlobalArray("main::'");  // @'

        // Initialize default formats
        // Create a default STDOUT format to prevent "Undefined format" errors
        // This allows comp/form_scope.t and other format tests to run
        RuntimeFormat stdoutFormat = GlobalVariable.getGlobalFormatRef("STDOUT");
        stdoutFormat.setTemplate("");  // Empty template - can be overridden by user code

        // Initialize hashes
        GlobalVariable.getGlobalHash("main::SIG");
        GlobalVariable.getGlobalHash(encodeSpecialVar("H"));
        GlobalVariable.getGlobalHash("main::+").elements = new HashSpecialVariable(HashSpecialVariable.Id.CAPTURE);  // regex %+
        GlobalVariable.getGlobalHash("main::-").elements = new HashSpecialVariable(HashSpecialVariable.Id.CAPTURE_ALL);  // regex %-
        GlobalVariable.getGlobalHash("main::!");  // TODO %!

        // Initialize %ENV
        Map<String, RuntimeScalar> env = GlobalVariable.getGlobalHash("main::ENV").elements;
        System.getenv().forEach((k, v) -> env.put(k, new RuntimeScalar(v)));

        /* Initialize @INC.
           @INC Search order is:
            - "-I" argument
            - JAR_PERLLIB, the jar directory: src/main/perl/lib
            - PERL5LIB env
           See also: https://stackoverflow.com/questions/2526804/how-is-perls-inc-constructed
         */
        List<RuntimeScalar> inc = GlobalVariable.getGlobalArray("main::INC").elements;

        inc.addAll(compilerOptions.inc.elements);   // add from `-I`
        inc.add(new RuntimeScalar(JAR_PERLLIB));    // internal src/main/perl/lib
        String[] directories = env.getOrDefault("PERL5LIB", new RuntimeScalar("")).toString().split(":");
        for (String directory : directories) {
            if (!directory.isEmpty()) {
                inc.add(new RuntimeScalar(directory)); // add from env PERL5LIB
            }
        }

        // Initialize %INC
        GlobalVariable.getGlobalHash("main::INC");

        // Initialize STDOUT, STDERR, STDIN
        initStdHandles();
        // Note: We don't pre-alias ARGV to STDIN here because it prevents proper
        // typeglob aliasing like *ARGV = *DATA in BEGIN blocks.
        // The diamond operator <> in DiamondIO.java handles the STDIN fallback
        // when @ARGV is empty.

        // Initialize built-in Perl classes
        DiamondIO.initialize(compilerOptions);
        Universal.initialize();
        Vars.initialize();
        Subs.initialize();
        Builtin.initialize();
        Exporter.initialize();
        Base.initialize();
        Symbol.initialize();
        ScalarUtil.initialize();
        Strict.initialize();
        IntegerPragma.initialize();
        BytesPragma.initialize();
        Utf8.initialize();
        Feature.initialize();
        Warnings.initialize();
        Internals.initialize();
        Parent.initialize();
        Lib.initialize();
        Re.initialize();
        Cwd.initialize();
        FileSpec.initialize();
        UnicodeNormalize.initialize();
        UnicodeUCD.initialize();
        TimeHiRes.initialize();
        TermReadLine.initialize();
        TermReadKey.initialize();
        FileTemp.initialize();
        Encode.initialize();
        JavaSystem.initialize();
        PerlIO.initialize();
        Version.initialize();   // Initialize version module for version objects
        XSLoader.initialize();  // XSLoader will load other classes on-demand
        // Filter::Util::Call will be loaded via XSLoader when needed

        // Reset method cache after initializing UNIVERSAL
        InheritanceResolver.invalidateCache();
    }

    public static String encodeSpecialVar(String name) {
        // Perl's $^X is represented as "main::" + (char)('X' - 'A' + 1) + "..."
        return "main::" + (char) (name.charAt(0) - 'A' + 1) + name.substring(1);
    }
}
