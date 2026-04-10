package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.jvm.CustomClassLoader;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

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

    /**
     * Counter for generating unique per-runtime PIDs.
     * Starts at the real JVM PID so the first runtime gets the actual PID,
     * subsequent runtimes get incrementing values (realPid+1, realPid+2, ...).
     * This ensures $$ is unique per interpreter for temp file isolation.
     */
    private static final AtomicLong PID_COUNTER =
            new AtomicLong(ProcessHandle.current().pid());

    /**
     * Per-runtime synthetic PID, used as Perl's $$.
     * First runtime gets the real JVM PID; subsequent runtimes get unique values.
     */
    public final long pid = PID_COUNTER.getAndIncrement();

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
     * Dynamic state stack for RuntimeArray "local" save/restore —
     * migrated from RuntimeArray.dynamicStateStack.
     */
    final Stack<RuntimeArray> arrayDynamicStateStack = new Stack<>();

    /**
     * Dynamic state stack for RuntimeHash "local" save/restore —
     * migrated from RuntimeHash.dynamicStateStack.
     */
    final Stack<RuntimeHash> hashDynamicStateStack = new Stack<>();

    /**
     * Dynamic state stack for RuntimeStash "local" save/restore —
     * migrated from RuntimeStash.dynamicStateStack.
     */
    final Stack<RuntimeStash> stashDynamicStateStack = new Stack<>();

    /**
     * Glob slot stack for RuntimeGlob "local" save/restore —
     * migrated from RuntimeGlob.globSlotStack.
     * Elements are RuntimeGlob.GlobSlotSnapshot (package-private inner type).
     */
    final Stack<Object> globSlotStack = new Stack<>();

    /**
     * Localized stack for GlobalRuntimeScalar "local" save/restore —
     * migrated from GlobalRuntimeScalar.localizedStack.
     * Elements are GlobalRuntimeScalar.SavedGlobalState (package-private inner type).
     */
    final Stack<Object> globalScalarLocalizedStack = new Stack<>();

    /**
     * Localized stack for GlobalRuntimeArray "local" save/restore —
     * migrated from GlobalRuntimeArray.localizedStack.
     * Elements are GlobalRuntimeArray.SavedGlobalArrayState (package-private inner type).
     */
    final Stack<Object> globalArrayLocalizedStack = new Stack<>();

    /**
     * Localized stack for GlobalRuntimeHash "local" save/restore —
     * migrated from GlobalRuntimeHash.localizedStack.
     * Elements are GlobalRuntimeHash.SavedGlobalHashState (package-private inner type).
     */
    final Stack<Object> globalHashLocalizedStack = new Stack<>();

    /**
     * Dynamic state stack for RuntimeHashProxyEntry "local" save/restore —
     * migrated from RuntimeHashProxyEntry.dynamicStateStack.
     */
    final Stack<RuntimeScalar> hashProxyDynamicStateStack = new Stack<>();

    /**
     * Dynamic state stacks for RuntimeArrayProxyEntry "local" save/restore —
     * migrated from RuntimeArrayProxyEntry.dynamicStateStackInt and dynamicStateStack.
     */
    final Stack<Integer> arrayProxyDynamicStateStackInt = new Stack<>();
    final Stack<RuntimeScalar> arrayProxyDynamicStateStack = new Stack<>();

    /**
     * Input line state stack for ScalarSpecialVariable "local" save/restore —
     * migrated from ScalarSpecialVariable.inputLineStateStack.
     * Elements are ScalarSpecialVariable.InputLineState (package-private inner type).
     */
    final Stack<Object> inputLineStateStack = new Stack<>();

    /**
     * State stack for OutputAutoFlushVariable "local" save/restore —
     * migrated from OutputAutoFlushVariable.stateStack.
     * Elements are OutputAutoFlushVariable.State (package-private inner type).
     */
    final Stack<Object> autoFlushStateStack = new Stack<>();

    /**
     * ORS stack for OutputRecordSeparator "local $\" save/restore —
     * migrated from OutputRecordSeparator.orsStack.
     */
    final Stack<String> orsStack = new Stack<>();

    /**
     * OFS stack for OutputFieldSeparator "local $," save/restore —
     * migrated from OutputFieldSeparator.ofsStack.
     */
    final Stack<String> ofsStack = new Stack<>();

    /**
     * Errno stacks for ErrnoVariable "local $!" save/restore —
     * migrated from ErrnoVariable.errnoStack and messageStack.
     */
    final Stack<int[]> errnoStack = new Stack<>();
    final Stack<String> errnoMessageStack = new Stack<>();

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

    // ---- Regex match state — migrated from RuntimeRegex static fields ----

    /** Java Matcher object; provides %+, %-, @-, @+ group info. */
    public Matcher regexGlobalMatcher;

    /** Full input string being matched; used by $&, $`, $'. */
    public String regexGlobalMatchString;

    /** The matched substring ($&). */
    public String regexLastMatchedString = null;

    /** Start offset of match (for $`/@-[0]). */
    public int regexLastMatchStart = -1;

    /** End offset of match (for $'/@+[0]). */
    public int regexLastMatchEnd = -1;

    /** Persists across failed matches — matched string. */
    public String regexLastSuccessfulMatchedString = null;

    /** Persists across failed matches — start offset. */
    public int regexLastSuccessfulMatchStart = -1;

    /** Persists across failed matches — end offset. */
    public int regexLastSuccessfulMatchEnd = -1;

    /** Full input string from last successful match. */
    public String regexLastSuccessfulMatchString = null;

    /** ${^LAST_SUCCESSFUL_PATTERN} and $^R via getLastCodeBlockResult(). */
    public RuntimeRegex regexLastSuccessfulPattern = null;

    /** Tracks if /p was used (for ${^PREMATCH}, ${^MATCH}, ${^POSTMATCH}). */
    public boolean regexLastMatchUsedPFlag = false;

    /** Tracks if \K was used; adjusts group offsets. */
    public boolean regexLastMatchUsedBackslashK = false;

    /** Capture groups $1, $2, ...; persists across non-capturing matches. */
    public String[] regexLastCaptureGroups = null;

    /** Preserves BYTE_STRING type on captures. */
    public boolean regexLastMatchWasByteString = false;

    // ---- RuntimeCode compilation state — migrated from RuntimeCode static fields ----

    /** Tracks eval BEGIN block IDs during compilation. */
    public final java.util.IdentityHashMap<Object, Integer> evalBeginIds = new java.util.IdentityHashMap<>();

    /** LRU cache for compiled eval STRING results. */
    public final Map<String, Class<?>> evalCache = new java.util.LinkedHashMap<String, Class<?>>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Class<?>> eldest) {
            return size() > 100;
        }
    };

    /** LRU cache for method handles. */
    public final Map<Class<?>, java.lang.invoke.MethodHandle> methodHandleCache = new java.util.LinkedHashMap<Class<?>, java.lang.invoke.MethodHandle>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Class<?>, java.lang.invoke.MethodHandle> eldest) {
            return size() > 100;
        }
    };

    /** Temporary storage for anonymous subroutines during compilation. */
    public final HashMap<String, Class<?>> anonSubs = new HashMap<>();

    /** Storage for interpreter fallback closures. */
    public final HashMap<String, Object> interpretedSubs = new HashMap<>();

    /** Storage for eval string compiler context (values are EmitterContext but stored as Object to avoid circular deps). */
    public final HashMap<String, Object> evalContext = new HashMap<>();

    /** Current eval nesting depth for $^S support. */
    public int evalDepth = 0;

    /**
     * Whether GlobalContext.initializeGlobals() has been called for this runtime.
     * Each PerlRuntime needs its own initialization of global variables, @INC, %ENV,
     * built-in modules, etc. Previously this was a shared static boolean in
     * PerlLanguageProvider, which caused threads 2-N to skip initialization.
     */
    public boolean globalInitialized = false;

    /**
     * Per-runtime current working directory.
     * Initialized from System.getProperty("user.dir") at construction time.
     * Updated by Directory.chdir(). All path resolution in RuntimeIO.resolvePath()
     * reads from this field instead of the JVM-global "user.dir" property,
     * ensuring each interpreter has its own isolated CWD.
     */
    public String cwd = System.getProperty("user.dir");

    /** Inline method cache for fast method dispatch. */
    public static final int METHOD_CALL_CACHE_SIZE = 4096;
    public final int[] inlineCacheBlessId = new int[METHOD_CALL_CACHE_SIZE];
    public final int[] inlineCacheMethodHash = new int[METHOD_CALL_CACHE_SIZE];
    public final RuntimeCode[] inlineCacheCode = new RuntimeCode[METHOD_CALL_CACHE_SIZE];

    // ---- Warning/Hints stacks — migrated from WarningBitsRegistry ThreadLocals ----

    /** Stack of warning bits for the current execution context. */
    public final Deque<String> warningCurrentBitsStack = new ArrayDeque<>();

    /** Warning bits at the current call site. */
    public String warningCallSiteBits = null;

    /** Stack saving caller's call-site warning bits across subroutine calls. */
    public final Deque<String> warningCallerBitsStack = new ArrayDeque<>();

    /** Compile-time $^H (hints) at the current call site. */
    public int warningCallSiteHints = 0;

    /** Stack saving caller's $^H hints across subroutine calls. */
    public final Deque<Integer> warningCallerHintsStack = new ArrayDeque<>();

    /** Compile-time %^H (hints hash) snapshot at the current call site. */
    public Map<String, RuntimeScalar> warningCallSiteHintHash = new HashMap<>();

    /** Stack saving caller's %^H across subroutine calls. */
    public final Deque<Map<String, RuntimeScalar>> warningCallerHintHashStack = new ArrayDeque<>();

    // ---- HintHashRegistry stacks — migrated from HintHashRegistry ThreadLocals ----

    /** Current call site's hint hash snapshot ID. */
    public int hintCallSiteSnapshotId = 0;

    /** Stack saving caller's hint hash snapshot ID across subroutine calls. */
    public final Deque<Integer> hintCallerSnapshotIdStack = new ArrayDeque<>();

    // ---- RuntimeCode stacks — migrated from RuntimeCode ThreadLocals ----

    /** Eval runtime context (used during eval STRING compilation). */
    public Object evalRuntimeContext = null;

    /** Stack of @_ argument arrays across subroutine calls. */
    public final Deque<RuntimeArray> argsStack = new ArrayDeque<>();

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
     * Returns the current working directory for the current runtime.
     * Falls back to System.getProperty("user.dir") if no runtime is bound.
     */
    public static String getCwd() {
        PerlRuntime rt = CURRENT.get();
        return rt != null ? rt.cwd : System.getProperty("user.dir");
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
