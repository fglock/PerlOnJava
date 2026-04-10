package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.jvm.CustomClassLoader;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.mro.InheritanceResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 * PerlRuntime represents an independent Perl interpreter instance.
 * Each PerlRuntime holds its own copy of all mutable runtime state,
 * enabling multiple Perl interpreters to coexist within the same JVM
 * (multiplicity).
 *
 * <p>The current runtime is stored in a ThreadLocal, so each thread
 * can be bound to a different PerlRuntime. Access the current runtime
 * via {@link #current()}.</p>
 *
 * <p>Migration strategy: Subsystems are migrated incrementally from
 * static fields to PerlRuntime instance fields. During migration, the
 * original classes (e.g., CallerStack, DynamicVariableManager) retain
 * their static method signatures but delegate to PerlRuntime.current()
 * internally, so callers don't need to change.</p>
 *
 * @see <a href="dev/design/concurrency.md">Concurrency Design Document</a>
 */
public final class PerlRuntime {

    private static final ThreadLocal<PerlRuntime> CURRENT = new ThreadLocal<>();

    // ---- Per-runtime state (migrated from static fields) ----

    /**
     * Caller stack for caller() function — migrated from CallerStack.callerStack.
     * Stores CallerInfo and LazyCallerInfo objects.
     */
    final List<Object> callerStack = new ArrayList<>();

    /**
     * Dynamic variable stack for Perl's "local" — migrated from DynamicVariableManager.variableStack.
     * Using ArrayDeque for performance (no synchronization overhead).
     */
    final Deque<DynamicState> dynamicVariableStack = new ArrayDeque<>();

    /**
     * Dynamic state stack for RuntimeScalar "local" save/restore —
     * migrated from RuntimeScalar.dynamicStateStack.
     */
    final Stack<RuntimeScalar> dynamicStateStack = new Stack<>();

    /**
     * Special block arrays (END, INIT, CHECK) — migrated from SpecialBlock.
     */
    final RuntimeArray endBlocks = new RuntimeArray();
    final RuntimeArray initBlocks = new RuntimeArray();
    final RuntimeArray checkBlocks = new RuntimeArray();

    // ---- I/O state — migrated from RuntimeIO static fields ----

    /**
     * Standard output stream handle (STDOUT) — migrated from RuntimeIO.stdout.
     */
    RuntimeIO ioStdout;

    /**
     * Standard error stream handle (STDERR) — migrated from RuntimeIO.stderr.
     */
    RuntimeIO ioStderr;

    /**
     * Standard input stream handle (STDIN) — migrated from RuntimeIO.stdin.
     */
    RuntimeIO ioStdin;

    /**
     * The currently selected filehandle for output operations (Perl's select()).
     * Used by print/printf when no filehandle is specified.
     */
    RuntimeIO ioSelectedHandle;

    /**
     * The last handle used for output writes (print/say/etc).
     */
    RuntimeIO ioLastWrittenHandle;

    /**
     * The last accessed filehandle, used for Perl's ${^LAST_FH} special variable.
     */
    RuntimeIO ioLastAccessedHandle;

    /**
     * The variable/handle name used in the last readline operation.
     */
    String ioLastReadlineHandleName;

    // ---- Inheritance / MRO state — migrated from InheritanceResolver static fields ----

    /**
     * Cache for linearized class hierarchies (C3/DFS results).
     */
    public final Map<String, List<String>> linearizedClassesCache = new HashMap<>();

    /**
     * Per-package MRO algorithm settings.
     */
    public final Map<String, InheritanceResolver.MROAlgorithm> packageMRO = new HashMap<>();

    /**
     * Method resolution cache (method name -> code ref).
     */
    public final Map<String, RuntimeScalar> methodCache = new HashMap<>();

    /**
     * Cache for OverloadContext instances by blessing ID.
     */
    public final Map<Integer, OverloadContext> overloadContextCache = new HashMap<>();

    /**
     * Tracks ISA array states for change detection.
     */
    public final Map<String, List<String>> isaStateCache = new HashMap<>();

    /**
     * Whether AUTOLOAD is enabled for method resolution.
     */
    public boolean autoloadEnabled = true;

    /**
     * Default MRO algorithm (DFS by default, matching Perl 5).
     */
    public InheritanceResolver.MROAlgorithm currentMRO = InheritanceResolver.MROAlgorithm.DFS;

    // ---- Symbol table state — migrated from GlobalVariable static fields ----

    /** Global scalar variables (%main:: scalar namespace). */
    public final Map<String, RuntimeScalar> globalVariables = new HashMap<>();

    /** Global array variables. */
    public final Map<String, RuntimeArray> globalArrays = new HashMap<>();

    /** Global hash variables. */
    public final Map<String, RuntimeHash> globalHashes = new HashMap<>();

    /** Cache for package existence checks. */
    public final Map<String, Boolean> packageExistsCache = new HashMap<>();

    /** Tracks subroutines declared via 'use subs' pragma. */
    public final Map<String, Boolean> isSubs = new HashMap<>();

    /** Global code references (subroutine namespace). */
    public final Map<String, RuntimeScalar> globalCodeRefs = new HashMap<>();

    /** Global IO references (filehandle globs). */
    public final Map<String, RuntimeGlob> globalIORefs = new HashMap<>();

    /** Global format references. */
    public final Map<String, RuntimeFormat> globalFormatRefs = new HashMap<>();

    /** Pinned code references that survive stash deletion. */
    public final Map<String, RuntimeScalar> pinnedCodeRefs = new HashMap<>();

    /** Stash aliasing: *Dst:: = *Src:: makes Dst symbol table redirect to Src. */
    public final Map<String, String> stashAliases = new HashMap<>();

    /** Glob aliasing: *a = *b makes a and b share the same glob. */
    public final Map<String, String> globAliases = new HashMap<>();

    /** Flags for typeglob assignments (operator override detection). */
    public final Map<String, Boolean> globalGlobs = new HashMap<>();

    /** Global class loader for generated classes. Not final so it can be replaced. */
    public CustomClassLoader globalClassLoader =
            new CustomClassLoader(GlobalVariable.class.getClassLoader());

    /** Track explicitly declared global variables (via use vars, our, Exporter). */
    public final Set<String> declaredGlobalVariables = new HashSet<>();
    public final Set<String> declaredGlobalArrays = new HashSet<>();
    public final Set<String> declaredGlobalHashes = new HashSet<>();

    // ---- Static accessors ----

    /**
     * Returns the PerlRuntime bound to the current thread.
     * This is the primary entry point for all runtime state access.
     *
     * @return the current PerlRuntime, never null during normal execution
     * @throws IllegalStateException if no runtime is bound to this thread
     */
    public static PerlRuntime current() {
        PerlRuntime rt = CURRENT.get();
        if (rt == null) {
            throw new IllegalStateException(
                    "No PerlRuntime bound to current thread. " +
                    "Call PerlRuntime.initialize() or PerlRuntime.setCurrent() first.");
        }
        return rt;
    }

    /**
     * Returns the PerlRuntime bound to the current thread, or null if none.
     * Use this for checks where missing runtime is expected (e.g., initialization).
     */
    public static PerlRuntime currentOrNull() {
        return CURRENT.get();
    }

    /**
     * Binds the given PerlRuntime to the current thread.
     *
     * @param rt the runtime to bind, or null to unbind
     */
    public static void setCurrent(PerlRuntime rt) {
        if (rt == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(rt);
        }
    }

    /**
     * Creates a new PerlRuntime and binds it to the current thread.
     * If a runtime is already bound, it is replaced.
     *
     * @return the newly created runtime
     */
    public static PerlRuntime initialize() {
        PerlRuntime rt = new PerlRuntime();
        CURRENT.set(rt);
        return rt;
    }

    /**
     * Creates a new independent PerlRuntime (not bound to any thread).
     * Call {@link #setCurrent(PerlRuntime)} to bind it to a thread before use.
     */
    public PerlRuntime() {
        // Initialize standard I/O handles
        this.ioStdout = new RuntimeIO(new StandardIO(System.out, true));
        this.ioStderr = new RuntimeIO(new StandardIO(System.err, false));
        this.ioStderr.autoFlush = true;  // STDERR is unbuffered by default, like in Perl
        this.ioStdin = new RuntimeIO(new StandardIO(System.in));
        this.ioSelectedHandle = this.ioStdout;
        this.ioLastWrittenHandle = this.ioStdout;
    }
}
