package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.backend.bytecode.BytecodeCompiler;
import org.perlonjava.backend.bytecode.Disassemble;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.backend.bytecode.InterpreterState;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.backend.jvm.EmitterMethodCreator;
import org.perlonjava.backend.jvm.InterpreterFallbackException;
import org.perlonjava.backend.jvm.JavaClassInfo;
import org.perlonjava.frontend.analysis.ConstantFoldingVisitor;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.parser.Parser;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.runtime.ForkOpenCompleteException;
import org.perlonjava.runtime.HintHashRegistry;
import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.debugger.DebugHooks;
import org.perlonjava.runtime.debugger.DebugState;
import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.perlmodule.BHooksEndOfScope;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.CoreSubroutineGenerator;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

import static org.perlonjava.frontend.parser.ParserTables.CORE_PROTOTYPES;
import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;
import static org.perlonjava.frontend.parser.SpecialBlockParser.setCurrentScope;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;
import static org.perlonjava.runtime.runtimetypes.SpecialBlock.runUnitcheckBlocks;

/**
 * The RuntimeCode class represents a compiled code object in the runtime environment.
 * It provides functionality to compile, store, and execute Perl subroutines and eval strings.
 */
public class RuntimeCode extends RuntimeBase implements RuntimeScalarReference {

    private PerlRuntime boundRuntime;

    /** Bind Java callbacks that may be invoked by an arbitrary worker thread. */
    public RuntimeCode bindCallbackTo(PerlRuntime runtime) {
        this.boundRuntime = runtime;
        return this;
    }

    // Lookup object for performing method handle operations
    public static final MethodHandles.Lookup lookup = MethodHandles.lookup();

    public static IdentityHashMap<OperatorNode, Integer> evalBeginIds() {
        return PerlRuntime.current().runtimeCodeState().evalBeginIds;
    }

    /**
     * Flag to control whether eval STRING should use the interpreter backend.
     * Enabled by default. eval STRING compiles to InterpretedCode instead of generating JVM bytecode.
     * This provides 46x faster compilation for workloads with many unique eval strings.
     * <p>
     * Set environment variable JPERL_EVAL_NO_INTERPRETER=1 to disable.
     */
    public static final boolean EVAL_USE_INTERPRETER =
            System.getenv("JPERL_EVAL_NO_INTERPRETER") == null;

    /**
     * Flag to control whether eval compilation errors should be printed to stderr.
     * By default, eval failures are silent (errors only stored in $@).
     * <p>
     * Set environment variable JPERL_EVAL_VERBOSE=1 to enable verbose error reporting.
     * This is useful for debugging eval compilation issues, especially when testing
     * the interpreter path.
     */
    public static final boolean EVAL_VERBOSE =
            System.getenv("JPERL_EVAL_VERBOSE") != null;

    public static final boolean EVAL_TRACE =
            System.getenv("JPERL_EVAL_TRACE") != null;
    /**
     * ThreadLocal stack for runtime values of captured variables during eval STRING compilation.
     * <p>
     * PROBLEM: In perl5, BEGIN blocks inside eval STRING can access outer lexical variables' runtime values:
     * my @imports = qw(a b);
     * eval q{ BEGIN { say @imports } };  # perl5 prints: a b
     * <p>
     * In PerlOnJava, BEGIN blocks execute during parsing (before the eval class is instantiated),
     * so they couldn't access runtime values - they would see empty variables.
     * <p>
     * SOLUTION: When evalStringHelper() is called, the runtime values are pushed onto this ThreadLocal stack.
     * During parsing, when SpecialBlockParser sets up BEGIN blocks, it can access these runtime values
     * and use them to initialize the special globals that lexical variables become in BEGIN blocks.
     * <p>
     * This ThreadLocal stores:
     * - Key: The evalTag identifying this eval compilation
     * - Value: EvalRuntimeContext containing:
     * - runtimeValues: Object[] of captured variable values
     * - capturedEnv: String[] of captured variable names (matching array indices)
     * <p>
     * Thread-safety: Each thread's eval compilation uses its own ThreadLocal stack, so parallel
     * eval compilations don't interfere with each other. A stack is required because eval STRING
     * compilation can re-enter eval STRING compilation via BEGIN/use/require.
     */
    private static ArrayDeque<EvalRuntimeContext> evalRuntimeContextStack() {
        return PerlRuntime.current().executionState().evalRuntimeContexts;
    }
    private static ArrayDeque<ArrayList<String>> syntheticCallerFrames() {
        return PerlRuntime.current().executionState().syntheticCallerFrames;
    }
    private static Map<String, Class<?>> evalCache() {
        return PerlRuntime.current().runtimeCodeState().evalCache;
    }
    /**
     * Flag to enable disassembly of eval STRING bytecode.
     * When set, prints the interpreter bytecode for each eval STRING compilation.
     * <p>
     * Set environment variable JPERL_DISASSEMBLE=1 to enable, or use --disassemble CLI flag.
     * The --disassemble flag sets this via setDisassemble().
     */
    public static final boolean FORCE_INTERPRETER =
            System.getenv("JPERL_INTERPRETER") != null;
    public static final MethodType methodType =
            MethodType.methodType(RuntimeList.class, RuntimeArray.class, int.class);

    /**
     * Tracks the current eval nesting depth for $^S support.
     * 0 = not inside any eval, >0 = inside eval (eval STRING or eval BLOCK).
     * Incremented on eval entry, decremented on eval exit (success or failure).
     */
    public static int getEvalDepth() {
        return PerlRuntime.current().executionState().evalDepth;
    }

    public static void incrementEvalDepth() {
        PerlRuntime.current().executionState().evalDepth++;
    }

    public static void decrementEvalDepth() {
        PerlRuntime.current().executionState().evalDepth--;
    }

    public static void adjustEvalDepth(int delta) {
        PerlRuntime.current().executionState().evalDepth += delta;
    }

    /**
     * Thread-local stack of @_ arrays for each active subroutine call.
     * This allows nested code blocks (like those passed to List::Util::any/all/grep/map)
     * to access the outer subroutine's @_ via $_[0], $_[1], etc.
     * 
     * Push/pop is handled by RuntimeCode.apply() methods.
     * Access via getCurrentArgs() for Java-implemented functions that need caller's @_.
     */
    private static Deque<RuntimeArray> argsStack() {
        return PerlRuntime.current().executionState().argsStack;
    }

    /**
     * Thread-local stack of RuntimeCode objects currently executing on this
     * Java thread. Weak CODE backrefs, especially Sub::Defer's self slot, must
     * not be cleared while the referent CODE is still running.
     */
    private static Deque<RuntimeCode> activeCodeStack() {
        return PerlRuntime.current().executionState().activeCodeStack;
    }

    private static Deque<RuntimeCode> activeCodeStack(ExecutionRuntimeState executionState) {
        return executionState.activeCodeStack;
    }

    private record ActiveLexicalFrame(RuntimeCode code, Map<String, RuntimeBase> cells) {}
    @SuppressWarnings("unchecked")
    private static Deque<ActiveLexicalFrame> activeLexicalFrames(
            ExecutionRuntimeState executionState) {
        return (Deque<ActiveLexicalFrame>) (Deque<?>) executionState.activeLexicalFrames;
    }

    /**
     * Thread-local stack of pristine (unshifted) @_ snapshots taken at sub-entry
     * time. Used to populate {@code @DB::args} for {@code caller(N)} from package DB.
     * <p>
     * In Perl, {@code @DB::args} reflects the args the sub was called with,
     * regardless of whether the sub later shifted or otherwise mutated @_.
     * Without this snapshot, patterns like DBIC's TxnScopeGuard double-DESTROY
     * detection — which relies on {@code @DB::args} to hold a strong reference
     * to the object being destroyed — would break once the callee does
     * {@code shift(@_)}.
     * <p>
     * The snapshot is a cheap new ArrayList of the same RuntimeScalar element
     * references; subsequent shifts/modifications of the live @_ don't affect it.
     */
    private static Deque<java.util.List<RuntimeScalar>> pristineArgsStack() {
        return PerlRuntime.current().executionState().pristineArgsStack;
    }

    /**
     * Thread-local stack tracking whether each call frame created a fresh @_ (hasargs).
     * In Perl 5, caller()[4] (hasargs) is 1 when the subroutine was called with explicit
     * arguments (func() or &func()), and false/empty when called via &func (no parens)
     * which inherits the caller's @_.
     *
     * Push/pop is handled alongside argsStack in the apply() methods.
     */
    private static Deque<Boolean> hasArgsStack() {
        return PerlRuntime.current().executionState().hasArgsStack;
    }

    /**
     * Thread-local stack tracking the context each active subroutine was called in.
     * Perl exposes this as caller(EXPR)[5]: undef for void, false for scalar, true
     * for list context.
     */
    private static Deque<Integer> callContextStack() {
        return PerlRuntime.current().executionState().callContextStack;
    }

    /**
     * Get the current subroutine's @_ array.
     * Used by Java-implemented functions (like List::Util::any) that need to pass
     * the caller's @_ to code blocks.
     *
     * @return The current @_ array, or null if not in a subroutine
     */
    public static RuntimeArray getCurrentArgs() {
        Deque<RuntimeArray> stack = argsStack();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * Return the current call frame's argument array for {@code goto &sub}.
     *
     * <p>A {@code local *_} performed by this call frame replaces its argument
     * array for a tail call. An underscore glob localized by an outer frame
     * (as File::Find does) must not replace a nested call's arguments.</p>
     */
    public static RuntimeArray getGotoArgs(RuntimeArray lexicalArgs, String packageName) {
        RuntimeArray localized = RuntimeGlob.localizedUnderscoreArrayForCurrentCall();
        if (localized != null) return localized;
        return lexicalArgs;
    }

    public static java.util.List<RuntimeArray> snapshotArgsStack() {
        return new java.util.ArrayList<>(argsStack());
    }

    /**
     * Snapshot the original arguments of active calls before @_ mutations.
     * A method commonly shifts its invocant into a lexical; retaining these
     * frame arguments keeps reachability sweeps from destroying that invocant
     * while the method is still executing.
     */
    public static java.util.List<java.util.List<RuntimeScalar>> snapshotPristineArgsStack() {
        java.util.List<java.util.List<RuntimeScalar>> snapshot = new java.util.ArrayList<>();
        for (java.util.List<RuntimeScalar> args : pristineArgsStack()) {
            snapshot.add(new java.util.ArrayList<>(args));
        }
        return snapshot;
    }

    public static int argsStackDepth() {
        return argsStack().size();
    }

    public static void pushActiveCode(RuntimeCode code) {
        PerlRuntime runtime = PerlRuntime.current();
        ExecutionRuntimeState executionState = runtime.executionState();
        activeCodeStack(executionState).push(code);
        if (runtime.runtimeCodeState().lexicalAliasSupportEnabled) {
            activeLexicalFrames(executionState).push(
                    new ActiveLexicalFrame(code, new HashMap<>()));
        }
    }

    public static void popActiveCode(RuntimeCode code) {
        PerlRuntime runtime = PerlRuntime.current();
        ExecutionRuntimeState executionState = runtime.executionState();
        if (runtime.runtimeCodeState().lexicalAliasSupportEnabled) {
            Deque<ActiveLexicalFrame> frames = activeLexicalFrames(executionState);
            if (!frames.isEmpty() && frames.peek().code() == code) {
                frames.pop();
            } else {
                frames.removeIf(frame -> frame.code() == code);
            }
        }
        Deque<RuntimeCode> stack = activeCodeStack(executionState);
        if (!stack.isEmpty() && stack.peek() == code) {
            stack.pop();
            return;
        }
        stack.removeFirstOccurrence(code);
    }

    public static boolean isActiveCode(RuntimeCode code) {
        if (code == null) return false;
        for (RuntimeCode active : activeCodeStack()) {
            if (active == code) return true;
        }
        return false;
    }

    public static RuntimeCode getActiveCodeAt(int depth) {
        if (depth < 0) return null;
        int i = 0;
        for (RuntimeCode active : activeCodeStack()) {
            if (i++ == depth) {
                return active;
            }
        }
        return null;
    }

    /** True when an interpreter-owned Future::AsyncAwait frame is active. */
    public static boolean hasActiveFutureAsyncAwaitSub() {
        for (RuntimeCode active : activeCodeStack()) {
            if (active instanceof InterpretedCode interpreted
                    && interpreted.futureAsyncAwaitSub) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasActiveCode() {
        return !activeCodeStack().isEmpty();
    }

    public static void enableLexicalAliasSupport() {
        PerlRuntime.current().runtimeCodeState().lexicalAliasSupportEnabled = true;
    }

    private static boolean sameLogicalCode(RuntimeCode left, RuntimeCode right) {
        if (left == right) return true;
        return left != null && left.__SUB__ != null && left.__SUB__.value == right
                || right != null && right.__SUB__ != null && right.__SUB__.value == left;
    }

    private static void registerActiveLexical(
            RuntimeCode code, String variableName, RuntimeBase cell) {
        PerlRuntime runtime = PerlRuntime.current();
        if (!runtime.runtimeCodeState().lexicalAliasSupportEnabled) return;
        Deque<ActiveLexicalFrame> frames = activeLexicalFrames(runtime.executionState());
        for (ActiveLexicalFrame frame : frames) {
            if (sameLogicalCode(frame.code(), code)) {
                frame.cells().put(variableName, cell);
                return;
            }
        }
        // Lazy named-CV materialization and ithread adoption can execute a
        // runtime-owned CODE object while generated lexical initialization
        // still carries the logically equivalent template CODE reference.
        // The top frame is nevertheless the authoritative invocation whose
        // cell is being initialized. Without this fallback the child frame is
        // left empty and runtime regex source captures undef for outer cells.
        if (!frames.isEmpty()) {
            frames.peek().cells().put(variableName, cell);
        }
    }

    public static RuntimeBase findActiveLexical(RuntimeCode code, String variableName) {
        PerlRuntime runtime = PerlRuntime.current();
        if (!runtime.runtimeCodeState().lexicalAliasSupportEnabled) return null;
        for (ActiveLexicalFrame frame : activeLexicalFrames(runtime.executionState())) {
            if (sameLogicalCode(frame.code(), code)) {
                RuntimeBase cell = frame.cells().get(variableName);
                if (cell != null) return cell;
            }
        }
        return null;
    }

    /** Find the Perl lexical name for a live cell, for value-specific diagnostics. */
    public static String findActiveLexicalName(RuntimeBase cell) {
        if (cell == null) return null;
        PerlRuntime runtime = PerlRuntime.current();
        if (!runtime.runtimeCodeState().lexicalAliasSupportEnabled) return null;
        for (ActiveLexicalFrame frame : activeLexicalFrames(runtime.executionState())) {
            for (Map.Entry<String, RuntimeBase> entry : frame.cells().entrySet()) {
                if (entry.getValue() == cell) return entry.getKey();
            }
        }
        return null;
    }

    /** Return a stable snapshot of the live lexical cells for an active CV. */
    public static Map<String, RuntimeBase> snapshotActiveLexicals(RuntimeCode code) {
        if (code == null) return Collections.emptyMap();
        PerlRuntime runtime = PerlRuntime.current();
        if (!runtime.runtimeCodeState().lexicalAliasSupportEnabled) {
            return Collections.emptyMap();
        }
        for (ActiveLexicalFrame frame : activeLexicalFrames(runtime.executionState())) {
            if (sameLogicalCode(frame.code(), code)) {
                return new LinkedHashMap<>(frame.cells());
            }
        }
        return Collections.emptyMap();
    }

    /**
     * Get the caller's @_ array (one level up from current).
     * Used by Java-implemented functions (like List::Util::any) that need to pass
     * the outer Perl subroutine's @_ to code blocks.
     * 
     * When a Java method is called via RuntimeCode.apply(), its @_ is pushed onto the stack.
     * To get the @_ from the Perl subroutine that called this Java method, we need to look
     * one level deeper in the stack.
     *
     * @return The caller's @_ array, or null if not available
     */
    public static RuntimeArray getCallerArgs() {
        Deque<RuntimeArray> stack = argsStack();
        if (stack.size() < 2) {
            return null;
        }
        // Convert to array to access by index (skip top element)
        RuntimeArray[] arr = stack.toArray(new RuntimeArray[0]);
        return arr[1];
    }

    /**
     * Push @_ onto the args stack when entering a subroutine.
     * Public so BytecodeInterpreter can use it when calling InterpretedCode directly.
     */
    public static void pushArgs(RuntimeArray args) {
        argsStack().push(args);
        // Snapshot the args list so @DB::args stays pristine even if the sub
        // later shifts/pops from @_.
        pristineArgsStack().push(
                args != null ? new java.util.ArrayList<>(args.elements) : new java.util.ArrayList<>());
    }

    public static void pushCallContext(int callContext) {
        callContextStack().push(callContext);
    }

    public static int currentRawCallContext() {
        Integer context = getCallContextAtCallerFrame(0);
        return context != null ? context : RuntimeContextType.SCALAR;
    }

    /**
     * Pop @_ and hasargs flag from their respective stacks when exiting a subroutine.
     * Both stacks are pushed in the instance apply() methods and must be popped together.
     * Public so BytecodeInterpreter can use it when calling InterpretedCode directly.
     */
    public static void popArgs() {
        Deque<RuntimeArray> stack = argsStack();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        Deque<java.util.List<RuntimeScalar>> pStack = pristineArgsStack();
        if (!pStack.isEmpty()) {
            pStack.pop();
        }
        Deque<Boolean> haStack = hasArgsStack();
        if (!haStack.isEmpty()) {
            haStack.pop();
        }
        Deque<Integer> ctxStack = callContextStack();
        if (!ctxStack.isEmpty()) {
            ctxStack.pop();
        }
    }

    /**
     * Return the frame-N snapshot of original invocation args, used by
     * caller()'s {@code @DB::args} support. Frame 0 is the innermost call.
     *
     * @param frame zero-based frame index (0 = current sub)
     * @return a RuntimeArray wrapping the snapshot, or null if frame is out of range
     */
    public static RuntimeArray getOriginalArgsAt(int frame) {
        Deque<java.util.List<RuntimeScalar>> stack = pristineArgsStack();
        if (frame < 0 || frame >= stack.size()) return null;
        int i = 0;
        for (java.util.List<RuntimeScalar> list : stack) {
            if (i++ == frame) {
                RuntimeArray ra = new RuntimeArray();
                ra.elements = new java.util.ArrayList<>(list);
                return ra;
            }
        }
        return null;
    }

    /** True when this scalar is one of the current call's original @_ aliases. */
    public static boolean isCurrentArgumentAlias(RuntimeScalar scalar) {
        if (scalar == null) return false;
        if (PerlRuntime.currentOrNull() == null) return false;
        Deque<java.util.List<RuntimeScalar>> stack = pristineArgsStack();
        if (stack.isEmpty()) return false;
        for (RuntimeScalar argument : stack.peek()) {
            if (argument == scalar) return true;
        }
        return false;
    }

    /**
     * Return the pristine arguments belonging to a specific active code object.
     * The formatted caller stack collapses compiler/interpreter wrapper pairs,
     * while the argument stack retains both entries, so a logical caller frame
     * cannot always be used as a raw argument-stack index.
     */
    private static RuntimeArray getOriginalArgsForCode(RuntimeCode target) {
        if (target == null) return null;
        Iterator<RuntimeCode> codeIt = activeCodeStack().iterator();
        Iterator<java.util.List<RuntimeScalar>> argsIt = pristineArgsStack().iterator();
        while (codeIt.hasNext() && argsIt.hasNext()) {
            if (codeIt.next() == target) {
                java.util.List<RuntimeScalar> list = argsIt.next();
                RuntimeArray result = new RuntimeArray();
                result.elements = new java.util.ArrayList<>(list);
                return result;
            }
            argsIt.next();
        }
        return null;
    }

    /**
     * Get the hasargs flag for a given call depth.
     * depth=0 is the current (innermost) frame, depth=1 is its caller, etc.
     *
     * This depth maps directly to the user-supplied argument of caller(N):
     * caller(0) queries depth 0, caller(1) queries depth 1, etc.
     * The mapping works because hasArgsStack has one entry per Perl subroutine
     * call (pushed in the instance apply() methods), and the Deque iteration
     * order is LIFO (most recent first), matching the call stack order.
     *
     * @return true if the frame at that depth created fresh @_, false if it
     *         inherited @_ (via &amp;func with no parens), null if depth is out of range
     */
    public static Boolean getHasArgsAt(int depth) {
        Deque<Boolean> stack = hasArgsStack();
        int i = 0;
        for (Boolean b : stack) {
            if (i == depth) return b;
            i++;
        }
        return null;
    }

    public static Integer getCallContextAt(int depth) {
        Deque<Integer> stack = callContextStack();
        int i = 0;
        for (Integer callContext : stack) {
            if (i == depth) return callContext;
            i++;
        }
        return null;
    }

    /**
     * Return the outermost raw context for one logical Perl call frame.
     * Interpreter execution can add an adjacent compiler-wrapper frame with
     * effective scalar context; parent-op consumers need the outer raw frame.
     */
    public static Integer getCallContextAtCallerFrame(int logicalFrame) {
        if (logicalFrame < 0) return null;
        java.util.List<RuntimeCode> codes = new java.util.ArrayList<>(activeCodeStack());
        java.util.List<Integer> contexts = new java.util.ArrayList<>(callContextStack());
        int size = Math.min(codes.size(), contexts.size());
        int logicalIndex = 0;
        for (int i = 0; i < size; ) {
            RuntimeCode previous = codes.get(i);
            Integer outermostContext = contexts.get(i);
            int j = i + 1;
            while (j < size) {
                RuntimeCode next = codes.get(j);
                if (next != previous && !isCompilerWrapperPair(next, previous)) break;
                outermostContext = contexts.get(j);
                previous = next;
                j++;
            }
            if (logicalIndex++ == logicalFrame) return outermostContext;
            i = j;
        }
        return null;
    }

    private static RuntimeScalar callerWantarrayScalar(Integer callContext) {
        if (callContext == null) {
            return RuntimeScalarCache.scalarUndef;
        }
        if (RuntimeContextType.isListLike(callContext)) {
            return RuntimeScalarCache.scalarTrue;
        }
        if (callContext == RuntimeContextType.SCALAR || callContext == RuntimeContextType.LVALUE
                || callContext == RuntimeContextType.OBJECT) {
            return RuntimeScalarCache.scalarFalse;
        }
        return RuntimeScalarCache.scalarUndef;
    }

    /**
     * Inline method cache for fast method dispatch at monomorphic call sites.
     * 
     * In OO code, most call sites (e.g., `$obj->method()`) repeatedly call the same
     * method on objects of the same class. This is called a "monomorphic" call site.
     * Without caching, each call requires a full method lookup traversing the @ISA
     * hierarchy, which is expensive.
     * 
     * How it works:
     * 1. Each method call site in compiled code gets a unique callsiteId (allocated
     *    at compile time via allocateMethodCallsiteId()).
     * 2. The callsiteId maps to a cache slot via: cacheIndex = callsiteId & (SIZE - 1)
     * 3. Each cache slot stores: (blessId, methodHash, RuntimeCode)
     *    - blessId: identifies the class of the object (from $obj's bless)
     *    - methodHash: identifies which method is being called
     *    - RuntimeCode: the resolved method to invoke
     * 4. On cache hit (same blessId + methodHash), we skip method resolution and
     *    directly invoke the cached MethodHandle.
     * 5. On cache miss, we do full method resolution and update the cache.
     * 
     * Cache invalidation:
     * When @ISA changes or methods are redefined, InheritanceResolver.invalidateCache()
     * calls clearInlineMethodCache() to clear all cached entries.
     * 
     * This optimization provides ~50% speedup for method-heavy code like:
     *   while ($i < 10000) { $obj->method($arg); $i++ }
     */
    public static int allocateMethodCallsiteId() {
        return PerlRuntime.current().runtimeCodeState().allocateMethodCallsiteId();
    }
    
    /**
     * Clear the inline method cache. Should be called when method definitions change.
     */
    public static void clearInlineMethodCache() {
        PerlRuntime.current().runtimeCodeState().clearInlineMethodCache();
    }

    public static int effectiveCallContext(int callContext) {
        return callContext == RuntimeContextType.LVALUE || callContext == RuntimeContextType.OBJECT
                ? RuntimeContextType.SCALAR
                : callContext;
    }

    public static int effectiveCallContext(RuntimeCode code, int callContext) {
        if (isLvalueCode(code)
                && (callContext == RuntimeContextType.LVALUE
                || callContext == RuntimeContextType.LVALUE_LIST)) {
            return callContext;
        }
        return effectiveCallContext(callContext);
    }

    public static RuntimeList returnList(RuntimeBase retVal, int callContext) {
        return returnList(retVal, callContext, true);
    }

    public static RuntimeList returnList(RuntimeBase retVal, int callContext, boolean copyReferenceScalars) {
        if (retVal == null) {
            return new RuntimeList();
        }
        if (callContext == RuntimeContextType.LVALUE_LIST
                && !(retVal instanceof RuntimeControlFlowList)) {
            RuntimeList result = new RuntimeList();
            result.add(retVal);
            return result;
        }
        RuntimeList result = retVal.getList();
        if (copyReferenceScalars && callContext == RuntimeContextType.LIST) {
            RuntimeList copied = copyReturnedReferenceScalars(result, callContext, true);
            if (copied != result) {
                MortalList.pushTemporaryRoot(copied);
            }
            return copied;
        }
        return result;
    }

    /**
     * Perl collapses a multi-value list returned from a subroutine when the callee runs in
     * scalar context: only the last element survives as the actual return SV. PerlOnJava
     * historically returned the raw {@link RuntimeList} and relied on the caller (e.g.
     * chained {@code ->}) to call {@link RuntimeList#scalar()}, which ran too late — mortal
     * temporaries from intermediate values (DBI execute results, etc.) could be flushed and
     * tear down shared JDBC state before the outer method ran.
     */
    public static RuntimeList coerceScalarCallResult(RuntimeList result, int effectiveContext) {
        return coerceScalarCallResult(result, effectiveContext, effectiveContext, true);
    }

    public static RuntimeList coerceScalarCallResult(RuntimeList result, int effectiveContext, int originalContext) {
        return coerceScalarCallResult(result, effectiveContext, originalContext, true);
    }

    public static RuntimeList coerceScalarCallResult(RuntimeList result, int effectiveContext,
                                                     int originalContext, boolean copyCapturedScalars) {
        try {
            if (result == null) {
                return null;
            }
            if (result instanceof RuntimeControlFlowList) {
                return result;
            }
            if (effectiveContext == RuntimeContextType.SCALAR && result.elements.size() > 1) {
                return copyReturnedReferenceScalars(new RuntimeList(result.scalar()), originalContext, copyCapturedScalars);
            }
            if (effectiveContext == RuntimeContextType.SCALAR && result.elements.size() == 1) {
                RuntimeBase value = result.elements.getFirst();
                if (originalContext != RuntimeContextType.LVALUE
                        && originalContext != RuntimeContextType.LVALUE_LIST
                        && value instanceof RuntimeScalar scalar
                        && scalar.type == RuntimeScalarType.TIED_SCALAR) {
                    return copyReturnedReferenceScalars(new RuntimeList(scalar.tiedFetch()), originalContext, copyCapturedScalars);
                }
            }
            return copyReturnedReferenceScalars(copyReadonlyListReturns(result, effectiveContext),
                    originalContext, copyCapturedScalars);
        } finally {
            MortalList.popTemporaryRoot(result);
        }
    }

    private static RuntimeList copyReturnedReferenceScalars(RuntimeList result, int originalContext,
                                                        boolean copyCapturedScalars) {
        if (result == null
                || result instanceof RuntimeControlFlowList
                || !copyCapturedScalars
                || originalContext == RuntimeContextType.LVALUE
                || originalContext == RuntimeContextType.LVALUE_LIST) {
            return result;
        }
        for (RuntimeBase value : result.elements) {
            if (value instanceof RuntimeScalar scalar
                    && !isCodeScalar(scalar)) {
                return result.cloneScalars();
            }
        }
        return result;
    }

    private static boolean isCodeScalar(RuntimeScalar scalar) {
        RuntimeScalar current = scalar;
        while (current != null && current.type == RuntimeScalarType.READONLY_SCALAR) {
            current = (RuntimeScalar) current.value;
        }
        return current != null && current.type == RuntimeScalarType.CODE;
    }

    private static RuntimeList copyReadonlyListReturns(RuntimeList result, int effectiveContext) {
        if (effectiveContext != RuntimeContextType.LIST) {
            return result;
        }

        RuntimeList copy = null;
        for (int i = 0; i < result.elements.size(); i++) {
            RuntimeBase value = result.elements.get(i);
            RuntimeBase replacement = value;

            if (value instanceof RuntimeScalarReadOnly ro && !(value instanceof ReadOnlyAlias)) {
                RuntimeScalar scalar = new RuntimeScalar();
                scalar.type = ro.type;
                scalar.value = ro.value;
                scalar.blessId = ro.blessId;
                replacement = scalar;
            }

            if (replacement != value && copy == null) {
                copy = new RuntimeList();
                copy.elements.addAll(result.elements.subList(0, i));
            }
            if (copy != null) {
                copy.elements.add(replacement);
            }
        }

        return copy != null ? copy : result;
    }

    /**
     * Keep the blessed invocant's referent alive for the duration of a method dispatch.
     * Nested {@code MortalList.flushAboveMark()} (e.g. from {@link RuntimeScalar#set})
     * can otherwise dequeue deferred decrements and run DESTROY on chain temporaries such as
     * {@code $db->query(...)->arrays} mid-callee — JDBC cursors appear truncated vs a lexical
     * holding the intermediate result (DBIx::Simple).
     */
    private static RuntimeBase acquireMethodInvocantHold(RuntimeScalar runtimeScalar) {
        RuntimeScalar v = runtimeScalar;
        while (v != null && v.type == RuntimeScalarType.READONLY_SCALAR) {
            v = (RuntimeScalar) v.value;
        }
        if (v == null || !RuntimeScalarType.isReference(v)) {
            return null;
        }
        if (!(v.value instanceof RuntimeBase base)) {
            return null;
        }
        if (base.blessId == 0) {
            return null;
        }
        if (base.refCount == Integer.MIN_VALUE || base.currentlyDestroying) {
            return null;
        }
        if (base.refCount < 0) {
            return null;
        }
        base.traceRefCount(+1, "RuntimeCode.method invocant hold (+1)");
        base.refCount++;
        return base;
    }

    private static void releaseMethodInvocantHold(RuntimeBase holdBase) {
        if (holdBase == null) {
            return;
        }
        holdBase.traceRefCount(-1, "RuntimeCode.method invocant hold release (-1)");
        if (holdBase.refCount > 0 && holdBase.refCount != Integer.MIN_VALUE && !holdBase.currentlyDestroying) {
            holdBase.refCount--;
        }
    }

    private static boolean isReferenceToGlobInvocant(RuntimeScalar invocant) {
        if (invocant.type != REFERENCE || !(invocant.value instanceof RuntimeScalar inner)) {
            return false;
        }
        while (inner.type == READONLY_SCALAR) {
            inner = (RuntimeScalar) inner.value;
        }
        return inner.type == GLOB || inner.type == GLOBREFERENCE;
    }

    public static boolean isLvalueCode(RuntimeCode code) {
        return code != null && code.attributes != null && code.attributes.contains("lvalue");
    }

    private static void restoreLazyAttributes(RuntimeCode code, java.util.List<String> savedAttributes) {
        if (savedAttributes == null || savedAttributes.isEmpty()) {
            return;
        }
        if (code.attributes == null) {
            code.attributes = new java.util.ArrayList<>(savedAttributes);
            return;
        }
        for (String attribute : savedAttributes) {
            if (!code.attributes.contains(attribute)) {
                code.attributes.add(attribute);
            }
        }
    }

    public static void requireLvalueCallable(RuntimeCode code, int callContext, String subroutineName) {
        if ((callContext != RuntimeContextType.LVALUE && callContext != RuntimeContextType.LVALUE_LIST)
                || isLvalueCode(code)
                || (code != null && code.isTryExpressionWrapper)) {
            return;
        }

        String displayName = subroutineName;
        if ((displayName == null || displayName.isEmpty()) && code != null
                && code.packageName != null && code.subName != null) {
            displayName = code.packageName + "::" + code.subName;
        }
        if (displayName == null || displayName.isEmpty()) {
            displayName = "__ANON__";
        }

        throw new PerlCompilerException(
                "Can't modify non-lvalue subroutine call of &" + displayName + " in scalar assignment");
    }

    private RuntimeList detachTryExpressionLvalueResult(RuntimeList result, int callContext) {
        if (!isTryExpressionWrapper
                || (callContext != RuntimeContextType.LVALUE
                && callContext != RuntimeContextType.LVALUE_LIST)
                || result == null
                || result instanceof RuntimeControlFlowList) {
            return result;
        }
        if (callContext == RuntimeContextType.LVALUE_LIST) {
            RuntimeList detached = new RuntimeList();
            for (RuntimeBase elem : result.elements) {
                RuntimeList values = elem.getList();
                for (RuntimeBase value : values.elements) {
                    detached.elements.add(value instanceof RuntimeScalar scalar ? scalar.clone() : value);
                }
            }
            return detached;
        }
        return result.cloneScalars();
    }
    
    public static void registerAnonymousSub(String className, Class<?> generatedClass) {
        PerlRuntime.current().runtimeCodeState().anonymousSubs.put(className, generatedClass);
    }

    public static void putInterpretedSub(String key, Object code) {
        PerlRuntime.current().runtimeCodeState().interpretedSubs.put(key, code);
    }

    public static Object getInterpretedSub(String key) {
        return PerlRuntime.current().runtimeCodeState().interpretedSubs.get(key);
    }

    public static void putEvalContext(String evalTag, EmitterContext context) {
        PerlRuntime.current().runtimeCodeState().evalContexts.put(evalTag, context);
    }

    private static EmitterContext getEvalContext(String evalTag) {
        return PerlRuntime.current().runtimeCodeState().evalContexts.get(evalTag);
    }

    public static void registerPadConstants(String className, RuntimeBase[] constants) {
        PerlRuntime.current().runtimeCodeState().padConstantsByClassName.put(className, constants);
    }
    // Method object representing the compiled subroutine (legacy - used by PerlModuleBase)
    public MethodHandle methodHandle;
    // Functional interface for direct subroutine invocation (preferred for generated classes)
    public PerlSubroutine subroutine;
    public boolean isStatic;
    public String autoloadVariableName = null;
    // Code object instance used during execution (legacy - used with methodHandle)
    public Object codeObject;
    // Prototype of the subroutine
    public String prototype;
    // Attributes associated with the subroutine
    public List<String> attributes = new ArrayList<>();
    public boolean isTryExpressionWrapper = false;
    // Method context information for next::method support
    public String packageName;
    public String subName;
    // Source package for imported forward declarations (used for AUTOLOAD resolution)
    public String sourcePackage = null;
    // Historical marker for symbolic references created by \&{string}. A CODE
    // scalar is defined as a scalar value even when its underlying subroutine is
    // only declared; RuntimeCode.defined() still reports whether the subroutine
    // itself has an implementation.
    public boolean isSymbolicReference = false;
    // Stash slot from which an explicit CODE reference such as `\&Pkg::name`
    // was taken. Stored on the CV so detached scalar snapshots retain it.
    public String referenceOriginFqn = null;
    /** Last symbol-table name found for anonymous-name recovery. */
    private volatile String registeredCodeNameCache = null;
    /** CODE-table version for the cached name, including a cached miss. */
    private volatile long registeredCodeNameCacheVersion = Long.MIN_VALUE;
    // An undefined named CV assigned into a different typeglob must remain a
    // live alias when the source CV is defined later. Module::Install uses
    // `*authors = \&author` before dynamically installing `author`.
    public boolean hasForwardGlobAlias = false;
    // Undefined CV placeholders can already be cached independently by call
    // sites for both glob names. Keep those placeholders in one alias group so
    // a later definition can populate every cached CV in place.
    public List<RuntimeCode> forwardGlobAliases = new ArrayList<>();
    // Set only when interpreter bytecode lowers `*target = \&source` to a live
    // source-slot load. JVM compilation resolves later named subs at compile
    // time and must retain its existing replacement behavior.
    public boolean requiresForwardGlobAliasGroup = false;
    // Flag to indicate this is a built-in operator
    public boolean isBuiltin = false;
    // Flag to indicate this was explicitly declared (sub foo; or sub foo { ... })
    // as opposed to auto-created by getGlobalCodeRef() for lookups.
    // In Perl 5, declared subs (even forward declarations) are visible via *{glob}{CODE}.
    public boolean isDeclared = false;
    // Flag to indicate this is a closure prototype (the template CV before cloning).
    // In Perl 5, MODIFY_CODE_ATTRIBUTES receives the closure prototype for closures.
    // Calling a closure prototype should die with "Closure prototype called".
    public boolean isClosurePrototype = false;
    // Anonymous CODE attributes are dispatched before backend compilation.
    // These flags carry built-in effects until the executable definition and
    // (for closures) captured environment are available.
    public boolean definitionPending = false;
    public boolean attributesDispatchedAtCompileTime = false;
    public boolean deferredConstAttribute = false;
    // Flag to indicate this code is a map/grep block - non-local return should propagate through it
    public boolean isMapGrepBlock = false;
    // Executable regex callbacks are Perl pseudo-blocks. They execute through
    // an implementation CV but must not add a caller() frame.
    public boolean isRegexCallbackPseudoBlock = false;
    // A callback compiled as part of qr// contributes Perl's anonymous-regex
    // frame to caller(), unlike an inline m// callback pseudo-block.
    public boolean isQuotedRegexCallback = false;
    // Implementation callbacks such as map/grep do not introduce a Perl
    // subroutine scope, so their __SUB__ comes from the enclosing RuntimeCode.
    public boolean inheritsSelfReference = false;
    // Flag to indicate this code is an eval BLOCK - non-local return should propagate through it
    public boolean isEvalBlock = false;
    // Flag to indicate this CV has been explicitly renamed via Sub::Name::subname
    // (or Sub::Util::set_subname). When set, B::svref_2object($cv)->GV->NAME should
    // return the assigned name even if the resulting fully-qualified name does not
    // correspond to an installed stash entry — matching real-Perl XS Sub::Name
    // behaviour, where the CV's CvGV points to a free-floating GV with the name.
    public boolean explicitlyRenamed = false;

    /**
     * Source location of the start of this CV's body (Perl {@code B::CV->START->line} /
     * COP). Used by the stub {@code B} module; {@code 0} means unknown.
     */
    public String cvStartFile;
    public int cvStartLine;
    public String deparseSourceText;
    public int deparseFlags;
    public int deparseSourceOffset = -1;
    /** Exclusive source offset for the compiler-recorded subroutine span. */
    public int deparseSourceEnd = -1;
    public static final int DEPARSE_FLAG_STRICT = 1;
    public static final int DEPARSE_FLAG_WARNINGS = 2;
    /**
     * True when the parser recognized this CV as a compile-time constant sub
     * (for example {@code sub foo () { 1 }}). {@link #constantValue} covers
     * constants installed through {@code use constant} and stash assignment.
     */
    public boolean isConstantCv;

    /**
     * When a coderef is installed with {@code *Package::name = $cr}, records the
     * stash slot FQN for method dispatch helpers without mutating
     * {@link #packageName}/{@link #subName}, which must stay anonymous for
     * {@code caller()} and {@code Sub::Util::subname()} unless the CV was renamed
     * via {@code Sub::Name}/{@code Sub::Util::set_subname}.
     */
    public String stashInstallPackage;
    public String stashInstallSub;
    /**
     * True once this CODE object has been installed in a package stash. After
     * the visible stash slot is deleted, weak refs to the CODE should not be
     * kept alive by stale internal owner records.
     */
    public boolean hadStashRef = false;
    /**
     * True when this CV was last installed with {@code *Pkg::name = $anonymous_cr}
     * (stash slot recorded, but not {@code Sub::Name}/{@code set_subname}).
     * Such subs must not use the fast {@code next::method} path.
     */
    public boolean installedViaAnonGlobAssign;

    // Depth of active recursive calls to this subroutine, used by the
    // "Deep recursion on subroutine" warning. Incremented on entry and
    // decremented in a finally-block on exit.
    // Depth threshold for the "Deep recursion on subroutine" warning.
    // Matches Perl's default PERL_SUB_DEPTH_WARN value.
    public static final int DEEP_RECURSION_WARN_DEPTH = 100;
    private static final int REGEX_CALLBACK_RECURSION_LIMIT = 1024;

    // When the tail-call trampoline in the static apply() re-enters a sub,
    // we want to skip the "Deep recursion" tracking for that entry.
    // The goto &sub caller's `no warnings 'recursion'` scope has already
    // unwound by the time the trampoline runs, so we can't honor it; and
    // tail calls don't consume real Java stack, so a depth warning for
    // them is misleading anyway. Nested tail-call trampolines use an int
    // counter so re-entries only skip tracking for the outermost trampoline.
    /**
     * Increment the recursion depth counter and, if we've just crossed the
     * "Deep recursion on subroutine" threshold for the first time in this
     * recursion chain, emit a warning under the "recursion" warnings category.
     * Must be matched with a call to exitCall() in a finally-block.
     *
     * Map/grep/eval blocks are exempt so that map { ... } and eval { ... }
     * don't report their dispatch wrapper. Tail-call trampoline re-entries
     * are also exempt — see inTailCallTrampoline.
     */
    private void enterCall() {
        if (isMapGrepBlock || isEvalBlock || isBuiltin) {
            return;
        }
        ExecutionRuntimeState executionState = PerlRuntime.current().executionState();
        if (executionState.tailCallTrampolineDepth > 0) {
            return;
        }
        ExecutionRuntimeState.CallDepthState callState =
                executionState.callDepth(this);
        int depth = ++callState.depth;
        if (isRegexCallbackPseudoBlock && depth > REGEX_CALLBACK_RECURSION_LIMIT) {
            // Joni callback recursion consumes Java stack outside the matcher's
            // own backtracking stack. Bound it independently of -Xss so a
            // recursive callback cannot monopolize the process until the JVM
            // eventually exhausts a large configured stack.
            callState.depth--;
            throw new StackOverflowError("Regex callback recursion limit exceeded");
        }
        if (depth > DEEP_RECURSION_WARN_DEPTH && !callState.warned) {
            callState.warned = true;
            String name = (packageName != null && subName != null)
                    ? packageName + "::" + subName
                    : (subName != null ? subName : "__ANON__");
            WarnDie.warnWithCategory(
                    new RuntimeScalar("Deep recursion on subroutine \"" + name + "\""),
                    RuntimeScalarCache.scalarEmptyString,
                    "recursion");
        }
    }

    /** Paired with enterCall() — decrements the recursion counter. */
    private void exitCall() {
        if (isMapGrepBlock || isEvalBlock || isBuiltin) {
            return;
        }
        ExecutionRuntimeState executionState = PerlRuntime.current().executionState();
        if (executionState.tailCallTrampolineDepth > 0) {
            return;
        }
        ExecutionRuntimeState.CallDepthState callState =
                executionState.callDepth(this);
        if (--callState.depth <= 0) {
            callState.depth = 0;
            callState.warned = false;
            executionState.releaseCallDepth(this);
        }
    }
    // State variables
    public Map<String, Boolean> stateVariableInitialized = new HashMap<>();
    public Map<String, RuntimeScalar> stateVariable = new HashMap<>();
    public Map<String, RuntimeArray> stateArray = new HashMap<>();
    public Map<String, RuntimeHash> stateHash = new HashMap<>();
    public RuntimeList constantValue;
    // Field to hold the thread compiling this code
    public Supplier<Void> compilerSupplier;
    // Self-reference for __SUB__ (set after construction for InterpretedCode)
    public RuntimeScalar __SUB__;

    /** Assign the enclosing Perl subroutine as an implementation callback's __SUB__. */
    public static void inheritSelfReference(RuntimeScalar callbackRef, RuntimeScalar enclosingRef) {
        if (callbackRef == null || !(callbackRef.value instanceof RuntimeCode code)) {
            return;
        }
        code.__SUB__ = enclosingRef;
        Object implementation = code.codeObject != null ? code.codeObject : code.subroutine;
        if (implementation != null) {
            try {
                Field field = implementation.getClass().getDeclaredField("__SUB__");
                field.set(implementation, enclosingRef);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to inherit __SUB__ for callback", e);
            }
        }
    }

    /** Restore this CODE value's own __SUB__ reference after an ithread clone. */
    void restoreClonedSelfReference(RuntimeScalar selfRef) {
        __SUB__ = selfRef;
        Object implementation = codeObject != null ? codeObject : subroutine;
        if (implementation == null || implementation instanceof org.perlonjava.backend.bytecode.InterpretedCode) {
            return;
        }
        try {
            Field field = implementation.getClass().getDeclaredField("__SUB__");
            field.set(implementation, selfRef);
        } catch (NoSuchFieldException ignored) {
            // Native/Java-backed implementations have no generated __SUB__ field.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to restore cloned __SUB__ reference", e);
        }
    }

    /**
     * Captured RuntimeScalar variables from the enclosing scope.
     * Set by {@link #makeCodeObject} for closures that capture lexical variables.
     * Used to properly track blessed object lifetimes across closure boundaries:
     * captured variables' blessed refs should not be destroyed at the inner scope
     * exit, but only when the closure itself is released.
     */
    public RuntimeScalar[] capturedScalars;

    /**
     * Captured aggregate lexicals (arrays/hashes). Their element cleanup is
     * deferred while the closure keeps the aggregate alive.
     */
    public RuntimeBase[] capturedAggregates;

    /** Live lexical containers keyed by their Perl pad names for PadWalker. */
    public Map<String, RuntimeBase> closedOverVariables;

    /** Lexicals declared by this CV, exposed by PadWalker::peek_sub. */
    public Set<String> lexicalVariableNames;

    /** Compile-time {@code our} aliases visible in this CV (name to package). */
    public Map<String, String> ourVariableRegistry;

    /**
     * Attach the lexical names declared directly by an anonymous subroutine.
     * Returning the original scalar keeps this convenient for JVM bytecode
     * emission, where the CODE reference is already on the operand stack.
     */
    public static RuntimeScalar attachLexicalVariableNames(
            RuntimeScalar codeRef, String[] variableNames) {
        if (codeRef != null && codeRef.value instanceof RuntimeCode code) {
            code.lexicalVariableNames = variableNames == null
                    ? null
                    : new java.util.LinkedHashSet<>(java.util.Arrays.asList(variableNames));
        }
        return codeRef;
    }

    /** Devel::LexAlias replacements applied when a lexical is instantiated. */
    public Map<String, RuntimeBase> lexicalAliases;

    public RuntimeBase resolveLexicalAlias(String variableName, RuntimeBase defaultValue) {
        RuntimeBase cell = defaultValue;
        if (lexicalAliases != null) {
            RuntimeBase replacement = lexicalAliases.get(variableName);
            if (replacement != null) cell = replacement;
        }
        registerActiveLexical(this, variableName, cell);
        return cell;
    }

    /** Refresh a live lexical binding after foreach replaces its alias cell. */
    public RuntimeBase bindActiveLexical(String variableName, RuntimeBase cell) {
        registerActiveLexical(this, variableName, cell);
        return cell;
    }

    public static RuntimeBase bindActiveLexical(
            RuntimeBase cell, RuntimeScalar codeRef, String variableName) {
        if (codeRef != null && codeRef.value instanceof RuntimeCode code) {
            return code.bindActiveLexical(variableName, cell);
        }
        RuntimeCode active = getActiveCodeAt(0);
        return active == null ? cell : active.bindActiveLexical(variableName, cell);
    }

    public static RuntimeBase resolveLexicalAlias(
            RuntimeBase defaultValue, RuntimeScalar codeRef, String variableName) {
        if (codeRef != null && codeRef.value instanceof RuntimeCode code) {
            return code.resolveLexicalAlias(variableName, defaultValue);
        }
        // Top-level code has no Perl-visible __SUB__, but it still owns a real
        // active lexical frame. Runtime regex source admitted by `use re eval`
        // must capture those live cells just like eval STRING does.
        RuntimeCode active = getActiveCodeAt(0);
        if (active != null) {
            return active.resolveLexicalAlias(variableName, defaultValue);
        }
        return defaultValue;
    }

    public void setLexicalAlias(String variableName, RuntimeBase replacement) {
        if (lexicalVariableNames == null || !lexicalVariableNames.contains(variableName)) {
            return;
        }
        if (lexicalAliases == null) lexicalAliases = new HashMap<>();
        lexicalAliases.put(variableName, replacement);
    }

    /**
     * Tracks the number of stash (glob) entries that reference this CODE object.
     * Stash entries created via {@code *Foo::bar = $coderef} are invisible to the
     * selective refCount because glob assignments go through a container that
     * may be overwritten independently.
     * <p>
     * When stashRefCount > 0, the CODE ref should NOT be considered dead even if
     * the selective refCount reaches 0, because the stash still holds a live
     * reference. This prevents premature {@code releaseCaptures()} which would
     * cascade to clear weak references (e.g., in Sub::Defer's %DEFERRED hash).
     */
    public int stashRefCount = 0;

    /**
     * Cached constants referenced via backslash (e.g., \"yay") inside this subroutine.
     * When the CODE slot of a glob is replaced, weak references to these constants
     * are cleared to emulate Perl 5's "optree reaping" behavior.
     */
    public RuntimeBase[] padConstants;

    /**
     * Registry mapping generated class names to their pad constants.
     * Used to transfer pad constants from compile time to runtime for anonymous subs.
     */
    /**
     * Clears weak references to this subroutine's pad constants.
     * Called when the CODE slot of a glob is replaced, emulating Perl 5's
     * behavior where replacing a sub frees its op-tree and clears weak refs
     * to compile-time constants.
     */
    public void clearPadConstantWeakRefs() {
        if (padConstants != null) {
            for (RuntimeBase constant : padConstants) {
                WeakRefRegistry.clearWeakRefsTo(constant);
            }
        }
    }

    /**
     * Return whether a readonly scalar is owned by the optree/pad of a
     * currently installed named subroutine.
     *
     * <p>Pad constants are JVM references that selective refcounting cannot
     * observe. This targeted lookup lets mortal processing preserve a weak
     * reference until the owning glob is replaced, when
     * {@link #clearPadConstantWeakRefs()} performs Perl-compatible optree
     * reaping.</p>
     */
    public static boolean isInstalledPadConstant(RuntimeBase target) {
        if (target == null) return false;
        for (RuntimeScalar codeScalar : GlobalVariable.globalCodeRefs.values()) {
            if (codeScalar == null || !(codeScalar.value instanceof RuntimeCode code)
                    || code.padConstants == null) {
                continue;
            }
            for (RuntimeBase constant : code.padConstants) {
                if (constant == target) return true;
            }
        }
        return false;
    }

    /**
     * Release captured variable references. Called when this closure is being
     * discarded (scope exit, undef, or reassignment of the variable holding
     * this CODE ref). Decrements {@code captureCount} on each captured scalar,
     * and if it reaches zero, defers the blessed ref decrement via MortalList.
     * <p>
     * Handles cascading: if a captured scalar itself holds a CODE ref with
     * captures, those are released recursively.
     */
    public void releaseCaptures() {
        if (capturedScalars != null) {
            RuntimeScalar[] scalars = capturedScalars;
            capturedScalars = null;  // null out first to prevent re-entry
            for (RuntimeScalar s : scalars) {
                s.releaseClosureCapture();
                if (s.captureCount == 0) {
                    // If the captured scalar itself holds an unowned CODE ref with
                    // captures, release those recursively (handles nested closures).
                    // Do not recurse into CODE refs still stored elsewhere (for
                    // example Iterator.pm keeps a callback in %code_for while an
                    // eval block temporarily captures the local $code scalar).
                    if (s.type == RuntimeScalarType.CODE
                            && s.value instanceof RuntimeCode innerCode
                            && innerCode.refCount <= 0) {
                        innerCode.releaseCaptures();
                    }
                    // The captured variable's scope has exited but refCount was NOT
                    // decremented at that time (scopeExitCleanup returns early for
                    // captured variables to prevent premature clearing while the
                    // closure is alive). Now that the last closure is releasing this
                    // capture, decrement refCount to balance the original increment.
                    //
                    // Balance the captured lexical's owned reference now that the
                    // last closure is gone. MortalList handles the weak-ref rescue
                    // cases for unblessed containers whose selective refCount can
                    // transiently reach zero while still reachable through live CODE.
                    if (s.scopeExited) {
                        if (s.type == RuntimeScalarType.TIED_SCALAR
                                && s.value instanceof TiedVariableBase tiedVariable) {
                            tiedVariable.releaseTiedObject();
                            MortalList.noteDeferredCaptureMayBeReady();
                            continue;
                        }
                        if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                                && s.value instanceof RuntimeBase rb
                                && (rb.blessId != 0 || !WeakRefRegistry.hasWeakRefsTo(rb))) {
                            MortalList.releaseCapturedDecrement(s);
                        }
                        MortalList.noteDeferredCaptureMayBeReady();
                    }
                }
            }
        }
        if (capturedAggregates != null) {
            RuntimeBase[] aggregates = capturedAggregates;
            capturedAggregates = null;
            for (RuntimeBase aggregate : aggregates) {
                if (aggregate != null) {
                    aggregate.releaseClosureCapture();
                }
            }
        }
    }

    /**
     * Constructs a RuntimeCode instance with the specified prototype and attributes.
     *
     * @param prototype  the prototype of the subroutine
     * @param attributes the attributes associated with the subroutine
     */
    public RuntimeCode(String prototype, List<String> attributes) {
        this.prototype = prototype;
        this.attributes = attributes;
    }

    public RuntimeCode(MethodHandle methodObject, Object codeObject, String prototype) {
        this.methodHandle = methodObject;
        this.codeObject = codeObject;
        this.prototype = prototype;
    }

    /**
     * Constructs a RuntimeCode instance with a PerlSubroutine functional interface.
     * This is the preferred constructor for generated Perl code.
     *
     * @param subroutine the functional interface implementation
     * @param prototype  the prototype of the subroutine
     */
    public RuntimeCode(PerlSubroutine subroutine, String prototype) {
        this.subroutine = subroutine;
        this.prototype = prototype;
    }

    private static void evalTrace(String msg) {
        if (EVAL_TRACE) {
            System.err.println("[eval-trace] " + msg);
        }
    }

    /**
     * Create a callable clone of this RuntimeCode for closure prototype support.
     * The original will be marked as a closure prototype (non-callable);
     * the clone is the actual closure that can be called.
     */
    public RuntimeCode cloneForClosure() {
        RuntimeCode clone;
        if (this.subroutine != null) {
            clone = new RuntimeCode(this.subroutine, this.prototype);
        } else {
            clone = new RuntimeCode(this.methodHandle, this.codeObject, this.prototype);
        }
        clone.attributes = this.attributes != null ? new java.util.ArrayList<>(this.attributes) : null;
        clone.packageName = this.packageName;
        clone.subName = this.subName;
        clone.stashInstallPackage = this.stashInstallPackage;
        clone.stashInstallSub = this.stashInstallSub;
        clone.installedViaAnonGlobAssign = this.installedViaAnonGlobAssign;
        clone.cvStartFile = this.cvStartFile;
        clone.cvStartLine = this.cvStartLine;
        clone.isRegexCallbackPseudoBlock = this.isRegexCallbackPseudoBlock;
        clone.isQuotedRegexCallback = this.isQuotedRegexCallback;
        clone.deparseSourceText = this.deparseSourceText;
        clone.deparseFlags = this.deparseFlags;
        clone.deparseSourceOffset = this.deparseSourceOffset;
        clone.deparseSourceEnd = this.deparseSourceEnd;
        clone.isConstantCv = this.isConstantCv;
        clone.isStatic = this.isStatic;
        clone.isDeclared = this.isDeclared;
        clone.constantValue = this.constantValue;
        clone.lexicalVariableNames = this.lexicalVariableNames == null
                ? null : new java.util.LinkedHashSet<>(this.lexicalVariableNames);
        clone.ourVariableRegistry = this.ourVariableRegistry == null
                ? null : new java.util.LinkedHashMap<>(this.ourVariableRegistry);
        clone.compilerSupplier = this.compilerSupplier;
        clone.attributesDispatchedAtCompileTime = this.attributesDispatchedAtCompileTime;
        clone.deferredConstAttribute = this.deferredConstAttribute;
        // isClosurePrototype stays false for the clone (it's callable)
        return clone;
    }

    /**
     * Called by CLI argument parser when --disassemble is set.
     */
    public static void setDisassemble(boolean value) {
        PerlRuntime.current().runtimeCodeState().disassemble = value;
    }

    public static boolean isDisassemble() {
        return PerlRuntime.current().runtimeCodeState().disassemble;
    }

    public static void setUseInterpreter(boolean value) {
        PerlRuntime.current().runtimeCodeState().useInterpreter = value;
    }

    public static boolean isUseInterpreter() {
        return PerlRuntime.current().runtimeCodeState().useInterpreter;
    }

    /**
     * Returns the fully-qualified name of the $AUTOLOAD variable that should
     * receive the name of the method being autoloaded.
     * <p>
     * Real Perl sets $AUTOLOAD in the package where the AUTOLOAD sub was
     * <em>compiled</em> (CvSTASH), not in the package whose glob referenced
     * it. This matters when the AUTOLOAD is aliased into a child class via
     * {@code *Child::AUTOLOAD = \&Parent::AUTOLOAD} — Perl sets
     * {@code $Parent::AUTOLOAD}, not {@code $Child::AUTOLOAD}.
     * <p>
     * Falls back to {@code lookupPackage} (the package used to find the CV)
     * when the CV has no recorded compile-time package, which preserves the
     * old behaviour for anonymous/stub cases.
     *
     * @param autoloadCoderef the AUTOLOAD coderef that was located
     * @param lookupPackage   the package name used to look it up
     *                        (e.g. "{@code Child}")
     * @return fully-qualified name of the dynamic $AUTOLOAD variable
     */
    private static String autoloadVarFor(RuntimeScalar autoloadCoderef, String lookupPackage) {
        if (autoloadCoderef != null && autoloadCoderef.value instanceof RuntimeCode rc
                && rc.packageName != null && !rc.packageName.isEmpty()) {
            return rc.packageName + "::AUTOLOAD";
        }
        return lookupPackage + "::AUTOLOAD";
    }

    /**
     * Preserve the byte/Unicode flag of a dynamically named method in
     * $AUTOLOAD. Perl treats that flag as part of string regex semantics.
     */
    private static void setAutoloadMethodName(
            String variableName, String fullMethodName, RuntimeScalar methodName) {
        RuntimeScalar value = new RuntimeScalar(fullMethodName).propagateTaint(methodName);
        if (methodName.type == RuntimeScalarType.BYTE_STRING) {
            value.type = RuntimeScalarType.BYTE_STRING;
        }
        getGlobalVariable(variableName).set(value);
    }

    private static String qualifyAutoloadMethodName(String methodName, String perlClassName) {
        return methodName.contains("::")
                ? methodName
                : perlClassName + "::" + methodName;
    }

    public static boolean isCodeDefined(RuntimeScalar codeRef) {
        return codeRef != null
                && codeRef.type == RuntimeScalarType.CODE
                && codeRef.value instanceof RuntimeCode code
                && code.defined();
    }

    private static RuntimeScalar resolveDirectCallTarget(RuntimeScalar runtimeScalar, String subroutineName) {
        String lookupName = subroutineName;
        if ((lookupName == null || lookupName.isEmpty() || "tailcall".equals(lookupName))
                && runtimeScalar != null
                && runtimeScalar.globalCodeRefFqn != null) {
            lookupName = runtimeScalar.globalCodeRefFqn;
        }
        return GlobalVariable.getLocalizedCodeRefForDirectCall(lookupName, runtimeScalar);
    }

    private static String knownUndefinedSubroutineName(RuntimeScalar runtimeScalar, String subroutineName) {
        if (runtimeScalar != null
                && runtimeScalar.globalCodeRefFqn != null
                && !runtimeScalar.globalCodeRefFqn.isEmpty()) {
            return runtimeScalar.globalCodeRefFqn;
        }
        if (subroutineName != null && !subroutineName.isEmpty() && !"tailcall".equals(subroutineName)) {
            return subroutineName;
        }
        return null;
    }

    private static RuntimeList undefCodeRefResultOrThrow(RuntimeScalar runtimeScalar, String subroutineName, int callContext) {
        String fullSubName = knownUndefinedSubroutineName(runtimeScalar, subroutineName);
        if (fullSubName != null) {
            throw new PerlCompilerException(gotoErrorPrefix(subroutineName)
                    + "ndefined subroutine &" + fullSubName + " called");
        }
        if (RuntimeContextType.isListLike(callContext)) {
            return new RuntimeList();
        }
        return new RuntimeList(new RuntimeScalar());
    }

    private static RuntimeScalar resolveLateDefinedForwardCodeRef(RuntimeScalar current, RuntimeCode code) {
        if (code == null
                || code.defined()
                || code.packageName == null
                || code.subName == null
                || code.subName.isEmpty()) {
            return null;
        }

        RuntimeScalar resolved = GlobalVariable.globalCodeRefs.get(code.packageName + "::" + code.subName);
        if (resolved == null
                || resolved.type != RuntimeScalarType.CODE
                || !(resolved.value instanceof RuntimeCode resolvedCode)
                || resolvedCode == code
                || !resolvedCode.defined()) {
            return null;
        }
        return resolved;
    }

    /**
     * Preflight for generated direct subroutine calls. Perl reports an
     * undefined direct call at the line containing the function token, but
     * caller() inside a successfully entered multiline call sees the closing
     * call-site line. The emitter calls this while the bytecode line is still
     * the function-token line, then moves the real apply() instruction to the
     * call-site line.
     */
    public static void throwIfDirectCallUndefined(RuntimeScalar runtimeScalar, String subroutineName) {
        RuntimeScalar curScalar = resolveDirectCallTarget(runtimeScalar, subroutineName);

        while (curScalar != null) {
            if (curScalar.type == RuntimeScalarType.TIED_SCALAR) {
                curScalar = curScalar.tiedFetch();
                continue;
            }
            if (curScalar.type == READONLY_SCALAR) {
                curScalar = (RuntimeScalar) curScalar.value;
                continue;
            }
            if (curScalar.type == RuntimeScalarType.UNDEF) {
                String fullSubName = knownUndefinedSubroutineName(curScalar, subroutineName);
                if (fullSubName != null) {
                    throw new PerlCompilerException(gotoErrorPrefix(subroutineName)
                            + "ndefined subroutine &" + fullSubName + " called");
                }
                return;
            }

            if (curScalar.type == RuntimeScalarType.CODE) {
                RuntimeCode code = (RuntimeCode) curScalar.value;

                if (code.isClosurePrototype) {
                    return;
                }

                if (code.compilerSupplier != null) {
                    RuntimeList savedConstantValue = code.constantValue;
                    java.util.List<String> savedAttributes = code.attributes;
                    code.compilerSupplier.get();
                    code = (RuntimeCode) curScalar.value;
                    if (savedConstantValue != null && code.constantValue == null) {
                        code.constantValue = savedConstantValue;
                    }
                    restoreLazyAttributes(code, savedAttributes);
                }

                if (!code.defined() && "CORE".equals(code.packageName) && code.subName != null) {
                    if (CoreSubroutineGenerator.generateWrapper(code.subName)) {
                        curScalar = GlobalVariable.getGlobalCodeRef("CORE::" + code.subName);
                        if (curScalar.type == RuntimeScalarType.CODE) {
                            code = (RuntimeCode) curScalar.value;
                        }
                    }
                }

                RuntimeScalar lateResolved = resolveLateDefinedForwardCodeRef(curScalar, code);
                if (lateResolved != null) {
                    curScalar = lateResolved;
                    code = (RuntimeCode) curScalar.value;
                }

                if (code.defined()) {
                    return;
                }

                String fullSubName = subroutineName;
                if ((fullSubName == null || fullSubName.isEmpty()) && code.packageName != null && code.subName != null) {
                    fullSubName = code.packageName + "::" + code.subName;
                }

                if (fullSubName != null && !fullSubName.isEmpty()) {
                    RuntimeScalar importedStubAutoload = findImportedStubAutoload(code, fullSubName);
                    if (importedStubAutoload != null) {
                        return;
                    }

                    if (code.sourcePackage != null && !code.sourcePackage.isEmpty()) {
                        String sourceAutoloadString = code.sourcePackage + "::AUTOLOAD";
                        RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                        if (isCodeDefined(sourceAutoload)) {
                            return;
                        }
                    }

                    int sep = fullSubName.lastIndexOf("::");
                    if (sep >= 0) {
                        String autoloadString = fullSubName.substring(0, sep + 2) + "AUTOLOAD";
                        RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                        if (isCodeDefined(autoload)) {
                            return;
                        }
                    }

                    throw new PerlCompilerException(gotoErrorPrefix(subroutineName)
                            + "ndefined subroutine &" + fullSubName + " called");
                }
                return;
            }

            if (curScalar.type == RuntimeScalarType.GLOB) {
                RuntimeGlob glob = (RuntimeGlob) curScalar.value;
                if (glob.globName != null) {
                    curScalar = GlobalVariable.getGlobalCodeRef(glob.globName);
                    continue;
                }
                if (glob.codeSlot != null) {
                    curScalar = glob.codeSlot;
                    continue;
                }
                return;
            }

            if ((curScalar.type == RuntimeScalarType.REFERENCE || curScalar.type == RuntimeScalarType.GLOBREFERENCE)
                    && curScalar.value instanceof RuntimeGlob glob) {
                if (glob.globName != null) {
                    curScalar = GlobalVariable.getGlobalCodeRef(glob.globName);
                    continue;
                }
                if (glob.codeSlot != null) {
                    curScalar = glob.codeSlot;
                    continue;
                }
                return;
            }

            if (curScalar.type == STRING || curScalar.type == BYTE_STRING) {
                String varName = NameNormalizer.normalizeVariableName(curScalar.toString(), "main");
                curScalar = GlobalVariable.getGlobalCodeRef(varName);
                continue;
            }

            RuntimeScalar overloadedCode = handleCodeOverload(curScalar);
            if (overloadedCode != null) {
                curScalar = overloadedCode;
                continue;
            }

            return;
        }
    }

    private static RuntimeScalar findImportedStubAutoload(RuntimeCode code, String fullSubName) {
        if (code.packageName == null || code.packageName.isEmpty()
                || fullSubName == null || fullSubName.isEmpty()) {
            return null;
        }
        int sep = fullSubName.lastIndexOf("::");
        if (sep < 0) {
            return null;
        }
        String lookupPackage = fullSubName.substring(0, sep);
        if (code.packageName.equals(lookupPackage)) {
            return null;
        }

        String shortName = fullSubName.substring(sep + 2);
        String sourceSubName = (code.subName != null
                && !code.subName.isEmpty()
                && !"__ANON__".equals(code.subName))
                ? code.subName
                : shortName;
        String sourceAutoloadString = code.packageName + "::AUTOLOAD";
        RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
        if (!isCodeDefined(sourceAutoload)) {
            return null;
        }

        getGlobalVariable(autoloadVarFor(sourceAutoload, code.packageName))
                .set(code.packageName + "::" + sourceSubName);
        return sourceAutoload;
    }

    /**
     * Check if AUTOLOAD exists for a given RuntimeCode's package.
     * Checks source package first (for imported subs), then current package.
     *
     * @param code The RuntimeCode to check AUTOLOAD for
     * @return true if AUTOLOAD exists and is defined
     */
    public static boolean hasAutoload(RuntimeCode code) {
        if (code.packageName == null) {
            return false;
        }
        // Check source package AUTOLOAD first (for imported subs)
        if (code.sourcePackage != null && !code.sourcePackage.equals(code.packageName)) {
            String sourceAutoloadString = code.sourcePackage + "::AUTOLOAD";
            RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
            if (isCodeDefined(sourceAutoload)) {
                return true;
            }
        }
        // Then check current package AUTOLOAD
        String autoloadString = code.packageName + "::AUTOLOAD";
        RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
        return isCodeDefined(autoload);
    }


    /**
     * Get the current eval runtime context for accessing variable runtime values during parsing.
     * This is called by SpecialBlockParser when setting up BEGIN blocks.
     *
     * @return The current eval runtime context, or null if not in eval STRING compilation
     */
    public static EvalRuntimeContext getEvalRuntimeContext() {
        ArrayDeque<EvalRuntimeContext> stack = evalRuntimeContextStack();
        return stack.isEmpty() ? null : stack.peekFirst();
    }

    /**
     * Save and clear the eval runtime context.
     * Used by require/do to prevent inner compilations from seeing the eval's captured variables.
     * The returned value should be passed to {@link #restoreEvalRuntimeContext} to restore it.
     *
     * @return The saved eval runtime context (may be null)
     */
    public static EvalRuntimeContext saveAndClearEvalRuntimeContext() {
        ArrayDeque<EvalRuntimeContext> stack = evalRuntimeContextStack();
        if (stack.isEmpty()) {
            return null;
        }
        EvalRuntimeContext saved = stack.removeFirst();
        if (stack.isEmpty()) {
            evalRuntimeContextStack().clear();
        }
        return saved;
    }

    public static EvalRuntimeContext saveAndClearEvalRuntimeContextAndAliases() {
        EvalRuntimeContext saved = saveAndClearEvalRuntimeContext();
        if (saved != null) {
            deactivateEvalRuntimeAliases(saved);
        }
        return saved;
    }

    /**
     * Restore a previously saved eval runtime context.
     *
     * @param saved The context returned by {@link #saveAndClearEvalRuntimeContext}
     */
    public static void restoreEvalRuntimeContext(EvalRuntimeContext saved) {
        if (saved != null) {
            evalRuntimeContextStack().addFirst(saved);
            reactivateEvalRuntimeAliases(saved);
        }
    }

    private static void pushEvalRuntimeContext(EvalRuntimeContext context) {
        evalRuntimeContextStack().addFirst(context);
    }

    private static void popEvalRuntimeContext(EvalRuntimeContext context) {
        ArrayDeque<EvalRuntimeContext> stack = evalRuntimeContextStack();
        if (!stack.isEmpty()) {
            if (stack.peekFirst() == context) {
                stack.removeFirst();
            } else {
                for (Iterator<EvalRuntimeContext> it = stack.iterator(); it.hasNext(); ) {
                    if (it.next() == context) {
                        it.remove();
                        break;
                    }
                }
            }
        }
        if (stack.isEmpty()) {
            evalRuntimeContextStack().clear();
        }
    }

    private static void pushSyntheticEvalCallerFrame(String packageName, String filename, int line) {
        pushSyntheticCallerFrame(packageName, filename, line, "(eval)", "virtual-eval");
    }

    public static void pushSyntheticCallerFrame(String packageName, String filename, int line, String subName) {
        pushSyntheticCallerFrame(packageName, filename, line, subName, "synthetic-own-sub");
    }

    private static void pushSyntheticCallerFrame(String packageName, String filename, int line, String subName, String marker) {
        ArrayList<String> frame = new ArrayList<>();
        frame.add(packageName != null && !packageName.isEmpty() ? packageName : "main");
        frame.add(filename != null ? filename : "(eval)");
        frame.add(String.valueOf(line));
        frame.add(subName);
        frame.add(marker);
        syntheticCallerFrames().addLast(frame);
    }

    public static void popSyntheticCallerFrame() {
        ArrayDeque<ArrayList<String>> stack = syntheticCallerFrames();
        if (!stack.isEmpty()) {
            stack.removeLast();
        }
        if (stack.isEmpty()) {
            syntheticCallerFrames().clear();
        }
    }

    private static void popSyntheticEvalCallerFrame() {
        popSyntheticCallerFrame();
    }

    private static void registerEvalRuntimeAlias(EvalRuntimeContext context, char sigil, String fullName, RuntimeBase value) {
        context.aliases().add(new EvalRuntimeAlias(sigil, fullName, value));
    }

    private static void deactivateEvalRuntimeAliases(EvalRuntimeContext context) {
        for (EvalRuntimeAlias alias : context.aliases()) {
            if (!alias.active) {
                continue;
            }
            switch (alias.sigil) {
                case '$' -> {
                    if (GlobalVariable.globalVariables.get(alias.fullName) == alias.value) {
                        GlobalVariable.globalVariables.remove(alias.fullName);
                    }
                }
                case '@' -> {
                    if (GlobalVariable.globalArrays.get(alias.fullName) == alias.value) {
                        GlobalVariable.globalArrays.remove(alias.fullName);
                    }
                }
                case '%' -> {
                    if (GlobalVariable.globalHashes.get(alias.fullName) == alias.value) {
                        GlobalVariable.globalHashes.remove(alias.fullName);
                    }
                }
            }
            alias.active = false;
        }
    }

    private static void reactivateEvalRuntimeAliases(EvalRuntimeContext context) {
        for (EvalRuntimeAlias alias : context.aliases()) {
            if (alias.active) {
                continue;
            }
            boolean installed = switch (alias.sigil) {
                case '$' -> alias.value instanceof RuntimeScalar scalar
                        && GlobalVariable.globalVariables.putIfAbsent(alias.fullName, scalar) == null;
                case '@' -> alias.value instanceof RuntimeArray array
                        && GlobalVariable.globalArrays.putIfAbsent(alias.fullName, array) == null;
                case '%' -> alias.value instanceof RuntimeHash hash
                        && GlobalVariable.globalHashes.putIfAbsent(alias.fullName, hash) == null;
                default -> false;
            };
            alias.active = installed;
        }
    }

    /**
     * Gets the next eval sequence number and generates a filename.
     * Used by both baseline compiler and interpreter for consistent naming.
     *
     * @return Filename like "(eval 1)", "(eval 2)", etc.
     */
    public static String getNextEvalFilename() {
        return PerlRuntime.current().runtimeCodeState().nextEvalFilename();
    }

    // Add a method to clear caches when globals are reset
    public static void clearCaches() {
        PerlRuntime runtime = PerlRuntime.current();
        runtime.runtimeCodeState().clearCaches();
        runtime.executionState().evalRuntimeContexts.clear();
    }

    public static void copy(RuntimeCode code, RuntimeCode codeFrom) {
        code.prototype = codeFrom.prototype;
        code.attributes = codeFrom.attributes;
        code.methodHandle = codeFrom.methodHandle;
        code.subroutine = codeFrom.subroutine;
        code.isStatic = codeFrom.isStatic;
        code.codeObject = codeFrom.codeObject;
        code.cvStartFile = codeFrom.cvStartFile;
        code.cvStartLine = codeFrom.cvStartLine;
        code.deparseSourceText = codeFrom.deparseSourceText;
        code.deparseFlags = codeFrom.deparseFlags;
        code.deparseSourceOffset = codeFrom.deparseSourceOffset;
        code.deparseSourceEnd = codeFrom.deparseSourceEnd;
    }

    public void adoptDefinitionFrom(RuntimeCode codeFrom) {
        Supplier<Void> sourceCompilerSupplier = codeFrom.compilerSupplier;
        boolean sourceNeedsDelegation = (codeFrom instanceof InterpretedCode && this.hasForwardGlobAlias)
                || (sourceCompilerSupplier != null
                && codeFrom.constantValue == null
                && codeFrom.subroutine == null
                && codeFrom.methodHandle == null
                && codeFrom.codeObject == null);

        this.methodHandle = codeFrom.methodHandle;
        this.subroutine = codeFrom.subroutine;
        this.isStatic = codeFrom.isStatic;
        this.autoloadVariableName = codeFrom.autoloadVariableName;
        this.codeObject = codeFrom.codeObject;
        this.prototype = codeFrom.prototype;
        this.attributes = codeFrom.attributes != null ? new ArrayList<>(codeFrom.attributes) : null;
        this.packageName = codeFrom.packageName;
        this.subName = codeFrom.subName;
        this.sourcePackage = codeFrom.sourcePackage;
        this.isSymbolicReference = codeFrom.isSymbolicReference;
        this.isBuiltin = codeFrom.isBuiltin;
        this.isDeclared = codeFrom.isDeclared;
        this.isClosurePrototype = codeFrom.isClosurePrototype;
        this.definitionPending = codeFrom.definitionPending;
        this.attributesDispatchedAtCompileTime = codeFrom.attributesDispatchedAtCompileTime;
        this.deferredConstAttribute = codeFrom.deferredConstAttribute;
        this.isMapGrepBlock = codeFrom.isMapGrepBlock;
        this.isRegexCallbackPseudoBlock = codeFrom.isRegexCallbackPseudoBlock;
        this.isQuotedRegexCallback = codeFrom.isQuotedRegexCallback;
        this.inheritsSelfReference = codeFrom.inheritsSelfReference;
        this.isEvalBlock = codeFrom.isEvalBlock;
        this.explicitlyRenamed = codeFrom.explicitlyRenamed;
        this.cvStartFile = codeFrom.cvStartFile;
        this.cvStartLine = codeFrom.cvStartLine;
        this.deparseSourceText = codeFrom.deparseSourceText;
        this.deparseFlags = codeFrom.deparseFlags;
        this.deparseSourceOffset = codeFrom.deparseSourceOffset;
        this.deparseSourceEnd = codeFrom.deparseSourceEnd;
        this.isConstantCv = codeFrom.isConstantCv;
        this.stashInstallPackage = codeFrom.stashInstallPackage;
        this.stashInstallSub = codeFrom.stashInstallSub;
        this.hadStashRef = codeFrom.hadStashRef;
        this.installedViaAnonGlobAssign = codeFrom.installedViaAnonGlobAssign;
        this.stateVariableInitialized = codeFrom.stateVariableInitialized;
        this.stateVariable = codeFrom.stateVariable;
        this.stateArray = codeFrom.stateArray;
        this.stateHash = codeFrom.stateHash;
        this.constantValue = codeFrom.constantValue;
        if (sourceNeedsDelegation) {
            this.subroutine = (runtimeArgs, callContext) ->
                    RuntimeCode.apply(new RuntimeScalar(codeFrom), runtimeArgs, callContext);
            this.codeObject = codeFrom;
            this.compilerSupplier = null;
        } else {
            this.compilerSupplier = codeFrom.compilerSupplier;
        }
        this.__SUB__ = codeFrom.__SUB__;
        this.capturedScalars = codeFrom.capturedScalars;
        this.capturedAggregates = codeFrom.capturedAggregates;
        this.closedOverVariables = codeFrom.closedOverVariables;
        this.lexicalVariableNames = codeFrom.lexicalVariableNames;
        this.ourVariableRegistry = codeFrom.ourVariableRegistry;
        this.padConstants = codeFrom.padConstants;
    }

    /**
     * Backwards-compatible overload for code compiled before runtimeValues parameter was added.
     * This allows pre-compiled Perl modules to continue working with the new signature.
     *
     * @param code    the RuntimeScalar containing the eval string
     * @param evalTag the tag used to retrieve the eval context
     * @return the compiled Class representing the anonymous subroutine
     * @throws Exception if an error occurs during compilation
     */
    public static Class<?> evalStringHelper(RuntimeScalar code, String evalTag) throws Exception {
        return evalStringHelper(code, evalTag, new Object[0]);
    }

    public static boolean shouldDecodeEvalbytesUtf8Source(String source) {
        if (source == null) {
            return false;
        }
        int pos = source.indexOf("use utf8");
        while (pos >= 0) {
            boolean startsAtWordBoundary = pos == 0 || !Character.isJavaIdentifierPart(source.charAt(pos - 1));
            int after = pos + "use utf8".length();
            boolean endsAtWordBoundary = after >= source.length()
                    || Character.isWhitespace(source.charAt(after))
                    || source.charAt(after) == ';';
            if (startsAtWordBoundary && endsAtWordBoundary) {
                for (int i = 0; i < pos; i++) {
                    if (source.charAt(i) > 127) {
                        return false;
                    }
                }
                return true;
            }
            pos = source.indexOf("use utf8", pos + 1);
        }
        return false;
    }

    public static String decodeEvalbytesUtf8Source(String source) {
        byte[] bytes = new byte[source.length()];
        for (int i = 0; i < source.length(); i++) {
            bytes[i] = (byte) (source.charAt(i) & 0xFF);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("Malformed UTF-8 character (fatal)", e);
        }
    }

    /**
     * Compiles the text of an eval string into a Class that represents an anonymous subroutine.
     * After the Class is returned to the caller, an instance of the Class will be populated
     * with closure variables, and then makeCodeObject() will be called to transform the Class
     * instance into a Perl CODE object.
     * <p>
     * IMPORTANT CHANGE: This method now accepts runtime values of captured variables.
     * <p>
     * WHY THIS IS NEEDED:
     * In perl5, BEGIN blocks inside eval STRING can access outer lexical variables' runtime values.
     * For example:
     * my @imports = qw(md5 md5_hex);
     * eval q{ use Digest::MD5 @imports };  # BEGIN block sees @imports = (md5 md5_hex)
     * <p>
     * Previously in PerlOnJava, BEGIN blocks would see empty variables because they execute
     * during parsing, before the eval class is instantiated with runtime values.
     * <p>
     * NOW: We pass runtime values to this method and store them in ThreadLocal storage.
     * SpecialBlockParser can then access these values when setting up BEGIN blocks,
     * allowing lexical variables to be initialized with their runtime values.
     *
     * @param code          the RuntimeScalar containing the eval string
     * @param evalTag       the tag used to retrieve the eval context
     * @param runtimeValues the runtime values of captured variables (Object[] matching capturedEnv order)
     * @return the compiled Class representing the anonymous subroutine
     * @throws Exception if an error occurs during compilation
     */
    public static Class<?> evalStringHelper(RuntimeScalar code, String evalTag, Object[] runtimeValues) throws Exception {

        try (PerlRuntime.Binding runtimeBinding = PerlRuntime.current().bind();
             PerlLanguageProvider.CompilationLockGuard ignored =
                     PerlLanguageProvider.acquireCompilationLock()) {

        rejectTaintedEval(code);

        // Retrieve the eval context that was saved at program compile-time
        EmitterContext ctx = getEvalContext(evalTag);

        // Handle missing eval context - this can happen when compiled code (e.g., INIT blocks
        // with eval) is executed after the runtime has been reset. In JUnit parallel tests,
        // PerlLanguageProvider.resetAll() clears evalContext between tests, but compiled
        // bytecode (which references specific evalTags) survives and may be re-executed.
        if (ctx == null) {
            throw new RuntimeException(
                    "Eval context not found for tag: " + evalTag +
                    ". This can happen when eval is called from code that was compiled " +
                    "in a previous session (e.g., INIT blocks in cached modules). " +
                    "The module may need to be reloaded. " +
                    "If this occurs in tests, ensure module caches are cleared along with eval contexts.");
        }

        // Save the current scope so we can restore it after eval compilation.
        // This is critical because eval may be called from code compiled with different
        // warning/feature flags than the caller, and we must not leak the eval's scope.
        ScopedSymbolTable savedCurrentScope = getCurrentScope();
        String savedRuntimeWarningBits = WarningBitsRegistry.getRuntimeWarningBits();

        // Store runtime values in ThreadLocal so SpecialBlockParser can access them during parsing.
        // This enables BEGIN blocks to see outer lexical variables' runtime values.
        //
        // CRITICAL: The runtimeValues array matches capturedEnv order (both skip first 3 variables).
        // SpecialBlockParser will use getRuntimeValue() to look up values by variable name.
        //
        // Example: If @imports is at capturedEnv[5], its runtime value is at runtimeValues[5-3=2]
        //          (because both arrays skip 'this', '@_', and 'wantarray')
        EvalRuntimeContext runtimeCtx = new EvalRuntimeContext(
                runtimeValues,
                ctx.capturedEnv,  // Variable names in same order as runtimeValues
                evalTag
        );
        pushEvalRuntimeContext(runtimeCtx);

        try {
            // Check if the eval string contains non-ASCII characters
            // If so, treat it as Unicode source to preserve Unicode characters during parsing
            // EXCEPT for evalbytes, which must treat everything as bytes
            String evalString = code.toString();
            boolean evalbytesUtf8Source = ctx.isEvalbytes && shouldDecodeEvalbytesUtf8Source(evalString);
            boolean byteStringUtf8Source = !ctx.isEvalbytes
                    && code.type == RuntimeScalarType.BYTE_STRING
                    && (ctx.symbolTable.strictOptionsStack.peek() & Strict.HINT_UTF8) != 0;
            if (evalbytesUtf8Source || byteStringUtf8Source) {
                evalString = decodeEvalbytesUtf8Source(evalString);
            }
            boolean hasUnicode = false;
            if (evalbytesUtf8Source || byteStringUtf8Source) {
                hasUnicode = true;
            } else if (!ctx.isEvalbytes && code.type != RuntimeScalarType.BYTE_STRING) {
                for (int i = 0; i < evalString.length(); i++) {
                    if (evalString.charAt(i) > 127) {
                        hasUnicode = true;
                        break;
                    }
                }
            }

            // Clone compiler options and set isUnicodeSource if needed
            // This only affects string parsing, not symbol table or method resolution
            // Always clone to avoid modifying the original and to set a unique filename
            CompilerOptions evalCompilerOptions = ctx.compilerOptions.clone();
            // The eval string can originate from either a Perl STRING or BYTE_STRING scalar.
            // For BYTE_STRING source we must treat the source as raw bytes (latin-1-ish) and
            // NOT re-encode characters to UTF-8 when simulating 'non-unicode source'.
            boolean isByteStringSource = !ctx.isEvalbytes
                    && code.type == RuntimeScalarType.BYTE_STRING
                    && !byteStringUtf8Source;
            if (hasUnicode) {
                evalCompilerOptions.isUnicodeSource = true;
            }
            if (ctx.isEvalbytes && !evalbytesUtf8Source) {
                evalCompilerOptions.isEvalbytes = true;
            }
            if (isByteStringSource) {
                evalCompilerOptions.isUnicodeSource = false;
                evalCompilerOptions.isByteStringSource = true;
            }

            // Check $^P to determine if we should use caching
            // Give each eval a unique filename for correct source location tracking.
            // Previously, all evals from the same source file shared the caller's filename,
            // which caused source location info collisions when multiple evals had the same
            // tokenIndex (since each eval's tokenization starts from 0).
            // When debugging is enabled ($^P is set), we skip the cache entirely.
            int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
            boolean isDebugging = debugFlags != 0;

            // Always generate a unique filename for each eval to prevent source location collisions
            String actualFileName = getNextEvalFilename();
            evalCompilerOptions.fileName = actualFileName;

            // Check if the result is already cached (include hasUnicode, isEvalbytes, byte-string-source, feature flags, and package in cache key)
            // Skip caching when $^P is set, so each eval gets a unique filename
            // Include package name in cache key to ensure source location info is correct per-package
            int featureFlags = ctx.symbolTable.featureFlagsStack.peek();
            String currentPackage = ctx.symbolTable.getCurrentPackage();
            String cacheKey = evalString + '\0' + evalTag + '\0' + hasUnicode + '\0' + ctx.isEvalbytes + '\0' + evalbytesUtf8Source + '\0' + byteStringUtf8Source + '\0' + isByteStringSource + '\0' + featureFlags + '\0' + currentPackage;
            Class<?> cachedClass = null;
            if (!isDebugging) {
                Map<String, Class<?>> runtimeEvalCache = evalCache();
                synchronized (runtimeEvalCache) {
                    if (runtimeEvalCache.containsKey(cacheKey)) {
                        cachedClass = runtimeEvalCache.get(cacheKey);
                    }
                }

                if (cachedClass != null) {
                    return cachedClass;
                }
            }

            // IMPORTANT: The eval call site (EmitEval) computes the constructor signature from
            // ctx.symbolTable (captured at compile-time). We must use that exact symbol table for
            // codegen, otherwise the generated <init>(...) descriptor may not match what the
            // call site is looking up via reflection.
            ScopedSymbolTable capturedSymbolTable = ctx.symbolTable;

            // %^H is the compile-time hints hash. eval STRING must not leak modifications to the
            // caller scope, so snapshot and restore it across compilation.
            RuntimeHash capturedHintHash = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
            Map<String, RuntimeScalar> savedHintHash = new HashMap<>(capturedHintHash.elements);

            // Restore %^H from the call site's compile-time snapshot for eval STRING.
            // At runtime, %^H is normally empty. But eval STRING needs to inherit the
            // compile-time %^H that was active when the eval statement was compiled,
            // so that pragmas like 'use mypragma' are visible inside eval.
            java.util.Map<String, String> callSiteHints = HintHashRegistry.getCurrentCallSiteHintHash();
            if (callSiteHints != null) {
                for (java.util.Map.Entry<String, String> entry : callSiteHints.entrySet()) {
                    capturedHintHash.elements.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
                }
            }

            // eval may include lexical pragmas (use strict/warnings/features). We need those flags
            // during codegen of the eval body, but they must NOT leak back into the caller scope.
            BitSet savedWarningFlags = (BitSet) capturedSymbolTable.warningFlagsStack.peek().clone();
            int savedFeatureFlags = capturedSymbolTable.featureFlagsStack.peek();
            int savedStrictOptions = capturedSymbolTable.strictOptionsStack.peek();

            // Parse using a mutable clone so lexical declarations inside the eval do not
            // change the captured environment / constructor signature.
            // IMPORTANT: The parseSymbolTable starts with the captured flags so that
            // the eval code is parsed with the correct feature/strict/warning context
            ScopedSymbolTable parseSymbolTable = capturedSymbolTable.snapShot();
            // BEGIN blocks execute while eval STRING is parsed. Point the
            // special-variable pragma facade at this eval's private scope so
            // assignments to $^H/${^WARNING_BITS} affect the generated body,
            // not the caller's saved compiler scope. The outer finally restores
            // the previous scope on every success/cache/error path.
            setCurrentScope(parseSymbolTable);

            // CRITICAL: Pre-create aliases for captured variables BEFORE parsing
            // This allows BEGIN blocks in the eval string to access outer lexical variables.
            //
            // When the eval string is parsed, variable references in BEGIN blocks will be
            // resolved to these special package globals that we're aliasing now.
            //
            // Example: my @arr = qw(a b); eval q{ BEGIN { say @arr } };
            // We create: globalArrays["BEGIN_PKG_x::@arr"] = (the runtime @arr object)
            // Then when "say @arr" is parsed in the BEGIN, it resolves to BEGIN_PKG_x::@arr
            // which is aliased to the runtime array with values (a, b).
            Map<Integer, SymbolTable.SymbolEntry> capturedVars = capturedSymbolTable.getAllVisibleVariables();
            for (SymbolTable.SymbolEntry entry : capturedVars.values()) {
                if (!entry.name().equals("@_") && !entry.decl().isEmpty() && !entry.name().startsWith("&")) {
                    if (!entry.decl().equals("our")) {
                        // "my" or "state" variables get special BEGIN package globals
                        Object runtimeValue = runtimeCtx.getRuntimeValue(entry.name());
                        if (runtimeValue != null) {
                            // Get or create the special package ID.
                            // IMPORTANT: Do NOT mutate the AST node (ast.id) — the AST is
                            // shared with the JVM compiler and mutation would corrupt `my`
                            // variable reinitialization in loops.
                            OperatorNode ast = entry.ast();
                            if (ast != null) {
                                int beginId = evalBeginIds().computeIfAbsent(
                                        ast,
                                        k -> EmitterMethodCreator.classCounter.getAndIncrement());
                                String packageName = PersistentVariable.beginPackage(beginId);
                                String varNameWithoutSigil = entry.name().substring(1);
                                String fullName = packageName + "::" + varNameWithoutSigil;

                                // Only install the alias (and schedule cleanup) if no other
                                // alias is already in place for this key. See matching comment
                                // below in the BytecodeInterpreter path.
                                boolean installed = false;
                                if (runtimeValue instanceof RuntimeArray) {
                                    if (GlobalVariable.globalArrays.putIfAbsent(fullName, (RuntimeArray) runtimeValue) == null) {
                                        installed = true;
                                    }
                                } else if (runtimeValue instanceof RuntimeHash) {
                                    if (GlobalVariable.globalHashes.putIfAbsent(fullName, (RuntimeHash) runtimeValue) == null) {
                                        installed = true;
                                    }
                                } else if (runtimeValue instanceof RuntimeScalar) {
                                    if (GlobalVariable.globalVariables.putIfAbsent(fullName, (RuntimeScalar) runtimeValue) == null) {
                                        installed = true;
                                    }
                                }
                                if (installed) {
                                    registerEvalRuntimeAlias(runtimeCtx, entry.name().charAt(0), fullName, (RuntimeBase) runtimeValue);
                                }
                            }
                        }
                    }
                }
            }

            EmitterContext evalCtx = new EmitterContext(
                    new JavaClassInfo(),  // internal java class name
                    parseSymbolTable, // symbolTable
                    null, // method visitor
                    null, // class writer
                    ctx.contextType, // call context
                    true, // is boxed
                    ctx.errorUtil, // error message utility
                    evalCompilerOptions, // possibly modified for Unicode source
                    ctx.unitcheckBlocks);
            // Mark as eval string so goto &sub can emit proper error
            evalCtx.javaClassInfo.isInEvalString = true;
            // evalCtx.logDebug("evalStringHelper EmitterContext: " + evalCtx);
            // evalCtx.logDebug("evalStringHelper Code: " + code);

            // Process the string source code to create the LexerToken list
            Lexer lexer = new Lexer(evalString);
            List<LexerToken> tokens = lexer.tokenize(); // Tokenize the Perl code
            Node ast = null;
            Class<?> generatedClass;
            try {
                // Create the AST
                // Create an instance of ErrorMessageUtil with the file name and token list
                evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.compilerOptions.fileName, tokens);
                Parser parser = new Parser(evalCtx, tokens); // Parse the tokens
                BHooksEndOfScope.beginFileLoad(evalCompilerOptions.fileName);
                try {
                    ast = parser.parse(); // Generate the abstract syntax tree (AST)
                } finally {
                    BHooksEndOfScope.endFileLoad(evalCompilerOptions.fileName);
                }

                // ast = ConstantFoldingVisitor.foldConstants(ast);

                // Constant folding: inline user-defined constant subs and fold constant expressions.
                ast = ConstantFoldingVisitor.foldConstants(ast, evalCtx.symbolTable.getCurrentPackage());

                // Create a new instance of ErrorMessageUtil, resetting the line counter
                // Use evalCtx.compilerOptions.fileName (the eval's filename, e.g. "(eval 1)")
                // not ctx.compilerOptions.fileName (the outer file, e.g. "-e") so that
                // anonymous subs compiled inside eval STRING get the correct source filename
                // for #line directives and caller() reporting
                evalCtx.errorUtil = new ErrorMessageUtil(evalCtx.compilerOptions.fileName, tokens);
                ScopedSymbolTable postParseSymbolTable = evalCtx.symbolTable;
                evalCtx.symbolTable = capturedSymbolTable;
                evalCtx.symbolTable.copyFlagsFrom(postParseSymbolTable);
                setCurrentScope(evalCtx.symbolTable);

                // Use the captured environment array from compile-time to ensure
                // constructor signature matches what EmitEval generated bytecode for
                if (ctx.capturedEnv != null) {
                    evalCtx.capturedEnv = ctx.capturedEnv;
                }

                ast.setAnnotation("blockIsSubroutine", true);
                generatedClass = EmitterMethodCreator.createClassWithMethod(
                        evalCtx,
                        ast,
                        false  // use try-catch
                );
                runUnitcheckBlocks(ctx.unitcheckBlocks);
            } catch (Throwable e) {
                // Compilation error in eval-string

                // Set the global error variable "$@"
                RuntimeScalar err = GlobalVariable.getGlobalVariable("main::@");
                // A BEGIN block can die with a blessed error object (for
                // example Error::TypeTiny).  Compilation currently catches
                // that exception here, so retain its payload instead of
                // converting it through Throwable.getMessage(), which uses
                // the Java object's identity string.
                PerlDieException die = null;
                Throwable current = e;
                while (current != null) {
                    if (current instanceof PerlDieException pde) {
                        die = pde;
                        break;
                    }
                    Throwable next = current.getCause();
                    if (next == current) break;
                    current = next;
                }
                if (die != null && die.getPayload() != null) {
                    err.set(die.getPayload().getFirst());
                } else {
                    err.set(e.getMessage());
                }

                // If EVAL_VERBOSE is set, print the error to stderr for debugging
                if (EVAL_VERBOSE) {
                    System.err.println("eval compilation error: " + e.getMessage());
                    if (e.getCause() != null) {
                        System.err.println("Caused by: " + e.getCause().getMessage());
                    }
                }

                // Check if $SIG{__DIE__} handler is defined
                RuntimeScalar sig = GlobalVariable.getGlobalHash("main::SIG").get("__DIE__");
                if (sig.getDefinedBoolean()) {
                    // Call the $SIG{__DIE__} handler (similar to what die() does)
                    RuntimeScalar sigHandler = new RuntimeScalar(sig);

                    // Undefine $SIG{__DIE__} before calling to avoid infinite recursion
                    int level = DynamicVariableManager.getLocalLevel();
                    DynamicVariableManager.pushLocalVariable(sig);

                    try {
                        RuntimeArray args = new RuntimeArray();
                        RuntimeArray.push(args, new RuntimeScalar(err));
                        apply(sigHandler, args, RuntimeContextType.SCALAR);
                    } catch (Throwable handlerException) {
                        // If the handler dies, use its payload as the new error
                        if (handlerException instanceof RuntimeException && handlerException.getCause() instanceof PerlDieException pde) {
                            RuntimeBase handlerPayload = pde.getPayload();
                            if (handlerPayload != null) {
                                err.set(handlerPayload.getFirst());
                            }
                        } else if (handlerException instanceof PerlDieException pde) {
                            RuntimeBase handlerPayload = pde.getPayload();
                            if (handlerPayload != null) {
                                err.set(handlerPayload.getFirst());
                            }
                        }
                        // If handler throws other exceptions, ignore them (keep original error in $@)
                    } finally {
                        // Restore $SIG{__DIE__}
                        DynamicVariableManager.popToLocalLevel(level);
                    }
                }

                // Return null to signal compilation failure (don't throw exception)
                // This prevents the exception from escaping to outer eval blocks
                return null;
            } finally {
                // Restore caller lexical flags (do not leak eval pragmas).
                capturedSymbolTable.warningFlagsStack.pop();
                capturedSymbolTable.warningFlagsStack.push((BitSet) savedWarningFlags.clone());

                capturedSymbolTable.featureFlagsStack.pop();
                capturedSymbolTable.featureFlagsStack.push(savedFeatureFlags);

                capturedSymbolTable.strictOptionsStack.pop();
                capturedSymbolTable.strictOptionsStack.push(savedStrictOptions);

                // Restore %^H (compile-time hints hash) to the caller snapshot.
                capturedHintHash.elements.clear();
                capturedHintHash.elements.putAll(savedHintHash);

                // Note: Scope restoration moved to outer finally block to handle cache hits

                // Clean up BEGIN aliases for captured variables after compilation.
                // These aliases were only needed during parsing (for BEGIN blocks to access
                // outer lexicals). Leaving them in GlobalVariable would cause corruption
                // if a recursive call re-enters the same function and its `my` declaration
                // calls retrieveBeginScalar, finding the stale alias instead of creating
                // a fresh variable.
                deactivateEvalRuntimeAliases(runtimeCtx);
                runtimeCtx.aliases().clear();

                // Store source lines in symbol table if $^P flags are set
                // Do this on both success and failure paths when flags require retention
                // Use the original evalString and actualFileName; AST may be null on failure
                storeSourceLines(evalString, actualFileName, ast, tokens);
            }

            // Cache the result (unless debugging is enabled)
            if (!isDebugging) {
                Map<String, Class<?>> runtimeEvalCache = evalCache();
                synchronized (runtimeEvalCache) {
                    runtimeEvalCache.put(cacheKey, generatedClass);
                }
            }

            return generatedClass;
        } finally {
            // Restore the original current scope, not the captured symbol table.
            // This prevents eval from leaking its compile-time scope to the caller.
            // This MUST be in the outer finally to handle both cache hits and compilation paths.
            setCurrentScope(savedCurrentScope);
            WarningBitsRegistry.setRuntimeWarningBits(savedRuntimeWarningBits);

            // Clean up this eval's ThreadLocal stack entry to prevent memory leaks.
            // IMPORTANT: Always pop in the finally block even if compilation fails.
            popEvalRuntimeContext(runtimeCtx);
        }
        }
    }

    /**
     * Stores source lines in the symbol table for debugger support when $^P flags are set.
     *
     * <p>This method is used by both the baseline compiler and the interpreter to save
     * eval source code for debugging when $^P flags require it.
     *
     * @param evalString The source code string to store
     * @param filename   The filename (e.g., "(eval 1)")
     * @param ast        The AST to check for subroutine definitions (may be null on compilation failure)
     * @param tokens     Lexer tokens for #line directive processing
     */
    public static void storeSourceLines(String evalString, String filename, Node ast, List<LexerToken> tokens) {
        // Check $^P for debugger flags
        int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
        // 0x02 (2): Line-by-line debugging (also saves source like 0x400)
        // 0x400 (1024): Save source code lines
        // 0x800 (2048): Include evals that generate no subroutines
        // 0x1000 (4096): Include source that did not compile
        boolean shouldSaveSource = (debugFlags & 0x02) != 0 || (debugFlags & 0x400) != 0;
        boolean saveWithoutSubs = (debugFlags & 0x800) != 0;

        if (shouldSaveSource) {
            // Note: We can't reliably detect subroutine definitions from the AST because
            // subroutines are processed at parse-time and removed from the AST.
            // Use a simple heuristic: check if the eval string contains "sub " followed by
            // an identifier or block.
            boolean definesSubs = evalString.matches("(?s).*\\bsub\\s+(?:\\w+|\\{).*");

            // Only save if either:
            // - The eval defines subroutines, OR
            // - The 0x800 flag is set (save evals without subs)
            if (!definesSubs && !saveWithoutSubs) {
                return;  // Skip this eval
            }
            // Store in the symbol table as @{"_<(eval N)"}
            String symbolKey = "_<" + filename;

            // Split the eval string into lines (without including trailing empty strings)
            String[] lines = evalString.split("\n");

            // Create the array with the format expected by the debugger:
            // [0] = undef, [1..n] = lines with \n, [n+1] = \n, [n+2] = ;
            String arrayKey = "main::" + symbolKey;
            RuntimeArray sourceArray = GlobalVariable.getGlobalArray(arrayKey);
            sourceArray.elements.clear();

            // Index 0: undef
            sourceArray.elements.add(RuntimeScalarCache.scalarUndef);

            // Indexes 1..n: each line with "\n" appended
            for (String line : lines) {
                sourceArray.elements.add(new RuntimeScalar(line + "\n"));
            }

            // Index n+1: "\n"
            sourceArray.elements.add(new RuntimeScalar("\n"));

            // Index n+2: ";"
            sourceArray.elements.add(new RuntimeScalar(";"));

            // Process #line directives to populate @{"_<filename"} arrays
            processLineDirectives(evalString, lines, tokens);
        }
    }

    /**
     * Process #line directives in the eval string to populate @{"_<filename"} arrays.
     * This implements the debugger behavior where #line N "file" causes subsequent
     * source lines to be stored in @{"_<file"} at index N.
     *
     * @param evalString The full eval source string
     * @param lines      The split lines of the eval string
     * @param tokens     Lexer tokens (may be null on compilation failure)
     */
    private static void processLineDirectives(String evalString, String[] lines, List<LexerToken> tokens) {
        String currentFilename = null;
        int currentLineOffset = 0; // 0-based index into lines array

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Simple #line directive parsing: #line N "filename"
            // Allow optional leading whitespace
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*#line\\s+(\\d+)\\s+\"([^\"]+)\"").matcher(line);
            if (m.find()) {
                int targetLine = Integer.parseInt(m.group(1)); // 1-based line number in target file
                currentFilename = m.group(2);
                currentLineOffset = i + 1; // Next line in eval corresponds to targetLine
                // Ensure the target array exists and is properly sized
                String targetKey = "main::_<" + currentFilename;
                RuntimeArray targetArray = GlobalVariable.getGlobalArray(targetKey);
                // Ensure array is large enough (sparse behavior)
                while (targetArray.elements.size() <= targetLine) {
                    targetArray.elements.add(RuntimeScalarCache.scalarUndef);
                }
                // Place the next line at the correct index
                if (i + 1 < lines.length) {
                    targetArray.elements.set(targetLine, new RuntimeScalar(lines[i + 1] + "\n"));
                }
            } else if (currentFilename != null && i >= currentLineOffset) {
                // Continue populating the current filename array
                int targetLine = (i - currentLineOffset) + 1; // Convert to 1-based
                String targetKey = "main::_<" + currentFilename;
                RuntimeArray targetArray = GlobalVariable.getGlobalArray(targetKey);
                // Ensure array is large enough (sparse behavior)
                while (targetArray.elements.size() <= targetLine) {
                    targetArray.elements.add(RuntimeScalarCache.scalarUndef);
                }
                targetArray.elements.set(targetLine, new RuntimeScalar(line + "\n"));
            }
        }
    }

    /**
     * Execute eval STRING using the interpreter backend for faster compilation.
     * This method parses the eval string and compiles it to InterpretedCode instead
     * of generating JVM bytecode, which is 46x faster for workloads with many unique eval strings.
     *
     * @param code          The RuntimeScalar containing the eval string
     * @param evalTag       The unique identifier for this eval site
     * @param runtimeValues The captured variable values from the outer scope
     * @param args          The @_ arguments to pass to the eval
     * @param callContext   The calling context (SCALAR/LIST/VOID)
     * @return The result of executing the eval as a RuntimeList
     * @throws Throwable if compilation or execution fails
     */
    public static RuntimeList evalStringWithInterpreter(
            RuntimeScalar code,
            String evalTag,
            Object[] runtimeValues,
            RuntimeArray args,
            int callContext) throws Throwable {

        try (PerlRuntime.Binding runtimeBinding = PerlRuntime.current().bind()) {
        PerlLanguageProvider.CompilationLockGuard compilationLock =
                PerlLanguageProvider.acquireCompilationLock();
        try {

        rejectTaintedEval(code);

        evalTrace("evalStringWithInterpreter enter tag=" + evalTag + " ctx=" + callContext +
                " codeType=" + code.type + " codeLen=" + (code.toString() != null ? code.toString().length() : -1));

        // Retrieve the eval context that was saved at program compile-time
        EmitterContext ctx = getEvalContext(evalTag);

        // Handle missing eval context - this can happen when compiled code (e.g., INIT blocks
        // with eval) is executed after the runtime has been reset. In JUnit parallel tests,
        // PerlLanguageProvider.resetAll() clears evalContext between tests, but compiled
        // bytecode (which references specific evalTags) survives and may be re-executed.
        if (ctx == null) {
            throw new RuntimeException(
                    "Eval context not found for tag: " + evalTag +
                    ". This can happen when eval is called from code that was compiled " +
                    "in a previous session (e.g., INIT blocks in cached modules). " +
                    "The module may need to be reloaded. " +
                    "If this occurs in tests, ensure module caches are cleared along with eval contexts.");
        }

        // Save the current scope so we can restore it after eval execution.
        // This is critical because eval may be called from code compiled with different
        // warning/feature flags than the caller, and we must not leak the eval's scope.
        ScopedSymbolTable savedCurrentScope = getCurrentScope();

        // Store runtime values in ThreadLocal for BEGIN block support
        EvalRuntimeContext runtimeCtx = new EvalRuntimeContext(
                runtimeValues,
                ctx.capturedEnv,
                evalTag
        );
        pushEvalRuntimeContext(runtimeCtx);

        InterpretedCode interpretedCode = null;
        RuntimeList result;

        // Declare these outside try block so they're accessible in finally block for debugger support
        Node ast = null;
        List<LexerToken> tokens = null;

        // Save dynamic variable level to restore after eval.
        // IMPORTANT: Scope InterpreterState.currentPackage around eval execution.
        // This is the interpreter-side equivalent of the same scoping done in
        // EvalStringHandler for the JVM bytecode path. Without this, SET_PACKAGE
        // opcodes inside the eval permanently mutate the caller's package state,
        // breaking caller() and subsequent eval compilations.
        // See InterpreterState.currentPackage javadoc for the full design rationale.
        int dynamicVarLevel = DynamicVariableManager.getLocalLevel();
        String savedPkg = InterpreterState.currentPackage.get().toString();
        DynamicVariableManager.pushLocalVariable(InterpreterState.currentPackage.get());
        InterpreterState.currentPackage.get().set(savedPkg);

        try {
            String evalString = code.toString();
            boolean evalbytesUtf8Source = ctx.isEvalbytes && shouldDecodeEvalbytesUtf8Source(evalString);
            boolean byteStringUtf8Source = !ctx.isEvalbytes
                    && code.type == RuntimeScalarType.BYTE_STRING
                    && (ctx.symbolTable.strictOptionsStack.peek() & Strict.HINT_UTF8) != 0;
            if (evalbytesUtf8Source || byteStringUtf8Source) {
                evalString = decodeEvalbytesUtf8Source(evalString);
            }
            evalTrace("evalStringWithInterpreter parse start tag=" + evalTag + " ctx=" + callContext +
                    " fileName=" + ctx.compilerOptions.fileName);
            // Handle Unicode source detection (same logic as evalStringHelper)
            boolean hasUnicode = false;
            if (evalbytesUtf8Source || byteStringUtf8Source) {
                hasUnicode = true;
            } else if (!ctx.isEvalbytes && code.type != RuntimeScalarType.BYTE_STRING) {
                for (int i = 0; i < evalString.length(); i++) {
                    if (evalString.charAt(i) > 127) {
                        hasUnicode = true;
                        break;
                    }
                }
            }

            // Clone compiler options and set isUnicodeSource if needed
            // Always clone to avoid modifying the original and to set a unique filename
            CompilerOptions evalCompilerOptions = ctx.compilerOptions.clone();
            boolean isByteStringSource = !ctx.isEvalbytes
                    && code.type == RuntimeScalarType.BYTE_STRING
                    && !byteStringUtf8Source;
            if (hasUnicode) {
                evalCompilerOptions.isUnicodeSource = true;
            }
            if (ctx.isEvalbytes && !evalbytesUtf8Source) {
                evalCompilerOptions.isEvalbytes = true;
            }
            if (isByteStringSource) {
                evalCompilerOptions.isUnicodeSource = false;
                evalCompilerOptions.isByteStringSource = true;
            }
            // Always generate a unique filename for each eval to prevent source location collisions
            evalCompilerOptions.fileName = getNextEvalFilename();

            // Setup for BEGIN block support - create aliases for captured variables.
            // IMPORTANT: Do NOT mutate AST nodes (e.g. operatorAst.id) here.
            // The AST is shared with the JVM compiler; mutating it would change how
            // `my` declarations are compiled (NEW vs RETRIEVE_BEGIN), causing variables
            // inside loops to stop being reinitialized between iterations.
            ScopedSymbolTable capturedSymbolTable = ctx.symbolTable;
            Map<Integer, SymbolTable.SymbolEntry> capturedVars = capturedSymbolTable.getAllVisibleVariables();
            for (SymbolTable.SymbolEntry entry : capturedVars.values()) {
                if (!entry.name().equals("@_") && !entry.decl().isEmpty() && !entry.name().startsWith("&")) {
                    if (!entry.decl().equals("our")) {
                        Object runtimeValue = runtimeCtx.getRuntimeValue(entry.name());
                        if (runtimeValue != null) {
                            OperatorNode operatorAst = entry.ast();
                            if (operatorAst != null) {
                                int beginId = evalBeginIds().computeIfAbsent(
                                        operatorAst,
                                        k -> EmitterMethodCreator.classCounter.getAndIncrement());
                                String packageName = PersistentVariable.beginPackage(beginId);
                                String varNameWithoutSigil = entry.name().substring(1);
                                String fullName = packageName + "::" + varNameWithoutSigil;

                                // Only install the alias (and schedule cleanup) if no other
                                // alias is already in place for this key. An alias may already
                                // exist because SubroutineParser.handleNamedSub registered one
                                // at compile time for a named sub that closes over the same
                                // outer `my`. That alias must stay alive until the owning
                                // `my %name = (...)` runs and calls retrieveBeginHash, which
                                // takes ownership. If we unconditionally put+remove here, we
                                // delete SubroutineParser's alias in the finally block, and the
                                // later `my` creates a fresh, unshared object — breaking the
                                // named sub's closure over that variable.
                                boolean installed = false;
                                if (runtimeValue instanceof RuntimeArray) {
                                    if (GlobalVariable.globalArrays.putIfAbsent(fullName, (RuntimeArray) runtimeValue) == null) {
                                        installed = true;
                                    }
                                } else if (runtimeValue instanceof RuntimeHash) {
                                    if (GlobalVariable.globalHashes.putIfAbsent(fullName, (RuntimeHash) runtimeValue) == null) {
                                        installed = true;
                                    }
                                } else if (runtimeValue instanceof RuntimeScalar) {
                                    if (GlobalVariable.globalVariables.putIfAbsent(fullName, (RuntimeScalar) runtimeValue) == null) {
                                        installed = true;
                                    }
                                }
                                if (installed) {
                                    registerEvalRuntimeAlias(runtimeCtx, entry.name().charAt(0), fullName, (RuntimeBase) runtimeValue);
                                }
                            }
                        }
                    }
                }
            }

            try {
                // Parse the eval string
                Lexer lexer = new Lexer(evalString);
                tokens = lexer.tokenize();

                // Create parser context
                ScopedSymbolTable parseSymbolTable = capturedSymbolTable.snapShot();
                // Eval STRING inherits the caller's lexical warning bits. The
                // interpreter does not have JVM call-site instructions to
                // reconstruct this state later, so seed the parser scope from
                // the saved caller frame before BEGIN blocks are compiled.
                String callerWarningBits = WarningBitsRegistry.getCallerBitsAtFrame(0);
                if (callerWarningBits == null || callerWarningBits.isEmpty()) {
                    callerWarningBits = WarningBitsRegistry.getCallSiteBits();
                }
                if (callerWarningBits == null || callerWarningBits.isEmpty()) {
                    callerWarningBits = WarningBitsRegistry.getCurrent();
                }
                // An empty caller-stack entry means the interpreter had no
                // more precise runtime value.  Do not let that erase the
                // compile-time warning mask already present in the eval
                // call site's captured symbol table.
                if (callerWarningBits != null && !callerWarningBits.isEmpty()) {
                    WarningFlags.setWarningBitsFromString(parseSymbolTable, callerWarningBits);
                }
                // BEGIN blocks execute while the eval string is being parsed.
                // Make their lexical pragma changes land in this eval's parser
                // scope; otherwise executePerlAST propagates them to the
                // caller's stale scope and nested subs lose warning state.
                setCurrentScope(parseSymbolTable);
                EmitterContext evalCtx = new EmitterContext(
                        new JavaClassInfo(),
                        parseSymbolTable,
                        null,
                        null,
                        callContext,  // Use the runtime calling context, not the saved one!
                        true,
                        new ErrorMessageUtil(evalCompilerOptions.fileName, tokens),
                        evalCompilerOptions,
                        ctx.unitcheckBlocks);

                Parser parser = new Parser(evalCtx, tokens);
                BHooksEndOfScope.beginFileLoad(evalCompilerOptions.fileName);
                try {
                    ast = parser.parse();
                } finally {
                    BHooksEndOfScope.endFileLoad(evalCompilerOptions.fileName);
                }

                // Run UNITCHECK blocks
                runUnitcheckBlocks(evalCtx.unitcheckBlocks);

                // Build adjusted registry for captured variables
                // Map variable names to register indices (3+ for captured variables)
                Map<String, Integer> adjustedRegistry = new HashMap<>();
                adjustedRegistry.put("this", 0);
                adjustedRegistry.put("@_", 1);
                adjustedRegistry.put("wantarray", 2);

                // IMPORTANT: Captured variables are loaded into registers starting at 3
                // (see BytecodeInterpreter's `System.arraycopy(code.capturedVars, 0, registers, 3, ...)`).
                // Therefore the variable registry must map captured variable names to packed
                // register indices 3+(i-skipVariables), in the same order as runtimeValues[].
                // Do NOT pack indices: runtimeValues[] is sized for the full capturedEnv range
                // and may contain null gaps, so the corresponding registers must keep the same
                // slot numbering.
                if (ctx.capturedEnv != null) {
                    for (int i = 3; i < ctx.capturedEnv.length; i++) {
                        String varName = ctx.capturedEnv[i];
                        if (varName == null) {
                            continue;
                        }
                        adjustedRegistry.put(varName, i);
                    }
                }

                // Build a parallel map of declaration kinds so the BytecodeCompiler can distinguish
                // 'our' (package) variables from true lexicals. 'our' variables must resolve via
                // GlobalVariable.getGlobalVariable() on each access so that `local $OurVar` in the
                // caller is visible inside the eval. Without this hint, captured 'our' vars are
                // treated as lexical 'my' vars bound to the scalar captured at eval-entry time.
                Map<String, String> adjustedDecls = new HashMap<>();
                // Parallel map of `our` var name → declaring package. Seeded so the eval's
                // BytecodeCompiler keeps the caller's lexical alias intact even after the
                // eval body changes package (e.g. `package Foo; $x`).
                Map<String, String> adjustedOurPackages = new HashMap<>();
                if (ctx.capturedEnv != null) {
                    for (int i = 3; i < ctx.capturedEnv.length; i++) {
                        String varName = ctx.capturedEnv[i];
                        if (varName == null) continue;
                        SymbolTable.SymbolEntry entry = capturedSymbolTable.getSymbolEntry(varName);
                        if (entry != null && !entry.decl().isEmpty()) {
                            adjustedDecls.put(varName, entry.decl());
                            if ("our".equals(entry.decl()) && entry.perlPackage() != null) {
                                adjustedOurPackages.put(varName, entry.perlPackage());
                            }
                        }
                    }
                }

                // Compile to InterpretedCode with variable registry.
                //
                // setCompilePackage() is safe here (unlike EvalStringHandler) because:
                //   - evalCtx.errorUtil uses evalCompilerOptions.fileName (the outer script name),
                //     not the eval string's tokens, so die/warn location baking is already
                //     relative to the outer script and is unaffected by the package change.
                //   - capturedSymbolTable.getCurrentPackage() gives the compile-time package
                //     of the eval call site (e.g. "FOO3"), so bare names like *named are
                //     correctly qualified to FOO3::named in the bytecode string pool.
                //   - Without this call, the BytecodeCompiler defaults to "main", causing
                //     eval q[*named{CODE}] to look up main::named instead of FOO3::named.
                BytecodeCompiler compiler = new BytecodeCompiler(
                        evalCompilerOptions.fileName,
                        1,
                        evalCtx.errorUtil,
                        adjustedRegistry,
                        adjustedDecls,
                        adjustedOurPackages);
                compiler.setCompilePackage(capturedSymbolTable.getCurrentPackage());
                interpretedCode = compiler.compile(ast, evalCtx);
                evalTrace("evalStringWithInterpreter compiled tag=" + evalTag +
                        " bytecodeLen=" + (interpretedCode != null ? interpretedCode.bytecode.length : -1) +
                        " src=" + (interpretedCode != null ? interpretedCode.sourceName : "null"));
                if (isDisassemble()) {
                    System.out.println(Disassemble.disassemble(interpretedCode));
                }

                // Set captured variables
                if (runtimeValues.length > 0) {
                    RuntimeBase[] capturedVars2 = new RuntimeBase[runtimeValues.length];
                    for (int i = 0; i < runtimeValues.length; i++) {
                        capturedVars2[i] = (RuntimeBase) runtimeValues[i];
                    }
                    interpretedCode = interpretedCode.withCapturedVars(capturedVars2);
                }

            } catch (Throwable e) {
                // Compilation error in eval-string
                // Set the global error variable "$@"
                RuntimeScalar err = GlobalVariable.getGlobalVariable("main::@");
                // Preserve blessed die payloads thrown by BEGIN blocks.  The
                // Java exception message is only a diagnostic and may be the
                // object's identity string rather than its Perl overload.
                PerlDieException die = null;
                Throwable current = e;
                while (current != null) {
                    if (current instanceof PerlDieException pde) {
                        die = pde;
                        break;
                    }
                    Throwable next = current.getCause();
                    if (next == current) break;
                    current = next;
                }
                if (die != null && die.getPayload() != null) {
                    err.set(die.getPayload().getFirst());
                } else {
                    String evalError = e.getMessage();
                    if (evalError != null && evalString != null
                            && evalString.matches("(?s).*\\n\\s*[^\\n;]+\\s*(?:<|>|\\+|-|\\*|/|%)\\s*;.*")) {
                        java.util.regex.Matcher syntax = java.util.regex.Pattern
                                .compile("(syntax error at \\(eval \\d+\\) line )(\\d+)(, near)")
                                .matcher(evalError);
                        if (syntax.find()) {
                            int line = Integer.parseInt(syntax.group(2));
                            evalError = syntax.replaceFirst(java.util.regex.Matcher.quoteReplacement(
                                    syntax.group(1) + (line - 1) + syntax.group(3)));
                        }
                    }
                    err.set(evalError);
                }

                // If EVAL_VERBOSE is set, print the error to stderr for debugging
                if (EVAL_VERBOSE) {
                    System.err.println("eval compilation error: " + e.getMessage());
                    String src = evalString;
                    if (src != null) {
                        int maxLen = 4000;
                        if (src.length() > maxLen) {
                            src = src.substring(0, maxLen) + "\n...";
                        }
                        System.err.println("eval source:\n" + src);
                    }
                    if (e.getCause() != null) {
                        System.err.println("Caused by: " + e.getCause().getMessage());
                    }
                }

                // Check if $SIG{__DIE__} handler is defined
                RuntimeScalar sig = GlobalVariable.getGlobalHash("main::SIG").get("__DIE__");
                if (sig.getDefinedBoolean()) {
                    // Call the $SIG{__DIE__} handler (similar to what die() does)
                    RuntimeScalar sigHandler = new RuntimeScalar(sig);

                    // Undefine $SIG{__DIE__} before calling to avoid infinite recursion
                    int level = DynamicVariableManager.getLocalLevel();
                    DynamicVariableManager.pushLocalVariable(sig);

                    incrementEvalDepth();
                    boolean pushedEvalFrame = InterpreterState.pushEvalFrameForCurrentInterpreter();
                    boolean pushedSyntheticEvalFrame = false;
                    if (!pushedEvalFrame) {
                        pushSyntheticEvalCallerFrame(
                                InterpreterState.currentPackage.get().toString(),
                                evalCompilerOptions.fileName,
                                1);
                        pushedSyntheticEvalFrame = true;
                    }
                    try {
                        RuntimeArray handlerArgs = new RuntimeArray();
                        RuntimeArray.push(handlerArgs, new RuntimeScalar(err));
                        apply(sigHandler, handlerArgs, RuntimeContextType.SCALAR);
                    } catch (Throwable handlerException) {
                        // If the handler dies, use its payload as the new error
                        if (handlerException instanceof RuntimeException && handlerException.getCause() instanceof PerlDieException pde) {
                            RuntimeBase handlerPayload = pde.getPayload();
                            if (handlerPayload != null) {
                                err.set(handlerPayload.getFirst());
                            }
                        } else if (handlerException instanceof PerlDieException pde) {
                            RuntimeBase handlerPayload = pde.getPayload();
                            if (handlerPayload != null) {
                                err.set(handlerPayload.getFirst());
                            }
                        }
                        // If handler throws other exceptions, ignore them (keep original error in $@)
                    } finally {
                        if (pushedEvalFrame) {
                            InterpreterState.pop();
                        }
                        if (pushedSyntheticEvalFrame) {
                            popSyntheticEvalCallerFrame();
                        }
                        decrementEvalDepth();
                        // Restore $SIG{__DIE__}
                        DynamicVariableManager.popToLocalLevel(level);
                    }
                }

                // Return undef/empty list to signal compilation failure
                if (RuntimeContextType.isListLike(callContext)) {
                    return new RuntimeList();
                } else {
                    return new RuntimeList(new RuntimeScalar());
                }
            }

            // Clean up BEGIN aliases BEFORE execution. These aliases were only needed during
            // parsing/compilation (for BEGIN blocks to access outer lexicals). If left in
            // GlobalVariable during execution, a recursive call that re-enters the same
            // function would find the alias via retrieveBeginScalar, sharing the same
            // RuntimeScalar object instead of creating a fresh one.
            deactivateEvalRuntimeAliases(runtimeCtx);
            runtimeCtx.aliases().clear();
            setCurrentScope(savedCurrentScope);
            compilationLock.close();

            // Execute the interpreted code
            // Track eval depth for $^S support
            incrementEvalDepth();
            try {
                result = interpretedCode.apply(args, callContext);

                evalTrace("evalStringWithInterpreter exec ok tag=" + evalTag + " ctx=" + callContext +
                        " resultClass=" + (result != null ? result.getClass().getSimpleName() : "null") +
                        " resultScalar=" + (result != null ? result.scalar().toString() : "null") +
                        " resultBool=" + (result != null && result.scalar() != null && result.scalar().getBoolean()));

                // Clear $@ on successful execution
                GlobalVariable.setGlobalVariable("main::@", "");

                return result;

            } catch (PerlDieException e) {
                evalTrace("evalStringWithInterpreter exec die tag=" + evalTag + " ctx=" + callContext +
                        " payload=" + (e.getPayload() != null ? e.getPayload().getFirst().toString() : "null"));
                // Runtime error - set $@ and return undef/empty list
                RuntimeScalar err = GlobalVariable.getGlobalVariable("main::@");
                RuntimeBase payload = e.getPayload();
                if (payload != null) {
                    err.set(payload.getFirst());
                } else {
                    err.set("Died");
                }

                if (EVAL_VERBOSE) {
                    System.err.println("eval runtime error: " + err);
                    String src = evalString;
                    if (src != null) {
                        int maxLen = 4000;
                        if (src.length() > maxLen) {
                            src = src.substring(0, maxLen) + "\n...";
                        }
                        System.err.println("eval source:\n" + src);
                    }
                }

                // Return undef/empty list
                if (RuntimeContextType.isListLike(callContext)) {
                    return new RuntimeList();
                } else {
                    return new RuntimeList(new RuntimeScalar());
                }

            } catch (Throwable e) {
                evalTrace("evalStringWithInterpreter exec throwable tag=" + evalTag + " ctx=" + callContext +
                        " ex=" + e.getClass().getSimpleName() + " msg=" + e.getMessage());
                WarnDie.catchEval(e);

                // Return undef/empty list
                if (RuntimeContextType.isListLike(callContext)) {
                    return new RuntimeList();
                } else {
                    return new RuntimeList(new RuntimeScalar());
                }
            } finally {
                decrementEvalDepth();
            }

        } finally {
            PerlLanguageProvider.COMPILE_LOCK.lock();
            try {
            evalTrace("evalStringWithInterpreter exit tag=" + evalTag + " ctx=" + callContext +
                    " $@=" + GlobalVariable.getGlobalVariable("main::@"));
            // Restore dynamic variables (local) to their state before eval
            DynamicVariableManager.popToLocalLevel(dynamicVarLevel);

            // Restore the original current scope, not the captured symbol table.
            // This prevents eval from leaking its compile-time scope to the caller.
            setCurrentScope(savedCurrentScope);

            // Store source lines in debugger symbol table if $^P flags are set
            // Do this on both success and failure paths when flags require retention
            // ast and tokens may be null if parsing failed early, but storeSourceLines handles that
            int debugFlags = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("P")).getInt();
            if (debugFlags != 0 && tokens != null) {
                String evalFilename = getNextEvalFilename();
                storeSourceLines(code.toString(), evalFilename, ast, tokens);
            }

            // Clean up this eval's ThreadLocal stack entry.
            popEvalRuntimeContext(runtimeCtx);
            } finally {
                PerlLanguageProvider.COMPILE_LOCK.unlock();
                compilationLock.close();
            }
        }
        } finally {
            compilationLock.close();
        }
        }
    }

    /** Perl forbids compiling tainted eval STRING input while -T is active. */
    public static void rejectTaintedEval(RuntimeScalar code) {
        if (code != null && code.isTainted() && GlobalContext.isTaintModeActive()) {
            throw new PerlCompilerException(
                    "Insecure dependency in eval while running with -T switch");
        }
    }

    // make sure we return a RuntimeScalar from __SUB__
    public static RuntimeScalar selfReferenceMaybeNull(RuntimeScalar codeRef) {
        return codeRef == null
                ? scalarUndef
                : codeRef;
    }

    /**
     * Factory method to create a CODE object (anonymous subroutine).
     * This is called right after a new Class is compiled.
     * The codeObject is an instance of the new Class, with the closure variables in place.
     *
     * @param codeObject the instance of the compiled Class
     * @return a RuntimeScalar representing the CODE object
     * @throws Exception if an error occurs during method retrieval
     */
    public static RuntimeScalar makeCodeObject(Object codeObject) throws Exception {
        return makeCodeObject(codeObject, null);
    }

    public static RuntimeScalar makeCodeObject(Object codeObject, String prototype) throws Exception {
        return makeCodeObject(codeObject, prototype, null);
    }

    /**
     * Factory method to create a CODE object with CvSTASH (package name) tracking.
     * The packageName parameter sets the CvSTASH equivalent, which determines where
     * $AUTOLOAD is set and what B::svref_2object->STASH->NAME returns.
     *
     * @param codeObject  the instance of the compiled Class
     * @param prototype   the prototype string (may be null)
     * @param packageName the compile-time package (CvSTASH equivalent, may be null)
     * @return a RuntimeScalar representing the CODE object
     * @throws Exception if an error occurs during method retrieval
     */
    public static RuntimeScalar makeCodeObject(Object codeObject, String prototype, String packageName) throws Exception {
        return makeCodeObject(codeObject, prototype, packageName, null, 0);
    }

    /**
     * Like {@link #makeCodeObject(Object, String, String)} plus COP source
     * location for {@code B::CV->START} (e.g. Fennec::Lite line filtering).
     */
    public static RuntimeScalar makeCodeObject(
            Object codeObject,
            String prototype,
            String packageName,
            String cvStartFile,
            int cvStartLine) throws Exception {
        return makeCodeObject(codeObject, prototype, packageName, cvStartFile, cvStartLine, null, 0);
    }

    public static RuntimeScalar makeCodeObject(
            Object codeObject,
            String prototype,
            String packageName,
            String cvStartFile,
            int cvStartLine,
            String deparseSourceText,
            int deparseFlags) throws Exception {
        return makeCodeObject(codeObject, prototype, packageName, cvStartFile, cvStartLine,
                deparseSourceText, deparseFlags, -1);
    }

    public static RuntimeScalar makeCodeObject(
            Object codeObject,
            String prototype,
            String packageName,
            String cvStartFile,
            int cvStartLine,
            String deparseSourceText,
            int deparseFlags,
            int deparseSourceOffset) throws Exception {
        return makeCodeObject(codeObject, prototype, packageName, cvStartFile, cvStartLine,
                deparseSourceText, deparseFlags, deparseSourceOffset, -1);
    }

    public static RuntimeScalar makeCodeObject(
            Object codeObject,
            String prototype,
            String packageName,
            String cvStartFile,
            int cvStartLine,
            String deparseSourceText,
            int deparseFlags,
            int deparseSourceOffset,
            int deparseSourceEnd) throws Exception {
        // Retrieve the class of the provided code object
        Class<?> clazz = codeObject.getClass();

        // Cast to PerlSubroutine - generated classes implement this interface
        // This allows direct interface calls without MethodHandle conversion errors
        PerlSubroutine subroutine = (PerlSubroutine) codeObject;

        // Create a new RuntimeCode using the functional interface
        RuntimeCode code = new RuntimeCode(subroutine, prototype);
        // Set CvSTASH (the package where this sub was compiled)
        if (packageName != null) {
            code.packageName = packageName;
        }
        if (cvStartFile != null && !cvStartFile.isEmpty()) {
            code.cvStartFile = cvStartFile;
        }
        if (cvStartLine > 0) {
            code.cvStartLine = cvStartLine;
        }
        code.deparseSourceText = deparseSourceText;
        code.deparseFlags = deparseFlags;
        code.deparseSourceOffset = deparseSourceOffset;
        code.deparseSourceEnd = deparseSourceEnd;

        // Look up pad constants registered at compile time for this class.
        // These track cached string literals referenced via \ inside the sub,
        // needed for optree reaping (clearing weak refs when sub is replaced).
        String internalClassName = clazz.getName().replace('.', '/');
        RuntimeBase[] padConsts = PerlRuntime.current().runtimeCodeState()
                .padConstantsByClassName.remove(internalClassName);
        if (padConsts != null) {
            code.padConstants = padConsts;
        }

        // Extract captured RuntimeScalar fields for closure DESTROY tracking.
        // Each instance field of type RuntimeScalar (except __SUB__) is a
        // captured lexical variable. We store them so that releaseCaptures()
        // can decrement blessed ref refCounts when the closure is discarded.
        Field[] allFields = clazz.getDeclaredFields();
        List<RuntimeScalar> captured = new ArrayList<>();
        List<RuntimeBase> capturedAggregates = new ArrayList<>();
        for (Field f : allFields) {
            if (f.getType() == RuntimeScalar.class && !"__SUB__".equals(f.getName())) {
                RuntimeScalar capturedVar = (RuntimeScalar) f.get(codeObject);
                if (capturedVar != null) {
                    if (code.closedOverVariables == null) {
                        code.closedOverVariables = new LinkedHashMap<>();
                    }
                    code.closedOverVariables.put(f.getName(), capturedVar);
                    captured.add(capturedVar);
                    capturedVar.retainClosureCapture();
                }
            } else if (f.getType() == RuntimeArray.class || f.getType() == RuntimeHash.class) {
                RuntimeBase capturedAggregate = (RuntimeBase) f.get(codeObject);
                if (capturedAggregate != null) {
                    if (code.closedOverVariables == null) {
                        code.closedOverVariables = new LinkedHashMap<>();
                    }
                    code.closedOverVariables.put(f.getName(), capturedAggregate);
                    capturedAggregates.add(capturedAggregate);
                    capturedAggregate.retainClosureCapture();
                }
            }
        }
        if (!captured.isEmpty()) {
            code.capturedScalars = captured.toArray(new RuntimeScalar[0]);
        }
        if (!capturedAggregates.isEmpty()) {
            code.capturedAggregates = capturedAggregates.toArray(new RuntimeBase[0]);
        }
        if (!captured.isEmpty() || !capturedAggregates.isEmpty()) {
            // Enable refCount tracking for closures with captures.
            // When the CODE ref's refCount drops to 0, releaseCaptures()
            // fires (via DestroyDispatch.callDestroy), letting captured
            // blessed objects run DESTROY.
            code.refCount = 0;
        }

        RuntimeScalar codeRef = new RuntimeScalar(code);

        // Set the __SUB__ instance field
        Field field = clazz.getDeclaredField("__SUB__");
        field.set(codeObject, codeRef);

        return codeRef;
    }

    /**
     * Call a method in a Perl-like class hierarchy using the C3 linearization algorithm.
     * This version accepts a native RuntimeBase[] array for parameters.
     *
     * @param runtimeScalar The object to call the method on.
     * @param method        The method to resolve.
     * @param currentSub    The subroutine to resolve SUPER::method in.
     * @param args          The arguments to pass to the method as native array.
     * @param callContext   The call context.
     * @return The result of the method call.
     */
    public static RuntimeList call(RuntimeScalar runtimeScalar,
                                   RuntimeScalar method,
                                   RuntimeScalar currentSub,
                                   RuntimeBase[] args,
                                   int callContext) {
        // Handle tied scalars: in Perl 5, $tied->method() evaluates $tied
        // (triggering FETCH) before method dispatch
        if (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) {
            runtimeScalar = runtimeScalar.tiedFetch();
        }
        // Transform the native array to RuntimeArray of aliases (Perl variable `@_`)
        // Note: `this` (runtimeScalar) will be inserted by the RuntimeArray version
        RuntimeArray a = new RuntimeArray(args.length);
        for (RuntimeBase arg : args) {
            arg.setArrayOfAlias(a);
        }
        return call(runtimeScalar, method, currentSub, a, callContext);
    }

    /**
     * Call a method with inline caching for fast dispatch.
     * Each call site caches the resolved method for the most recent (blessId, methodName) pair.
     *
     * @param callsiteId    Unique ID for this call site (used for cache indexing).
     * @param runtimeScalar The object to call the method on.
     * @param method        The method to resolve.
     * @param currentSub    The subroutine to resolve SUPER::method in.
     * @param args          The arguments to pass to the method as native array.
     * @param callContext   The call context.
     * @return The result of the method call.
     */
    public static RuntimeList callCached(int callsiteId,
                                         RuntimeScalar runtimeScalar,
                                         RuntimeScalar method,
                                         RuntimeScalar currentSub,
                                         RuntimeBase[] args,
                                         int callContext) {
        // Establish a MyVarCleanupStack boundary so that my-variables
        // registered by the called method's bytecode are cleaned up if
        // the method dies. Without this, the method's my-variable entries
        // linger on the stack and their refCount decrements are lost,
        // causing blessed objects to leak (DESTROY never fires).
        int cleanupMark = MyVarCleanupStack.pushMark();
        try {
        return callCachedInner(callsiteId, runtimeScalar, method, currentSub, args, callContext);
        } catch (RuntimeException e) {
            if (!(e instanceof PerlExitException)) {
                MyVarCleanupStack.unwindTo(cleanupMark);
                MortalList.flush();
            }
            throw e;
        } finally {
            MyVarCleanupStack.popMark(cleanupMark);
        }
    }

    private static RuntimeList callCachedInner(int callsiteId,
                                         RuntimeScalar runtimeScalar,
                                         RuntimeScalar method,
                                         RuntimeScalar currentSub,
                                         RuntimeBase[] args,
                                         int callContext) {
        // Handle tied scalars: the invocant may be a TIED_SCALAR returned
        // from a tied hash / array FETCH (e.g. $tied_hash{obj}->method).
        // Dispatch sees only the TIED_SCALAR shell, so unwrap to the
        // underlying blessed reference and re-enter callCached (which
        // re-establishes a cleanup boundary for the unwrapped invocant).
        if (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) {
            return callCached(callsiteId, runtimeScalar.tiedFetch(), method,
                    currentSub, args, callContext);
        }
        RuntimeBase pjMethodInvHold = acquireMethodInvocantHold(runtimeScalar);
        try {
        // Fast path: check inline cache for monomorphic call sites
        if (method.type == RuntimeScalarType.STRING || method.type == RuntimeScalarType.BYTE_STRING) {
            // Unwrap READONLY_SCALAR for blessId check (same as in call())
            RuntimeScalar invocant = runtimeScalar;
            while (invocant.type == RuntimeScalarType.READONLY_SCALAR) {
                invocant = (RuntimeScalar) invocant.value;
            }
            if (RuntimeScalarType.isReference(invocant)) {
                int blessId = ((RuntimeBase) invocant.value).blessId;
                if (blessId != 0) {
                    int methodHash = System.identityHashCode(method.value);
                    RuntimeCodeRuntimeState runtimeCodeState =
                            PerlRuntime.current().runtimeCodeState();

                    // Check if cache hit
                    RuntimeCode cachedCode = runtimeCodeState.cachedInlineMethod(
                            callsiteId, blessId, methodHash);
                    if (cachedCode != null
                            && (cachedCode.subroutine != null || cachedCode.methodHandle != null)) {
                            // Cache hit: skip method lookup, but still enter through
                            // RuntimeCode.apply() so caller(), next::method, warnings,
                            // recursion tracking, and scope cleanup see a real Perl frame.
                            try {
                                RuntimeArray a = new RuntimeArray(args.length + 1);
                                a.elements.add(runtimeScalar);
                                for (RuntimeBase arg : args) {
                                    arg.setArrayOfAlias(a);
                                }
                                
                                // If this is an AUTOLOAD, set $AUTOLOAD before calling
                                String autoloadVariableName = cachedCode.autoloadVariableName;
                                if (autoloadVariableName != null) {
                                    String methodName = method.toString();
                                    // Only set $AUTOLOAD when dispatching to AUTOLOAD as a fallback
                                    // (method name != "AUTOLOAD"). When calling AUTOLOAD directly
                                    // (e.g., $self->SUPER::AUTOLOAD), the caller has already set it.
                                    if (!methodName.equals("AUTOLOAD")) {
                                        // Use the original calling class (perlClassName), not the class
                                        // where AUTOLOAD was found. Perl sets $AUTOLOAD to Child::method
                                        // even when AUTOLOAD is inherited from Base.
                                        String perlClassName = NameNormalizer.getBlessStr(blessId);
                                        String fullMethodName =
                                                qualifyAutoloadMethodName(methodName, perlClassName);
                                        setAutoloadMethodName(
                                                autoloadVariableName, fullMethodName, method);
                                    }
                                }
                                
                                MortalList.pushMark();
                                try {
                                    return applyCachedMethod(cachedCode, a, callContext);
                                } finally {
                                    MortalList.popMark();
                                }
                            } catch (Throwable e) {
                                if (e instanceof RuntimeException) throw (RuntimeException) e;
                                throw new RuntimeException(e);
                            }
                    }
                    
                    // Cache miss - do full lookup and update cache
                    String methodName = method.toString();
                    if (!methodName.contains("::")) {
                        String perlClassName = NameNormalizer.getBlessStr(blessId);
                        RuntimeScalar resolvedMethod = InheritanceResolver.findMethodInHierarchy(methodName, perlClassName, null, 0);
                        if (resolvedMethod != null && resolvedMethod.type == RuntimeScalarType.CODE) {
                            RuntimeCode code = (RuntimeCode) resolvedMethod.value;
                            
                            // Run compiler supplier if needed
                            if (code.compilerSupplier != null) {
                                code.compilerSupplier.get();
                                code = (RuntimeCode) resolvedMethod.value;
                            }
                            
                            // Only cache if method is defined and has a subroutine or method handle
                            if (code.subroutine != null || code.methodHandle != null) {
                                // Update cache
                                runtimeCodeState.cacheInlineMethod(
                                        callsiteId, blessId, methodHash, code);
                            }
                            
                            // Call the method with function-scoped mortal boundary
                            RuntimeArray a = new RuntimeArray(args.length + 1);
                            a.elements.add(runtimeScalar);
                            for (RuntimeBase arg : args) {
                                arg.setArrayOfAlias(a);
                            }
                            
                            String autoloadVariableName = code.autoloadVariableName;
                            if (autoloadVariableName != null && !methodName.equals("AUTOLOAD")) {
                                // Use the original calling class, not where AUTOLOAD was found
                                String fullMethodName =
                                        qualifyAutoloadMethodName(methodName, perlClassName);
                                setAutoloadMethodName(
                                        autoloadVariableName, fullMethodName, method);
                            }
                            MortalList.pushMark();
                            try {
                                return applyCachedMethod(code, a, callContext);
                            } finally {
                                MortalList.popMark();
                            }
                        }
                    }
                }
            }
        }
        
        // Fall back without nesting through call(...) — avoids double refcount hold
        // (this outer frame already holds the invocant for the inlined-cache miss path).
        RuntimeArray aFallback = new RuntimeArray(args.length + 1);
        aFallback.elements.add(runtimeScalar);
        for (RuntimeBase arg : args) {
            arg.setArrayOfAlias(aFallback);
        }
        return dispatchPerlMethodAfterSelfInjected(runtimeScalar, method, currentSub, aFallback, callContext);
        } finally {
            releaseMethodInvocantHold(pjMethodInvHold);
        }
    }

    /**
     * Preserve the normal Perl-subroutine boundary when the method inline cache
     * invokes a resolved RuntimeCode directly. In particular, an explicit
     * return inside map/grep must cross generated block callbacks but stop at
     * the method that owns those blocks; letting the marker escape makes the
     * method's caller return as well.
     */
    private static RuntimeList applyCachedMethod(
            RuntimeCode code, RuntimeArray args, int callContext) {
        int effectiveContext = effectiveCallContext(code, callContext);
        try {
            // Preserve LVALUE here: generated code performs the callable check
            // from the raw context. Normalizing it first would silently turn a
            // forbidden lvalue method assignment into an ordinary scalar call.
            return code.apply(args, callContext);
        } catch (PerlNonLocalReturnException e) {
            if (e.targetCode != null && e.targetCode != code) {
                throw e;
            }
            if (code.isMapGrepBlock) {
                throw e;
            }
            RuntimeList result = e.returnValue != null
                    ? e.returnValue.getList() : new RuntimeList();
            return coerceScalarCallResult(
                    result, effectiveContext, callContext, !isLvalueCode(code));
        }
    }

    /**
     * Dispatches {@code METHOD $self, ...} after {@code $self} is already element 0 of {@code args}.
     * Caller must unwrap TIED_SCALAR and apply {@link #acquireMethodInvocantHold}/{@link
     * #releaseMethodInvocantHold} unless another frame already retains the blessed invocant.
     */
    private static RuntimeList dispatchPerlMethodAfterSelfInjected(
            RuntimeScalar runtimeScalar,
            RuntimeScalar method,
            RuntimeScalar currentSub,
            RuntimeArray args,
            int callContext) {

        // System.out.println("call ->" + method + " " + currentPackage + " " + args + " " + callContext);

        if (method.type == RuntimeScalarType.CODE) {
            // If method is a subroutine reference, just call it
            return apply(method, args, callContext);
        }

        String methodName = method.toString();
        RuntimeScalar requestedMethod = method;

        // Unwrap READONLY_SCALAR for method dispatch.
        // Constants created via `use constant` with blessed refs go through
        // Internals::SvREADONLY which wraps the scalar in READONLY_SCALAR.
        // We must unwrap to see the actual reference type and blessId.
        RuntimeScalar invocant = runtimeScalar;
        while (invocant.type == RuntimeScalarType.READONLY_SCALAR) {
            invocant = (RuntimeScalar) invocant.value;
        }

        // Retrieve Perl class name
        String perlClassName;

        if (RuntimeScalarType.isReference(invocant)) {
            // Handle all reference types (REFERENCE, ARRAYREFERENCE, HASHREFERENCE, etc.)
            int blessId = ((RuntimeBase) invocant.value).blessId;
            if (blessId == 0) {
                if (invocant.type == GLOBREFERENCE || isReferenceToGlobInvocant(invocant)) {
                    // Auto-bless file handler to IO::File which inherits from both IO::Handle and IO::Seekable
                    // This allows GLOBs to call methods like seek, tell, etc.
                    perlClassName = "IO::File";
                    // Load the module if needed
                    // TODO - optimize by creating a flag in RuntimeIO
                    ModuleOperators.require(new RuntimeScalar("IO/File.pm"));
                } else if (invocant.type == REGEX) {
                    // qr// objects are implicitly blessed into the Regexp class in Perl 5
                    // This allows $qr->isa("Regexp"), $qr->can("..."), etc.
                    perlClassName = "Regexp";
                } else {
                    // Not auto-blessed
                    throw new PerlCompilerException("Can't call method \"" + methodName + "\" on unblessed reference");
                }
            } else {
                perlClassName = NameNormalizer.getBlessStr(blessId);
            }
        } else if (invocant.type == RuntimeScalarType.GLOB) {
            // Bare typeglob used as method invocant (e.g., *FH->print(...))
            // Auto-bless to IO::File, same as GLOBREFERENCE
            perlClassName = "IO::File";
            ModuleOperators.require(new RuntimeScalar("IO/File.pm"));
        } else if (!invocant.getDefinedBoolean()) {
            throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
        } else {
            perlClassName = invocant.toString();
            if (perlClassName.isEmpty()) {
                throw new PerlCompilerException("Can't call method \"" + methodName + "\" on an undefined value");
            }
            
            // Check if this string is a bareword filehandle (like IN, OUT, etc.)
            // If so, look up the glob and call the method on it
            String normalizedGlobName = NameNormalizer.normalizeVariableName(perlClassName, "main");
            if (GlobalVariable.isGlobalIODefined(normalizedGlobName)) {
                // This is a filehandle - get the glob reference and recurse
                RuntimeGlob glob = GlobalVariable.getGlobalIO(normalizedGlobName);
                RuntimeScalar globRef = glob.createReference();
                // Remove the invocant we already added and re-add with the glob reference
                args.elements.removeFirst();
                return call(globRef, method, currentSub, args, callContext);
            }
            
            if (perlClassName.endsWith("::")) {
                perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
            }
            if (perlClassName.startsWith("::")) {
                perlClassName = perlClassName.substring(2);
            }
            if (perlClassName.startsWith("main::")) {
                perlClassName = perlClassName.substring(6);
            }
            if (perlClassName.isEmpty()) {
                // Nothing left
                perlClassName = "main";
            }
        }

        // Method name can be:
        // - A short name (e.g., "new")
        // - Fully qualified name
        // - A variable or dereference (e.g., $file->${ \'save' })
        // - "SUPER::name"

        // Class name can be:
        // - A string
        // - STDOUT
        // - A subroutine (e.g., Class->new() is Class()->new() if Class is a subroutine)
        // - Class::->new() is the same as Class->new()
        // - Class->Other::new() fully qualified method name

        // System.out.println("call perlClassName: " + perlClassName + " methodName: " + methodName);

        if (methodName.contains("::")) {

            // Handle next::method calls
            if (methodName.equals("next::method")) {
                return NextMethod.nextMethodWithContext(args, currentSub, callContext);
            }

            // Handle next::can calls
            if (methodName.equals("next::can")) {
                return NextMethod.nextCanWithContext(args, currentSub, callContext);
            }

            // Handle maybe::next::method calls
            if (methodName.equals("maybe::next::method")) {
                return NextMethod.maybeNextMethodWithContext(args, currentSub, callContext);
            }

            // Handle SUPER::method calls
            if (methodName.startsWith("SUPER::")) {
                method = NextMethod.superMethod(currentSub, methodName, perlClassName);
            } else if (methodName.contains("::SUPER::")) {
                // Handle Package::SUPER::method syntax
                // This is used by Moo to explicitly specify which package's parent to use
                // Example: $class->GrandChild::SUPER::new(@_)
                int superIdx = methodName.indexOf("::SUPER::");
                String packageName = methodName.substring(0, superIdx);
                String actualMethod = methodName.substring(superIdx + 9); // skip "::SUPER::"
                method = InheritanceResolver.findMethodInHierarchy(
                        actualMethod,
                        packageName,
                        methodName,  // cache key includes the full qualified name
                        1   // start looking in the parent package
                );
            } else {
                // Fully qualified method name: $obj->Pkg::method(...)
                // Perl semantics: look up `method` starting in `Pkg` and
                // walk `@Pkg::ISA` via normal MRO. A direct symbol-table
                // lookup would miss methods inherited into Pkg from its
                // base classes (DBI.pm relies on this for
                // `$drh->DBD::_::dr::STORE(...)` to find STORE in
                // DBD::_::common via @DBD::_::dr::ISA).
                int sep = methodName.lastIndexOf("::");
                String targetPackage = methodName.substring(0, sep);
                String shortMethod   = methodName.substring(sep + 2);
                method = InheritanceResolver.findMethodInHierarchy(
                        shortMethod, targetPackage, methodName, 0);
                if (method == null || !isCodeDefined(method)) {
                    throw new PerlCompilerException("Undefined subroutine &" + methodName + " called");
                }
            }
        } else {
            // Regular method lookup through inheritance
            if ("__ANON__".equals(perlClassName)) {
                throw new PerlCompilerException("Can't use anonymous symbol table for method lookup");
            }
            method = InheritanceResolver.findMethodInHierarchy(methodName, perlClassName, null, 0);
        }

        if (method != null) {
            // System.out.println("call ->" + method + " " + currentPackage + " " + args + " AUTOLOAD: " + ((RuntimeCode) method.value).autoloadVariableName);

            String autoloadVariableName = ((RuntimeCode) method.value).autoloadVariableName;
            if (autoloadVariableName != null
                    && !methodName.equals("AUTOLOAD") && !methodName.endsWith("::AUTOLOAD")) {
                // The inherited method is an autoloaded subroutine
                // Set the $AUTOLOAD variable to the name of the method that was called

                // Extract class name from the original calling class (perlClassName),
                // not from the AUTOLOAD variable name. In Perl, $AUTOLOAD is set to
                // OriginalClass::method even when AUTOLOAD is inherited from a parent.
                String fullMethodName =
                        qualifyAutoloadMethodName(methodName, perlClassName);
                // Set the $AUTOLOAD variable to the fully qualified name of the method
                setAutoloadMethodName(autoloadVariableName, fullMethodName, requestedMethod);
            }

            return apply(method, args, callContext);
        }

        // If the method is not found in any class, handle special cases
        // 'import' is special in Perl - it should not throw an exception
        if (methodName.equals("import")) {
            return new RuntimeScalar().getList();
        } else {
            String errorMethodName = methodName;
            // For SUPER:: calls, strip the prefix for error reporting to match Perl behavior
            if (methodName.startsWith("SUPER::")) {
                errorMethodName = methodName.substring(7);
            } else {
                int qualifiedSuperIndex = methodName.lastIndexOf("::SUPER::");
                if (qualifiedSuperIndex >= 0) {
                    errorMethodName = methodName.substring(
                            qualifiedSuperIndex + "::SUPER::".length());
                }
            }
            throw new PerlCompilerException("Can't locate object method \"" + errorMethodName + "\" via package \"" + perlClassName + "\" (perhaps you forgot to load \"" + perlClassName + "\"?)");
        }
    }

    /**
     * Call a method in a Perl-like class hierarchy using the C3 linearization algorithm.
     *
     * @param runtimeScalar The object to call the method on.
     * @param method        The method to resolve.
     * @param currentSub    The subroutine to resolve SUPER::method in.
     * @param args          The arguments to pass to the method.
     * @param callContext   The call context.
     * @return The result of the method call.
     */
    public static RuntimeList call(RuntimeScalar runtimeScalar,
                                   RuntimeScalar method,
                                   RuntimeScalar currentSub,
                                   RuntimeArray args,
                                   int callContext) {
        // Handle tied scalars: the invocant may be a TIED_SCALAR returned
        // from a tied hash / array FETCH. Unwrap before dispatch so
        // isReference / blessId checks see the real underlying value.
        if (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) {
            return call(runtimeScalar.tiedFetch(), method, currentSub, args, callContext);
        }

        RuntimeBase invHold = acquireMethodInvocantHold(runtimeScalar);
        // insert `this` into the parameter list
        args.elements.addFirst(runtimeScalar);
        try {
            return dispatchPerlMethodAfterSelfInjected(runtimeScalar, method, currentSub, args, callContext);
        } finally {
            releaseMethodInvocantHold(invHold);
        }
    }

    /**
     * Implementation of Perl's caller() builtin.
     * This version doesn't have access to __SUB__, so it can't honor set_subname for JVM code.
     */
    public static RuntimeList caller(RuntimeList args, int ctx) {
        return callerWithSub(args, ctx, null);
    }

    /**
     * Implementation of Perl's caller() builtin with __SUB__ support.
     * When currentSub is provided, its subName is used for caller(0) to honor set_subname.
     *
     * @param args       The arguments (frame number)
     * @param ctx        The calling context
     * @param currentSub The __SUB__ reference from the calling subroutine (may be null)
     */
    public static RuntimeList callerWithSub(RuntimeList args, int ctx, RuntimeScalar currentSub) {
        RuntimeList res = new RuntimeList();
        int frame = 0;
        boolean hasExplicitExpr = !args.isEmpty();
        if (hasExplicitExpr) {
            frame = args.getFirst().getInt();
        }

        frame = callerFrameIncludingRegexPseudoBlocks(frame);

        // Save the original user-supplied frame before the JVM skip adjustment.
        // This value maps directly to hasArgsStack depth: caller(0) → depth 0 (current frame),
        // caller(1) → depth 1 (caller's frame), etc. The hasArgsStack is pushed/popped in the
        // instance apply() methods, one entry per Perl subroutine call, so the Nth entry from
        // the top corresponds to the Nth caller() frame.
        int originalFrame = frame;

        Throwable t = new Throwable();
        ExceptionFormatter.StackTraceResult result = ExceptionFormatter.formatExceptionDetailed(t);
        ArrayList<ArrayList<String>> stackTrace = result.frames();
        ArrayDeque<ArrayList<String>> syntheticFrames = syntheticCallerFrames();
        if (!syntheticFrames.isEmpty()) {
            int baseSkip = result.firstFrameFromInterpreter() ? 0 : 1;
            int insertAt = Math.min(baseSkip + 1, stackTrace.size());
            ArrayList<ArrayList<String>> framesToInsert = new ArrayList<>();
            for (Iterator<ArrayList<String>> it = syntheticFrames.descendingIterator(); it.hasNext(); ) {
                ArrayList<String> syntheticFrame = new ArrayList<>(it.next());
                if (isSyntheticOwnSubFrame(syntheticFrame)) {
                    stackTrace.add(syntheticOwnSubInsertAt(stackTrace, syntheticFrame), syntheticFrame);
                } else if (isVirtualEvalFrame(syntheticFrame)) {
                    stackTrace.add(virtualEvalInsertAt(stackTrace), syntheticFrame);
                } else {
                    framesToInsert.add(syntheticFrame);
                }
            }
            stackTrace.addAll(insertAt, framesToInsert);
        }
        java.util.ArrayList<String> javaClassNames = extractJavaClassNames(t);
        int stackTraceSize = stackTrace.size();
        // Skip the first frame for JVM-compiled code, where the first frame represents
        // the sub's own location (not the call site). For interpreter code, the first
        // frame from CallerStack already IS the call site, so no skip is needed.
        int argsFrame = frame; // Save pre-skip frame for argsStack indexing
        boolean currentFrameIsInterpreter = frame < stackTraceSize
                && stackTrace.get(frame).size() > 4
                && "interpreter".equals(stackTrace.get(frame).get(4));
        if (!currentFrameIsInterpreter) {
            currentFrameIsInterpreter = frame < javaClassNames.size()
                    && javaClassNames.get(frame) != null
                    && javaClassNames.get(frame).startsWith("interpreter:");
        }
        boolean currentFrameIsVirtualEval = frame < stackTraceSize
                && isVirtualEvalFrame(stackTrace.get(frame));
        boolean interpreterFrameBeforeVirtualEval = currentFrameIsInterpreter
                && frame + 1 < stackTraceSize
                && isVirtualEvalFrame(stackTrace.get(frame + 1));
        if (stackTraceSize > 0 && !result.firstFrameFromInterpreter()
                && !currentFrameIsVirtualEval && !interpreterFrameBeforeVirtualEval) {
            frame++;
        }

        // Check if caller() is being called from package DB (for @DB::args support).
        // In Perl 5, @DB::args is populated whenever caller() is invoked from within
        // package DB, regardless of debugger mode.
        // Two sources: (1) __SUB__.packageName for subs defined in package DB (JVM path),
        // (2) InterpreterState.currentPackage for `package DB;` inside sub body (both paths).
        boolean calledFromDB = false;
        if (currentSub != null && currentSub.type == RuntimeScalarType.CODE) {
            RuntimeCode code = (RuntimeCode) currentSub.value;
            calledFromDB = "DB".equals(code.packageName);
        }
        if (!calledFromDB) {
            calledFromDB = "DB".equals(InterpreterState.currentPackage.get().toString());
        }

        if (frame >= 0 && frame < stackTraceSize) {
            // An interpreted subroutine immediately inside an eval BLOCK has
            // its own frame directly before the synthetic eval frame.  Keep
            // the interpreted frame for caller()[3] (the callee name), but
            // take caller()[0..2] from the eval frame, which is the actual
            // Perl call site.  Advancing the whole frame would instead report
            // `(eval)` as the called subroutine.
            ArrayList<String> frameInfo = stackTrace.get(frame);
            ArrayList<String> locationFrameInfo = interpreterFrameBeforeVirtualEval
                    ? stackTrace.get(frame + 1)
                    : frameInfo;
            // Runtime stack trace
            if (ctx == RuntimeContextType.SCALAR) {
                String pkg = locationFrameInfo.getFirst();
                res.add(new RuntimeScalar(normalizeCallerPackage(pkg)));
            } else {
                int syntheticOwnSubFramesBefore = countSyntheticOwnSubFramesBefore(stackTrace, frame);
                int trackedOriginalFrame = Math.max(0, originalFrame - syntheticOwnSubFramesBefore);
                // Interpreter stack traces may contain a synthetic entry for the
                // current subroutine.  That entry is already represented by the
                // active-code stack, so do not subtract it when selecting the
                // logical caller frame.
                int trackedActiveCodeFrame = activeCodeFrameForCaller(
                        result.firstFrameFromInterpreter() ? originalFrame : trackedOriginalFrame);
                int trackedArgsFrame = Math.max(0, argsFrame - syntheticOwnSubFramesBefore);
                String pkg = locationFrameInfo.get(0);
                RuntimeCode callSiteOwner = activeCodeAtCallerFrame(trackedActiveCodeFrame + 1);
                if (callSiteOwner != null && callSiteOwner.isQuotedRegexCallback) {
                    // Calls made by a qr// callback are compiled from the
                    // regex fragment's private token stream. Use the lexical
                    // matcher package for caller() rather than a stale raw
                    // interpreter or compiler-wrapper call-site frame.
                    String callbackPackage = PerlRuntime.current().executionState()
                            .activeRegexCallbackPackages.peek();
                    if (callbackPackage != null && !callbackPackage.isEmpty()) {
                        pkg = callbackPackage;
                    } else if (callSiteOwner.packageName != null
                            && !callSiteOwner.packageName.isEmpty()) {
                        pkg = callSiteOwner.packageName;
                    }
                }
                res.add(new RuntimeScalar(normalizeCallerPackage(pkg)));  // package
                res.add(new RuntimeScalar(locationFrameInfo.get(1)));  // filename
                String locationLine = locationFrameInfo.get(2);
                if (callSiteOwner != null && callSiteOwner.isQuotedRegexCallback
                        && callSiteOwner.cvStartLine > 0) {
                    locationLine = Integer.toString(callSiteOwner.cvStartLine);
                }
                res.add(new RuntimeScalar(locationLine));  // line

                // Perl's caller() without EXPR returns only 3 elements: (package, filename, line).
                // caller(EXPR) returns 11 elements including subroutine name, hasargs, etc.
                if (hasExplicitExpr) {

                // The subroutine name at frame N is actually stored at frame N-1
                // because it represents the sub that IS CALLING frame N
                String subName = null;
                String frameSubName = frameInfo.size() > 3 ? frameInfo.get(3) : null;
                boolean virtualEvalFrame = frameInfo.size() > 4
                        && "virtual-eval".equals(frameInfo.get(4));
                boolean syntheticOwnSubFrame = frameInfo.size() > 4
                        && "synthetic-own-sub".equals(frameInfo.get(4));

                // Synthetic frames such as eval blocks describe the frame itself,
                // not the previous caller, so preserve their own subroutine name.
                if ((syntheticOwnSubFrame || virtualEvalFrame && frameSubName != null && frameSubName.startsWith("("))) {
                    subName = frameSubName;
                }

                // The active-code stack can describe the anonymous wrapper
                // used to execute an eval.  Preserve the stack trace's Perl
                // `(eval)` marker before applying the generic __ANON__
                // fallback, otherwise caller()[3] and caller()[4] both acquire
                // ordinary-sub semantics for eval BLOCK and eval STRING.
                String previousFrameSubName = null;
                if (frame > 0 && frame - 1 < stackTraceSize) {
                    ArrayList<String> previousFrame = stackTrace.get(frame - 1);
                    if (previousFrame.size() > 3) {
                        previousFrameSubName = previousFrame.get(3);
                    }
                }
                boolean previousFrameIsEval = previousFrameSubName != null
                        && previousFrameSubName.startsWith("(eval");

                // Ordinary interpreter entries describe the subroutine whose
                // own bytecode produced that frame. After the normal own-frame
                // skip, the previous formatted entry is therefore the Perl
                // caller name. The frame immediately before a virtual eval is
                // deliberately not skipped, so its own name is authoritative.
                // Prefer this source-level metadata over activeCodeStack, whose
                // eval compiler wrappers can be one logical frame out of phase.
                if (subName == null && currentFrameIsInterpreter) {
                    String interpreterSubName = interpreterFrameBeforeVirtualEval
                            ? frameSubName : previousFrameSubName;
                    if (interpreterSubName != null && !interpreterSubName.startsWith("(eval")) {
                        subName = interpreterSubName;
                    }
                }

                RuntimeCode activeCode = activeCodeAtCallerFrame(trackedActiveCodeFrame);
                if (virtualEvalFrame && activeCode != null) {
                    // A synthetic eval frame can occupy the formatted slot for
                    // a still-active named subroutine. At that same logical
                    // depth the active-code stack is authoritative; only keep
                    // `(eval)` when no named Perl code exists (the next outer
                    // caller really is the eval itself).
                    String activeSubName = callerSubNameForCode(activeCode);
                    if (activeSubName != null && !activeSubName.startsWith("(eval")) {
                        subName = activeSubName;
                    }
                }
                if (subName == null && activeCode != null) {
                    subName = callerSubNameForCode(activeCode);
                    if (subName == null && !activeCode.explicitlyRenamed
                            && activeCode.packageName != null && !previousFrameIsEval) {
                        subName = normalizeCallerPackage(activeCode.packageName) + "::__ANON__";
                    }
                }

                if (activeCode != null && activeCode.isQuotedRegexCallback
                        && !previousFrameIsEval) {
                    // The interpreter may retain the package of an earlier qr//
                    // compiler wrapper in its formatted frame.  The callback is
                    // nevertheless an anonymous CV in the package where this
                    // regex was quoted, which the matcher records for the whole
                    // callback invocation.
                    String callbackPackage = PerlRuntime.current().executionState()
                            .activeRegexCallbackPackages.peek();
                    if (callbackPackage != null && !callbackPackage.isEmpty()) {
                        subName = normalizeCallerPackage(callbackPackage) + "::__ANON__";
                    }
                }
                
                // For the innermost frame (frame == 1 after skip), check currentSub first
                // to honor set_subname() which modifies RuntimeCode.subName at runtime
                if (subName == null && frame == 1 && currentSub != null && currentSub.type == RuntimeScalarType.CODE) {
                    RuntimeCode code = (RuntimeCode) currentSub.value;
                    if (code.subName != null && !code.subName.isEmpty()) {
                        String codePkg = code.packageName != null ? code.packageName : "main";
                        subName = codePkg + "::" + code.subName;
                    } else if (!code.explicitlyRenamed && code.packageName != null) {
                        // Anonymous sub: honor `local *PKG::__ANON__ = 'name'`
                        // by reading the package's *__ANON__ glob's nameOverride.
                        // See dev/modules/anon_sub_naming.md.
                        RuntimeGlob anonGlob = GlobalVariable.peekGlobalIO(
                                code.packageName + "::__ANON__");
                        if (anonGlob != null && anonGlob.nameOverride != null
                                && !anonGlob.nameOverride.isEmpty()) {
                            subName = code.packageName + "::" + anonGlob.nameOverride;
                        }
                    }
                }
                
                // Fall back to stack trace info
                if (subName == null && previousFrameSubName != null) {
                    subName = previousFrameSubName;
                }

                // In Perl, caller() always returns a defined subroutine name.
                // For anonymous code blocks (closures, callbacks), use __ANON__.
                if (subName == null || subName.isEmpty()) {
                    if (frame > 0 && frame - 1 < stackTraceSize) {
                        String prevPkg = stackTrace.get(frame - 1).getFirst();
                        subName = (prevPkg != null && !prevPkg.isEmpty() ? prevPkg : "main") + "::__ANON__";
                    }
                }

                // Honor `local *PKG::__ANON__ = 'name'` for any anonymous-sub
                // frame, not just the innermost one. After both fallbacks,
                // an anon frame ends up as "Pkg::__ANON__"; if the package's
                // *__ANON__ glob currently has a name override active, swap
                // it in. See dev/modules/anon_sub_naming.md.
                subName = applyAnonNameOverride(subName);

                if (subName != null && !subName.isEmpty()) {
                    res.add(new RuntimeScalar(subName));  // subroutine
                } else {
                    res.add(RuntimeScalarCache.scalarUndef);
                }

                // Populate @DB::args when caller() is called from package DB
                // Carp.pm relies on this to get function arguments for stack traces
                //
                // Phase 2 (refcount_alignment_plan.md): populate with
                // setFromListAliased so @DB::args entries are aliases (non-counting
                // references). This matches Perl 5's semantics — @DB::args shares
                // SV slots with the caller's @_, not counted copies — and allows
                // user code like `push @kept, @DB::args` to create real counted
                // refs in @kept while the @DB::args slots remain aliases. Required
                // for DBIC's Devel::StackTrace-resurrection test (txn_scope_guard
                // test 18).
                if (calledFromDB) {
                    RuntimeArray dbArgs = GlobalVariable.getGlobalArray("DB::args");
                    if (DebugState.isDebugMode()) {
                        RuntimeArray frameArgs = DebugState.getArgsForFrame(frame);
                        if (frameArgs != null) {
                            dbArgs.setFromListAliased(frameArgs.getList());
                        } else {
                            dbArgs.setFromListAliased(new RuntimeList());
                        }
                    } else {
                        // Not in debug mode — use the pristineArgsStack snapshot
                        // (via getOriginalArgsAt) instead of the live argsStack, so
                        // that callees which do `shift(@_)` don't clear @DB::args
                        // out from under the caller. Perl preserves the invocation
                        // args here — critical for DBIC TxnScopeGuard double-DESTROY
                        // detection.
                        RuntimeArray frameArgs = getOriginalArgsAt(trackedArgsFrame);
                        RuntimeArray codeFrameArgs = getOriginalArgsForCode(activeCode);
                        if (codeFrameArgs != null) {
                            frameArgs = codeFrameArgs;
                        }
                        if (WarnDie.isInsideUnhandledDieHandler() && syntheticOwnSubFramesBefore > 0) {
                            RuntimeArray activeFrameArgs = getOriginalArgsAt(trackedActiveCodeFrame);
                            if (activeFrameArgs != null) {
                                frameArgs = activeFrameArgs;
                            }
                        } else if (frameArgs == null && WarnDie.isInsideUnhandledDieHandler()) {
                            frameArgs = getOriginalArgsAt(trackedActiveCodeFrame);
                        }
                        if (frameArgs != null) {
                            dbArgs.setFromListAliased(frameArgs.getList());
                        } else {
                            dbArgs.setFromListAliased(new RuntimeList());
                        }
                    }
                }

                // Add hasargs (element 4): whether @_ was freshly created for this call.
                // In Perl 5, this is 1 for func(args) and &func(args), but false/empty
                // for &func (no parens) which inherits the caller's @_.
                // We consult hasArgsStack which is pushed in the instance apply() methods:
                //   - apply(RuntimeArray, int) pushes false  (shared args / &func)
                //   - apply(String, RuntimeArray, int) pushes true  (fresh args / func())
                // Fall back to the name-based heuristic for frames outside our tracking
                // (e.g., top-level code, eval frames).
                Boolean hasArgsFromStack = getHasArgsAt(trackedOriginalFrame);
                if (WarnDie.isInsideUnhandledDieHandler() && syntheticOwnSubFramesBefore > 0) {
                    Boolean activeHasArgs = getHasArgsAt(trackedActiveCodeFrame);
                    if (activeHasArgs != null) {
                        hasArgsFromStack = activeHasArgs;
                    }
                } else if (hasArgsFromStack == null && WarnDie.isInsideUnhandledDieHandler()) {
                    hasArgsFromStack = getHasArgsAt(trackedActiveCodeFrame);
                }
                if (hasArgsFromStack != null) {
                    res.add(hasArgsFromStack ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarUndef);
                } else {
                    // Fallback: assume hasargs=true for named subs, false for eval
                    boolean hasArgs = subName != null && !subName.isEmpty() && 
                                      !subName.equals("(eval)") && !subName.endsWith("::(eval)");
                    res.add(hasArgs ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarUndef);
                }

                // Add wantarray (element 5): undef for void, 0 for scalar, 1 for list.
                Integer frameCallContext = getCallContextAt(trackedOriginalFrame);
                if (WarnDie.isInsideUnhandledDieHandler() && syntheticOwnSubFramesBefore > 0) {
                    Integer activeCallContext = getCallContextAt(trackedActiveCodeFrame);
                    if (activeCallContext != null) {
                        frameCallContext = activeCallContext;
                    }
                } else if (frameCallContext == null && WarnDie.isInsideUnhandledDieHandler()) {
                    frameCallContext = getCallContextAt(trackedActiveCodeFrame);
                }
                res.add(callerWantarrayScalar(frameCallContext));

                // Add evaltext (element 6): The eval text if inside eval STRING
                // For eval {...}, this is undef; for eval "...", this is the string
                // Check if filename looks like an eval (e.g., "(eval 123)")
                String filename = frameInfo.get(1);
                if (filename != null && filename.startsWith("(eval ") && filename.endsWith(")")) {
                    // This is an eval frame - we don't have the actual text, return empty string
                    // Perl uses "" for eval {} and actual text for eval "..."
                    res.add(RuntimeScalarCache.scalarUndef);
                } else {
                    res.add(RuntimeScalarCache.scalarUndef);
                }

                // Add is_require (element 7): 1 if inside require/use, undef otherwise
                // We don't currently distinguish require from regular code
                res.add(RuntimeScalarCache.scalarUndef);

                // Add hints (element 8): Compile-time $^H value
                // Use per-call-site hints from callerHintsStack
                int hints = WarningBitsRegistry.getCallerHintsAtFrame(frame - 1);
                res.add(new RuntimeScalar(hints >= 0 ? hints : 0));

                // Add bitmask (element 9): Compile-time warnings bitmask
                // First try per-call-site bits from callerBitsStack (accurate per-statement)
                // frame is 1-based here (after skip increment), callerBitsStack is 0-based
                String warningBits = WarningBitsRegistry.getCallerBitsAtFrame(frame - 1);
                if (warningBits == null) {
                    // Fall back to per-class bits
                    if (frame < javaClassNames.size()) {
                        String className = javaClassNames.get(frame);
                        if (className != null) {
                            warningBits = WarningBitsRegistry.get(className);
                        }
                    }
                }
                if (warningBits != null) {
                    res.add(new RuntimeScalar(warningBits));
                } else {
                    res.add(RuntimeScalarCache.scalarUndef);
                }

                // Add hinthash (element 10): Compile-time %^H hash reference
                // Use the per-call-site hint hash registry to get the %^H snapshot
                // that was active when the calling code was compiled.
                // Falls back to the global %^H for compile-time calls (BEGIN blocks).
                java.util.Map<String, String> callerHintHash = HintHashRegistry.getCallerHintHashAtFrame(frame - 1);
                if (callerHintHash != null) {
                    RuntimeHash snapshot = new RuntimeHash();
                    for (java.util.Map.Entry<String, String> entry : callerHintHash.entrySet()) {
                        snapshot.elements.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
                    }
                    res.add(snapshot.createReference());
                } else {
                    // Fallback: check global %^H (for compile-time calls in BEGIN blocks)
                    RuntimeHash hintHash = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
                    if (!hintHash.elements.isEmpty()) {
                        RuntimeHash snapshot = new RuntimeHash();
                        snapshot.setFromList(hintHash.getList());
                        res.add(snapshot.createReference());
                    } else {
                        res.add(RuntimeScalarCache.scalarUndef);
                    }
                }
                } // end if (hasExplicitExpr)
            }
        } else if (frame >= stackTraceSize) {
            int trackedOriginalFrame = Math.max(0, originalFrame - countSyntheticOwnSubFramesBefore(stackTrace, stackTrace.size()));
            RuntimeCode activeCode = hasExplicitExpr
                    ? activeCodeAtCallerFrame(activeCodeFrameForCaller(
                            result.firstFrameFromInterpreter() ? originalFrame : trackedOriginalFrame))
                    : null;
            String activeSubName = activeCode != null
                    ? applyAnonNameOverride(callerSubNameForCode(activeCode))
                    : null;
            if (activeCode != null && !calledFromDB
                    && hasExplicitlyRenamedActiveCode()
                    && activeSubName != null && !activeSubName.isEmpty()) {
                String pkg = normalizeCallerPackage(activeCode.packageName);
                if (ctx == RuntimeContextType.SCALAR) {
                    res.add(new RuntimeScalar(pkg));
                } else {
                    res.add(new RuntimeScalar(pkg));
                    res.add(new RuntimeScalar(activeCode.cvStartFile != null ? activeCode.cvStartFile : ""));
                    res.add(new RuntimeScalar(activeCode.cvStartLine));
                    res.add(new RuntimeScalar(activeSubName));
                    Boolean hasArgsFromStack = getHasArgsAt(trackedOriginalFrame);
                    res.add(hasArgsFromStack != null && hasArgsFromStack
                            ? RuntimeScalarCache.scalarTrue
                            : RuntimeScalarCache.scalarUndef);
                    res.add(RuntimeScalarCache.scalarUndef);
                    res.add(RuntimeScalarCache.scalarUndef);
                    res.add(RuntimeScalarCache.scalarUndef);
                    int hints = WarningBitsRegistry.getCallerHintsAtFrame(frame - 1);
                    res.add(new RuntimeScalar(hints >= 0 ? hints : 0));
                    String warningBits = WarningBitsRegistry.getCallerBitsAtFrame(frame - 1);
                    res.add(warningBits != null
                            ? new RuntimeScalar(warningBits)
                            : RuntimeScalarCache.scalarUndef);
                    java.util.Map<String, String> callerHintHash = HintHashRegistry.getCallerHintHashAtFrame(frame - 1);
                    if (callerHintHash != null) {
                        RuntimeHash snapshot = new RuntimeHash();
                        for (java.util.Map.Entry<String, String> entry : callerHintHash.entrySet()) {
                            snapshot.elements.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
                        }
                        res.add(snapshot.createReference());
                    } else {
                        res.add(RuntimeScalarCache.scalarUndef);
                    }
                }
                return res;
            }
            // Fallback: check CallerStack for synthetic frames pushed during compile-time
            // operations (e.g., MODIFY_*_ATTRIBUTES called from Java).
            // The excess frames beyond the Java stack trace are served from CallerStack.
            // Skip entries already consumed by ExceptionFormatter to avoid duplicates.
            int callerStackFrame = frame - stackTraceSize + result.callerStackConsumed();
            CallerStack.CallerInfo info = CallerStack.peek(callerStackFrame);
            if (info != null) {
                if (ctx == RuntimeContextType.SCALAR) {
                    res.add(new RuntimeScalar(normalizeCallerPackage(info.packageName())));
                } else {
                    res.add(new RuntimeScalar(normalizeCallerPackage(info.packageName())));
                    res.add(new RuntimeScalar(info.filename()));
                    res.add(new RuntimeScalar(info.line()));
                }
            }
        }
        return res;
    }

    private static String callerSubNameForCode(RuntimeCode code) {
        if (code == null || code.subName == null || code.subName.isEmpty()
                || code.subName.startsWith("(")) {
            if (code != null) {
                String registeredName = registeredCodeName(code);
                if (registeredName != null) {
                    return registeredName;
                }
            }
            return null;
        }
        if (code.subName.contains("::")) {
            return code.subName;
        }
        String pkg = normalizeCallerPackage(code.packageName);
        return pkg + "::" + code.subName;
    }

    /**
     * Interpreter calls through imported or prototyped aliases may retain the
     * code object without retaining its declared subName.  Recover the Perl
     * name from the live symbol table before reporting an anonymous frame.
     */
    private static String registeredCodeName(RuntimeCode code) {
        long codeRefVersion = GlobalVariable.codeRefVersion();
        if (code.registeredCodeNameCacheVersion == codeRefVersion) {
            return code.registeredCodeNameCache;
        }
        String packagePrefix = code.packageName == null || code.packageName.isEmpty()
                ? null : code.packageName + "::";
        String fallback = verifiedRegisteredCodeName(code, code.registeredCodeNameCache);
        if (fallback != null && (packagePrefix == null || fallback.startsWith(packagePrefix))) {
            code.registeredCodeNameCacheVersion = codeRefVersion;
            return fallback;
        }
        for (Map.Entry<String, RuntimeScalar> entry : GlobalVariable.globalCodeRefs.entrySet()) {
            RuntimeScalar value = entry.getValue();
            if (value == null || value.type != RuntimeScalarType.CODE || value.value != code) {
                continue;
            }
            String name = entry.getKey();
            if (packagePrefix != null && name.startsWith(packagePrefix)) {
                code.registeredCodeNameCache = name;
                code.registeredCodeNameCacheVersion = codeRefVersion;
                return name;
            }
            if (fallback == null) {
                fallback = name;
            }
        }
        code.registeredCodeNameCache = fallback;
        code.registeredCodeNameCacheVersion = codeRefVersion;
        return fallback;
    }

    private static String verifiedRegisteredCodeName(RuntimeCode code, String name) {
        if (name == null) return null;
        RuntimeScalar value = GlobalVariable.globalCodeRefs.get(name);
        if (value != null && value.type == RuntimeScalarType.CODE && value.value == code) {
            return name;
        }
        code.registeredCodeNameCache = null;
        return null;
    }

    private static int activeCodeFrameForCaller(int originalFrame) {
        return originalFrame + (WarnDie.isInsideUnhandledDieHandler() ? 1 : 0);
    }

    /**
     * Return the active code for a logical Perl caller frame.
     *
     * The interpreter can enter the same InterpretedCode through both the
     * compiler-supplied wrapper and the interpreted body.  That leaves
     * adjacent duplicate RuntimeCode entries on activeCodeStack, even though
     * Perl sees one call frame.  Collapse only adjacent duplicates here so
     * caller(N) remains expressed in Perl frames without changing the stack
     * used by lifetime tracking.
     */
    private static RuntimeCode activeCodeAtCallerFrame(int logicalFrame) {
        if (logicalFrame < 0) {
            return null;
        }
        RuntimeCode previous = null;
        int logicalIndex = 0;
        for (RuntimeCode active : activeCodeStack()) {
            if (active == previous || isCompilerWrapperPair(active, previous)) {
                continue;
            }
            if (logicalIndex++ == logicalFrame) {
                return active;
            }
            previous = active;
        }
        return null;
    }

    /** Map a Perl-visible caller depth to the implementation stack depth. */
    private static int callerFrameIncludingRegexPseudoBlocks(int logicalFrame) {
        if (logicalFrame < 0) return logicalFrame;
        RuntimeCode previous = null;
        int physical = 0;
        int visible = 0;
        for (RuntimeCode active : activeCodeStack()) {
            if (active == previous || isCompilerWrapperPair(active, previous)) {
                continue;
            }
            previous = active;
            if (active.isRegexCallbackPseudoBlock && !active.isQuotedRegexCallback) {
                physical++;
                continue;
            }
            if (visible++ == logicalFrame) return physical;
            physical++;
        }
        return logicalFrame + (physical - visible);
    }

    public static RuntimeCode getActiveCodeAtCallerFrame(int logicalFrame) {
        return activeCodeAtCallerFrame(logicalFrame);
    }

    /**
     * Return the outermost runtime frame implementing one logical Perl caller.
     * Lvalue/interpreted subs can have an adjacent body + compiler-wrapper pair;
     * non-local returns must cross both before they have left the Perl sub.
     */
    public static RuntimeCode getOutermostActiveCodeAtCallerFrame(int logicalFrame) {
        if (logicalFrame < 0) return null;
        RuntimeCode previous = null;
        RuntimeCode candidate = null;
        int logicalIndex = -1;
        for (RuntimeCode active : activeCodeStack()) {
            boolean sameFrame = previous != null
                    && (active == previous || isCompilerWrapperPair(active, previous));
            if (!sameFrame) {
                logicalIndex++;
                if (logicalIndex > logicalFrame) break;
            }
            if (logicalIndex == logicalFrame) candidate = active;
            previous = active;
        }
        return candidate;
    }

    /**
     * Resolve a PadWalker caller level from inside its Perl wrapper.
     * Java builtins and map/grep block CVs are runtime implementation frames,
     * not Perl subroutine levels, and therefore must not shift LEVEL.
     */
    public static RuntimeCode getActiveCodeAtPadWalkerFrame(int level) {
        if (level < 0) return null;
        RuntimeCode previous = null;
        int plumbingFrames = 2; // Internals::jperl_var_name and PadWalker::var_name
        int logicalIndex = 0;
        for (RuntimeCode active : activeCodeStack()) {
            if (active == previous || isCompilerWrapperPair(active, previous)) {
                continue;
            }
            previous = active;
            if (active.isBuiltin || active.isMapGrepBlock) continue;
            if (plumbingFrames > 0) {
                plumbingFrames--;
                continue;
            }
            if (logicalIndex++ == level) return active;
        }
        return null;
    }

    private static boolean isCompilerWrapperPair(RuntimeCode left, RuntimeCode right) {
        return left != null && right != null
                && Objects.equals(left.packageName, right.packageName)
                && Objects.equals(left.subName, right.subName)
                && (left instanceof org.perlonjava.backend.bytecode.InterpretedCode)
                        != (right instanceof org.perlonjava.backend.bytecode.InterpretedCode);
    }

    private static boolean isSyntheticOwnSubFrame(ArrayList<String> frame) {
        return frame.size() > 4 && "synthetic-own-sub".equals(frame.get(4));
    }

    private static boolean isVirtualEvalFrame(ArrayList<String> frame) {
        return frame.size() > 4 && "virtual-eval".equals(frame.get(4));
    }

    /** Place an eval outside the contiguous interpreted calls executing inside it. */
    private static int virtualEvalInsertAt(ArrayList<ArrayList<String>> stackTrace) {
        int index = 0;
        while (index < stackTrace.size()) {
            ArrayList<String> frame = stackTrace.get(index);
            if (frame.size() <= 4 || !"interpreter".equals(frame.get(4))) {
                break;
            }
            index++;
        }
        return index;
    }

    private static int syntheticOwnSubInsertAt(ArrayList<ArrayList<String>> stackTrace, ArrayList<String> syntheticFrame) {
        String syntheticPackage = syntheticFrame.isEmpty() ? null : syntheticFrame.get(0);
        String syntheticFile = syntheticFrame.size() > 1 ? syntheticFrame.get(1) : null;
        for (int i = 0; i < stackTrace.size(); i++) {
            ArrayList<String> frame = stackTrace.get(i);
            String framePackage = !frame.isEmpty() ? frame.get(0) : null;
            String frameFile = frame.size() > 1 ? frame.get(1) : null;
            if (Objects.equals(syntheticFile, frameFile)
                    && (Objects.equals(syntheticPackage, framePackage)
                    || syntheticPackage == null || syntheticPackage.isEmpty())) {
                return i;
            }
        }
        for (int i = 0; i < stackTrace.size(); i++) {
            ArrayList<String> frame = stackTrace.get(i);
            String frameFile = frame.size() > 1 ? frame.get(1) : null;
            if (Objects.equals(syntheticFile, frameFile)) {
                return i;
            }
        }
        return stackTrace.size();
    }

    private static int countSyntheticOwnSubFramesBefore(ArrayList<ArrayList<String>> stackTrace, int frame) {
        int count = 0;
        int end = Math.min(frame, stackTrace.size());
        for (int i = 0; i < end; i++) {
            if (isSyntheticOwnSubFrame(stackTrace.get(i))) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasExplicitlyRenamedActiveCode() {
        for (RuntimeCode active : activeCodeStack()) {
            if (active.explicitlyRenamed) {
                return true;
            }
        }
        return false;
    }

    private static String applyAnonNameOverride(String subName) {
        if (subName != null && subName.endsWith("::__ANON__")) {
            String anonPkg = subName.substring(0,
                    subName.length() - "::__ANON__".length());
            RuntimeGlob anonGlob = GlobalVariable.peekGlobalIO(
                    anonPkg + "::__ANON__");
            if (anonGlob != null && anonGlob.nameOverride != null
                    && !anonGlob.nameOverride.isEmpty()) {
                return anonPkg + "::" + anonGlob.nameOverride;
            }
        }
        return subName;
    }

    private static String normalizeCallerPackage(String packageName) {
        return packageName == null || packageName.isEmpty() ? "main" : packageName;
    }

    /**
     * Returns the appropriate error prefix for undefined subroutine errors.
     * For tail calls (goto &sub), returns "Goto u" so message is "Goto undefined...".
     * For regular calls, returns "U" so message is "Undefined...".
     */
    private static String gotoErrorPrefix(String subroutineName) {
        return "tailcall".equals(subroutineName) ? "Goto u" : "U";
    }

    /**
     * Extracts Java class names from a Throwable's stack trace, parallel to
     * how ExceptionFormatter.formatException produces Perl frames.
     * This allows caller() to look up warning bits from WarningBitsRegistry.
     *
     * @param t The Throwable containing the stack trace
     * @return List of Java class names, one per Perl frame in same order as formatException
     */
    private static java.util.ArrayList<String> extractJavaClassNames(Throwable t) {
        java.util.ArrayList<String> classNames = new java.util.ArrayList<>();
        java.util.HashSet<String> seenLocations = new java.util.HashSet<>();
        
        // Track interpreter frames similar to ExceptionFormatter
        var interpreterFrames = InterpreterState.getStack();
        int interpreterFrameIndex = 0;
        boolean addedFrameForCurrentLevel = false;
        
        for (var element : t.getStackTrace()) {
            if (element.getClassName().equals("org.perlonjava.frontend.parser.StatementParser") &&
                    element.getMethodName().equals("parseUseDeclaration")) {
                // Use statement - no class name for warning bits lookup
                classNames.add(null);
            } else if (element.getClassName().equals("org.perlonjava.backend.bytecode.InterpretedCode") &&
                    element.getMethodName().equals("apply")) {
                // InterpretedCode.apply marks the END of a Perl call level
                if (addedFrameForCurrentLevel) {
                    interpreterFrameIndex++;
                    addedFrameForCurrentLevel = false;
                }
            } else if (element.getClassName().equals("org.perlonjava.backend.bytecode.BytecodeInterpreter") &&
                    element.getMethodName().equals("execute")) {
                // Interpreter frame - use InterpretedCode's class for warning bits lookup
                if (!addedFrameForCurrentLevel && interpreterFrameIndex < interpreterFrames.size()) {
                    var frame = interpreterFrames.get(interpreterFrameIndex);
                    if (frame != null && frame.code() != null) {
                        // For interpreter, warning bits come from InterpretedCode.warningBits
                        // For now, we use the code's identifier as a pseudo-class name
                        String codeId = "interpreter:" + System.identityHashCode(frame.code());
                        classNames.add(codeId);
                        addedFrameForCurrentLevel = true;
                    }
                }
            } else if (element.getClassName().contains("org.perlonjava.anon") ||
                    element.getClassName().contains("org.perlonjava.runtime.perlmodule")) {
                // JVM frame - use the actual class name for warning bits lookup
                // Use source location key to avoid duplicates (same logic as ExceptionFormatter)
                String locationKey = element.getFileName() + ":" + element.getLineNumber();
                if (!seenLocations.contains(locationKey)) {
                    seenLocations.add(locationKey);
                    classNames.add(element.getClassName());
                }
            }
        }
        
        return classNames;
    }

    // Method to apply (execute) a subroutine reference
    //
    // Iterative trampoline: all dispatch-chain cases (TIED_SCALAR, READONLY,
    // GLOB, STRING, overload, AUTOLOAD, TAILCALL from `goto &func`) loop
    // back to the top of this method instead of recursing, so long chains
    // of `goto &func` (common in Moo/DBIC/Sub::Defer) stay O(1) in Java
    // stack depth. Previously the tailcall path recursed into apply() which
    // grew the stack O(N) in the chain length and overflowed on large
    // DBIC test runs (t/60core.t, t/96_is_deteministic_value.t,
    // t/cdbi/68-inflate_has_a.t).
    public static RuntimeList apply(RuntimeScalar runtimeScalar, RuntimeArray a, int callContext) {
        // Java/native callback entry points normally dispatch through this
        // static facade.  Bind before any warning/caller/cleanup facade is
        // consulted; binding only inside RuntimeCode.apply(instance) is too
        // late for a callback arriving on an otherwise unbound provider thread.
        if (runtimeScalar != null && runtimeScalar.type == RuntimeScalarType.CODE
                && runtimeScalar.value instanceof RuntimeCode callback
                && callback.boundRuntime != null
                && PerlRuntime.currentOrNull() != callback.boundRuntime) {
            try (PerlRuntime.Binding ignored = callback.boundRuntime.bind()) {
                return apply(runtimeScalar, a, callContext);
            }
        }
        // NOTE: flush() was removed from here. Return values from nested calls
        // (e.g., receiver(coerce => quote_sub(...))) may have pending refCount
        // decrements from their scope exits. Flushing here would decrement them
        // to 0 and call clearWeakRefsTo before the callee captures them, breaking
        // weak ref tracking (Sub::Quote/Sub::Defer pattern). DESTROY still fires
        // at the next setLarge() or popAndFlush() — typically inside the callee.

        // Local copies that the trampoline can mutate across iterations.
        RuntimeScalar curScalar = runtimeScalar;
        RuntimeArray curArgs = a;
        boolean curArgsFromTailCall = false;

        while (true) {
        // Handle tied scalars - fetch the underlying value first
        if (curScalar.type == RuntimeScalarType.TIED_SCALAR) {
            curScalar = curScalar.tiedFetch();
            continue;
        }
        if (curScalar.type == READONLY_SCALAR) {
            curScalar = (RuntimeScalar) curScalar.value;
            continue;
        }
        // Check if the type of this RuntimeScalar is CODE
        if (curScalar.type == RuntimeScalarType.CODE) {
            RuntimeCode code = (RuntimeCode) curScalar.value;

            // Check for closure prototype — calling one should die
            if (code.isClosurePrototype) {
                throw new PerlDieException(new RuntimeScalar("Closure prototype called"));
            }

            // CRITICAL: Run compilerSupplier BEFORE checking defined()
            // The compilerSupplier may replace curScalar.value with InterpretedCode
            if (code.compilerSupplier != null) {
                RuntimeList savedConstantValue = code.constantValue;
                java.util.List<String> savedAttributes = code.attributes;
                code.compilerSupplier.get();
                // Reload code from curScalar.value in case it was replaced
                code = (RuntimeCode) curScalar.value;
                // Transfer fields that were set on the old code (e.g., by :const attribute)
                if (savedConstantValue != null && code.constantValue == null) {
                    code.constantValue = savedConstantValue;
                }
                restoreLazyAttributes(code, savedAttributes);
            }

            // Check if it's an unfilled forward declaration (not defined)
            if (!code.defined()) {
                // Lazily generate CORE:: subroutine wrappers on first call
                if ("CORE".equals(code.packageName) && code.subName != null) {
                    boolean generated = CoreSubroutineGenerator.generateWrapper(code.subName);
                    if (generated) {
                        // Reload code after wrapper generation
                        curScalar = GlobalVariable.getGlobalCodeRef("CORE::" + code.subName);
                        code = (RuntimeCode) curScalar.value;
                        if (code.defined()) {
                            // Fall through to normal execution below
                        }
                    }
                }
            }
            RuntimeScalar lateResolved = resolveLateDefinedForwardCodeRef(curScalar, code);
            if (lateResolved != null) {
                curScalar = lateResolved;
                continue;
            }
            if (!code.defined()) {
                // Try to find AUTOLOAD for this subroutine
                String subroutineName = code.packageName + "::" + code.subName;
                if (code.packageName != null && code.subName != null && !subroutineName.isEmpty()) {
                    // If this is an imported forward declaration, check AUTOLOAD in the source package FIRST
                    // This matches Perl semantics where imported subs resolve via the exporting package's AUTOLOAD
                    if (code.sourcePackage != null && !code.sourcePackage.equals(code.packageName)) {
                        String sourceAutoloadString = code.sourcePackage + "::AUTOLOAD";
                        RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                        if (isCodeDefined(sourceAutoload)) {
                            // Set $AUTOLOAD name to the original package function name
                            String sourceSubroutineName = code.sourcePackage + "::" + code.subName;
                            getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                            // Call AUTOLOAD from the source package (iterative)
                            curScalar = sourceAutoload;
                            continue;
                        }
                    }

                    // Then check if AUTOLOAD exists in the current package
                    String autoloadString = code.packageName + "::AUTOLOAD";
                    RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                    if (isCodeDefined(autoload)) {
                        // Set $AUTOLOAD — in the package where the AUTOLOAD sub
                        // was compiled, not in the package we looked it up from
                        // (see autoloadVarFor() for details).
                        getGlobalVariable(autoloadVarFor(autoload, code.packageName)).set(subroutineName);
                        // Call AUTOLOAD (iterative — continue the outer dispatch
                        // loop rather than recursing into apply(), to avoid
                        // Java-stack growth on long AUTOLOAD chains).
                        curScalar = autoload;
                        continue;
                    }
                }
                throw new PerlCompilerException("Undefined subroutine &" + subroutineName + " called");
            }
            String resolvedSubroutineName = code.packageName != null && code.subName != null
                    ? code.packageName + "::" + code.subName
                    : null;
            requireLvalueCallable(code, callContext, resolvedSubroutineName);
            int effectiveContext = effectiveCallContext(code, callContext);
            // Look up warning bits for the code's class and push to context stack
            // This enables FATAL warnings to work even at top-level (no caller frame)
            org.perlonjava.runtime.CompilationRuntimeState compilationState =
                    PerlRuntime.current().compilationState;
            String warningBits = getWarningBitsForCode(code, compilationState);
            if (warningBits != null) {
                WarningBitsRegistry.pushCurrent(warningBits, compilationState);
            }
            // Save caller's call-site warning bits so caller()[9] can retrieve them
            WarningBitsRegistry.pushCallerBits(compilationState);
            // Save caller's $^H so caller()[8] can retrieve them
            WarningBitsRegistry.pushCallerHints(compilationState);
            // Save caller's call-site hint hash so caller()[10] can retrieve them
            HintHashRegistry.pushCallerHintHash(compilationState);
            int cleanupMark = MyVarCleanupStack.pushMark();
            // Establish a function-scoped mortal boundary so that
            // statement-boundary flushAboveMark() inside this function
            // only processes entries from this scope, not entries from
            // the caller (e.g., bless mortal entries for method chain
            // temporaries like Foo->new()->method()).
            MortalList.pushMark();
            // Holds the tailcall target if the body returns one. Populated
            // inside the try block; after the finally runs we loop back
            // to the top of apply() instead of recursing, preventing
            // Java-stack growth on long `goto &func` chains.
            RuntimeScalar nextTailCode = null;
            RuntimeArray nextTailArgs = null;
            boolean cleanupArgsAfterCall = curArgsFromTailCall;
            RuntimeScalar codeRefForCall = curScalar;
            RuntimeArray argsForCall = curArgs;
            try {
                // Cast the value to RuntimeCode and call apply()
                RuntimeList result = code.apply(argsForCall, callContext);
                // Handle tail calls (goto &func).
                // JVM-generated bytecode has its own trampoline; this handles calls from Java code.
                if (result instanceof RuntimeControlFlowList cfList
                        && cfList.getControlFlowType() == ControlFlowType.TAILCALL) {
                    nextTailCode = cfList.getTailCallCodeRef();
                    RuntimeArray tailArgs = cfList.getTailCallArgs();
                    nextTailArgs = tailArgs != null ? tailArgs : curArgs;
                    // Fall through to finally; outer loop will re-enter apply()
                    // with the new code ref. We stay inside this apply()
                    // invocation, so enterCall/exitCall depth tracking is
                    // not re-entered (no inTailCallTrampoline bump needed).
                } else {
                    if (result instanceof RuntimeControlFlowList) {
                        MyVarCleanupStack.unwindTo(cleanupMark);
                        MortalList.flush();
                    }
                    // Mortal-ize blessed refs with refCount==0 in void-context calls.
                    // These are objects that were created but never stored in a named
                    // variable (e.g., discarded return values from constructors).
                    if (effectiveContext == RuntimeContextType.VOID) {
                        MortalList.mortalizeForVoidDiscard(result);
                        // Flush deferred DESTROY decrements from the sub's scope exit.
                        // Sub bodies use flush=false in emitScopeExitNullStores to protect
                        // return values on the stack, but in void context there is no return
                        // value to protect. Without this flush, DESTROY fires outside the
                        // caller's dynamic scope — e.g., after local $SIG{__WARN__} unwinds,
                        // causing Test::Warn to miss warnings from DESTROY.
                        MortalList.flushAboveMark();
                        return result;
                    }
                    return coerceScalarCallResult(result, effectiveContext, callContext, !isLvalueCode(code));
                }
            } catch (PerlNonLocalReturnException e) {
                if (e.targetCode != null && e.targetCode != code) {
                    throw e;
                }
                // Non-local return from map/grep block
                if (code.isMapGrepBlock) {
                    throw e;  // Propagate through nested map/grep blocks
                }
                // Consume at normal subroutine and eval-block boundaries.
                // Perl treats `return` inside eval { ... } as the eval value,
                // including when it originates from a nested map/grep block.
                RuntimeList result = e.returnValue != null ? e.returnValue.getList() : new RuntimeList();
                return coerceScalarCallResult(result, effectiveContext, callContext, !isLvalueCode(code));
            } catch (RuntimeException e) {
                e = WarnDie.maybeInvokeUnhandledDieHandler(e);
                // On die: run scopeExitCleanup for my-variables whose normal
                // SCOPE_EXIT_CLEANUP bytecodes were skipped by the exception.
                // PerlExitException (exit()) is excluded — global destruction handles it.
                if (!(e instanceof PerlExitException)
                        && !(e instanceof PerlThreadExitException)) {
                    MyVarCleanupStack.unwindTo(cleanupMark);
                    MortalList.flush();
                }
                throw e;
            } finally {
                if (cleanupArgsAfterCall) {
                    cleanupTailCallArgs(argsForCall);
                    cleanupTailCallCodeRef(codeRefForCall);
                }
                // Pop the function-scoped mortal mark. Entries added by this
                // function's scope-exit cleanup "fall" to the caller's scope
                // and will be processed by the caller's flushAboveMark().
                MortalList.popMark();
                // After unwindTo, entries are already removed; popMark is a no-op.
                // On normal return, popMark discards registrations without cleanup.
                MyVarCleanupStack.popMark(cleanupMark);
                HintHashRegistry.popCallerHintHash(compilationState);
                WarningBitsRegistry.popCallerHints(compilationState);
                WarningBitsRegistry.popCallerBits(compilationState);
                if (warningBits != null) {
                    WarningBitsRegistry.popCurrent(compilationState);
                }
                // eval BLOCK is compiled as an immediately-invoked anonymous sub
                // (sub { ... }->()) that captures outer lexicals, incrementing their
                // captureCount. Unlike a normal closure that may be stored and reused,
                // eval BLOCK executes once and is discarded. Release captures eagerly
                // so captureCount is decremented promptly, allowing scopeExitCleanup
                // to properly decrement refCount when the outer scope exits.
                // (eval STRING uses applyEval() which already does this.)
                if (code.isEvalBlock) {
                    code.releaseCaptures();
                }
            }
            // If we get here, the body returned a tailcall. Iterate
            // with the new code ref / args instead of recursing.
            curScalar = nextTailCode;
            curArgs = nextTailArgs;
            curArgsFromTailCall = true;
            continue;
        }

        // Handle GLOB type - extract CODE slot from the glob
        if (curScalar.type == RuntimeScalarType.GLOB) {
            RuntimeGlob glob = (RuntimeGlob) curScalar.value;
            if (glob.globName != null) {
                curScalar = GlobalVariable.getGlobalCodeRef(glob.globName);
                continue;
            } else if (glob.codeSlot != null) {
                curScalar = glob.codeSlot;
                continue;
            }
        }

        // Handle REFERENCE to GLOB (e.g., \*Foo) - dereference to get the glob, then extract CODE
        if ((curScalar.type == RuntimeScalarType.REFERENCE || curScalar.type == RuntimeScalarType.GLOBREFERENCE)
                && curScalar.value instanceof RuntimeGlob glob) {
            if (glob.globName != null) {
                curScalar = GlobalVariable.getGlobalCodeRef(glob.globName);
                continue;
            } else if (glob.codeSlot != null) {
                curScalar = glob.codeSlot;
                continue;
            }
        }

        if (curScalar.type == STRING || curScalar.type == BYTE_STRING) {
            String varName = NameNormalizer.normalizeVariableName(curScalar.toString(), "main");
            curScalar = GlobalVariable.getGlobalCodeRef(varName);
            continue;
        }

        RuntimeScalar overloadedCode = handleCodeOverload(curScalar);
        if (overloadedCode != null) {
            curScalar = overloadedCode;
            continue;
        }

        // If the type is not CODE, throw an exception indicating an invalid state
        throw new PerlCompilerException("Not a CODE reference");
        } // end while(true)
    }

    // Method to apply (execute) a subroutine reference for eval/evalbytes.
    // Eval STRING must allow next/last/redo to propagate to the enclosing scope.
    // The caller is responsible for handling RuntimeControlFlowList markers.
    public static RuntimeList applyEval(RuntimeScalar runtimeScalar, RuntimeArray a, int callContext) {
        incrementEvalDepth();
        try {
            RuntimeList result = apply(runtimeScalar, a, callContext);
            // Perl clears $@ on successful eval (even if nested evals previously set it).
            GlobalVariable.setGlobalVariable("main::@", "");
            return result;
        } catch (PerlNonLocalReturnException e) {
            // Non-local return from map/grep inside eval STRING - propagate, don't catch
            throw e;
        } catch (Throwable t) {
            // Perl eval catches exceptions; set $@ and return undef / empty list.
            WarnDie.catchEval(t);

            // If $@ is set and $^P flags require source retention, we may need to retain lines
            // for runtime errors (e.g., BEGIN/UNITCHECK die) where storeSourceLines wasn't called.
            // Try to extract the eval string from the codeRef if available
            String evalString = null;
            String filename = null;
            if (runtimeScalar.type == RuntimeScalarType.CODE) {
                RuntimeCode code = (RuntimeCode) runtimeScalar.value;
                // Use the evalString if it was captured in the codeRef
                // Note: This is a best-effort fallback; the primary path is evalStringHelper
                if (code.packageName != null && code.packageName.startsWith("(eval")) {
                    filename = code.packageName;
                    // We cannot reconstruct the exact eval string here, so skip retention
                }
            }

            if (RuntimeContextType.isListLike(callContext)) {
                return new RuntimeList();
            }
            return new RuntimeList(new RuntimeScalar());
        } finally {
            decrementEvalDepth();
            // Release captured variable references from the eval's code object.
            // After eval STRING finishes executing, its captures are no longer needed.
            if (runtimeScalar.type == RuntimeScalarType.CODE && runtimeScalar.value instanceof RuntimeCode code) {
                code.releaseCaptures();
            }
        }
    }

    private static RuntimeScalar handleCodeOverload(RuntimeScalar runtimeScalar) {
        // Check if object is eligible for overloading
        int blessId = blessedId(runtimeScalar);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(&{}", new RuntimeArray(
                        runtimeScalar, RuntimeScalarCache.scalarUndef, RuntimeScalarCache.scalarFalse));
                // If the subroutine returns the object itself then it will not be called again
                if (result != null && result != runtimeScalar && result.value != runtimeScalar.value) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Gets the warning bits string for a RuntimeCode.
     * For InterpretedCode, uses the stored warningBitsString field.
     * For JVM-compiled code, looks up in WarningBitsRegistry by class name.
     *
     * @param code The RuntimeCode to get warning bits for
     * @return The warning bits string, or null if not available
     */
    public static String getWarningBitsForCode(RuntimeCode code) {
        return getWarningBitsForCode(code, PerlRuntime.current().compilationState);
    }

    private static String getWarningBitsForCode(
            RuntimeCode code,
            org.perlonjava.runtime.CompilationRuntimeState compilationState) {
        // For InterpretedCode, use the stored field directly
        if (code instanceof org.perlonjava.backend.bytecode.InterpretedCode interpCode) {
            return interpCode.warningBitsString;
        }
        
        // For JVM-compiled code, look up by class name in the registry
        // The methodHandle's class is the generated class that has WARNING_BITS field
        if (code.methodHandle != null) {
            // Get the declaring class of the method handle
            try {
                // The type contains the declaring class as the first parameter type for instance methods
                // For our generated apply methods, we use the class that was loaded
                String className = code.methodHandle.type().parameterType(0).getName();
                return compilationState.warningBitsByClass.get(className);
            } catch (Exception e) {
                // If we can't get the class name, fall back to null
                return null;
            }
        }
        
        return null;
    }

    // Method to apply (execute) a subroutine reference using native array for parameters
    public static RuntimeList apply(RuntimeScalar runtimeScalar, String subroutineName, RuntimeBase[] args, int callContext) {
        runtimeScalar = resolveDirectCallTarget(runtimeScalar, subroutineName);

        // Handle tied scalars - fetch the underlying value first
        if (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) {
            return apply(runtimeScalar.tiedFetch(), subroutineName, args, callContext);
        }
        if (runtimeScalar.type == READONLY_SCALAR) {
            return apply((RuntimeScalar) runtimeScalar.value, subroutineName, args, callContext);
        }
        // WORKAROUND for eval-defined subs not filling lexical forward declarations:
        // If the RuntimeScalar is undef (forward declaration never filled),
        // silently return undef so tests can continue running.
        // This is a temporary workaround for the architectural limitation that eval        // contexts are captured at compile time.
        if (runtimeScalar.type == RuntimeScalarType.UNDEF) {
            return undefCodeRefResultOrThrow(runtimeScalar, subroutineName, callContext);
        }

        // Check if the type of this RuntimeScalar is CODE
        if (runtimeScalar.type == RuntimeScalarType.CODE) {

            // Transform the native array to RuntimeArray of aliases (Perl variable `@_`)
            RuntimeArray a = new RuntimeArray(args.length);
            for (RuntimeBase arg : args) {
                arg.setArrayOfAlias(a);
            }

            RuntimeCode code = (RuntimeCode) runtimeScalar.value;

            // The interpreter's shared-argument call opcode intentionally does
            // not carry a source-level name. Recover it from the registered
            // code reference so stack traces retain the called subroutine.
            if ((subroutineName == null || subroutineName.isEmpty())
                    && (code.subName == null || code.subName.isEmpty())) {
                String registeredName = registeredCodeName(code);
                if (registeredName != null) {
                    subroutineName = registeredName;
                }
            }

            // Check for closure prototype — calling one should die
            if (code.isClosurePrototype) {
                throw new PerlDieException(new RuntimeScalar("Closure prototype called"));
            }

            // CRITICAL: Run compilerSupplier BEFORE checking defined()
            // The compilerSupplier may replace runtimeScalar.value with InterpretedCode
            if (code.compilerSupplier != null) {
                RuntimeList savedConstantValue = code.constantValue;
                java.util.List<String> savedAttributes = code.attributes;
                code.compilerSupplier.get();
                // Reload code from runtimeScalar.value in case it was replaced
                code = (RuntimeCode) runtimeScalar.value;
                // Transfer fields that were set on the old code (e.g., by :const attribute)
                if (savedConstantValue != null && code.constantValue == null) {
                    code.constantValue = savedConstantValue;
                }
                restoreLazyAttributes(code, savedAttributes);
            }

            // Lazily generate CORE:: subroutine wrappers on first call
            if (!code.defined() && "CORE".equals(code.packageName) && code.subName != null) {
                if (CoreSubroutineGenerator.generateWrapper(code.subName)) {
                    runtimeScalar = GlobalVariable.getGlobalCodeRef("CORE::" + code.subName);
                    code = (RuntimeCode) runtimeScalar.value;
                }
            }

            RuntimeScalar lateResolved = resolveLateDefinedForwardCodeRef(runtimeScalar, code);
            if (lateResolved != null) {
                return apply(lateResolved, subroutineName, args, callContext);
            }

            if (code.defined()) {
                requireLvalueCallable(code, callContext, subroutineName);
                int effectiveContext = effectiveCallContext(code, callContext);
                // Look up warning bits for the code's class and push to context stack
                org.perlonjava.runtime.CompilationRuntimeState compilationState =
                        PerlRuntime.current().compilationState;
                String warningBits = getWarningBitsForCode(code, compilationState);
                if (warningBits != null) {
                    WarningBitsRegistry.pushCurrent(warningBits, compilationState);
                }
                // Interpreter statement boundaries expose their lexical warning
                // bits through runtimeWarningBits. A call into JVM-compiled Perl
                // must replace that value with the callee's definition-time bits
                // (or null when it has none), otherwise caller warnings leak into
                // helpers such as test.pl::skip despite `local $^W = 0`.
                String savedRuntimeWarningBits = compilationState.runtimeWarningBits;
                compilationState.runtimeWarningBits = warningBits;
                // Save caller's call-site warning bits so caller()[9] can retrieve them
                WarningBitsRegistry.pushCallerBits(compilationState);
                // Save caller's $^H so caller()[8] can retrieve them
                WarningBitsRegistry.pushCallerHints(compilationState);
                // Save caller's call-site hint hash so caller()[10] can retrieve them
                HintHashRegistry.pushCallerHintHash(compilationState);
                int cleanupMark = MyVarCleanupStack.pushMark();
                MortalList.pushMark();
                try {
                    // Cast the value to RuntimeCode and call apply()
                    RuntimeList result = code.apply(subroutineName, a, callContext);
                    // Flush deferred DESTROY decrements for void-context calls.
                    // See the 3-arg apply() overload for detailed rationale.
                    if (effectiveContext == RuntimeContextType.VOID) {
                        MortalList.mortalizeForVoidDiscard(result);
                        MortalList.flushAboveMark();
                    }
                    return result;
                } catch (PerlNonLocalReturnException e) {
                    if (e.targetCode != null && e.targetCode != code) {
                        throw e;
                    }
                    // Non-local return from map/grep block
                    if (code.isMapGrepBlock) {
                        throw e;  // Propagate through nested map/grep blocks
                    }
                    // Consume at normal subroutine and eval-block boundaries.
                    RuntimeList result = e.returnValue != null ? e.returnValue.getList() : new RuntimeList();
                    return coerceScalarCallResult(result, effectiveContext, callContext, !isLvalueCode(code));
                } catch (RuntimeException e) {
                    e = WarnDie.maybeInvokeUnhandledDieHandler(e);
                    if (!(e instanceof PerlExitException)) {
                        MyVarCleanupStack.unwindTo(cleanupMark);
                        MortalList.flush();
                    }
                    throw e;
                } finally {
                    if ("tailcall".equals(subroutineName)) {
                        cleanupTailCallArgs(a);
                        cleanupTailCallCodeRef(runtimeScalar);
                    }
                    MortalList.popMark();
                    MyVarCleanupStack.popMark(cleanupMark);
                    HintHashRegistry.popCallerHintHash(compilationState);
                    WarningBitsRegistry.popCallerHints(compilationState);
                    WarningBitsRegistry.popCallerBits(compilationState);
                    compilationState.runtimeWarningBits = savedRuntimeWarningBits;
                    if (warningBits != null) {
                        WarningBitsRegistry.popCurrent(compilationState);
                    }
                }
            }

            // Does AUTOLOAD exist?
            // If subroutineName is empty, construct it from the RuntimeCode's package and sub name
            String fullSubName = subroutineName;
            if (fullSubName.isEmpty() && code.packageName != null && code.subName != null) {
                fullSubName = code.packageName + "::" + code.subName;
            }

            if (!fullSubName.isEmpty()) {
                RuntimeScalar importedStubAutoload = findImportedStubAutoload(code, fullSubName);
                if (importedStubAutoload != null) {
                    return apply(importedStubAutoload, a, callContext);
                }

                // If this is an imported forward declaration, check AUTOLOAD in the source package FIRST
                // This matches Perl semantics where imported subs resolve via the exporting package's AUTOLOAD
                if (code.sourcePackage != null && !code.sourcePackage.isEmpty()) {
                    String sourceAutoloadString = code.sourcePackage + "::AUTOLOAD";
                    RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                    if (isCodeDefined(sourceAutoload)) {
                        // Set $AUTOLOAD name to the original package function name
                        String sourceSubroutineName = code.sourcePackage + "::" + code.subName;
                        getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                        // Call AUTOLOAD from the source package
                        return apply(sourceAutoload, a, callContext);
                    }
                }

                // Then check if AUTOLOAD exists in the current package
                String autoloadString = fullSubName.substring(0, fullSubName.lastIndexOf("::") + 2) + "AUTOLOAD";
                RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                if (isCodeDefined(autoload)) {
                    // Set $AUTOLOAD in the AUTOLOAD sub's compile-time package
                    // (see autoloadVarFor() for the reasoning).
                    String lookupPkg = fullSubName.substring(0, fullSubName.lastIndexOf("::"));
                    getGlobalVariable(autoloadVarFor(autoload, lookupPkg)).set(fullSubName);
                    // Call AUTOLOAD
                    return apply(autoload, a, callContext);
                }
                throw new PerlCompilerException(gotoErrorPrefix(subroutineName) + "ndefined subroutine &" + fullSubName + " called");
            }
        }

        // Handle GLOB type - extract CODE slot from the glob
        if (runtimeScalar.type == RuntimeScalarType.GLOB) {
            RuntimeGlob glob = (RuntimeGlob) runtimeScalar.value;
            if (glob.globName != null) {
                RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(glob.globName);
                return apply(resolved, subroutineName, args, callContext);
            } else if (glob.codeSlot != null) {
                return apply(glob.codeSlot, subroutineName, args, callContext);
            }
        }

        // Handle REFERENCE to GLOB (e.g., \*Foo) - dereference to get the glob, then extract CODE
        if ((runtimeScalar.type == RuntimeScalarType.REFERENCE || runtimeScalar.type == RuntimeScalarType.GLOBREFERENCE)
                && runtimeScalar.value instanceof RuntimeGlob glob) {
            if (glob.globName != null) {
                RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(glob.globName);
                return apply(resolved, subroutineName, args, callContext);
            } else if (glob.codeSlot != null) {
                return apply(glob.codeSlot, subroutineName, args, callContext);
            }
        }

        if (runtimeScalar.type == STRING || runtimeScalar.type == BYTE_STRING) {
            String varName = NameNormalizer.normalizeVariableName(runtimeScalar.toString(), "main");
            RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(varName);
            return apply(resolved, subroutineName, args, callContext);
        }

        RuntimeScalar overloadedCode = handleCodeOverload(runtimeScalar);
        if (overloadedCode != null) {
            return apply(overloadedCode, subroutineName, args, callContext);
        }

        throw new PerlCompilerException("Not a CODE reference");
    }

    /**
     * Resolve any pending TAILCALL markers in {@code result}, looping until
     * the call chain terminates or a non-TAILCALL marker is returned.
     *
     * <p>This is the runtime companion to the inline trampoline that was previously
     * emitted at every JVM call site by {@code EmitSubroutine.handleApplyOperator()}.
     * Moving the loop here reduces generated bytecode by ~65 bytes per Perl sub call.
     *
     * @param result      the {@code RuntimeList} returned by the most recent {@code apply()} call
     * @param callContext the call context (SCALAR / LIST / VOID) of the original site
     * @return  the final non-TAILCALL result, or a non-TAILCALL control-flow marker
     *          (LAST / NEXT / REDO / GOTO / RETURN) ready for the call-site dispatcher
     */
    public static RuntimeList resolveTailCalls(RuntimeList result, int callContext) {
        while (result instanceof RuntimeControlFlowList cfList
                && cfList.getControlFlowType() == ControlFlowType.TAILCALL) {
            RuntimeScalar codeRef = cfList.getTailCallCodeRef();
            RuntimeArray  args    = cfList.getTailCallArgs();
            // args may theoretically be null (defensive); treat as empty args list
            RuntimeArray tailArgs = args != null ? args : new RuntimeArray();
            try {
                result = apply(codeRef, "tailcall", tailArgs, callContext);
            } finally {
                cleanupTailCallArgs(tailArgs);
                cleanupTailCallCodeRef(codeRef);
            }
        }
        return result;
    }

    public static void cleanupTailCallArgs(RuntimeArray tailArgs) {
        if (tailArgs == null) return;
        // Tail-call args may be a pure alias array for the caller's @_.
        MortalList.releaseTailCallArgs(tailArgs);
    }

    public static void cleanupTailCallCodeRef(RuntimeScalar tailCodeRef) {
        if (tailCodeRef == null) return;
        MortalList.releaseTailCallCodeRef(tailCodeRef);
    }

    // Method to apply (execute) a subroutine reference (legacy method for compatibility)
    public static RuntimeList apply(RuntimeScalar runtimeScalar, String subroutineName, RuntimeBase list, int callContext) {

        // If this is a tail-call trampoline re-entry (emitted by the JVM bytecode
        // trampoline for `goto &sub`), mark it so enterCall/exitCall skip depth
        // tracking. See enterCall() / inTailCallTrampoline for the rationale.
        boolean isTailCall = "tailcall".equals(subroutineName);
        if (isTailCall) {
            PerlRuntime.current().executionState().tailCallTrampolineDepth++;
            try {
                return applyImpl(runtimeScalar, subroutineName, list, callContext);
            } finally {
                PerlRuntime.current().executionState().tailCallTrampolineDepth--;
            }
        }
        return applyImpl(runtimeScalar, subroutineName, list, callContext);
    }

    private static RuntimeList applyImpl(RuntimeScalar runtimeScalar, String subroutineName, RuntimeBase list, int callContext) {
        runtimeScalar = resolveDirectCallTarget(runtimeScalar, subroutineName);

        // Handle tied scalars - fetch the underlying value first
        if (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) {
            return apply(runtimeScalar.tiedFetch(), subroutineName, list, callContext);
        }
        if (runtimeScalar.type == READONLY_SCALAR) {
            return apply((RuntimeScalar) runtimeScalar.value, subroutineName, list, callContext);
        }

        // WORKAROUND for eval-defined subs not filling lexical forward declarations:
        // If the RuntimeScalar is undef (forward declaration never filled),
        // silently return undef so tests can continue running.
        // This is a temporary workaround for the architectural limitation that eval
        // contexts are captured at compile time.
        if (runtimeScalar.type == RuntimeScalarType.UNDEF) {
            return undefCodeRefResultOrThrow(runtimeScalar, subroutineName, callContext);
        }

        // Check if the type of this RuntimeScalar is CODE
        if (runtimeScalar.type == RuntimeScalarType.CODE) {

            // Transform the value in the stack to RuntimeArray of aliases (Perl variable `@_`)
            RuntimeArray a = list.getArrayOfAlias();

            RuntimeCode code = (RuntimeCode) runtimeScalar.value;

            // Check for closure prototype — calling one should die
            if (code.isClosurePrototype) {
                throw new PerlDieException(new RuntimeScalar("Closure prototype called"));
            }

            // CRITICAL: Run compilerSupplier BEFORE checking defined()
            // The compilerSupplier may replace runtimeScalar.value with InterpretedCode
            if (code.compilerSupplier != null) {
                RuntimeList savedConstantValue = code.constantValue;
                java.util.List<String> savedAttributes = code.attributes;
                code.compilerSupplier.get();
                // Reload code from runtimeScalar.value in case it was replaced
                code = (RuntimeCode) runtimeScalar.value;
                // Transfer fields that were set on the old code (e.g., by :const attribute)
                if (savedConstantValue != null && code.constantValue == null) {
                    code.constantValue = savedConstantValue;
                }
                restoreLazyAttributes(code, savedAttributes);
            }

            // Lazily generate CORE:: subroutine wrappers on first call
            if (!code.defined() && "CORE".equals(code.packageName) && code.subName != null) {
                if (CoreSubroutineGenerator.generateWrapper(code.subName)) {
                    runtimeScalar = GlobalVariable.getGlobalCodeRef("CORE::" + code.subName);
                    code = (RuntimeCode) runtimeScalar.value;
                }
            }

            RuntimeScalar lateResolved = resolveLateDefinedForwardCodeRef(runtimeScalar, code);
            if (lateResolved != null) {
                return apply(lateResolved, subroutineName, list, callContext);
            }

            if (code.defined()) {
                requireLvalueCallable(code, callContext, subroutineName);
                int effectiveContext = effectiveCallContext(code, callContext);
                // Look up warning bits for the code's class and push to context stack
                org.perlonjava.runtime.CompilationRuntimeState compilationState =
                        PerlRuntime.current().compilationState;
                String warningBits = getWarningBitsForCode(code, compilationState);
                if (warningBits != null) {
                    WarningBitsRegistry.pushCurrent(warningBits, compilationState);
                }
                // Save caller's call-site warning bits so caller()[9] can retrieve them
                WarningBitsRegistry.pushCallerBits(compilationState);
                // Save caller's $^H so caller()[8] can retrieve them
                WarningBitsRegistry.pushCallerHints(compilationState);
                // Save caller's call-site hint hash so caller()[10] can retrieve them
                HintHashRegistry.pushCallerHintHash(compilationState);
                int cleanupMark = MyVarCleanupStack.pushMark();
                MortalList.pushMark();
                try {
                    // Cast the value to RuntimeCode and call apply()
                    RuntimeList result = code.apply(subroutineName, a, callContext);
                    // Flush deferred DESTROY decrements for void-context calls.
                    // See the 3-arg apply() overload for detailed rationale.
                    if (effectiveContext == RuntimeContextType.VOID) {
                        MortalList.mortalizeForVoidDiscard(result);
                        MortalList.flushAboveMark();
                    }
                    return result;
                } catch (PerlNonLocalReturnException e) {
                    if (e.targetCode != null && e.targetCode != code) {
                        throw e;
                    }
                    // Non-local return from map/grep block
                    if (code.isMapGrepBlock) {
                        throw e;  // Propagate through nested map/grep blocks
                    }
                    // Consume at normal subroutine and eval-block boundaries.
                    RuntimeList result = e.returnValue != null ? e.returnValue.getList() : new RuntimeList();
                    return coerceScalarCallResult(result, effectiveContext, callContext, !isLvalueCode(code));
                } catch (RuntimeException e) {
                    e = WarnDie.maybeInvokeUnhandledDieHandler(e);
                    if (!(e instanceof PerlExitException)) {
                        MyVarCleanupStack.unwindTo(cleanupMark);
                        MortalList.flush();
                    }
                    throw e;
                } finally {
                    if ("tailcall".equals(subroutineName)) {
                        cleanupTailCallArgs(a);
                        cleanupTailCallCodeRef(runtimeScalar);
                    }
                    MortalList.popMark();
                    MyVarCleanupStack.popMark(cleanupMark);
                    HintHashRegistry.popCallerHintHash(compilationState);
                    WarningBitsRegistry.popCallerHints(compilationState);
                    WarningBitsRegistry.popCallerBits(compilationState);
                    if (warningBits != null) {
                        WarningBitsRegistry.popCurrent(compilationState);
                    }
                }
            }

            // Does AUTOLOAD exist?
            String fullSubName = (code.packageName != null && code.subName != null)
                    ? code.packageName + "::" + code.subName
                    : subroutineName;

            if (!fullSubName.isEmpty() && fullSubName.contains("::")) {
                RuntimeScalar importedStubAutoload = findImportedStubAutoload(code, fullSubName);
                if (importedStubAutoload != null) {
                    return apply(importedStubAutoload, a, callContext);
                }

                // If this is an imported forward declaration, check AUTOLOAD in the source package FIRST
                if (code.sourcePackage != null && !code.sourcePackage.isEmpty()) {
                    String sourceAutoloadString = code.sourcePackage + "::AUTOLOAD";
                    RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                    if (isCodeDefined(sourceAutoload)) {
                        String sourceSubroutineName = code.sourcePackage + "::" + code.subName;
                        getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                        return apply(sourceAutoload, a, callContext);
                    }
                }

                // Check if AUTOLOAD exists in the current package
                String autoloadString = fullSubName.substring(0, fullSubName.lastIndexOf("::") + 2) + "AUTOLOAD";
                RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                if (isCodeDefined(autoload)) {
                    // Set $AUTOLOAD in the AUTOLOAD sub's compile-time package
                    // (see autoloadVarFor() for the reasoning).
                    String lookupPkg = fullSubName.substring(0, fullSubName.lastIndexOf("::"));
                    getGlobalVariable(autoloadVarFor(autoload, lookupPkg)).set(fullSubName);
                    return apply(autoload, a, callContext);
                }
                throw new PerlCompilerException(gotoErrorPrefix(subroutineName) + "ndefined subroutine &" + fullSubName + " called");
            }
            throw new PerlCompilerException(gotoErrorPrefix(subroutineName) + "ndefined subroutine &" + fullSubName + " called");
        }

        // Handle GLOB type - extract CODE slot from the glob
        if (runtimeScalar.type == RuntimeScalarType.GLOB) {
            RuntimeGlob glob = (RuntimeGlob) runtimeScalar.value;
            if (glob.globName != null) {
                RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(glob.globName);
                return apply(resolved, subroutineName, list, callContext);
            } else if (glob.codeSlot != null) {
                return apply(glob.codeSlot, subroutineName, list, callContext);
            }
        }

        // Handle REFERENCE to GLOB (e.g., \*Foo) - dereference to get the glob, then extract CODE
        if ((runtimeScalar.type == RuntimeScalarType.REFERENCE || runtimeScalar.type == RuntimeScalarType.GLOBREFERENCE)
                && runtimeScalar.value instanceof RuntimeGlob glob) {
            if (glob.globName != null) {
                RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(glob.globName);
                return apply(resolved, subroutineName, list, callContext);
            } else if (glob.codeSlot != null) {
                return apply(glob.codeSlot, subroutineName, list, callContext);
            }
        }

        if (runtimeScalar.type == STRING || runtimeScalar.type == BYTE_STRING) {
            String varName = NameNormalizer.normalizeVariableName(runtimeScalar.toString(), "main");
            RuntimeScalar resolved = GlobalVariable.getGlobalCodeRef(varName);
            return apply(resolved, subroutineName, list, callContext);
        }

        RuntimeScalar overloadedCode = handleCodeOverload(runtimeScalar);
        if (overloadedCode != null) {
            return apply(overloadedCode, subroutineName, list, callContext);
        }

        throw new PerlCompilerException("Not a CODE reference");
    }

    // Handle \$var where $var might be a CODE reference (for lexical subs)
    // If the value is a CODE reference, return it directly
    // Otherwise, create a scalar reference to it
    public static RuntimeScalar maybeUnwrapCodeReference(RuntimeBase base) {
        if (base instanceof RuntimeScalar scalar) {
            // If it's already a CODE reference, return it directly
            // This handles \&foo where foo is a lexical sub
            if (scalar.type == RuntimeScalarType.CODE) {
                return scalar;
            }
        }
        // For all other cases, create a normal reference
        return base.createReference();
    }

    // Return a reference to the subroutine with this name: \&$a
    public static RuntimeScalar createCodeReference(RuntimeScalar runtimeScalar, String packageName) {
        // Special case: if the scalar already contains a CODE reference (lexical sub hidden variable),
        // just return it directly
        if (runtimeScalar.type == RuntimeScalarType.CODE) {
            if (runtimeScalar.globalCodeRefFqn != null) {
                ((RuntimeCode) runtimeScalar.value).referenceOriginFqn = runtimeScalar.globalCodeRefFqn;
            }
            // Ensure the subroutine is fully compiled before returning the reference
            // This is important for compile-time usage (e.g., use overload qr => \&lexical_sub)
            RuntimeCode code = (RuntimeCode) runtimeScalar.value;
            if (code.compilerSupplier != null) {
                code.compilerSupplier.get(); // Wait for compilation to finish
            }
            return runtimeScalar;
        }

        // Check if object is eligible for &{} overloading (e.g., blessed object with &{} operator)
        // This handles cases like \&{$constraint_obj} where $constraint_obj overloads &{}
        // blessId: negative = blessed with overload, positive = blessed without overload, 0 = not blessed
        int blessId = blessedId(runtimeScalar);
        // System.err.println("DEBUG createCodeReference: type=" + runtimeScalar.type + " blessId=" + blessId + " value=" + runtimeScalar.value);
        if (blessId != 0) {
            // Object is blessed
            if (blessId < 0) {
                // Has overloading - try to get &{} overload
                OverloadContext ctx = OverloadContext.prepare(blessId);
                if (ctx != null) {
                    RuntimeScalar result = ctx.tryOverload("(&{}", new RuntimeArray(
                            runtimeScalar, RuntimeScalarCache.scalarUndef, RuntimeScalarCache.scalarFalse));
                    if (result != null && result != runtimeScalar && result.value != runtimeScalar.value) {
                        // Successfully got a CODE reference via overload, return it
                        if (result.type == RuntimeScalarType.CODE) {
                            return result;
                        }
                        // Recursively handle if not CODE yet
                        return createCodeReference(result, packageName);
                    }
                }
            }
            // Blessed reference without &{} overload - this is an error in Perl
            // "Not a subroutine reference"
            throw new PerlCompilerException("Not a subroutine reference");
        }

        // Check if this is a reference type that isn't CODE - error "Not a subroutine reference"
        // This catches cases like \&{$hashref} where $hashref is an unblessed reference
        if (runtimeScalar.type == RuntimeScalarType.REFERENCE) {
            RuntimeScalar deref = (RuntimeScalar) runtimeScalar.value;
            if (deref.type != RuntimeScalarType.CODE) {
                throw new PerlCompilerException("Not a subroutine reference");
            }
        }

        // Handle GLOB type: \&{*glob} - get the code slot directly from the glob
        // Globs stringify with a "*" prefix (e.g., "*main::test_sub") which would
        // cause normalizeVariableName to look up the wrong name
        if (runtimeScalar.type == RuntimeScalarType.GLOB) {
            RuntimeGlob glob = (RuntimeGlob) runtimeScalar.value;
            // For detached globs (null globName, from stash delete), use local code slot
            if (glob.globName == null) {
                if (glob.codeSlot != null) {
                    RuntimeScalar snapshot = new RuntimeScalar();
                    snapshot.type = glob.codeSlot.type;
                    snapshot.value = glob.codeSlot.value;
                    return snapshot;
                }
                return new RuntimeScalar(); // undef
            }
            RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(glob.globName);
            
            // Return a snapshot of the current code reference
            RuntimeScalar snapshot = new RuntimeScalar();
            snapshot.type = codeRef.type;
            snapshot.value = codeRef.value;
            return snapshot;
        }

        String name = NameNormalizer.normalizeVariableName(runtimeScalar.toString(), packageName);
        // System.out.println("Creating code reference: " + name + " got: " + GlobalContext.getGlobalCodeRef(name));
        RuntimeScalar pseudoConstantCodeRef = GlobalVariable.createPseudoConstantCodeRef(name);
        if (pseudoConstantCodeRef != null) {
            return pseudoConstantCodeRef;
        }
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(name);

        // Lazily generate CORE:: subroutine wrappers on first reference
        if (name.startsWith("CORE::") && codeRef.type == RuntimeScalarType.CODE
                && codeRef.value instanceof RuntimeCode rc && !rc.defined()) {
            String opName = name.substring(6);
            if (org.perlonjava.runtime.CoreSubroutineGenerator.generateWrapper(opName)) {
                codeRef = GlobalVariable.getGlobalCodeRef(name);
            }
        }

        if (codeRef.type == RuntimeScalarType.CODE && codeRef.value instanceof RuntimeCode runtimeCode
                && !runtimeCode.defined()) {
            runtimeCode.isDeclared = true;
        }

        // Note: We used to return a reference to the constant value here, but that
        // breaks Exporter which does `*{$pkg::$sym} = \&{$src::$sym}`. The glob
        // assignment expects a CODE reference, not a scalar reference.
        // The constant value optimization is handled separately when calling the sub.

        // Return a snapshot of the current code reference, not the global entry itself.
        // This ensures that saved code references (\&sub) point to the current RuntimeCode
        // and won't be affected if the subroutine is later redefined.
        // This matches Perl's behavior where $orig = \&foo; sub foo {...} leaves $orig
        // pointing to the old version.
        RuntimeScalar snapshot = new RuntimeScalar();
        snapshot.type = codeRef.type;
        snapshot.value = codeRef.value;
        if (codeRef.value instanceof RuntimeCode referencedCode) {
            referencedCode.referenceOriginFqn = name;
        }
        return snapshot;
    }

    public static RuntimeScalar prototype(RuntimeScalar runtimeScalar, String packageName) {
        RuntimeScalar code = runtimeScalar;
        if (code.type != RuntimeScalarType.CODE) {
            String name = NameNormalizer.normalizeVariableName(code.toString(), packageName);
            // System.out.println("Looking for prototype: " + name);

            if (name.startsWith("CORE::")) {
                String key = name.substring(6);
                if (!CORE_PROTOTYPES.containsKey(key)) {
                    throw new PerlCompilerException("Can't find an opnumber for \"" + key + "\"");
                }
                return new RuntimeScalar(CORE_PROTOTYPES.get(key));
            }

            code = GlobalVariable.getGlobalCodeRef(name);
        }
        // System.out.println("type: " + code.type);
        if (code.type == RuntimeScalarType.CODE) {
            // System.out.println("prototype: " + ((RuntimeCode) code.value).prototype);
            return new RuntimeScalar(((RuntimeCode) code.value).prototype);
        }
        return scalarUndef;
    }

    /**
     * Gets the current package name using caller() information
     *
     * @return The current package name with "::" suffix
     */
    public static String getCurrentPackage() {
        // Use caller() to get the current package
        RuntimeList callerInfo = caller(new RuntimeList(), RuntimeContextType.LIST);

        if (!callerInfo.isEmpty()) {
            String packageName = callerInfo.getFirst().toString();
            // Ensure it ends with "::" for prefix matching
            return packageName.endsWith("::") ? packageName : packageName + "::";
        }

        // Fallback to main package if caller info is not available
        return "main::";
    }

    /**
     * Replace lazy {@link ScalarSpecialVariable} references ($1, $&amp;, etc.) and
     * {@link RuntimeArray} references in a return list with concrete copies.
     * Must be called BEFORE {@link RegexState#restore()} and local variable restoration
     * so that the values reflect the subroutine's state, not the caller's.
     *
     * <p>This is critical for returning localized arrays (e.g., {@code local @ARGV}).
     * Without this, the array reference in the return list would point to the restored
     * (original) values after the local scope exits.</p>
     */
    public static void materializeSpecialVarsInResult(RuntimeList result) {
        materializeSpecialVarsInResult(result, RuntimeContextType.LIST);
    }

    public static void materializeSpecialVarsInResult(RuntimeList result, int callContext) {
        boolean preserveAggregateLvalues = callContext == RuntimeContextType.LVALUE_LIST;
        List<RuntimeBase> elems = result.elements;
        for (int i = 0; i < elems.size(); i++) {
            RuntimeBase elem = elems.get(i);
            if (elem instanceof ScalarSpecialVariable ssv) {
                RuntimeScalar resolved = ssv.getValueAsScalar();
                elems.set(i, new RuntimeScalar(resolved));
            } else if (callContext != RuntimeContextType.LVALUE
                    && callContext != RuntimeContextType.LVALUE_LIST
                    && elem instanceof RuntimeScalar scalar
                    && scalar.type == RuntimeScalarType.TIED_SCALAR) {
                // FETCH before RegexState/local teardown. A tied return can
                // depend on captures produced inside the callee; deferring its
                // magic until the caller consumes the value exposes the
                // restored caller capture state instead.
                elems.set(i, new RuntimeScalar(scalar));
            } else if (!preserveAggregateLvalues && elem instanceof RuntimeArray arr) {
                // Copy array elements to ensure independence from local restoration.
                // For tied arrays, use getList() which dispatches through FETCHSIZE/FETCH,
                // since TieArray.elements (the ArrayList) is empty — data lives in the tied object.
                // For regular arrays, copy elements directly.
                if (arr.type == RuntimeArray.TIED_ARRAY) {
                    RuntimeList arrList = arr.getList();
                    elems.set(i, arrList);
                } else {
                    RuntimeArray copy = new RuntimeArray();
                    for (RuntimeScalar arrElem : arr.elements) {
                        copy.elements.add(arrElem == null ? null : new RuntimeScalar(arrElem));
                    }
                    elems.set(i, copy);
                }
            } else if (!preserveAggregateLvalues && elem instanceof RuntimeHash hash) {
                // Copy hash elements for the same reason as arrays.
                // For tied hashes, use getList() which dispatches through FIRSTKEY/NEXTKEY/FETCH.
                if (hash.type == RuntimeHash.TIED_HASH) {
                    RuntimeList hashList = hash.getList();
                    elems.set(i, hashList);
                } else {
                    RuntimeHash copy = new RuntimeHash();
                    for (var entry : hash.elements.entrySet()) {
                        copy.elements.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
                    }
                    elems.set(i, copy);
                }
            }
        }
    }

    /**
     * Materialize a block's return value if it contains a lazy special variable proxy.
     * Must be called BEFORE {@link RegexState#restore()} at block exit so that
     * regex capture variables ($1, $&amp;, etc.) reflect the block's state, not the
     * restored caller state.
     *
     * <p>Uses {@code RuntimeScalar} parameter/return type (not {@code RuntimeBase})
     * to preserve JVM stack type information — the verifier needs the narrower type
     * when downstream code calls {@code RuntimeScalar}-specific methods.</p>
     *
     * @param result The block's return value (on the JVM operand stack)
     * @return A concrete copy if the value was a special variable proxy, otherwise the original
     */
    public static RuntimeScalar materializeBlockResult(RuntimeScalar result) {
        if (result instanceof ScalarSpecialVariable ssv) {
            RuntimeScalar resolved = ssv.getValueAsScalar();
            return new RuntimeScalar(resolved);
        }
        return result;
    }

    /**
     * A do-block returns a mortal copy of its last scalar expression, not the
     * underlying lvalue. In particular, a deleted hash element must stop
     * aliasing its former storage when it crosses the do-block boundary.
     */
    public static RuntimeScalar copyDoBlockResult(RuntimeScalar result) {
        return new RuntimeScalar(result);
    }

    /** Copy a do-block result used in list context without collapsing the list. */
    public static RuntimeBase copyDoBlockListResult(RuntimeBase result) {
        if (result instanceof RuntimeScalar scalar) {
            return new RuntimeScalar(scalar);
        }
        if (result instanceof RuntimeList list) {
            RuntimeList copy = new RuntimeList();
            for (RuntimeBase element : list.elements) {
                copy.elements.add(element instanceof RuntimeScalar scalar
                        ? new RuntimeScalar(scalar) : element);
            }
            return copy;
        }
        return result;
    }

    public boolean defined() {
        // Built-in operators are always considered "defined"
        if (this.isBuiltin) {
            return true;
        }
        // Note: isDeclared is NOT checked here. In Perl 5, defined(&foo) returns
        // false for forward declarations (sub foo;). The isDeclared flag is used
        // by RuntimeGlob.getGlobSlot("CODE") and exists(&foo), both of which see
        // a declared-but-undefined CODE slot.
        return this.constantValue != null || this.compilerSupplier != null 
                || this.subroutine != null || this.methodHandle != null;
    }

    /**
     * Invokes the JVM-compiled method associated with this code object.
     *
     * <p>Regex state scoping ($1, $&amp;, etc.) is handled by {@link RegexState#save()}
     * at subroutine entry, restored by {@link DynamicVariableManager#popToLocalLevel} at exit.
     *
     * @param a           the RuntimeArray containing the arguments for the subroutine
     * @param callContext the context in which the subroutine is called
     * @return the result of the subroutine execution as a RuntimeList
     */
    public RuntimeList apply(RuntimeArray a, int callContext) {
        if (boundRuntime != null && PerlRuntime.currentOrNull() != boundRuntime) {
            try (PerlRuntime.Binding ignored = boundRuntime.bind()) {
                return apply(a, callContext);
            }
        }
        if (constantValue != null) {
            requireLvalueCallable(this, callContext, null);
            return new RuntimeList(constantValue);
        }
        try {
            if (this.compilerSupplier != null) {
                this.compilerSupplier.get();
            }

            // Check if subroutine is defined (prefer functional interface over methodHandle)
            if (this.subroutine == null && this.methodHandle == null) {
                // Lazily generate CORE:: subroutine wrappers on first call
                if ("CORE".equals(this.packageName) && this.subName != null) {
                    if (CoreSubroutineGenerator.generateWrapper(this.subName)) {
                        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef("CORE::" + this.subName);
                        if (codeRef.type == RuntimeScalarType.CODE) {
                            RuntimeCode generated = (RuntimeCode) codeRef.value;
                            if (generated.defined()) {
                                return generated.apply(a, callContext);
                            }
                        }
                    }
                }
                RuntimeScalar lateResolved = resolveLateDefinedForwardCodeRef(null, this);
                if (lateResolved != null) {
                    return apply(lateResolved, a, callContext);
                }
                String fullSubName = "";
                if (this.packageName != null && this.subName != null) {
                    fullSubName = this.packageName + "::" + this.subName;
                }
                if (!fullSubName.isEmpty()) {
                    if (this.sourcePackage != null && !this.sourcePackage.isEmpty()) {
                        String sourceAutoloadString = this.sourcePackage + "::AUTOLOAD";
                        RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                        if (isCodeDefined(sourceAutoload)) {
                            String sourceSubroutineName = this.sourcePackage + "::" + this.subName;
                            getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                            return apply(sourceAutoload, a, callContext);
                        }
                    }
                    String autoloadString = fullSubName.substring(0, fullSubName.lastIndexOf("::") + 2) + "AUTOLOAD";
                    RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                    if (isCodeDefined(autoload)) {
                        // Set $AUTOLOAD in the AUTOLOAD sub's compile-time package
                        // (see autoloadVarFor() for the reasoning).
                        String lookupPkg = fullSubName.substring(0, fullSubName.lastIndexOf("::"));
                        getGlobalVariable(autoloadVarFor(autoload, lookupPkg)).set(fullSubName);
                        return apply(autoload, a, callContext);
                    }
                    throw new PerlCompilerException("Undefined subroutine &" + fullSubName + " called");
                }
                throw new PerlCompilerException("Undefined subroutine called at ");
            }

            requireLvalueCallable(this, callContext, null);
            int effectiveContext = effectiveCallContext(this, callContext);

            // Debug mode: push args and track subroutine entry
            if (DebugState.isDebugMode()) {
                String debugSubName = (this.subName != null)
                        ? NameNormalizer.normalizeVariableName(this.subName, this.packageName != null ? this.packageName : "main")
                        : "";
                DebugState.pushArgs(a);
                DebugHooks.enterSubroutine(debugSubName);
            }
            // Always push args for getCurrentArgs() support (used by List::Util::any/all/etc.)
            pushArgs(a);
            pushCallContext(callContext);
            pushActiveCode(this);

            // hasArgs tracking for caller()[4]:
            // This is the 2-arg instance method, called from the 3-arg static apply(scalar, array, ctx).
            // That static method is the "shared args" path — used when Perl code calls &func (no parens),
            // which inherits the caller's @_ instead of creating a fresh one.
            // Perl's caller()[4] (hasargs) should be false/empty for these calls.
            // See also: the 3-arg instance method apply(name, array, ctx) which pushes true.
            hasArgsStack().push(false);

            // Check deep recursion BEFORE pushing the callee's warning bits,
            // so the "Deep recursion on subroutine" warning is gated on the
            // caller's lexical warning bits (matching Perl's ckWARN at the
            // call site, not inside the callee).
            enterCall();
            // Push warning bits for FATAL warnings support
            String warningBits = getWarningBitsForCode(this);
            if (warningBits != null) {
                WarningBitsRegistry.pushCurrent(warningBits);
            }
            try {
                RuntimeList result;
                // Prefer functional interface over MethodHandle for better performance
                if (this.subroutine != null) {
                    result = this.subroutine.apply(a, effectiveContext);
                } else if (isStatic) {
                    result = (RuntimeList) this.methodHandle.invoke(a, effectiveContext);
                } else {
                    result = (RuntimeList) this.methodHandle.invoke(this.codeObject, a, effectiveContext);
                }
                return detachTryExpressionLvalueResult(
                        coerceScalarCallResult(result, effectiveContext, callContext, !isLvalueCode(this)),
                        callContext);
            } catch (RuntimeException e) {
                throw WarnDie.maybeInvokeUnhandledDieHandler(e);
            } finally {
                if (warningBits != null) {
                    WarningBitsRegistry.popCurrent();
                }
                exitCall();
                popActiveCode(this);
                popArgs(); // also pops hasArgsStack — see popArgs() implementation
                if (DebugState.isDebugMode()) {
                    DebugHooks.exitSubroutine();
                    DebugState.popArgs();
                }
            }
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            // Handle fork-open completion (from exec in fork-open emulation)
            if (targetException instanceof ForkOpenCompleteException forkEx) {
                if (forkEx.boundaryCode != null && forkEx.boundaryCode != this) {
                    throw forkEx;
                }
                return forkOpenResult(forkEx, callContext);
            }
            if (targetException instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(targetException);
        } catch (ForkOpenCompleteException e) {
            // Handle fork-open completion (from exec in fork-open emulation)
            if (e.boundaryCode != null && e.boundaryCode != this) {
                throw e;
            }
            return forkOpenResult(e, callContext);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public RuntimeList apply(String subroutineName, RuntimeArray a, int callContext) {
        if (boundRuntime != null && PerlRuntime.currentOrNull() != boundRuntime) {
            try (PerlRuntime.Binding ignored = boundRuntime.bind()) {
                return apply(subroutineName, a, callContext);
            }
        }
        if (constantValue != null) {
            requireLvalueCallable(this, callContext, subroutineName);
            return new RuntimeList(constantValue);
        }
        try {
            if (this.compilerSupplier != null) {
                this.compilerSupplier.get();
            }

            // Check if subroutine is defined (prefer functional interface over methodHandle)
            if (this.subroutine == null && this.methodHandle == null) {
                RuntimeScalar lateResolved = resolveLateDefinedForwardCodeRef(null, this);
                if (lateResolved != null) {
                    return apply(lateResolved, a, callContext);
                }
                String fullSubName = (this.packageName != null && this.subName != null)
                        ? this.packageName + "::" + this.subName
                        : subroutineName;
                if (fullSubName != null && !fullSubName.isEmpty() && fullSubName.contains("::")) {
                    if (this.sourcePackage != null && !this.sourcePackage.isEmpty()) {
                        String sourceAutoloadString = this.sourcePackage + "::AUTOLOAD";
                        RuntimeScalar sourceAutoload = GlobalVariable.getGlobalCodeRef(sourceAutoloadString);
                        if (isCodeDefined(sourceAutoload)) {
                            String sourceSubroutineName = this.sourcePackage + "::" + this.subName;
                            getGlobalVariable(sourceAutoloadString).set(sourceSubroutineName);
                            return apply(sourceAutoload, a, callContext);
                        }
                    }
                    String autoloadString = fullSubName.substring(0, fullSubName.lastIndexOf("::") + 2) + "AUTOLOAD";
                    RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadString);
                    if (isCodeDefined(autoload)) {
                        // Set $AUTOLOAD in the AUTOLOAD sub's compile-time package
                        // (see autoloadVarFor() for the reasoning).
                        String lookupPkg = fullSubName.substring(0, fullSubName.lastIndexOf("::"));
                        getGlobalVariable(autoloadVarFor(autoload, lookupPkg)).set(fullSubName);
                        return apply(autoload, a, callContext);
                    }
                    throw new PerlCompilerException(gotoErrorPrefix(subroutineName) + "ndefined subroutine &" + fullSubName + " called");
                }
                throw new PerlCompilerException(gotoErrorPrefix(subroutineName) + "ndefined subroutine &" + (fullSubName != null ? fullSubName : "") + " called");
            }

            requireLvalueCallable(this, callContext, subroutineName);
            int effectiveContext = effectiveCallContext(this, callContext);

            // Debug mode: push args and track subroutine entry
            if (DebugState.isDebugMode()) {
                String debugSubName;
                if (this.subName != null) {
                    debugSubName = NameNormalizer.normalizeVariableName(this.subName, this.packageName != null ? this.packageName : "main");
                } else if (subroutineName != null) {
                    debugSubName = subroutineName;
                } else {
                    debugSubName = "";
                }
                DebugState.pushArgs(a);
                DebugHooks.enterSubroutine(debugSubName);
            }
            // Always push args for getCurrentArgs() support (used by List::Util::any/all/etc.)
            pushArgs(a);
            pushCallContext(callContext);
            pushActiveCode(this);

            // hasArgs tracking for caller()[4]:
            // This is the 3-arg instance method, called from the 4-arg static apply(scalar, name, args[], ctx).
            // That static method is the "fresh args" path — used for normal func(args) and &func(args) calls,
            // which create a new @_ from the supplied arguments.
            // Perl's caller()[4] (hasargs) should be true (1) for these calls.
            // See also: the 2-arg instance method apply(array, ctx) which pushes false.
            hasArgsStack().push(true);

            // Check deep recursion BEFORE pushing the callee's warning bits,
            // so the "Deep recursion on subroutine" warning is gated on the
            // caller's lexical warning bits.
            enterCall();
            // Push warning bits for FATAL warnings support
            String warningBits = getWarningBitsForCode(this);
            if (warningBits != null) {
                WarningBitsRegistry.pushCurrent(warningBits);
            }
            try {
                RuntimeList result;
                // Prefer functional interface over MethodHandle for better performance
                if (this.subroutine != null) {
                    result = this.subroutine.apply(a, effectiveContext);
                } else if (isStatic) {
                    result = (RuntimeList) this.methodHandle.invoke(a, effectiveContext);
                } else {
                    result = (RuntimeList) this.methodHandle.invoke(this.codeObject, a, effectiveContext);
                }
                return detachTryExpressionLvalueResult(
                        coerceScalarCallResult(result, effectiveContext, callContext, !isLvalueCode(this)),
                        callContext);
            } catch (RuntimeException e) {
                throw WarnDie.maybeInvokeUnhandledDieHandler(e);
            } finally {
                if (warningBits != null) {
                    WarningBitsRegistry.popCurrent();
                }
                exitCall();
                popActiveCode(this);
                popArgs(); // also pops hasArgsStack — see popArgs() implementation
                if (DebugState.isDebugMode()) {
                    DebugHooks.exitSubroutine();
                    DebugState.popArgs();
                }
            }
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            // Handle fork-open completion (from exec in fork-open emulation)
            if (targetException instanceof ForkOpenCompleteException forkEx) {
                if (forkEx.boundaryCode != null && forkEx.boundaryCode != this) {
                    throw forkEx;
                }
                return forkOpenResult(forkEx, callContext);
            }
            if (targetException instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(targetException);
        } catch (ForkOpenCompleteException e) {
            // Handle fork-open completion (from exec in fork-open emulation)
            if (e.boundaryCode != null && e.boundaryCode != this) {
                throw e;
            }
            return forkOpenResult(e, callContext);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts fork-open captured output to a RuntimeList.
     * In list context, splits output into lines (like readline {@code <FH>}).
     * In scalar context, returns the entire output as a single scalar.
     */
    private static RuntimeList forkOpenOutputToList(String capturedOutput, int callContext) {
        if (RuntimeContextType.isListLike(callContext) && capturedOutput != null && !capturedOutput.isEmpty()) {
            // Split into lines preserving newlines (like Perl's <FH> in list context)
            RuntimeArray arr = new RuntimeArray();
            int start = 0;
            int len = capturedOutput.length();
            while (start < len) {
                int nlPos = capturedOutput.indexOf('\n', start);
                if (nlPos >= 0) {
                    arr.push(new RuntimeScalar(capturedOutput.substring(start, nlPos + 1)));
                    start = nlPos + 1;
                } else {
                    // Last line without trailing newline
                    arr.push(new RuntimeScalar(capturedOutput.substring(start)));
                    break;
                }
            }
            return new RuntimeList(arr);
        }
        return new RuntimeList(new RuntimeScalar(capturedOutput));
    }

    private static RuntimeList forkOpenResult(ForkOpenCompleteException exception, int callContext) {
        if (exception.boundaryCode == null) {
            return forkOpenOutputToList(exception.capturedOutput, callContext);
        }
        RuntimeList result = new RuntimeList();
        result.add(exception.fileHandle);
        return result;
    }

    /**
     * Returns a string representation of the CODE reference.
     *
     * @return a string representing the CODE reference
     */
    public String toStringRef() {
        registerReferenceAddress();
        String ref = "CODE(0x" + referenceAddressHex() + ")";
        return (blessId == 0
                ? ref
                : NameNormalizer.getBlessStr(blessId) + "=" + ref);
    }

    /**
     * Returns an integer representation of the CODE reference.
     *
     * @return an integer representing the CODE reference
     */
    public int getIntRef() {
        return this.hashCode();
    }

    /**
     * Returns a double representation of the CODE reference.
     *
     * @return a double representing the CODE reference
     */
    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Returns a boolean representation of the CODE reference.
     *
     * @return true, indicating the presence of the CODE reference
     */
    public boolean getBooleanRef() {
        return true;
    }

    // Get the Scalar alias into an Array
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        arr.elements.add(new RuntimeScalar(this));
        return arr;
    }

    public int countElements() {
        return 1;
    }

    public RuntimeList getList() {
        return new RuntimeList(this);
    }

    public RuntimeScalar scalar() {
        return new RuntimeScalar(this);
    }

    public boolean getBoolean() {
        return true;
    }

    public boolean getDefinedBoolean() {
        return true;
    }

    public RuntimeScalar createReference() {
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.REFERENCE;  // Fixed: should be REFERENCE, not CODE
        result.value = this;
        return result;
    }

    public void addToArray(RuntimeArray array) {
        List<RuntimeScalar> elements = array.elements;
        elements.add(new RuntimeScalar(this));
    }

    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this);
    }

    public Iterator<RuntimeScalar> iterator() {
        return this.scalar().iterator();
    }

    public RuntimeArray setFromList(RuntimeList value) {
        throw new PerlCompilerException("Can't modify constant item in list assignment");
    }

    public RuntimeArray keys() {
        throw new PerlCompilerException("Type of arg 1 to keys must be hash or array");
    }

    public RuntimeArray values() {
        throw new PerlCompilerException("Type of arg 1 to values must be hash or array");
    }

    public RuntimeList each(int ctx) {
        throw new PerlCompilerException("Type of arg 1 to each must be hash or array");
    }

    public RuntimeScalar chop() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public RuntimeScalar chomp() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public RuntimeGlob undefine() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public void dynamicSaveState() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    public void dynamicRestoreState() {
        throw new PerlCompilerException("Can't modify anonymous subroutine");
    }

    /**
     * Tracks one temporary BEGIN-package alias installed for eval STRING parsing.
     */
    public static final class EvalRuntimeAlias {
        final char sigil;
        final String fullName;
        final RuntimeBase value;
        boolean active = true;

        EvalRuntimeAlias(char sigil, String fullName, RuntimeBase value) {
            this.sigil = sigil;
            this.fullName = fullName;
            this.value = value;
        }
    }

    /**
     * Container for runtime context during eval STRING compilation.
     * Holds both the runtime values and variable names so SpecialBlockParser can
     * match variables to their values.
     */
    public record EvalRuntimeContext(Object[] runtimeValues, String[] capturedEnv, String evalTag,
                                     List<EvalRuntimeAlias> aliases) {

        public EvalRuntimeContext(Object[] runtimeValues, String[] capturedEnv, String evalTag) {
            this(runtimeValues, capturedEnv, evalTag, new ArrayList<>());
        }

        /**
         * Get the runtime value for a variable by name.
         * <p>
         * IMPORTANT: The capturedEnv array includes all variables (including 'this', '@_', 'wantarray'),
         * but runtimeValues array skips the first skipVariables (currently 3).
         * So if @imports is at capturedEnv[5], its value is at runtimeValues[5-3=2].
         *
         * @param varName The variable name (e.g., "@imports", "$scalar")
         * @return The runtime value, or null if not found
         */
        public Object getRuntimeValue(String varName) {
            int skipVariables = 3; // 'this', '@_', 'wantarray'
            for (int i = skipVariables; i < capturedEnv.length; i++) {
                if (varName.equals(capturedEnv[i])) {
                    int runtimeIndex = i - skipVariables;
                    if (runtimeIndex >= 0 && runtimeIndex < runtimeValues.length) {
                        return runtimeValues[runtimeIndex];
                    }
                }
            }
            return null;
        }
    }

}
