package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.core.Configuration;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.perlmodule.*;
import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeIO.initStdHandles;

/**
 * The GlobalContext class simulates Perl namespaces.
 * It manages global variables, arrays, hashes, and other global entities.
 */
public class GlobalContext {

    // Special variables internal names
    public static final String GLOBAL_PHASE = encodeSpecialVar("GLOBAL_PHASE"); // $^GLOBAL_PHASE
    public static final String OPEN = encodeSpecialVar("OPEN"); // $^OPEN
    public static final String WARNING_SCOPE = encodeSpecialVar("WARNING_SCOPE"); // ${^WARNING_SCOPE}

    // Virtual directory names for JAR-embedded Perl resources
    // E.g., @INC contains "jar:PERL5LIB", %INC contains "jar:PERL5LIB/DBI.pm"
    public static final String JAR_PERLLIB = "jar:PERL5LIB";   // maps to /lib/ in JAR
    public static final String JAR_PERLBIN = "jar:PERL5BIN";   // maps to /bin/ in JAR

    /**
     * Initializes global variables, arrays, hashes, internal modules, file handles, and other entities.
     *
     * @param compilerOptions The compiler options used for initialization.
     */
    public static void initializeGlobals(CompilerOptions compilerOptions) {

        // Clear package versions from previous runs
        ScopedSymbolTable.clearPackageVersions();

        // Initialize regex state and special variables
        RuntimeRegex.initialize();

        // Initialize scalar variables
        for (char c = 'A'; c <= 'Z'; c++) {
            // Initialize $^A.. $^Z
            String varName = "main::" + Character.toString(c - 'A' + 1);
            GlobalVariable.getGlobalVariable(varName);
        }
        // $^N - last capture group closed (not yet implemented, but must be read-only)
        GlobalVariable.globalVariables.put(encodeSpecialVar("N"), new RuntimeScalarReadOnly());
        // $^S - current state of the interpreter (undef=compiling, 0=not in eval, 1=in eval)
        GlobalVariable.globalVariables.put("main::" + Character.toString('S' - 'A' + 1),
                new ScalarSpecialVariable(ScalarSpecialVariable.Id.EVAL_STATE));
        GlobalVariable.getGlobalVariable("main::" + Character.toString('O' - 'A' + 1)).set(SystemUtils.getPerlOsName());    // initialize $^O
        GlobalVariable.getGlobalVariable("main::" + Character.toString('V' - 'A' + 1)).set(Configuration.getPerlVersionVString());    // initialize $^V
        GlobalVariable.getGlobalVariable("main::" + Character.toString('T' - 'A' + 1)).set((int) (System.currentTimeMillis() / 1000));    // initialize $^T to epoch time
        // Initialize $^W based on -w flag
        if (compilerOptions.warnFlag) {
            GlobalVariable.getGlobalVariable("main::" + Character.toString('W' - 'A' + 1)).set(1);    // initialize $^W = 1 for -w flag
        }

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
        GlobalVariable.globalVariables.put("main::!", new ErrnoVariable());    // initialize $! with dualvar support
        // Initialize $, (output field separator) with special variable class
        if (!GlobalVariable.globalVariables.containsKey("main::,")) {
            var ofs = new OutputFieldSeparator();
            ofs.set("");
            GlobalVariable.globalVariables.put("main::,", ofs);
        }
        GlobalVariable.globalVariables.put("main::|", new OutputAutoFlushVariable());
        // Only set $\ if it hasn't been set yet - prevents overwriting during re-entrant calls
        if (!GlobalVariable.globalVariables.containsKey("main::\\")) {
            var ors = new OutputRecordSeparator();
            ors.set(compilerOptions.outputRecordSeparator);    // initialize $\
            GlobalVariable.globalVariables.put("main::\\", ors);
        }
        GlobalVariable.getGlobalVariable("main::$").set(ProcessHandle.current().pid()); // initialize `$$` to process id
        GlobalVariable.getGlobalVariable("main::?");
        // Only set $0 if it hasn't been set yet - prevents overwriting during re-entrant calls
        // (e.g., when require() is called during module initialization)
        if (!GlobalVariable.globalVariables.containsKey("main::0")) {
            GlobalVariable.getGlobalVariable("main::0").set(compilerOptions.fileName);
        }
        GlobalVariable.getGlobalVariable(GLOBAL_PHASE).set("RUN"); // ${^GLOBAL_PHASE}
        // ${^TAINT} - set to 1 if -T (taint mode) was specified, 0 otherwise
        // Only initialize if not already set (to avoid overwriting during re-initialization)
        String taintVarName = encodeSpecialVar("TAINT");
        if (!GlobalVariable.globalVariables.containsKey(taintVarName) || 
            (compilerOptions.taintMode && GlobalVariable.globalVariables.get(taintVarName) == RuntimeScalarCache.scalarZero)) {
            GlobalVariable.globalVariables.put(taintVarName, 
                compilerOptions.taintMode ? RuntimeScalarCache.scalarOne : RuntimeScalarCache.scalarZero);
        }
        GlobalVariable.globalVariables.put("main::>", new ScalarSpecialVariable(ScalarSpecialVariable.Id.EFFECTIVE_UID));  // $> - effective UID (lazy)
        GlobalVariable.globalVariables.put("main::<", new ScalarSpecialVariable(ScalarSpecialVariable.Id.REAL_UID));  // $< - real UID (lazy)
        GlobalVariable.getGlobalVariable("main::;").set("\034");  // initialize $; (SUBSEP) to \034
        GlobalVariable.globalVariables.put("main::(", new ScalarSpecialVariable(ScalarSpecialVariable.Id.REAL_GID));  // $( - real GID (lazy)
        GlobalVariable.globalVariables.put("main::)", new ScalarSpecialVariable(ScalarSpecialVariable.Id.EFFECTIVE_GID));  // $) - effective GID (lazy)
        GlobalVariable.getGlobalVariable("main::=");  // TODO
        GlobalVariable.getGlobalVariable("main::^");  // TODO
        GlobalVariable.getGlobalVariable("main:::");  // TODO

        // Only set $/ if it hasn't been set yet - prevents overwriting during re-entrant calls
        if (!GlobalVariable.globalVariables.containsKey("main::/")) {
            GlobalVariable.globalVariables.put("main::/", new InputRecordSeparator(compilerOptions.inputRecordSeparator)); // initialize $/
        }

        GlobalVariable.globalVariables.put("main::`", new ScalarSpecialVariable(ScalarSpecialVariable.Id.PREMATCH));
        GlobalVariable.globalVariables.put("main::&", new ScalarSpecialVariable(ScalarSpecialVariable.Id.MATCH));
        GlobalVariable.globalVariables.put("main::'", new ScalarSpecialVariable(ScalarSpecialVariable.Id.POSTMATCH));
        GlobalVariable.globalVariables.put("main::.", new ScalarSpecialVariable(ScalarSpecialVariable.Id.INPUT_LINE_NUMBER)); // $.
        GlobalVariable.globalVariables.put("main::+", new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_PAREN_MATCH));
        GlobalVariable.globalVariables.put(encodeSpecialVar("LAST_SUCCESSFUL_PATTERN"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_SUCCESSFUL_PATTERN));
        GlobalVariable.globalVariables.put(encodeSpecialVar("LAST_FH"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_FH)); // $^LAST_FH
        GlobalVariable.globalVariables.put(encodeSpecialVar("H"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.HINTS)); // $^H - compile-time hints
        // $^R is writable, not read-only - initialize as regular variable instead of ScalarSpecialVariable
        // GlobalVariable.globalVariables.put(encodeSpecialVar("R"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_REGEXP_CODE_RESULT)); // $^R
        GlobalVariable.getGlobalVariable(encodeSpecialVar("R"));    // initialize $^R to "undef" - writable variable
        GlobalVariable.getGlobalVariable(encodeSpecialVar("A")).set("");    // initialize $^A to "" - format accumulator variable
        GlobalVariable.getGlobalVariable(encodeSpecialVar("P")).set(0);    // initialize $^P to 0 - debugger flags
        GlobalVariable.getGlobalVariable(encodeSpecialVar("WARNING_SCOPE")).set(0);    // initialize ${^WARNING_SCOPE} to 0 - runtime warning scope ID
        // Initialize $^I (in-place editing extension) from -i switch
        if (compilerOptions.inPlaceEdit) {
            GlobalVariable.getGlobalVariable(encodeSpecialVar("I")).set(
                compilerOptions.inPlaceExtension != null ? compilerOptions.inPlaceExtension : "");
        }
        GlobalVariable.globalVariables.put(encodeSpecialVar("LAST_SUCCESSFUL_PATTERN"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_SUCCESSFUL_PATTERN));
        GlobalVariable.globalVariables.put(encodeSpecialVar("LAST_FH"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_FH)); // $^LAST_FH
        GlobalVariable.getGlobalVariable(encodeSpecialVar("UNICODE")).set(0);    // initialize $^UNICODE to 0 - `-C` unicode flags

        GlobalVariable.globalVariables.put(encodeSpecialVar("PREMATCH"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.P_PREMATCH));
        GlobalVariable.globalVariables.put(encodeSpecialVar("MATCH"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.P_MATCH));
        GlobalVariable.globalVariables.put(encodeSpecialVar("POSTMATCH"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.P_POSTMATCH));

        GlobalVariable.getGlobalVariable(encodeSpecialVar("SAFE_LOCALES"));  // TODO

        // Initialize additional magic scalar variables that tests expect to exist at startup
        GlobalVariable.getGlobalVariable(encodeSpecialVar("UTF8LOCALE"));  // ${^UTF8LOCALE}
        GlobalVariable.globalVariables.put(encodeSpecialVar("WARNING_BITS"), new ScalarSpecialVariable(ScalarSpecialVariable.Id.WARNING_BITS));  // ${^WARNING_BITS}
        GlobalVariable.getGlobalVariable(encodeSpecialVar("UTF8CACHE")).set(0);  // ${^UTF8CACHE}
        GlobalVariable.getGlobalVariable("main::[").set(0);  // $[ (array base, deprecated)
        GlobalVariable.getGlobalVariable("main::~");  // $~ (current format name)
        GlobalVariable.getGlobalVariable("main::%").set(0);  // $% (page number)
        
        // Initialize capture variables $1-$9 (these are read-only and return undef until a match)
        for (int i = 1; i <= 9; i++) {
            GlobalVariable.getGlobalVariable("main::" + i);
        }

        // Initialize arrays
        RuntimeArray matchEnd = GlobalVariable.getGlobalArray("main::+");
        matchEnd.type = RuntimeArray.READONLY_ARRAY;
        matchEnd.elements = new ArraySpecialVariable(ArraySpecialVariable.Id.LAST_MATCH_END);  // regex @+
        RuntimeArray matchStart = GlobalVariable.getGlobalArray("main::-");
        matchStart.type = RuntimeArray.READONLY_ARRAY;
        matchStart.elements = new ArraySpecialVariable(ArraySpecialVariable.Id.LAST_MATCH_START);  // regex @-
        GlobalVariable.getGlobalArray(encodeSpecialVar("CAPTURE")).elements = new ArraySpecialVariable(ArraySpecialVariable.Id.CAPTURE);  // regex @{^CAPTURE}
        GlobalVariable.getGlobalArray("main::'");  // @'

        // Initialize default formats
        // Create a default STDOUT format to prevent "Undefined format" errors
        // This allows comp/form_scope.t and other format tests to run
        RuntimeFormat stdoutFormat = GlobalVariable.getGlobalFormatRef("STDOUT");
        stdoutFormat.setTemplate("");  // Empty template - can be overridden by user code

        // Initialize hashes
        // %SIG uses a special hash that auto-qualifies handler names for known signals
        GlobalVariable.globalHashes.put("main::SIG", new RuntimeSigHash());
        GlobalVariable.getGlobalHash(encodeSpecialVar("H"));
        GlobalVariable.getGlobalHash("main::+").elements = new HashSpecialVariable(HashSpecialVariable.Id.CAPTURE);  // regex %+
        GlobalVariable.getGlobalHash("main::-").elements = new HashSpecialVariable(HashSpecialVariable.Id.CAPTURE_ALL);  // regex %-
        GlobalVariable.getGlobalHash("main::!").elements = new ErrnoHash();  // %! errno hash

        // Initialize %ENV
        Map<String, RuntimeScalar> env = GlobalVariable.getGlobalHash("main::ENV").elements;
        System.getenv().forEach((k, v) -> env.put(k, new RuntimeScalar(v)));

        /* Initialize @INC.
           @INC Search order mirrors Perl 5's site_perl > core pattern:
            - "-I" argument              (highest priority, user override)
            - PERL5LIB env               (user environment override)
            - ~/.perlonjava/lib          (user-installed CPAN modules, like site_perl)
            - JAR_PERLLIB                (bundled modules, like core lib — lowest priority)
           This allows CPAN-installed modules to override bundled ones.
           See also: https://stackoverflow.com/questions/2526804/how-is-perls-inc-constructed
         */
        List<RuntimeScalar> inc = GlobalVariable.getGlobalArray("main::INC").elements;

        inc.addAll(compilerOptions.inc.elements);   // add from `-I`
        String[] directories = env.getOrDefault("PERL5LIB", new RuntimeScalar("")).toString().split(":");
        for (String directory : directories) {
            if (!directory.isEmpty()) {
                inc.add(new RuntimeScalar(directory)); // add from env PERL5LIB
            }
        }
        // Add user library path (~/.perlonjava/lib) for ExtUtils::MakeMaker installed modules
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isEmpty()) {
            String userLib = userHome + "/.perlonjava/lib";
            java.io.File userLibDir = new java.io.File(userLib);
            if (userLibDir.isDirectory()) {
                inc.add(new RuntimeScalar(userLib));
            }
        }
        inc.add(new RuntimeScalar(JAR_PERLLIB));    // internal src/main/perl/lib (lowest priority)

        // Honor PERL_USE_UNSAFE_INC=1 (required by CPAN.pm / Module::Install-based
        // Makefile.PL scripts that expect `.` in @INC). Perl 5.26 removed `.` from
        // @INC by default, but CPAN tooling sets PERL_USE_UNSAFE_INC=1 to restore it.
        String unsafeInc = env.getOrDefault("PERL_USE_UNSAFE_INC", new RuntimeScalar("")).toString();
        if (!unsafeInc.isEmpty() && !unsafeInc.equals("0")) {
            inc.add(new RuntimeScalar("."));
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
        Mro.initialize();  // mro functions available without 'use mro'
        Vars.initialize();
        Subs.initialize();
        // Register XSLoader first: several modules below (and pragmas loaded during
        // their .pm compilation such as 'use strict' inside Exporter.pm) call
        // XSLoader::load at BEGIN time. Defining it up-front avoids an "Undefined
        // subroutine &XSLoader::load" failure in the interpreter backend, where
        // require() actually executes the required file's top-level (including
        // the XSLoader::load call in strict.pm) before XSLoader.initialize() would
        // otherwise have run.
        // Note: XSLoader MUST be initialized before DynaLoader.initialize() because
        // DynaLoader's initializeExporter() triggers require(Exporter.pm), which
        // does `use strict;`, which (re)compiles strict.pm, which calls XSLoader::load.
        XSLoader.initialize();
        DynaLoader.initialize();
        Builtin.initialize();
        Base.initialize();
        Symbol.initialize();
        ScalarUtil.initialize();
        OverloadModule.initialize();  // overload::StrVal, overload::AddrRef (bypass overloaded "")
        Strict.initialize();
        IntegerPragma.initialize();
        BytesPragma.initialize();
        OverloadingPragma.initialize();
        Utf8.initialize();
        Feature.initialize();
        Warnings.initialize();
        Internals.initialize();
        Parent.initialize();
        Lib.initialize();
        Re.initialize();
        // Cwd.initialize();  // Use Perl Cwd.pm instead (has pure Perl fallbacks)
        FileSpec.initialize();
        // Deferred to XSLoader::load() for faster startup - only loaded when actually used:
        // UnicodeNormalize.initialize();  // Has XSLoader in Perl file
        // TimeHiRes.initialize();  // Has XSLoader in Perl file
        // Encode.initialize();  // Has XSLoader in Perl file - deferred for Encode::Alias support
        UnicodeUCD.initialize();  // No XSLoader in Perl file - needed at startup
        Charnames.initialize();   // Java-side charnames::viacode via ICU4J
        TermReadLine.initialize();  // No Perl file - needed at startup
        TermReadKey.initialize();  // No Perl file - needed at startup
        FileTemp.initialize();  // Perl uses eval require - keep for cleanup hooks
        // JavaSystem.initialize();  // Only for java:: integration
        PerlIO.initialize();
        IOHandle.initialize();  // IO::Handle methods (_sync, _error, etc.)
        Version.initialize();   // Initialize version module for version objects
        Attributes.initialize();  // attributes:: XS-equivalent functions (used by attributes.pm)
        // Filter::Util::Call will be loaded via XSLoader when needed

        // Reset method cache after initializing UNIVERSAL
        InheritanceResolver.invalidateCache();
    }

    public static String encodeSpecialVar(String name) {
        // Perl's $^X is represented as "main::" + (char)('X' - 'A' + 1) + "..."
        return "main::" + (char) (name.charAt(0) - 'A' + 1) + name.substring(1);
    }
}
