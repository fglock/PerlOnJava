package org.perlonjava.demo;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates PerlOnJava's "multiplicity" feature: multiple independent Perl
 * interpreters running concurrently within a single JVM process.
 *
 * Each interpreter has its own global variables, regex state, @INC, %ENV, etc.
 * They share the JVM heap; generated classes are loaded into each runtime's own
 * ClassLoader and become eligible for GC once the runtime is discarded.
 *
 * Usage:
 *   java -cp target/perlonjava-5.42.0.jar \
 *        org.perlonjava.demo.MultiplicityDemo script1.pl script2.pl ...
 *
 * Or with the helper script:
 *   ./dev/sandbox/run_multiplicity_demo.sh script1.pl script2.pl ...
 */
public class MultiplicityDemo {

    // Lock to serialize compilation (parser has shared static state — Phase 0 TODO).
    // initializeGlobals() also compiles built-in Perl modules, so it must be serialized too.
    private static final Object COMPILE_LOCK = new Object();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: MultiplicityDemo <script1.pl> [script2.pl] ...");
            System.err.println("Runs each Perl script in its own interpreter, concurrently.");
            System.exit(1);
        }

        // Read all script files upfront
        List<String> scriptNames = new ArrayList<>();
        List<String> scriptSources = new ArrayList<>();
        for (String arg : args) {
            Path p = Path.of(arg);
            if (!Files.isRegularFile(p)) {
                System.err.println("Error: not a file: " + arg);
                System.exit(1);
            }
            scriptNames.add(p.getFileName().toString());
            scriptSources.add(Files.readString(p));
        }

        int n = scriptNames.size();
        System.out.println("=== PerlOnJava Multiplicity Demo ===");
        System.out.println("Starting " + n + " independent Perl interpreter(s)...\n");

        // Latch so all threads begin execution at roughly the same time.
        // Uses countDown (not await-blocking), so a thread that fails during
        // compilation still releases the latch for the others.
        CountDownLatch ready = new CountDownLatch(n);

        // Per-thread output capture
        ByteArrayOutputStream[] outputs = new ByteArrayOutputStream[n];
        long[] durations = new long[n];
        Throwable[] errors = new Throwable[n];

        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final String name = scriptNames.get(i);
            final String source = scriptSources.get(i);
            outputs[idx] = new ByteArrayOutputStream();

            threads[i] = new Thread(() -> {
                try {
                    // --- Create an independent PerlRuntime for this thread ---
                    PerlRuntime.initialize();

                    // Redirect this interpreter's STDOUT to a private buffer.
                    // RuntimeIO.setStdout() operates on the current thread's PerlRuntime.
                    PrintStream ps = new PrintStream(outputs[idx], true);
                    RuntimeIO customOut = new RuntimeIO(new StandardIO(ps, true));
                    RuntimeIO.setStdout(customOut);
                    RuntimeIO.setSelectedHandle(customOut);
                    RuntimeIO.setLastWrittenHandle(customOut);

                    // Set up compiler options
                    CompilerOptions options = new CompilerOptions();
                    options.code = source;
                    options.fileName = name;

                    // Compile (serialized — parser and initializeGlobals have shared static state).
                    // initializeGlobals sets up $_, @INC, %ENV and compiles built-in Perl modules.
                    RuntimeCode code;
                    synchronized (COMPILE_LOCK) {
                        GlobalContext.initializeGlobals(options);
                        code = (RuntimeCode) PerlLanguageProvider.compilePerlCode(options);
                    }

                    // Signal that we're ready to execute
                    ready.countDown();

                    // Wait until all threads have compiled (with a timeout to avoid deadlock)
                    ready.await(30, TimeUnit.SECONDS);

                    // --- Execute concurrently — runtime state is fully isolated ---
                    long t0 = System.nanoTime();
                    code.apply(new RuntimeArray(), RuntimeContextType.VOID);
                    durations[idx] = System.nanoTime() - t0;

                    // Flush buffered output
                    RuntimeIO.flushFileHandles();

                } catch (Throwable t) {
                    errors[idx] = t;
                    ready.countDown();  // don't block others on failure
                }
            }, "perl-" + name);
        }

        // Start all threads
        long wallStart = System.nanoTime();
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join(60_000);  // 60s safety timeout
        long wallElapsed = System.nanoTime() - wallStart;

        // --- Print results ---
        System.out.println("=== Output from each interpreter ===\n");
        for (int i = 0; i < n; i++) {
            System.out.println("--- " + scriptNames.get(i) + " ---");
            if (errors[i] != null) {
                System.out.println("  ERROR: " + errors[i].getMessage());
                errors[i].printStackTrace(System.out);
            } else {
                // Indent each line for readability
                String out = outputs[i].toString().stripTrailing();
                for (String line : out.split("\n")) {
                    System.out.println("  " + line);
                }
                System.out.printf("  (executed in %.1f ms)%n", durations[i] / 1_000_000.0);
            }
            System.out.println();
        }

        System.out.printf("=== All %d interpreters finished (wall time: %.1f ms) ===%n",
                n, wallElapsed / 1_000_000.0);
    }
}
