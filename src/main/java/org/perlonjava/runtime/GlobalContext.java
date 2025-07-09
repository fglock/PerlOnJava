package org.perlonjava.runtime;

import org.perlonjava.ArgumentParser;
import org.perlonjava.Configuration;
import org.perlonjava.perlmodule.*;

import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.RuntimeIO.initStdHandles;

/**
 * The GlobalContext class simulates Perl namespaces.
 * It manages global variables, arrays, hashes, and other global entities.
 */
public class GlobalContext {

    // Special variables internal names
    public static final String LAST_FH = "main::" + Character.toString('L' - 'A' + 1) + "AST_FH"; // $^LAST_FH
    public static final String GLOBAL_PHASE = "main::" + Character.toString('G' - 'A' + 1) + "LOBAL_PHASE"; // $^GLOBAL_PHASE
    public static final String TAINT = "main::" + Character.toString('T' - 'A' + 1) + "AINT"; // $^TAINT
    public static final String OPEN = "main::" + Character.toString('O' - 'A' + 1) + "PEN"; // $^OPEN

    // Internal name of the "./src/main/perl/lib" directory
    public static final String JAR_PERLLIB = "jar:PERL5LIB";

    /**
     * Initializes global variables, arrays, hashes, internal modules, file handles, and other entities.
     *
     * @param compilerOptions The compiler options used for initialization.
     */
    public static void initializeGlobals(ArgumentParser.CompilerOptions compilerOptions) {

        // Initialize scalar variables
        for (char c = 'A'; c <= 'Z'; c++) {
            // Initialize $^A.. $^Z
            String varName = "main::" + Character.toString(c - 'A' + 1);
            GlobalVariable.getGlobalVariable(varName);
        }
        GlobalVariable.getGlobalVariable("main::" + Character.toString('O' - 'A' + 1)).set(SystemUtils.getPerlOsName());    // initialize $^O
        GlobalVariable.getGlobalVariable("main::" + Character.toString('V' - 'A' + 1)).set(Configuration.perlVersion);    // initialize $^V

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
        GlobalVariable.getGlobalVariable("main::/").set(compilerOptions.inputRecordSeparator); // initialize $/
        GlobalVariable.getGlobalVariable("main::$").set(ProcessHandle.current().pid()); // initialize `$$` to process id
        GlobalVariable.getGlobalVariable("main::?");
        GlobalVariable.getGlobalVariable("main::0").set(compilerOptions.fileName);
        GlobalVariable.getGlobalVariable(GLOBAL_PHASE).set(""); // ${^GLOBAL_PHASE}
        GlobalVariable.getGlobalVariable(TAINT); // ${^TAINT}

        GlobalVariable.globalVariables.put("main::`", new ScalarSpecialVariable(ScalarSpecialVariable.Id.PREMATCH));
        GlobalVariable.globalVariables.put("main::&", new ScalarSpecialVariable(ScalarSpecialVariable.Id.MATCH));
        GlobalVariable.globalVariables.put("main::'", new ScalarSpecialVariable(ScalarSpecialVariable.Id.POSTMATCH));
        GlobalVariable.globalVariables.put("main::.", new ScalarSpecialVariable(ScalarSpecialVariable.Id.INPUT_LINE_NUMBER)); // $.
        GlobalVariable.globalVariables.put("main::+", new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_PAREN_MATCH));
        GlobalVariable.globalVariables.put(LAST_FH, new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_FH)); // $^LAST_FH

        // Initialize arrays
        GlobalVariable.getGlobalArray("main::+").elements = new ArraySpecialVariable(ArraySpecialVariable.Id.LAST_MATCH_END);  // regex @+
        GlobalVariable.getGlobalArray("main::-").elements = new ArraySpecialVariable(ArraySpecialVariable.Id.LAST_MATCH_START);  // regex @-

        // Initialize hashes
        GlobalVariable.getGlobalHash("main::SIG");
        GlobalVariable.getGlobalHash("main::+").elements = new HashSpecialVariable(HashSpecialVariable.Id.CAPTURE);  // regex %+
        GlobalVariable.getGlobalHash("main::-").elements = new HashSpecialVariable(HashSpecialVariable.Id.CAPTURE_ALL);  // regex %-

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
        // ARGV file handle - If no files are specified, use standard input
        if (GlobalVariable.getGlobalArray("main::ARGV").isEmpty()) {
            GlobalVariable.getGlobalIO("main::ARGV").set(GlobalVariable.getGlobalIO("main::STDIN"));
        }

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
        Utf8.initialize();
        Feature.initialize();
        Warnings.initialize();
        Internals.initialize();
        Parent.initialize();
        Lib.initialize();
        Carp.initialize();
        Re.initialize();
        Cwd.initialize();
        FileSpec.initialize();
        Json.initialize();
        HttpTiny.initialize();
        Dbi.initialize();
        YamlPP.initialize();
        UnicodeNormalize.initialize();
        TimeHiRes.initialize();
        TermReadLine.initialize();
        TermReadKey.initialize();
        TextCsv.initialize();
        FileTemp.initialize();
        IOHandleModule.initialize();

        // Reset method cache after initializing UNIVERSAL
        InheritanceResolver.invalidateCache();
    }
}

