package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.frontend.parser.NumberParser;
import org.perlonjava.backend.bytecode.FutureAsyncAwaitRuntime;
import org.perlonjava.runtime.io.ClosedIOHandle;
import org.perlonjava.runtime.io.IOHandle;
import org.perlonjava.runtime.io.LayeredIOHandle;
import org.perlonjava.runtime.io.NativeSocketIOHandle;
import org.perlonjava.runtime.io.SocketIO;
import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.operators.StringOperators;
import org.perlonjava.runtime.operators.MathOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.perlmodule.Warnings;
import org.perlonjava.runtime.regex.RuntimeRegex;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.runtimetypes.RuntimeArray.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * The RuntimeScalar class simulates Perl scalar variables.
 *
 * <p>In Perl, a scalar variable can hold: - An integer - A double (floating-point number) - A
 * string - A reference (to arrays, hashes, subroutines, etc.) - A code reference (anonymous
 * subroutine) - Undefined value - Special literals (like filehandles, typeglobs, regular
 * expressions, etc.)
 *
 * <p>Perl scalars are dynamically typed, meaning their type can change at runtime. This class tries
 * to mimic this behavior by using an enum `RuntimeScalarType` to track the type of the value stored in the
 * scalar.
 */
public class RuntimeScalar extends RuntimeBase implements RuntimeScalarReference, DynamicState {
    /** Mutable IV payload used by repeatedly updated scalar cells. */
    private static final class MutableIntegerValue extends Number {
        private static final long serialVersionUID = 1L;
        private volatile long value;

        private MutableIntegerValue(long value) { this.value = value; }
        private void set(long value) { this.value = value; }
        @Override public int intValue() { return (int) value; }
        @Override public long longValue() { return value; }
        @Override public float floatValue() { return value; }
        @Override public double doubleValue() { return value; }
        @Override public String toString() { return integerToString(value); }
    }

    private static final int INT_STRING_CACHE_SIZE = 512;
    private static final String[] INT_STRING_CACHE = buildIntStringCache();

    private static String[] buildIntStringCache() {
        String[] cache = new String[INT_STRING_CACHE_SIZE];
        for (int i = 0; i < INT_STRING_CACHE_SIZE; i++) {
            cache[i] = Integer.toString(i);
        }
        return cache;
    }

    static String integerToString(long value) {
        if (value >= 0 && value < INT_STRING_CACHE_SIZE) {
            return INT_STRING_CACHE[(int) value];
        }
        return Long.toString(value);
    }

    private static Object copyScalarPayload(int scalarType, Object payload) {
        return scalarType == RuntimeScalarType.INTEGER && payload instanceof MutableIntegerValue integer
                ? new MutableIntegerValue(integer.longValue())
                : payload;
    }

    /** Reject localizing a dereference whose source is an actual reference. */
    public static void rejectLocalizeThroughReference(RuntimeScalar source) {
        if (source != null && (source.type == RuntimeScalarType.UNDEF
                || (source.type & RuntimeScalarType.REFERENCE_BIT) != 0)) {
            throw new PerlCompilerException("Can't localize through a reference");
        }
    }

    public static void rejectLocalizeThroughReference() {
        throw new PerlCompilerException("Can't localize through a reference");
    }

    /** Reject localization with a source location captured by the JVM emitter. */
    public static void rejectLocalizeThroughReference(String message) {
        throw new PerlCompilerException(message);
    }


    /**
     * Deferred storage for a plain string being grown with repeated {@code .=}.
     * Keeping this separate from {@link #value} preserves the long-standing
     * invariant that STRING scalars expose a {@link String} to Java callers.
     */
    private transient StringBuilder growingString;
    /** Backing aggregate when this scalar represents an assignable keys() result. */
    private transient RuntimeBase keysLvalueContainer;

    void setKeysLvalueContainer(RuntimeBase container) {
        keysLvalueContainer = container;
    }

    boolean isKeysLvalue() {
        return keysLvalueContainer != null;
    }

    void rejectKeysLvalueListAssignment() {
        String target = keysLvalueContainer instanceof RuntimeArray ? "keys on array" : "keys";
        throw new PerlCompilerException("Can't modify " + target + " in list assignment");
    }
    private transient boolean transferableGrowingString;
    /** Number of imprecision diagnostics already emitted for a run of auto-operations. */
    private transient int imprecisionAutoWarningCount;

    /** Rare scalar metadata kept off the common scalar object until first use. */
    static final class ExtendedState {
        /** Live substr lvalues that must be refreshed when this scalar is replaced. */
        List<WeakReference<RuntimeSubstrLvalue>> substrLvalueObservers;
        /** Perl magic-style callbacks installed by Scalar::Watcher. */
        Map<Long, RuntimeScalar> modifiedWatchers;
        Map<Long, RuntimeScalar> destroyedWatchers;
    }

    transient ExtendedState extendedState;
    private transient boolean watcherDestroyNotified;
    private transient int watcherMutationDepth;
    private static volatile boolean watcherCleanupNeeded;

    public void addModifiedWatcher(long id, RuntimeScalar callback) {
        watcherCleanupNeeded = true;
        ExtendedState state = extendedState();
        if (state.modifiedWatchers == null) state.modifiedWatchers = new LinkedHashMap<>();
        state.modifiedWatchers.put(id, callback);
    }

    public void addDestroyedWatcher(long id, RuntimeScalar callback) {
        watcherCleanupNeeded = true;
        ExtendedState state = extendedState();
        if (state.destroyedWatchers == null) state.destroyedWatchers = new LinkedHashMap<>();
        state.destroyedWatchers.put(id, callback);
    }

    public void removeWatcher(long id) {
        ExtendedState state = extendedState;
        if (state == null) return;
        if (state.modifiedWatchers != null) state.modifiedWatchers.remove(id);
        if (state.destroyedWatchers != null) state.destroyedWatchers.remove(id);
    }

    public boolean hasWatchers() {
        ExtendedState state = extendedState;
        return state != null && ((state.modifiedWatchers != null && !state.modifiedWatchers.isEmpty())
                || (state.destroyedWatchers != null && !state.destroyedWatchers.isEmpty()));
    }

    private void notifyModifiedWatchers() {
        if (watcherMutationDepth > 0) return;
        ExtendedState state = extendedState;
        if (state == null || state.modifiedWatchers == null || state.modifiedWatchers.isEmpty()) return;
        RuntimeArray args = new RuntimeArray(this);
        for (RuntimeScalar callback : new ArrayList<>(state.modifiedWatchers.values())) {
            RuntimeCode.apply(callback, args, RuntimeContextType.VOID);
        }
    }

    private void notifyDestroyedWatchers() {
        if (watcherDestroyNotified) return;
        watcherDestroyNotified = true;
        ExtendedState state = extendedState;
        if (state == null) return;
        if (state.destroyedWatchers != null && !state.destroyedWatchers.isEmpty()) {
            RuntimeArray args = new RuntimeArray(this);
            for (RuntimeScalar callback : new ArrayList<>(state.destroyedWatchers.values())) {
                RuntimeCode.apply(callback, args, RuntimeContextType.VOID);
            }
        }
        if (state.modifiedWatchers != null) state.modifiedWatchers.clear();
        if (state.destroyedWatchers != null) state.destroyedWatchers.clear();
    }

    private ExtendedState extendedState() {
        ExtendedState state = extendedState;
        if (state == null) {
            state = new ExtendedState();
            extendedState = state;
        }
        return state;
    }

    public static boolean watcherCleanupNeeded() {
        return watcherCleanupNeeded;
    }

    /** Fire destruction magic for scalar slots owned by a dying aggregate. */
    public static void notifyDestroyedWatchersRecursively(RuntimeBase value) {
        Set<RuntimeBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        notifyDestroyedWatchersRecursively(value, visited);
    }

    private static void notifyDestroyedWatchersRecursively(RuntimeBase value, Set<RuntimeBase> visited) {
        if (value == null || !visited.add(value)) return;
        Collection<RuntimeScalar> elements;
        if (value instanceof RuntimeArray array) elements = array.elements;
        else if (value instanceof RuntimeHash hash) elements = hash.elements.values();
        else return;
        for (RuntimeScalar scalar : elements) {
            if (scalar == null) continue;
            scalar.notifyDestroyedWatchers();
            if (scalar.value instanceof RuntimeBase nested) {
                notifyDestroyedWatchersRecursively(nested, visited);
            }
        }
    }

    void registerSubstrLvalue(RuntimeSubstrLvalue observer) {
        ExtendedState state = extendedState();
        if (state.substrLvalueObservers == null) {
            state.substrLvalueObservers = new ArrayList<>();
        }
        state.substrLvalueObservers.add(new WeakReference<>(observer));
    }

    public boolean hasLiveSubstrLvalueObservers() {
        ExtendedState state = extendedState;
        if (state == null || state.substrLvalueObservers == null) return false;
        state.substrLvalueObservers.removeIf(reference -> reference.get() == null);
        return !state.substrLvalueObservers.isEmpty();
    }

    private void refreshSubstrLvalues() {
        ExtendedState state = extendedState;
        if (state == null || state.substrLvalueObservers == null) return;
        state.substrLvalueObservers.removeIf(reference -> {
            RuntimeSubstrLvalue observer = reference.get();
            if (observer == null) return true;
            observer.refreshFromParent();
            return false;
        });
    }

    // Static stack to store saved "local" states of RuntimeScalar instances
    private static Stack<RuntimeScalar> dynamicStateStack() {
        return PerlRuntime.current().executionState().scalarDynamicStates;
    }

    // Pre-compiled regex pattern for decimal numification fast-path
    // INTEGER_PATTERN replaced with isIntegerString() for better performance
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$");

    private RuntimeArray unaryDerefOverloadArgs() {
        return new RuntimeArray(this, scalarUndef, scalarFalse);
    }

    private boolean overloadReturnedDifferentObject(RuntimeScalar result) {
        return result != null && result != this && result.value != this.value;
    }

    // Fast check if string might be a parseable integer
    // Returns true if first char suggests it could be an integer (digit or minus)
    // This avoids exception overhead for strings like "hello" while allowing
    // Long.parseLong to handle edge cases like overflow
    private static boolean mightBeInteger(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        return (c >= '0' && c <= '9') || c == '-';
    }

    // Type map for scalar types to their corresponding enum
    private static final Map<Class<?>, Integer> typeMap = new HashMap<>();

    static {
        typeMap.put(Integer.class, RuntimeScalarType.INTEGER);
        typeMap.put(Long.class, RuntimeScalarType.INTEGER);
        typeMap.put(BigInteger.class, RuntimeScalarType.INTEGER);
        typeMap.put(String.class, RuntimeScalarType.STRING);
        typeMap.put(Double.class, RuntimeScalarType.DOUBLE);
        typeMap.put(Boolean.class, RuntimeScalarType.BOOLEAN);
        typeMap.put(RuntimeCode.class, RuntimeScalarType.CODE);
        typeMap.put(RuntimeRegex.class, RuntimeScalarType.REGEX);
        // Add other known types if necessary
    }

    // Fields to store the type and value of the scalar variable
    public volatile int type;
    public volatile Object value;
    RuntimeArray localArrayOwner;
    int localArrayIndex = -1;

    void recordLocalArrayOwner(RuntimeArray owner, int index) {
        localArrayOwner = owner;
        localArrayIndex = index;
    }

    /**
     * Original decimal text for high-precision numeric literals. Java stores
     * numeric values as double, but Perl's sprintf can retain more decimal
     * precision on builds with wider NVs.
     */
    public String numericLiteralText;

    /** True for the non-reference scalar produced by dereferencing a qr// value. */
    public boolean firstClassRegexScalar;
    /** Compiled regex identity carried by the string-like PV from ${qr//}. */
    public RuntimeRegex firstClassRegexValue;

    /** Weak referent metadata for Internals::SvREFCNT on a dereferenced qr// scalar. */
    public WeakReference<RuntimeRegex> firstClassRegexReferent;

    /** Backing qr value for the mutable scalar view returned by qr dereference. */
    public RuntimeRegex firstClassRegexValue() {
        return null;
    }

    /** True for scalar proxies whose reference is exposed as Perl's LVALUE type. */
    public boolean isLvalueScalar() {
        return false;
    }

    /** Internal provenance used by formline for a picture built with a tainted repeat count. */
    public boolean formatPictureTainted;

    /**
     * True once a string scalar has been used in numeric context. Perl keeps a
     * numeric slot alongside the string slot on the same SV; this lightweight
     * bit is enough for compatibility code that needs to distinguish an
     * untouched numeric-looking string from a numified string.
     */
    public boolean numericContextSeen;

    /**
     * True when a bare typeglob was stored as a scalar value.  Such a value
     * retains its glob slots for filehandle operations, but numifies through
     * its string form.  A directly used typeglob has this false and is not a
     * numeric scalar.
     */
    private boolean scalarStoredGlob;

    /** Identity of the scalar slot's anonymous IO resource owner, when present. */
    PerlOwnerSlot ioOwnerSlot;
    /** Identity retaining a detached glob while this scalar stores its value. */
    private PerlOwnerSlot detachedGlobValueOwnerSlot;

    private void refreshDetachedGlobValueOwner() {
        prepareDetachedGlobValueOwner(type, value);
    }

    /**
     * Move the scalar's detached-glob value edge before mutating the scalar
     * payload. Perl releases a detached glob's component values while the
     * scalar being overwritten still contains the old glob; a component's
     * DESTROY can therefore inspect that old value. Acquire a replacement
     * glob edge first, then retire the previous edge against that state.
     */
    private void prepareDetachedGlobValueOwner(int nextType, Object nextValue) {
        RuntimeGlob next = nextType == GLOB && nextValue instanceof RuntimeGlob glob
                && glob != this && glob.retainsDetachedComponents()
                && hasDurableStrongValueSlot() ? glob : null;
        RuntimeGlob previous = detachedGlobValueOwnerSlot != null
                && detachedGlobValueOwnerSlot.referent() instanceof RuntimeGlob glob
                ? glob : null;
        if (previous == next) {
            releaseClearedDetachedGlobSelf();
            return;
        }

        PerlOwnerSlot nextSlot = null;
        if (next != null) {
            nextSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.GLOB_VALUE);
            nextSlot.acquireGlobValueIdentity(next);
        }
        PerlOwnerSlot previousSlot = detachedGlobValueOwnerSlot;
        detachedGlobValueOwnerSlot = nextSlot;
        if (previousSlot != null) previousSlot.release();
        if (previous != null) previous.releaseDetachedComponentsIfUnowned();
        releaseClearedDetachedGlobSelf();
    }

    private void releaseDetachedGlobValueOwner() {
        PerlOwnerSlot ownerSlot = detachedGlobValueOwnerSlot;
        RuntimeGlob glob = ownerSlot != null
                && ownerSlot.referent() instanceof RuntimeGlob target ? target : null;
        if (glob == null && type == GLOB && value instanceof RuntimeGlob target
                && target != this && target.retainsDetachedComponents()) {
            glob = target;
        }
        detachedGlobValueOwnerSlot = null;
        if (ownerSlot != null) ownerSlot.release();
        if (glob != null) glob.releaseDetachedComponentsIfUnowned();
        releaseClearedDetachedGlobSelf();
    }

    private void releaseClearedDetachedGlobSelf() {
        if (this instanceof RuntimeGlob glob && glob.retainsDetachedComponents()
                && (type != GLOB || value != glob)) {
            glob.releaseDetachedComponentsIfUnowned();
        }
    }

    /** Whether this scalar currently owns an anonymous IO resource slot. */
    public boolean hasIoOwner() {
        return ioOwnerSlot != null;
    }

    /** Active call-frame provenance for copies extracted from aliased arguments. */
    private Object copiedFromArgumentFrame;

    boolean copiedFromArgumentFrame(Object frame) {
        return frame != null && copiedFromArgumentFrame == frame;
    }

    Object copiedFromArgumentFrameToken() {
        return copiedFromArgumentFrame;
    }

    /** Clears a non-reference array slot after aggregate ownership is queued. */
    void clearForArraySlotRemoval() {
        prepareDetachedGlobValueOwner(RuntimeScalarType.UNDEF, null);
        this.type = UNDEF;
        this.value = null;
        this.tainted = false;
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;
    }

    /**
     * When {@link #type} is {@link RuntimeScalarType#STRING}, true if this value was produced by
     * {@code Encode::_utf8_on} on a {@link RuntimeScalarType#BYTE_STRING} without decoding octets.
     * Character ordinals then match raw UTF-8 bytes (possibly ill-formed), as in Perl.
     */
    public boolean utf8UncheckedOctets;

    /** Deferred malformed UTF-8 diagnostic associated with this input value. */
    public String utf8MalformedWarning;

    /**
     * True when this scalar value came from a tainted source such as %ENV while
     * running under -T. This is intentionally lightweight; enforcement remains
     * with callers that inspect Scalar::Util::tainted.
     */
    public boolean tainted;

    /**
     * When this scalar is installed in {@link GlobalVariable#globalCodeRefs}, the map key
     * (fully-qualified name such as {@code My::Pkg::foo}). Used to invalidate
     * method-resolution cache lines for that sub's leaf name after in-place CV updates
     * via {@link #set(RuntimeScalar)}.
     */
    public String globalCodeRefFqn;

    /** Compile-time descriptor for one direct named-call site, or its runtime cache. */
    private String compiledDirectCallSiteName;
    private int compiledDirectCallSiteIndex = -1;
    private WeakReference<RuntimeCode> compiledDirectCallInitialTarget;
    private WeakReference<RuntimeScalar> compiledDirectCallInitialSlot;
    private WeakReference<RuntimeScalar> compiledDirectCallPinSlot;
    private boolean compiledDirectCallSiteDescriptor;
    private boolean compiledDirectCallCompileTimePinned;
    private boolean compiledDirectCallRetainTargetIfDeletedBeforeInstall;
    private boolean compiledDirectCallHasDeferredParseOwner;
    private boolean updatingCompiledDirectCallCache;

    /** Mark this scalar as metadata for a compiled direct named-call site. */
    public void configureCompiledDirectCallSite(
            String name, int index, RuntimeScalar initialCodeRef) {
        configureCompiledDirectCallSite(name, index, initialCodeRef, false);
    }

    public void configureCompiledDirectCallSite(
            String name,
            int index,
            RuntimeScalar initialCodeRef,
            boolean compileTimePinned) {
        configureCompiledDirectCallSite(
                name, index, initialCodeRef, compileTimePinned, false);
    }

    public void configureCompiledDirectCallSite(
            String name,
            int index,
            RuntimeScalar initialCodeRef,
            boolean compileTimePinned,
            boolean retainTargetIfDeletedBeforeInstall) {
        compiledDirectCallSiteName = name;
        compiledDirectCallSiteIndex = index;
        compiledDirectCallSiteDescriptor = true;
        compiledDirectCallCompileTimePinned = compileTimePinned;
        compiledDirectCallRetainTargetIfDeletedBeforeInstall = retainTargetIfDeletedBeforeInstall;
        RuntimeCode target = initialCodeRef != null
                && initialCodeRef.value instanceof RuntimeCode code ? code : null;
        compiledDirectCallInitialSlot = initialCodeRef == null
                ? null : new WeakReference<>(initialCodeRef);
        compiledDirectCallInitialTarget = target == null ? null : new WeakReference<>(target);
        if (target != null) {
            type = RuntimeScalarType.CODE;
            value = target;
            retainCodeSlotBinding();
        }
    }

    public String compiledDirectCallSiteName() {
        return compiledDirectCallSiteName;
    }

    public int compiledDirectCallSiteIndex() {
        return compiledDirectCallSiteIndex;
    }

    boolean compiledDirectCallCompileTimePinned() {
        return compiledDirectCallCompileTimePinned;
    }

    boolean compiledDirectCallRetainTargetIfDeletedBeforeInstall() {
        return compiledDirectCallRetainTargetIfDeletedBeforeInstall;
    }

    public void setCompiledDirectCallHasDeferredParseOwner(boolean hasOwner) {
        compiledDirectCallHasDeferredParseOwner = hasOwner;
    }

    public void setCompiledDirectCallPinSlot(RuntimeScalar pinSlot) {
        compiledDirectCallPinSlot = pinSlot == null ? null : new WeakReference<>(pinSlot);
    }

    boolean compiledDirectCallHasDeferredParseOwner() {
        return compiledDirectCallHasDeferredParseOwner;
    }

    boolean isCompiledDirectCallSiteDescriptor() {
        return compiledDirectCallSiteDescriptor;
    }

    void releaseCompiledDirectCallSiteDescriptorBinding() {
        if (!compiledDirectCallSiteDescriptor) return;
        compiledDirectCallSiteDescriptor = false;
        if (hasCodeSlotBinding()) releaseCodeSlotBinding();
        type = RuntimeScalarType.UNDEF;
        value = null;
    }

    RuntimeCode compiledDirectCallInitialTarget() {
        return compiledDirectCallInitialTarget == null
                ? (value instanceof RuntimeCode code ? code : null)
                : compiledDirectCallInitialTarget.get();
    }

    RuntimeScalar compiledDirectCallInitialSlot() {
        return compiledDirectCallInitialSlot == null
                ? null : compiledDirectCallInitialSlot.get();
    }

    RuntimeScalar compiledDirectCallPinSlot() {
        return compiledDirectCallPinSlot == null
                ? null : compiledDirectCallPinSlot.get();
    }

    void updateCompiledDirectCallTarget(RuntimeCode target) {
        compiledDirectCallInitialTarget = target == null ? null : new WeakReference<>(target);
    }

    void initializeCompiledDirectCallCache(
            String name, int index, RuntimeCode target, boolean compileTimePinned) {
        compiledDirectCallSiteName = name;
        compiledDirectCallSiteIndex = index;
        compiledDirectCallSiteDescriptor = false;
        compiledDirectCallCompileTimePinned = compileTimePinned;
        compiledDirectCallInitialSlot = null;
        compiledDirectCallInitialTarget = target == null ? null : new WeakReference<>(target);
        type = RuntimeScalarType.CODE;
        value = target;
        globalCodeRefFqn = null;
    }

    void setCompiledDirectCallTarget(RuntimeScalar replacement) {
        if (replacement == null) return;
        updatingCompiledDirectCallCache = true;
        try {
            set(replacement);
        } finally {
            updatingCompiledDirectCallCache = false;
        }
        globalCodeRefFqn = null;
        RuntimeCode target = replacement.type == RuntimeScalarType.CODE
                && replacement.value instanceof RuntimeCode code ? code : null;
        updateCompiledDirectCallTarget(target);
    }

    void setCompiledDirectCallTarget(RuntimeCode target) {
        RuntimeScalar replacement = new RuntimeScalar();
        replacement.type = RuntimeScalarType.CODE;
        replacement.value = target;
        setCompiledDirectCallTarget(replacement);
    }

    /**
     * Source-level name for a generated lexical-sub storage scalar.  A
     * bodyless {@code my/state sub name;} is an undefined callable, not an
     * ordinary undef scalar: calls must report {@code &name} in diagnostics.
     */
    public String lexicalSubName;

    /** Source spelling retained for diagnostics that operate on a lexical scalar. */
    public String lexicalDisplayName;

    /** Package containing {@link #lexicalSubName} for an eval-filled forward declaration. */
    public String lexicalSubPackageName;

    /** Whether that package CV was already defined when the lexical forward was declared. */
    public boolean lexicalSubPackageCodeDefinedAtDeclaration;

    /**
     * Number of closures that have captured this RuntimeScalar variable.
     * When {@code captureCount > 0}, {@link #scopeExitCleanup} skips the
     * blessed ref decrement because a closure still holds a reference to
     * this variable. The count is incremented in
     * {@link RuntimeCode#makeCodeObject} and decremented in
     * {@link RuntimeCode#releaseCaptures}.
     */
    /** Explicit identity for this pad cell's closure owner edge. */
    private PerlOwnerSlot closureOwnerSlot;

    /** Number of legacy capture-count increments retained for this cell's referent. */
    public int captureRefCountOwned() {
        return closureOwnerSlot == null ? 0 : closureOwnerSlot.legacyCaptureCount();
    }

    /**
     * True if {@link #scopeExitCleanup} has been called for this variable
     * (i.e., the variable's declaring scope has exited), but cleanup was
     * deferred because {@code captureCount > 0}. Used by
     * {@link RuntimeCode#releaseCaptures} to know when it's safe to call
     * {@link MortalList#deferDecrementIfTracked}: only if the scope has
     * already exited (otherwise the variable is still alive and its refCount
     * will be decremented later by scopeExitCleanup when the scope exits).
     */
    public boolean scopeExited;

    // Last hash/array container that stored this scalar slot. Used only as a
    // narrow guard for live CODE refs whose captures must survive void discard.
    RuntimeBase containerOwner;
    private final Set<RuntimeBase> containerOwners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** Package aggregates that contained this value before becoming roots. */
    private final Set<RuntimeBase> prepopulatedPackageAggregateOwners =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Return the aggregate slot that directly owns this scalar, if any. */
    public RuntimeBase getContainerOwner() {
        return containerOwner;
    }

    void markContainerOwner(RuntimeBase owner) {
        // Immutable primitive literals are shared from the global scalar
        // cache. Their owner does not make them writable, but it would keep a
        // transient argument or container alive as long as the cache entry.
        // Reference and proxy-backed scalars retain this edge for lifetime
        // tracking.
        if (isSharedImmutableLiteralWithoutOwnerTracking()) return;
        synchronized (this) {
            if (owner != null) containerOwners.add(owner);
            this.containerOwner = owner;
        }
        refreshDetachedGlobValueOwner();
        propagateTiedHandlerMarkerToReferent();
    }

    private boolean isSharedImmutableLiteralWithoutOwnerTracking() {
        return getClass() == RuntimeScalarReadOnly.class
                && type != RuntimeScalarType.READONLY_SCALAR
                && (type & RuntimeScalarType.REFERENCE_BIT) == 0
                && !(value instanceof RuntimeBase)
                && !firstClassRegexScalar;
    }

    void markPrepopulatedPackageAggregateOwner(RuntimeBase owner) {
        boolean tracked;
        synchronized (this) {
            tracked = containerOwners.contains(owner);
        }
        if (!tracked || !isStoredInContainer(owner)) return;
        synchronized (this) {
            if (containerOwners.contains(owner)) prepopulatedPackageAggregateOwners.add(owner);
        }
    }

    /** Retire this cell's container edge when the container loses its last Perl owner. */
    void releaseContainerOwner(RuntimeBase expectedOwner) {
        removeContainerOwnerTracking(expectedOwner);
        refreshUntrackedReferenceOwner();
    }

    /** Retire this aggregate edge after a slot mutation removed its last copy. */
    void reconcileContainerOwnerAfterMutation(RuntimeBase owner) {
        if (owner == null) return;
        if (isSharedImmutableLiteralWithoutOwnerTracking()) return;
        if (isStoredInContainer(owner)) {
            synchronized (this) {
                containerOwners.add(owner);
                containerOwner = owner;
            }
            propagateTiedHandlerMarkerToReferent();
        } else {
            removeContainerOwnerTracking(owner);
        }
        refreshUntrackedReferenceOwner();
    }

    private void removeContainerOwnerTracking(RuntimeBase expectedOwner) {
        boolean replaceCurrentOwner;
        synchronized (this) {
            containerOwners.remove(expectedOwner);
            prepopulatedPackageAggregateOwners.remove(expectedOwner);
            replaceCurrentOwner = containerOwner == expectedOwner;
            if (replaceCurrentOwner) containerOwner = null;
        }
        if (replaceCurrentOwner) {
            RuntimeBase replacement = containerOwnerSnapshot().stream()
                    .filter(this::isStoredInContainer)
                    .findFirst().orElse(null);
            synchronized (this) {
                // A concurrent store may already have installed a newer owner.
                if (containerOwner == null) containerOwner = replacement;
            }
        }
    }

    /** Reconcile the fast owner hint with all currently stored container edges. */
    void reconcileContainerOwners() {
        for (RuntimeBase owner : containerOwnerSnapshot()) {
            if (!isStoredInContainer(owner)) removeContainerOwnerTracking(owner);
        }
        List<RuntimeBase> owners = containerOwnerSnapshot();
        RuntimeBase current = containerOwner;
        if (current != null && (!owners.contains(current) || !isStoredInContainer(current))) {
            synchronized (this) {
                if (containerOwner == current) containerOwner = null;
            }
        }
        if (containerOwner == null) {
            RuntimeBase replacement = owners.stream()
                    .filter(this::isStoredInContainer)
                    .findFirst().orElse(null);
            synchronized (this) {
                if (containerOwner == null) containerOwner = replacement;
            }
        }
    }

    private void propagateTiedHandlerMarkerToReferent() {
        RuntimeBase owner;
        RuntimeBase base;
        synchronized (this) {
            owner = containerOwner;
            base = (type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && value instanceof RuntimeBase referent ? referent : null;
        }
        if (owner != null && owner.possiblyStoredInTiedHandler && base != null) {
            base.markPossiblyStoredInTiedHandler();
        }
    }

    boolean isStoredInRegisteredContainerOwner() {
        return containerOwnerSnapshot().stream()
                .anyMatch(owner -> MyVarCleanupStack.isRegistered(owner)
                        && isStoredInAnyContainer(owner));
    }

    /** Whether this scalar is backed by durable Perl storage for lvalue use. */
    boolean hasDurableLvalueStorage() {
        return isPackageGlobalRoot
                || MyVarCleanupStack.isRegistered(this)
                || isStoredInRegisteredContainerOwner()
                || containerOwner == RuntimeCode.getCurrentArgs()
                || this instanceof RuntimeBaseProxy;
    }

    /** True when a container removal returned this scalar slot as a loose value. */
    private boolean isDetachedFromContainerOwner() {
        List<RuntimeBase> owners = containerOwnerSnapshot();
        if (owners.stream().anyMatch(this::isStoredInAnyContainer)) return false;
        if (owners.isEmpty()) {
            return !MyVarCleanupStack.isRegistered(this) && !isPackageGlobalRoot;
        }
        return true;
    }

    public synchronized void retainClosureCapture() {
        verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        retainClosureCapture(true);
        verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
    }

    private synchronized void retainClosureCapture(boolean legacyOwner) {
        boolean firstCapture = !hasClosureCaptureOwner();
        retainClosureCaptureCount(legacyOwner);
        finishClosureCaptureRetention(firstCapture);
    }

    private void finishClosureCaptureRetention(boolean firstCapture) {
        refreshUntrackedReferenceOwner();
        if (firstCapture && type == RuntimeScalarType.CODE) {
            retainClosureCaptureReferent();
        } else if (firstCapture && !refCountOwned) {
            // A captured lexical is a Perl owner even when this scalar is a
            // borrowed copy of the pad slot and therefore has no ordinary
            // refCountOwned token of its own. Keep the referent alive for the
            // lifetime of this closure capture without making weak slots,
            // untracked values, or conservative capture metadata strong.
            RuntimeBase base = semanticCaptureReferent();
            updateClosureOwnerSlot(base);
            retainClosureCaptureReferent();
        } else if (firstCapture) {
            // The ordinary pad-slot increment already exists in refCount.
            // Record the semantic edge separately so weak sweeping cannot
            // mistake a captured pad for an unowned JVM temporary.
            RuntimeBase base = semanticCaptureReferent();
            updateClosureOwnerSlot(base);
        }
    }

    /** Retain this lexical cell for one closure capture with a distinct owner identity. */
    public PerlOwnerSlot retainClosureCaptureSlot() {
        return retainClosureCaptureSlot(null);
    }

    /** Retain this lexical cell and identify the CODE object that owns the capture. */
    public synchronized PerlOwnerSlot retainClosureCaptureSlot(RuntimeCode captureOwner) {
        verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        boolean firstCapture = !hasClosureCaptureOwner();
        PerlOwnerSlot ownerSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        ownerSlot.acquireCaptureIdentity(this, captureOwner);
        retainClosureCaptureCount(false);
        finishClosureCaptureRetention(firstCapture);
        verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        return ownerSlot;
    }

    /** Release one closure's lexical-cell owner and its matching capture count. */
    public synchronized void releaseClosureCaptureSlot(PerlOwnerSlot ownerSlot) {
        if (ownerSlot == null) {
            releaseClosureCapture();
            return;
        }
        if (ownerSlot.kind() != PerlOwnerSlot.Kind.SCALAR_CAPTURE) {
            throw new IllegalStateException("capture release requires a scalar-capture slot");
        }
        if (!ownerSlot.isActive()) return;
        if (ownerSlot.referent() != this) {
            throw new IllegalStateException("scalar-capture slot belongs to a different pad cell");
        }
        beginPendingClosureCaptureRelease();
        try {
            ownerSlot.release();
        } finally {
            endPendingClosureCaptureRelease();
        }
        releaseClosureCapture(false);
        verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        // Releasing the owner slot runs while the capture release is marked
        // pending, so its lifecycle callback correctly waits. Retry after the
        // capture count and pending marker have both retired, matching the
        // aggregate capture path in RuntimeBase.releaseClosureCaptureOwner().
        if (!hasClosureCaptureOwner()) {
            WeakRefRegistry.onStrongOwnerSlotReleased(this);
        }
    }

    /** Reconstruct capture ownership for an independent ithread snapshot. */
    synchronized PerlOwnerSlot retainThreadCloneClosureCapture(RuntimeCode captureOwner) {
        verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        PerlOwnerSlot ownerSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        ownerSlot.acquireCaptureIdentity(this, captureOwner);
        retainClosureCaptureCount(false);
        refreshUntrackedReferenceOwner();
        // An owning lexical slot already protects its referent. Borrowed
        // argument aliases do not, and the source runtime's other owners are
        // not a lifetime guarantee in the cloned runtime.
        if (!refCountOwned) retainClosureCaptureReferent();
        verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        return ownerSlot;
    }

    public synchronized void releaseClosureCapture() {
        if (!hasClosureCaptureOwner()) {
            if (captureCount != 0) {
                throw new IllegalStateException("closure capture has no recorded owner");
            }
            return;
        }
        releaseClosureCapture(true);
        verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        if (!hasClosureCaptureOwner()) {
            WeakRefRegistry.onStrongOwnerSlotReleased(this);
        }
    }

    private synchronized void releaseClosureCapture(boolean legacyOwner) {
        releaseClosureCaptureCount(legacyOwner);
        if (!hasClosureCaptureOwner()) {
            releaseOneClosureCaptureReferent();
            releaseClosureOwnerSlot();
            if (scopeExited) {
                cleanupScalarReferenceBinding(this);
            }
            refreshUntrackedReferenceOwner();
        }
        if (!legacyOwner) {
            verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);
        }
    }

    /**
     * Retire this pad cell's semantic edge once its declaring scope has exited
     * and the pre-END reachability walk has established that no END block can
     * reach it.  The capture count intentionally remains intact: the compiled
     * eval code may still be Java-reachable, but it can no longer represent a
     * Perl lifetime owner at global-destruction time.
     */
    public void releaseUnreachableSemanticCaptureOwner() {
        releaseClosureOwnerSlot();
    }

    private void retainClosureCaptureReferent() {
        RuntimeBase base = closureCaptureReferent();
        if (base == null) return;
        updateClosureOwnerSlot(base, false);
        // The explicit CLOSURE_PAD slot is the capture's strong edge. Do not
        // mirror it with a selective-count increment; final slot release
        // drives weak cleanup through the owner ledger.
        closureOwnerSlot.acquireNativeOwner(base);
    }

    private void updateClosureOwnerSlot(RuntimeBase referent) {
        updateClosureOwnerSlot(referent, true);
    }

    private void updateClosureOwnerSlot(RuntimeBase referent, boolean blessedOnly) {
        RuntimeBase ownedReferent = referent != null
                && (!blessedOnly || referent.blessId != 0)
                ? referent : null;
        if (closureOwnerSlot == null && ownedReferent != null) {
            closureOwnerSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.CLOSURE_PAD, this);
        }
        if (closureOwnerSlot != null) closureOwnerSlot.transferTo(ownedReferent);
    }

    private void releaseClosureOwnerSlot() {
        if (closureOwnerSlot != null) closureOwnerSlot.release();
    }

    private RuntimeBase closureCaptureReferent() {
        if (WeakRefRegistry.isweak(this)) return null;
        if ((type & RuntimeScalarType.REFERENCE_BIT) == 0 || !(value instanceof RuntimeBase base)) {
            return null;
        }
        if (type == RuntimeScalarType.CODE && base.refCount < 0) {
            base.refCount = 0;
        }
        if (base.refCount < 0 || base.refCount == WeakRefRegistry.WEAKLY_TRACKED
                || base.refCount == Integer.MIN_VALUE) {
            return null;
        }
        return base;
    }

    /** The referent edge is semantic even when selective refcounting is not. */
    private RuntimeBase strongCaptureReferent() {
        if (WeakRefRegistry.isweak(this)
                || (type & RuntimeScalarType.REFERENCE_BIT) == 0
                || !(value instanceof RuntimeBase base)
                || base.refCount == WeakRefRegistry.WEAKLY_TRACKED
                || base.refCount == Integer.MIN_VALUE) {
            return null;
        }
        return base;
    }

    /**
     * The owner ledger tracks Perl's strong capture edge separately from the
     * selective refcount. A live referent can be WEAKLY_TRACKED when a weak
     * probe was installed before the closure was constructed; that sentinel
     * must not prevent the later strong capture from becoming an owner.
     */
    private RuntimeBase semanticCaptureReferent() {
        if (WeakRefRegistry.isweak(this)
                || (type & RuntimeScalarType.REFERENCE_BIT) == 0
                || !(value instanceof RuntimeBase base)
                || base.refCount == Integer.MIN_VALUE) {
            return null;
        }
        return base;
    }


    private void releaseOneClosureCaptureReferent() {
        if (captureRefCountOwned() <= 0) return;
        RuntimeBase base = (type & RuntimeScalarType.REFERENCE_BIT) != 0
                && value instanceof RuntimeBase referent ? referent : null;
        closureOwnerSlot.deferLegacyCaptureRelease(base);
    }

    private void releaseAllClosureCaptureReferents(RuntimeBase oldBase) {
        while (captureRefCountOwned() > 0) {
            closureOwnerSlot.deferLegacyCaptureRelease(oldBase);
        }
    }

    void releaseClosureCaptureReferentsForWeaken(RuntimeBase oldBase) {
        if (hasClosureCaptureOwner() && oldBase != null) {
            releaseClosureOwnerSlot();
        }
        releaseAllClosureCaptureReferents(oldBase);
    }

    /**
     * True if this scalar "owns" a refCount increment on its referent.
     * Set to true by {@link #setLarge} after incrementing the referent's refCount.
     * Cleared when the matching decrement fires (scope exit, overwrite, undef, weaken).
     * <p>
     * This prevents spurious decrements from copies that were created via the
     * copy constructor (which does NOT increment refCount). Without this flag,
     * scope exit cleanup would decrement refCount for every scalar holding a
     * tracked reference, even if that scalar never incremented it.
     */
    public boolean refCountOwned;

    /** Changes when a nested operation mutates this scalar's reference slot. */
    private long referenceSlotMutationVersion;

    private PerlOwnerSlot countedOwnerSlot;
    /** Identity for this scalar cell's strong edge to a non-counted referent. */
    private PerlOwnerSlot untrackedReferenceOwnerSlot;
    private ArrayList<PerlOwnerSlot> argumentAliasOwnerSlots;
    /** Frame identities following this mutable scalar while async code is paused. */
    private Set<PerlOwnerSlot> suspendedFrameOwnerSlots;

    long noteReferenceSlotMutation() {
        return ++referenceSlotMutationVersion;
    }

    private boolean isCurrentReferenceSlotMutation(long version) {
        return referenceSlotMutationVersion == version;
    }
    // Package names, compiler pins, localized saved slots, and compiled
    // references can share one physical CV edge. Keep visible stash bindings
    // distinct so weak cleanup does not confuse them with compiler pins.
    private int codeSlotBindings;
    private int stashCodeSlotBindings;

    boolean hasCodeSlotBinding() {
        return codeSlotBindings != 0;
    }

    boolean hasStashCodeSlotBinding() {
        return stashCodeSlotBindings != 0;
    }

    void retainCodeSlotBinding() {
        codeSlotBindings++;
        refreshCodeSlotOwner();
    }

    void retainStashCodeSlotBinding() {
        stashCodeSlotBindings++;
        retainCodeSlotBinding();
    }

    void releaseStashCodeSlotBinding() {
        if (stashCodeSlotBindings <= 0) {
            throw new IllegalStateException("unbalanced stash CODE slot binding");
        }
        stashCodeSlotBindings--;
        releaseCodeSlotBinding();
    }

    /** Release one compiler-owned CODE edge without draining unrelated releases. */
    void releaseCodeSlotBinding() {
        if (codeSlotBindings <= 0) throw new IllegalStateException("unbalanced CODE slot binding");
        if (--codeSlotBindings != 0) return;
        refreshUntrackedReferenceOwner();
        MortalList.releaseTrackedCodeSlotAtExactBoundary(this);
    }

    private void prepareCodeSlotValue(RuntimeScalar source) {
        if (codeSlotBindings == 0 || source.type != RuntimeScalarType.CODE
                || !(source.value instanceof RuntimeCode code)) return;
        code.hasCountedCodeSlots = true;
        if (source.compiledDirectCallSiteName == null) code.hadStashRef = true;
        if (code.refCount == -1) code.refCount = 0;
    }

    void refreshCodeSlotOwner() {
        if (codeSlotBindings == 0 || type != RuntimeScalarType.CODE
                || !(value instanceof RuntimeCode code)) return;
        MortalList.setActive(true);
        prepareCodeSlotValue(this);
        incrementRefCountForContainerStore(this, PerlOwnerSlot.Kind.CODE_SLOT);
    }
    private boolean mortalReturnSnapshot;
    private boolean mortalReturnValueOwner;
    private long mortalReturnSourceOwnerIdentity;
    private long mortalReturnSourceReleaseSequence;
    private RuntimeBase mortalReturnSourceReferent;
    private boolean tiedHandlerValueOwner;
    private boolean stagedValueOwner;
    /** This snapshot temporarily carries a scalar's counted edge during local(). */
    private boolean localizedSavedCountedOwner;

    boolean isMortalReturnSnapshot() {
        return mortalReturnSnapshot && !hasStorageOwnerForVoidDiscard()
                && !RuntimeCode.isCurrentArgumentAlias(this);
    }

    boolean hasMortalReturnSnapshot() {
        return mortalReturnSnapshot;
    }

    /** Whether this scalar is runtime staging, rather than durable Perl storage. */
    boolean isTransientArgumentValueOwner() {
        return mortalReturnSnapshot || mortalReturnValueOwner || stagedValueOwner;
    }

    /** Identity of this scalar cell's counted edge, retained across assignments. */
    PerlOwnerSlot countedOwnerSlot() {
        return countedOwnerSlot;
    }

    PerlOwnerSlot untrackedReferenceOwnerSlot() {
        return untrackedReferenceOwnerSlot;
    }

    private boolean hasDurableStrongValueSlot() {
        return hasDurableStrongValueSlot(false);
    }

    /** Whether a slot is attached to a container that itself still has a Perl owner. */
    boolean hasLiveDurableStrongValueSlot() {
        return hasDurableStrongValueSlot(true);
    }

    private boolean hasDurableStrongValueSlot(boolean requireLiveContainer) {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        boolean registeredLexical = runtime != null && MyVarCleanupStack.isRegistered(this);
        boolean liveOwningContainer = false;
        for (RuntimeBase owner : containerOwnerSnapshot()) {
            if (!isStoredInContainer(owner)) {
                removeContainerOwnerTracking(owner);
                continue;
            }
            boolean liveContainerOwner = owner.hasPerlStrongOwner()
                    || GlobalVariable.isPackageRootedAggregate(owner)
                    || (runtime != null && MyVarCleanupStack.isRegistered(owner));
            if (!requireLiveContainer || liveContainerOwner) liveOwningContainer = true;
        }
        return liveOwningContainer
                || ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT) != 0
                || ownerSlotCount(PerlOwnerSlot.Kind.GLOB_COMPONENT_SLOT) != 0
                || localBindingExists
                || hasCodeSlotBinding()
                || hasClosureCaptureOwner()
                || registeredLexical
                || mortalReturnValueOwner
                || tiedHandlerValueOwner
                || stagedValueOwner
                || (referencedByScalarReference
                    && (hasScalarCellOwner() || (!requireLiveContainer && refCount > 0)))
                || this instanceof RuntimeBaseProxy;
    }

    private boolean isStoredInContainer(RuntimeBase owner) {
        return owner instanceof RuntimeHash hash
                ? hash.containsElementCellIdentity(this)
                : owner instanceof RuntimeArray array && !array.elementsAliased
                    && array.elements != null && array.elements.contains(this);
    }

    private boolean isStoredInAnyContainer(RuntimeBase owner) {
        return owner instanceof RuntimeHash hash
                ? hash.containsElementCellIdentity(this)
                : owner instanceof RuntimeArray array
                    && array.elements != null && array.elements.contains(this);
    }

    private boolean hasStoredContainerOwnerWithoutCurrentOwnerMarker() {
        if (containerOwner != null) return false;
        for (RuntimeBase owner : containerOwnerSnapshot()) {
            if (isStoredInAnyContainer(owner)) return true;
        }
        return false;
    }

    private synchronized List<RuntimeBase> containerOwnerSnapshot() {
        return new ArrayList<>(containerOwners);
    }

    private synchronized List<RuntimeBase> prepopulatedPackageAggregateOwnerSnapshot() {
        return new ArrayList<>(prepopulatedPackageAggregateOwners);
    }

    boolean isPackageAggregateUntrackedReferenceOwner() {
        if (type != RuntimeScalarType.ARRAYREFERENCE
                && type != RuntimeScalarType.HASHREFERENCE) return false;
        RuntimeBase directOwner = containerOwner;
        return directOwner != null && hasPackageAggregateOwner();
    }

    boolean hasPackageAggregateUntrackedReferenceOwner() {
        if (type != RuntimeScalarType.ARRAYREFERENCE
                && type != RuntimeScalarType.HASHREFERENCE) return false;
        return hasPackageAggregateOwner();
    }

    private boolean hasPackageAggregateOwner() {
        for (RuntimeBase owner : containerOwnerSnapshot()) {
            if (isStoredInContainer(owner)
                    && GlobalVariable.isPackageRootedAggregate(owner)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasScalarCellOwnerOtherThanClosureCapture() {
        if (!referencedByScalarReference) return false;
        if (localBindingExists || hasPendingReturnOwner()) return true;
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (slot.isActive()
                    && slot.kind() != PerlOwnerSlot.Kind.SCALAR_CAPTURE
                    && PerlOwnerSlot.isPerlOwnerKind(slot.kind())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDirectScalarCellStorageOwner() {
        if (localBindingExists || MyVarCleanupStack.isRegistered(this)
                || ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT) != 0
                || hasCodeSlotBinding() || this instanceof RuntimeBaseProxy
                || isStoredInRegisteredContainerOwner() || hasCurrentCodeStateOwner()) {
            return true;
        }
        for (RuntimeBase owner : containerOwnerSnapshot()) {
            if (owner instanceof RuntimeHash hash && hash.isPackageRootedHash()
                    && hash.elements != null && hash.elements.containsValue(this)) {
                return true;
            }
            if (owner instanceof RuntimeArray array && array.isPackageGlobalRoot
                    && array.elements != null && array.elements.contains(this)) {
                return true;
            }
        }
        return referencedByScalarReference && (refCount > 0
                || hasScalarCellOwnerOtherThanClosureCapture());
    }

    private boolean hasCurrentCodeStateOwner() {
        for (PerlOwnerSlot ownerSlot : ownerSlotSnapshot()) {
            if (ownerSlot.kind() != PerlOwnerSlot.Kind.CODE_STATE
                    || !ownerSlot.isActive() || ownerSlot.referent() != this) continue;
            RuntimeCode owner = ownerSlot.captureOwner();
            if (owner != null && owner.hasStateScalarOwner(this, ownerSlot)) return true;
        }
        return false;
    }

    private void retainUntrackedReferenceOwner(RuntimeBase referent) {
        if (untrackedReferenceOwnerSlot == null) {
            untrackedReferenceOwnerSlot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.UNTRACKED_REFERENCE);
            untrackedReferenceOwnerSlot.associateUntrackedScalarOwner(this);
        }
        // Transfer the existing identity in place. PerlOwnerSlot installs its
        // new referent before releasing the previous one, so cleanup callbacks
        // cannot observe a gap where this scalar's current value has no owner.
        untrackedReferenceOwnerSlot.acquireUntrackedIdentity(referent);
    }

    void releaseUntrackedReferenceOwner(RuntimeBase expectedReferent) {
        if (untrackedReferenceOwnerSlot == null
                || untrackedReferenceOwnerSlot.referent() != expectedReferent) return;
        untrackedReferenceOwnerSlot.release();
        WeakRefRegistry.onStrongOwnerSlotReleased(expectedReferent);
    }

    void finalizeUntrackedValueAfterScopeExit(RuntimeBase expectedReferent) {
        if (expectedReferent == null) return;
        refreshUntrackedReferenceOwner();
        if (hasDurableStrongValueSlot()) return;
        releaseUntrackedReferenceOwner(expectedReferent);
        WeakRefRegistry.finalizeUntrackedAfterOwnerScopeExit(expectedReferent);
    }

    private void updateUntrackedReferenceOwner(RuntimeBase referent) {
        // RuntimeGraphCloner may rebuild a tied handler before it binds the
        // destination runtime. Such a fresh scalar cannot be a registered weak
        // observer yet, and its owner identity still belongs on the clone.
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        boolean weakValue = runtime != null && WeakRefRegistry.isweak(this);
        boolean existingOwnerForReferent = untrackedReferenceOwnerSlot != null
                && untrackedReferenceOwnerSlot.isActive()
                && untrackedReferenceOwnerSlot.referent() == referent;
        boolean countedOwnerActive = refCountOwned
                && countedOwnerSlot != null
                && countedOwnerSlot.isActive()
                && countedOwnerSlot.referent() == referent;
        boolean packageScalarValueOwner =
                ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT) != 0;
        boolean packageAggregateValueOwner = referent != null
                && prepopulatedPackageAggregateOwnerSnapshot().stream().anyMatch(owner ->
                    isStoredInContainer(owner)
                    && GlobalVariable.isPackageRootedAggregate(owner));
        boolean localAggregateValueOwner = containerOwnerSnapshot().stream().anyMatch(owner ->
                (owner instanceof RuntimeHash || owner instanceof RuntimeArray)
                    && !GlobalVariable.isPackageRootedAggregate(owner));
        boolean liveAggregateValueOwner = localAggregateValueOwner
                && hasLiveDurableStrongValueSlot();
        boolean untrackedStrongValue = !weakValue
                && referent != null
                && referent.refCount != Integer.MIN_VALUE
                && (referent.refCount < 0
                    || existingOwnerForReferent
                    || packageScalarValueOwner
                    || packageAggregateValueOwner
                    || liveAggregateValueOwner)
                && !countedOwnerActive;
        if (hasDurableStrongValueSlot() && untrackedStrongValue) {
            retainUntrackedReferenceOwner(referent);
        } else if (untrackedReferenceOwnerSlot != null) {
            releaseUntrackedReferenceOwner(untrackedReferenceOwnerSlot.referent());
        }
    }

    void refreshUntrackedReferenceOwner() {
        updateUntrackedReferenceOwner((type & RuntimeScalarType.REFERENCE_BIT) != 0
                && value instanceof RuntimeBase referent ? referent : null);
        refreshSuspendedFrameOwnerSlots();
        refreshDetachedGlobValueOwner();
    }

    static final class ArgumentAliasOwner {
        private final RuntimeScalar scalar;
        private final PerlOwnerSlot ownerSlot;

        private ArgumentAliasOwner(RuntimeScalar scalar, PerlOwnerSlot ownerSlot) {
            this.scalar = scalar;
            this.ownerSlot = ownerSlot;
        }

        boolean matches(RuntimeScalar candidate) {
            return scalar == candidate;
        }

        RuntimeScalar scalar() {
            return scalar;
        }

        void release() {
            scalar.releaseArgumentAliasOwner(ownerSlot);
        }
    }

    synchronized ArgumentAliasOwner retainArgumentAliasOwner() {
        PerlOwnerSlot ownerSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.ARGUMENT_ALIAS);
        if (argumentAliasOwnerSlots == null) argumentAliasOwnerSlots = new ArrayList<>();
        argumentAliasOwnerSlots.add(ownerSlot);
        try {
            refreshArgumentAliasOwnerSlots();
        } catch (Throwable acquisitionFailure) {
            argumentAliasOwnerSlots.remove(ownerSlot);
            if (argumentAliasOwnerSlots.isEmpty()) argumentAliasOwnerSlots = null;
            Throwable failure = RuntimeCode.attemptCleanup(
                    acquisitionFailure, ownerSlot::release);
            RuntimeCode.rethrowCleanupFailure(failure);
        }
        return new ArgumentAliasOwner(this, ownerSlot);
    }

    private void releaseArgumentAliasOwner(PerlOwnerSlot ownerSlot) {
        synchronized (this) {
            if (argumentAliasOwnerSlots != null) {
                argumentAliasOwnerSlots.remove(ownerSlot);
                if (argumentAliasOwnerSlots.isEmpty()) argumentAliasOwnerSlots = null;
            }
        }
        ownerSlot.release();
    }

    synchronized void refreshArgumentAliasOwnerSlots() {
        if (argumentAliasOwnerSlots != null) {
            RuntimeBase referent = null;
            if (((type & RuntimeScalarType.REFERENCE_BIT) != 0 || type == RuntimeScalarType.GLOB)
                    && value instanceof RuntimeBase base
                    && !WeakRefRegistry.isweak(this)) {
                referent = base;
            }
            for (PerlOwnerSlot ownerSlot : argumentAliasOwnerSlots) {
                if (referent == null) ownerSlot.release();
                else ownerSlot.acquireArgumentAliasIdentity(referent);
            }
        }
        refreshSuspendedFrameOwnerSlots();
    }

    private synchronized boolean hasActiveArgumentAliasOwner(RuntimeBase referent) {
        if (referent == null || argumentAliasOwnerSlots == null) return false;
        for (PerlOwnerSlot ownerSlot : argumentAliasOwnerSlots) {
            if (ownerSlot.isActive() && ownerSlot.referent() == referent) return true;
        }
        return false;
    }

    /** Attach the owner identity for one lexical register retained by a suspended frame. */
    public synchronized void attachSuspendedFrameOwner(PerlOwnerSlot ownerSlot) {
        if (ownerSlot == null || ownerSlot.kind() != PerlOwnerSlot.Kind.SUSPENDED_FRAME) {
            throw new IllegalArgumentException("suspended lexical owner requires a suspended-frame slot");
        }
        if (suspendedFrameOwnerSlots == null) {
            suspendedFrameOwnerSlots = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        suspendedFrameOwnerSlots.add(ownerSlot);
        ownerSlot.associateSuspendedScalarOwner(this);
        ownerSlot.updateSuspendedFrameIdentity(currentStrongReferenceValue());
    }

    /** Detach and release one suspended-frame identity from this scalar cell. */
    public synchronized void detachSuspendedFrameOwner(PerlOwnerSlot ownerSlot) {
        if (suspendedFrameOwnerSlots != null) {
            suspendedFrameOwnerSlots.remove(ownerSlot);
            if (suspendedFrameOwnerSlots.isEmpty()) suspendedFrameOwnerSlots = null;
        }
        if (ownerSlot != null) {
            ownerSlot.associateSuspendedScalarOwner(null);
            ownerSlot.release();
        }
    }

    private void refreshSuspendedFrameOwnerSlots() {
        if (suspendedFrameOwnerSlots == null) return;
        RuntimeBase referent = currentStrongReferenceValue();
        for (PerlOwnerSlot ownerSlot : new ArrayList<>(suspendedFrameOwnerSlots)) {
            ownerSlot.updateSuspendedFrameIdentity(referent);
        }
    }

    private RuntimeBase currentStrongReferenceValue() {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if ((runtime != null && WeakRefRegistry.isweak(this))
                || ((type & RuntimeScalarType.REFERENCE_BIT) == 0 && type != RuntimeScalarType.GLOB)
                || !(value instanceof RuntimeBase referent)
                || referent.refCount == Integer.MIN_VALUE) {
            return null;
        }
        return referent;
    }

    boolean hasStorageOwnerForVoidDiscard() {
        if (hasClosureCaptureOwner() || isPackageGlobalRoot
                || MyVarCleanupStack.isRegistered(this)) {
            return true;
        }
        return countedOwnerSlot != null && countedOwnerSlot.isActive()
                && (countedOwnerSlot.kind() == PerlOwnerSlot.Kind.ARRAY_ELEMENT
                    || countedOwnerSlot.kind() == PerlOwnerSlot.Kind.HASH_ELEMENT);
    }

    /** Whether this scalar is a durable Perl cell that can be aliased by a call. */
    boolean hasPersistentArgumentStorageCell() {
        if (hasClosureCaptureOwner() || isPackageGlobalRoot || localBindingExists
                || MyVarCleanupStack.isRegistered(this)) {
            return true;
        }
        if (containerOwner instanceof RuntimeHash) return true;
        return containerOwner instanceof RuntimeArray array && !array.elementsAliased;
    }

    /** Compatibility bridge for fixtures and pre-slot scalar construction. */
    void recordCountedOwner(RuntimeBase referent) {
        if (countedOwnerSlot == null) {
            countedOwnerSlot = new PerlOwnerSlot(countedOwnerKind());
        }
        countedOwnerSlot.acquireCountedIdentity(referent);
        countedOwnerSlot.associateCountedScalarOwner(this);
    }

    /** Verify that this scalar's Perl strong edge has its paired active slot. */
    private void requireCountedOwner(RuntimeBase referent, String operation) {
        PerlOwnerSlot slot = countedOwnerSlot;
        if (!refCountOwned || slot == null || !slot.isActive()
                || slot.referent() != referent || slot.countedScalarOwner() != this
                || !PerlOwnerSlot.isCountedKind(slot.kind())) {
            throw new IllegalStateException(
                    operation + " requires a paired active Perl owner slot");
        }
    }

    /** Acquire one counted edge for a scalar that already contains its referent. */
    public static void acquireCountedOwnerForCurrentValue(
            RuntimeScalar scalar, RuntimeBase referent,
            PerlOwnerSlot.Kind intendedOwnerKind, String acquisitionSite) {
        Objects.requireNonNull(scalar, "scalar");
        Objects.requireNonNull(referent, "referent");
        Objects.requireNonNull(acquisitionSite, "acquisitionSite");
        if (intendedOwnerKind != null && !PerlOwnerSlot.isCountedKind(intendedOwnerKind)) {
            throw new IllegalArgumentException("counted acquisition requires a counted owner kind");
        }
        if (scalar.value != referent
                || (!RuntimeScalarType.isReference(scalar)
                    && scalar.type != RuntimeScalarType.GLOB
                    && scalar.type != RuntimeScalarType.CODE)) {
            throw new IllegalStateException(
                    "counted acquisition requires the scalar's current referent");
        }
        if (scalar.refCountOwned) {
            PerlOwnerSlot slot = scalar.countedOwnerSlot;
            if (slot == null || !slot.isActive() || slot.referent() != referent
                    || slot.countedScalarOwner() != scalar) {
                throw new IllegalStateException(
                        "counted scalar flag has no matching active owner slot");
            }
            slot.reclassifyCountedIdentity(intendedOwnerKind == null
                    ? scalar.countedOwnerKind() : intendedOwnerKind);
            return;
        }
        PerlOwnerSlot slot = scalar.countedOwnerSlot;
        acquireCountedOwnerForPendingValue(scalar, referent,
                intendedOwnerKind == null ? scalar.countedOwnerKind() : intendedOwnerKind,
                acquisitionSite);
    }

    /** Reify an already-counted legacy storage edge as a slot before release. */
    static void adoptExistingCountedOwnerForCurrentValue(
            RuntimeScalar scalar, RuntimeBase referent,
            PerlOwnerSlot.Kind ownerKind, String acquisitionSite) {
        Objects.requireNonNull(scalar, "scalar");
        Objects.requireNonNull(referent, "referent");
        Objects.requireNonNull(ownerKind, "ownerKind");
        Objects.requireNonNull(acquisitionSite, "acquisitionSite");
        if (!PerlOwnerSlot.isCountedKind(ownerKind)
                || scalar.value != referent
                || (!RuntimeScalarType.isReference(scalar)
                    && scalar.type != RuntimeScalarType.GLOB
                    && scalar.type != RuntimeScalarType.CODE)
                || referent.refCount <= 0
                || referent.refCount == Integer.MIN_VALUE) {
            throw new IllegalStateException(
                    "legacy owner adoption requires an existing counted storage edge at "
                            + acquisitionSite);
        }
        PerlOwnerSlot slot = scalar.countedOwnerSlot;
        if (slot == null) {
            slot = new PerlOwnerSlot(ownerKind);
            scalar.countedOwnerSlot = slot;
        } else {
            if (slot.isActive()) {
                throw new IllegalStateException(
                        "legacy owner adoption found an active counted slot at "
                                + acquisitionSite);
            }
            slot.reclassifyCountedIdentity(ownerKind);
        }
        slot.acquireCountedIdentity(referent);
        slot.associateCountedScalarOwner(scalar);
        scalar.refCountOwned = true;
        referent.recordOwner(scalar, acquisitionSite);
    }

    /** Acquire a paired count and identity before a pending scalar value is published. */
    private static void acquireCountedOwnerForPendingValue(
            RuntimeScalar scalar, RuntimeBase referent,
            PerlOwnerSlot.Kind ownerKind, String acquisitionSite) {
        Objects.requireNonNull(scalar, "scalar");
        Objects.requireNonNull(referent, "referent");
        Objects.requireNonNull(ownerKind, "ownerKind");
        Objects.requireNonNull(acquisitionSite, "acquisitionSite");
        if (!PerlOwnerSlot.isCountedKind(ownerKind)) {
            throw new IllegalArgumentException("counted acquisition requires a counted owner kind");
        }
        if (scalar.refCountOwned) {
            throw new IllegalStateException(
                    "pending counted acquisition requires the scalar's old edge to be retired");
        }
        if (referent.refCount < 0 || referent.refCount == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "counted acquisition requires an available tracked reference count");
        }

        PerlOwnerSlot slot = scalar.countedOwnerSlot;
        if (slot == null) {
            slot = new PerlOwnerSlot(ownerKind);
            scalar.countedOwnerSlot = slot;
        } else {
            if (slot.isActive()) {
                throw new IllegalStateException(
                        "unowned scalar already has an active counted owner slot");
            }
            slot.reclassifyCountedIdentity(ownerKind);
        }
        slot.acquireCountedScalarOwner(scalar, referent, acquisitionSite);
    }

    boolean isCurrentAggregateElementOwner(RuntimeBase referent, PerlOwnerSlot slot) {
        if (countedOwnerSlot != slot || !refCountOwned || value != referent
                || !slot.isActive() || slot.referent() != referent) {
            return false;
        }
        if (localizedSavedCountedOwner) {
            return slot.kind() == PerlOwnerSlot.Kind.ARRAY_ELEMENT
                    || slot.kind() == PerlOwnerSlot.Kind.HASH_ELEMENT;
        }
        if (slot.kind() == PerlOwnerSlot.Kind.ARRAY_ELEMENT) {
            return containerOwner instanceof RuntimeArray array
                    && !array.elementsAliased && array.elements.contains(this);
        }
        return slot.kind() == PerlOwnerSlot.Kind.HASH_ELEMENT
                && containerOwner instanceof RuntimeHash hash
                && hash.elements.containsValue(this);
    }

    boolean isCurrentCountedScalarOwner(RuntimeBase referent, PerlOwnerSlot slot) {
        PerlOwnerSlot.Kind kind = slot.kind();
        if (localizedSavedCountedOwner) {
            return countedOwnerSlot == slot && refCountOwned && value == referent
                    && slot.isActive() && slot.referent() == referent;
        }
        boolean activeArgumentAlias = kind == PerlOwnerSlot.Kind.SCALAR_CELL
                && hasActiveArgumentAliasOwner(referent);
        if ((kind != PerlOwnerSlot.Kind.SCALAR_CELL
                && kind != PerlOwnerSlot.Kind.CODE_SLOT
                && kind != PerlOwnerSlot.Kind.ARGUMENT_VALUE
                && kind != PerlOwnerSlot.Kind.ARGUMENT_CONTAINER
                && kind != PerlOwnerSlot.Kind.RETURN_VALUE
                && kind != PerlOwnerSlot.Kind.TIE_HANDLER
                && kind != PerlOwnerSlot.Kind.ARGUMENT_TIE_HANDLER)
                || countedOwnerSlot != slot || !refCountOwned || value != referent
                || ((type & RuntimeScalarType.REFERENCE_BIT) == 0
                    && type != RuntimeScalarType.GLOB
                    && type != RuntimeScalarType.CODE)
                || WeakRefRegistry.isweak(this) || !slot.isActive()
                || slot.referent() != referent) {
            return false;
        }
        if (kind == PerlOwnerSlot.Kind.ARGUMENT_CONTAINER) return stagedValueOwner;
        if (kind == PerlOwnerSlot.Kind.SCALAR_CELL
                && hasStoredContainerOwnerWithoutCurrentOwnerMarker()
                && !localBindingExists
                && !MyVarCleanupStack.isRegistered(this)
                && ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT) == 0
                && !hasCodeSlotBinding()
                && !(this instanceof RuntimeBaseProxy)
                && !activeArgumentAlias) {
            return false;
        }
        if (kind != PerlOwnerSlot.Kind.SCALAR_CELL
                || hasDirectScalarCellStorageOwner() || activeArgumentAlias) {
            return true;
        }
        return hasCurrentAggregateStorageOwner()
                || isCurrentCapturedScalarCellOwner(referent, slot);
    }

    /**
     * Narrow compatibility proof for a standalone scalar cell whose storage
     * has not yet been registered with a lexical, package, code, or container
     * owner. This does not accept stale container associations or aliases.
     */
    boolean isUnregisteredCountedScalarCellOwner(RuntimeBase referent, PerlOwnerSlot slot) {
        if (slot.kind() != PerlOwnerSlot.Kind.SCALAR_CELL
                || countedOwnerSlot != slot || slot.countedScalarOwner() != this
                || !refCountOwned || value != referent
                || ((type & RuntimeScalarType.REFERENCE_BIT) == 0
                    && type != RuntimeScalarType.GLOB
                    && type != RuntimeScalarType.CODE)
                || WeakRefRegistry.isweak(this) || scopeExited
                || !slot.isActive() || slot.referent() != referent
                || hasClosureCaptureOwner() || hasActiveArgumentAliasOwner(referent)
                || !containerOwnerSnapshot().isEmpty()
                || hasDirectScalarCellStorageOwner()) {
            return false;
        }
        return true;
    }

    private boolean hasCurrentAggregateStorageOwner() {
        for (RuntimeBase owner : containerOwnerSnapshot()) {
            if (isStoredInAnyContainer(owner)) return true;
        }
        return false;
    }

    boolean isCurrentCapturedScalarCellOwner(RuntimeBase referent, PerlOwnerSlot slot) {
        if (slot.kind() != PerlOwnerSlot.Kind.SCALAR_CELL
                || countedOwnerSlot != slot || !refCountOwned || value != referent
                || ((type & RuntimeScalarType.REFERENCE_BIT) == 0
                    && type != RuntimeScalarType.GLOB
                    && type != RuntimeScalarType.CODE)
                || WeakRefRegistry.isweak(this) || !slot.isActive()
                || slot.referent() != referent || !hasClosureCaptureOwner()) {
            return false;
        }
        for (PerlOwnerSlot captureSlot : ownerSlotSnapshot()) {
            if (captureSlot.kind() != PerlOwnerSlot.Kind.SCALAR_CAPTURE
                    || !captureSlot.isActive() || captureSlot.referent() != this) continue;
            RuntimeCode captureOwner = captureSlot.captureOwner();
            if (captureOwner != null && captureOwner.hasPerlOwnerForCapture()) return true;
        }
        return false;
    }

    public PerlOwnerSlot.Kind countedOwnerKind() {
        return codeSlotBindings != 0 ? PerlOwnerSlot.Kind.CODE_SLOT
                : containerOwner instanceof RuntimeHash ? PerlOwnerSlot.Kind.HASH_ELEMENT
                : containerOwner instanceof RuntimeArray array
                    && !array.elementsAliased
                    ? PerlOwnerSlot.Kind.ARRAY_ELEMENT : PerlOwnerSlot.Kind.SCALAR_CELL;
    }

    /** Copy a reference into a return-owned scalar and defer its exact release. */
    public static RuntimeScalar copyToMortalReturnSlot(RuntimeScalar source) {
        RuntimeScalar copy = new RuntimeScalar(source);
        copy.mortalReturnSnapshot = true;
        copy.copiedFromArgumentFrame = null;
        copy.captureMortalReturnSourceOwner(source);
        incrementRefCountForContainerStore(copy, PerlOwnerSlot.Kind.RETURN_VALUE);
        if (copy.refCountOwned) {
            MortalList.deferDecrementIfTracked(copy);
        } else {
            copy.mortalReturnValueOwner = true;
            copy.refreshUntrackedReferenceOwner();
            if (copy.untrackedReferenceOwnerSlot != null) {
                MortalList.deferUntrackedReturnValueOwnerRelease(copy);
            } else {
                copy.mortalReturnValueOwner = false;
            }
        }
        source.releasePostfixReferenceTransferOwner(
                "RuntimeScalar.copyToMortalReturnSlot postfix temporary transfer");
        return copy;
    }

    /**
     * Retire the owner moved into a postfix result after another Perl value
     * has taken responsibility for that result. Assignment and subroutine
     * return are both such transfers; the latter first acquires its mortal
     * return-slot owner in {@link #copyToMortalReturnSlot(RuntimeScalar)}.
     */
    private void releasePostfixReferenceTransferOwner(String releaseSite) {
        if (!postfixReferenceOwnershipTransfer) return;
        postfixReferenceOwnershipTransfer = false;
        if (refCountOwned
                && (type & REFERENCE_BIT) != 0
                && value instanceof RuntimeBase) {
            MortalList.releaseCountedOwnerImmediately(this, releaseSite);
        }
    }

    private void captureMortalReturnSourceOwner(RuntimeScalar source) {
        if (source == null || !(source.value instanceof RuntimeBase referent)) return;
        PerlOwnerSlot sourceSlot = source.countedOwnerSlot;
        if (sourceSlot == null || sourceSlot.referent() != referent) return;
        if (source.refCountOwned && sourceSlot.isActive()) {
            mortalReturnSourceOwnerIdentity = sourceSlot.identity();
            mortalReturnSourceReleaseSequence = sourceSlot.nextReleaseSequenceValue();
            mortalReturnSourceReferent = referent;
            return;
        }
        PerlOwnerSlot.PendingRelease pendingRelease =
                MortalList.pendingCountedReleaseFor(source, referent);
        if (pendingRelease != null) {
            mortalReturnSourceOwnerIdentity = pendingRelease.ownerSlotIdentity();
            mortalReturnSourceReleaseSequence = pendingRelease.sequence();
            mortalReturnSourceReferent = referent;
        }
    }

    long mortalReturnSourceOwnerIdentity() {
        return mortalReturnSourceOwnerIdentity;
    }

    long mortalReturnSourceReleaseSequence() {
        return mortalReturnSourceReleaseSequence;
    }

    RuntimeBase mortalReturnSourceReferent() {
        return mortalReturnSourceReferent;
    }

    void finishMortalReturnHandoff() {
        mortalReturnSnapshot = false;
        mortalReturnSourceOwnerIdentity = 0;
        mortalReturnSourceReleaseSequence = 0;
        mortalReturnSourceReferent = null;
    }

    void releaseMortalReturnValueOwner() {
        if (!mortalReturnValueOwner) return;
        mortalReturnValueOwner = false;
        refreshUntrackedReferenceOwner();
    }

    /** Snapshot one tie wrapper's strong handler edge independently of its source. */
    static RuntimeScalar retainTiedHandlerValue(RuntimeScalar source) {
        return retainTiedHandlerValue(source, PerlOwnerSlot.Kind.TIE_HANDLER);
    }

    /** Retain a tie handler for an owner slot with its own Perl lifetime. */
    static RuntimeScalar retainTiedHandlerValue(
            RuntimeScalar source, PerlOwnerSlot.Kind ownerKind) {
        if (source == null) return null;
        if (ownerKind != PerlOwnerSlot.Kind.TIE_HANDLER
                && ownerKind != PerlOwnerSlot.Kind.ARGUMENT_TIE_HANDLER) {
            throw new IllegalArgumentException("invalid tie-handler owner kind");
        }
        RuntimeScalar owner = new RuntimeScalar(source);
        incrementRefCountForContainerStore(owner, ownerKind);
        owner.tiedHandlerValueOwner = true;
        owner.refreshUntrackedReferenceOwner();
        return owner;
    }

    /** Retire the tie owner while retaining a borrow for deferred magic restoration. */
    static void releaseTiedHandlerValue(RuntimeScalar owner) {
        if (owner == null || !owner.tiedHandlerValueOwner) return;
        owner.tiedHandlerValueOwner = false;
        owner.refreshUntrackedReferenceOwner();
        if (owner.refCountOwned) MortalList.deferDecrementIfTracked(owner);
        if (PerlRuntime.currentOrNull() != null) MortalList.flushAboveMark();
    }

    static RuntimeScalar retainEvalReturnValue(RuntimeScalar source) {
        RuntimeScalar owner = retainStagedArgumentValue(source);
        if (owner.refCountOwned) {
            owner.countedOwnerSlot.reclassifyCountedIdentity(PerlOwnerSlot.Kind.RETURN_VALUE);
        }
        return owner;
    }

    static RuntimeScalar retainStagedArgumentValue(RuntimeScalar source) {
        RuntimeScalar owner = new RuntimeScalar();
        owner.set(source);
        owner.stagedValueOwner = true;
        owner.refreshUntrackedReferenceOwner();
        if (owner.refCountOwned) {
            owner.countedOwnerSlot.reclassifyCountedIdentity(PerlOwnerSlot.Kind.ARGUMENT_VALUE);
        }
        return owner;
    }

    /** Acquire the containing aggregate edge for one active aliased argument. */
    static RuntimeScalar retainArgumentContainerValue(RuntimeBase container) {
        if (!(container instanceof RuntimeArray || container instanceof RuntimeHash)) return null;
        RuntimeScalar owner = new RuntimeScalar();
        owner.type = container instanceof RuntimeArray
                ? RuntimeScalarType.ARRAYREFERENCE : RuntimeScalarType.HASHREFERENCE;
        owner.value = container;
        owner.stagedValueOwner = true;
        incrementRefCountForContainerStore(owner, PerlOwnerSlot.Kind.ARGUMENT_CONTAINER);
        if (!owner.refCountOwned) owner.refreshUntrackedReferenceOwner();
        return owner;
    }

    /** Release one argument-container edge and complete its exact count transition now. */
    static void releaseArgumentContainerValue(RuntimeScalar owner) {
        if (owner == null || !owner.stagedValueOwner) return;
        owner.stagedValueOwner = false;
        owner.refreshUntrackedReferenceOwner();
        MortalList.releaseCountedOwnerImmediately(
                owner, "RuntimeCode argument-container alias release");
    }

    /** Release a temporary call/return owner after its boundary has drained. */
    static void releaseStagedArgumentValue(RuntimeScalar owner) {
        if (owner == null || !owner.stagedValueOwner) return;
        owner.stagedValueOwner = false;
        owner.refreshUntrackedReferenceOwner();
        MortalList.deferDecrementIfTracked(owner);
    }

    void releaseCountedOwner(RuntimeBase referent) {
        if (countedOwnerSlot != null && countedOwnerSlot.referent() == referent) {
            countedOwnerSlot.release();
        }
    }

    /** Retire a counted storage edge during host-side graph construction. */
    static void releaseCountedOwnerWithoutPerlRuntime(
            RuntimeScalar owner, RuntimeBase referent, String releaseSite) {
        MortalList.releaseCountedOwnerWithoutPerlRuntime(owner, referent, releaseSite);
    }

    /** Retire the paired Perl edge and IO lease of an assignment transport copy. */
    static void releaseMaterializedAssignmentCopy(
            RuntimeScalar scalar, String target, RuntimeBase returned) {
        if (scalar == null) return;
        releaseIoOwnerPreservingReturned(scalar, returned);
        if (!scalar.refCountOwned
                || ((scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0
                    && scalar.type != RuntimeScalarType.GLOB)
                || !(scalar.value instanceof RuntimeBase base)
                || base.refCount <= 0) return;
        MortalList.releaseCountedOwnerImmediately(
                scalar, "RuntimeList materialized-copy undo (" + target + ")");
    }

    PerlOwnerSlot.PendingRelease deferCountedOwnerRelease(RuntimeBase referent) {
        return releaseCountedOwnerIdentity(referent, null, false);
    }

    /**
     * Retire this scalar's exact counted Perl edge as one ownership transition.
     * Deferred queueing leaves the trace owner in place until RuntimeBase has
     * captured it; immediate releases remove that trace entry here.
     */
    PerlOwnerSlot.PendingRelease releaseCountedOwnerIdentity(
            RuntimeBase referent, String releaseSite, boolean releaseTrace) {
        PerlOwnerSlot slot = countedOwnerSlot;
        if (slot == null) {
            return null;
        }
        if (!refCountOwned || !slot.isActive() || slot.referent() != referent
                || slot.countedScalarOwner() != this
                || !PerlOwnerSlot.isCountedKind(slot.kind())) return null;
        PerlOwnerSlot.PendingRelease release = slot.releaseCountedIdentity();
        if (release == null) return null;
        refCountOwned = false;
        if (releaseTrace) referent.releaseOwner(this, releaseSite);
        return release;
    }

    private void transferCountedOwnerTo(RuntimeScalar destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination == this) {
            throw new IllegalArgumentException("counted owner transfer requires a distinct scalar");
        }
        if (!refCountOwned || countedOwnerSlot == null || !countedOwnerSlot.isActive()
                || countedOwnerSlot.referent() != value
                || destination.value != value) {
            throw new IllegalStateException(
                    "counted owner transfer requires the source and destination to carry the same active owner");
        }
        destination.countedOwnerSlot = countedOwnerSlot;
        countedOwnerSlot = null;
        destination.countedOwnerSlot.associateCountedScalarOwner(destination);
        destination.refCountOwned = true;
        refCountOwned = false;
    }

    /**
     * Promote the old value returned by postfix ++/-- to the existing counted
     * edge. A borrowed source may have no scalar token, so this Perl value
     * transition adopts the already-counted edge into the returned scalar.
     */
    private void transferPostfixReferenceOwnerTo(RuntimeScalar destination) {
        Objects.requireNonNull(destination, "destination");
        if (!(value instanceof RuntimeBase referent) || destination.value != referent) {
            throw new IllegalStateException(
                    "postfix owner promotion requires matching reference values");
        }
        if (refCountOwned && countedOwnerSlot != null && countedOwnerSlot.isActive()
                && countedOwnerSlot.referent() == referent) {
            transferCountedOwnerTo(destination);
            return;
        }
        if (refCountOwned) {
            throw new IllegalStateException(
                    "postfix owner promotion found a counted scalar without its active token");
        }
        adoptExistingCountedOwnerForCurrentValue(
                destination, referent, destination.countedOwnerKind(),
                "postfix reference result promotion");
    }

    /** Move a non-counted strong value edge with a localized scalar snapshot. */
    private void transferUntrackedOwnerTo(RuntimeScalar destination) {
        destination.untrackedReferenceOwnerSlot = untrackedReferenceOwnerSlot;
        untrackedReferenceOwnerSlot = null;
        if (destination.untrackedReferenceOwnerSlot != null) {
            destination.untrackedReferenceOwnerSlot.associateUntrackedScalarOwner(destination);
        }
    }

    private static void releaseDetachedUntrackedOwner(PerlOwnerSlot ownerSlot) {
        if (ownerSlot == null) return;
        RuntimeBase referent = ownerSlot.referent();
        ownerSlot.associateUntrackedScalarOwner(null);
        ownerSlot.release();
        if (referent != null) WeakRefRegistry.onStrongOwnerSlotReleased(referent);
    }


    /** A postfix ++/-- result transfers, rather than duplicates, its reference owner on assignment. */
    private boolean postfixReferenceOwnershipTransfer;

    /**
     * True for the synthetic {@code $_[0]} scalar passed to DESTROY.
     * Perl treats that invocant as read-only for operations such as
     * {@code weaken($_[0])}, but normal reads and method dispatch still
     * need the lightweight mutable RuntimeScalar representation.
     */
    public boolean destroySelfArgument;

    /** True after this scalar has acquired per-runtime {@code pos()} cache state. */
    volatile boolean posCacheEntryCreated;

    /** Identity for the current contents edge held through a scalar reference. */
    public PerlOwnerSlot scalarReferenceContentsOwner;
    private Set<PerlOwnerSlot> scalarReferenceContentsOwners;

    public boolean hasScalarReferenceContentsOwner() {
        return scalarReferenceContentsOwner != null
                && scalarReferenceContentsOwner.isActive();
    }

    synchronized void registerScalarReferenceContentsOwner(PerlOwnerSlot owner) {
        if (scalarReferenceContentsOwners == null) {
            scalarReferenceContentsOwners = Collections.newSetFromMap(
                    new IdentityHashMap<>());
        }
        scalarReferenceContentsOwners.add(owner);
    }

    synchronized void unregisterScalarReferenceContentsOwner(PerlOwnerSlot owner) {
        if (scalarReferenceContentsOwners != null) {
            scalarReferenceContentsOwners.remove(owner);
            if (scalarReferenceContentsOwners.isEmpty()) scalarReferenceContentsOwners = null;
        }
    }

    synchronized boolean hasScalarReferenceContentsOwner(
            PerlOwnerSlot except, RuntimeBase referent) {
        if (scalarReferenceContentsOwners == null) return false;
        for (PerlOwnerSlot owner : scalarReferenceContentsOwners) {
            if (owner != except && owner.isActive() && owner.referent() == referent) return true;
        }
        return false;
    }

    private synchronized List<PerlOwnerSlot> scalarReferenceContentsOwnersSnapshot() {
        return scalarReferenceContentsOwners == null || scalarReferenceContentsOwners.isEmpty()
                ? List.of() : List.copyOf(scalarReferenceContentsOwners);
    }

    synchronized void clearScalarReferenceContentsOwner(PerlOwnerSlot owner) {
        if (scalarReferenceContentsOwner == owner) scalarReferenceContentsOwner = null;
    }

    /**
     * True once a scalar reference has been created to this scalar, e.g.
     * {@code \$x}. Interpreter lexical assignment must preserve the scalar
     * object's identity after that point so existing references continue to
     * observe later assignments to the lexical.
     */
    public boolean referencedByScalarReference;

    /**
     * A reference was made after control flow materialized this lexical slot
     * before its declaration ran. The next declaration must retain that pad
     * cell so the forward reference remains an alias; ordinary escaped
     * {@code \$lexical} references must not set this marker.
     */
    private boolean retainMaterializedForwardReference;

    /** True only while this scalar represents a lexical slot skipped by control flow. */
    private boolean materializedBeforeLexicalDeclaration;

    /**
     * True when {@link #createReference()} promoted an otherwise-unbound
     * scalar temporary into selective reference counting. Such a referent has
     * no hidden lexical, closure, global, or aggregate owner, so weakening its
     * sole reference can clear it without a reachability walk.
     */
    boolean ephemeralScalarReferenceReferent;

    // Constructors
    public RuntimeScalar() {
        this.type = UNDEF;
    }

    /**
     * Return a writable lexical storage cell, creating Perl undef storage for
     * a declaration whose initializer was skipped by control flow.
     *
     * <p>The JVM backend keeps lexical variables in local slots. A forward
     * jump can reach refgen before the declaration initialized that slot, but
     * refgen must still bind to a distinct mutable cell.</p>
     */
    public static RuntimeScalar materializeLexicalCell(RuntimeScalar scalar) {
        // JVM lexical loads pass through this helper so skipped declarations
        // can be materialized on demand.  Ordinary scalar cells need no
        // transformation; keep their common case to a class and sentinel
        // check before the proxy/null handling below.
        if (scalar != null
                && scalar.getClass() == RuntimeScalar.class
                && scalar.refCount != Integer.MIN_VALUE) {
            return scalar;
        }
        // A foreach literal alias deliberately occupies a lexical slot.  It
        // is not an uninitialized declaration placeholder: retain its guard
        // so a mutation reports Perl's read-only-value error.
        if (scalar instanceof ReadOnlyAlias) {
            return scalar;
        }
        // Register recycling can leave the shared read-only undef placeholder
        // in a declaration skipped by control flow. It is not lexical storage
        // and must never become the target of a later refalias or assignment.
        // A skipped declaration can also inherit a pooled literal from a
        // recycled register.  Like scalarUndef, that literal is a read-only
        // expression result rather than lexical storage.  Refgen must never
        // expose it as a pad cell: later refaliasing would try to mutate the
        // literal and fail with "Modification of a read-only value attempted".
        if (scalar == null || scalar == RuntimeScalarCache.scalarUndef) {
            RuntimeScalar materialized = new RuntimeScalar();
            materialized.materializedBeforeLexicalDeclaration = true;
            return materialized;
        }
        if (scalar.refCount == Integer.MIN_VALUE) {
            // JVM local-slot reuse can assign a new declaration's initializer
            // into a destroyed prior cell before refgen materializes it. Keep
            // that new payload, but give the declaration a fresh scalar
            // identity rather than exposing the destroyed referent.
            return new RuntimeScalar(scalar);
        }
        if (scalar instanceof RuntimeScalarReadOnly) {
            // Loop iterators and constants may occupy a lexical register.
            // They need a new writable cell, but must retain their current
            // value rather than being mistaken for a skipped declaration.
            // A RuntimeScalar merely marked READONLY_SCALAR is different: it
            // is a real lexical that Internals::SvREADONLY froze at runtime.
            // Replacing it here would make a later reference escape a clone,
            // allowing the next block invocation to mutate the prior cell.
            RuntimeScalar writable = new RuntimeScalar();
            writable.set(scalar);
            return writable;
        }
        return scalar;
    }

    /**
     * Initialize a scalar declaration without replacing a cell retained by
     * a reference made after control flow skipped that declaration.
     */
    public static RuntimeScalar initializeLexicalCell(RuntimeScalar scalar) {
        // Interpreter register arrays are reused between calls.  Preserve the
        // writable cell installed by a forward refalias, but never retain a
        // destroyed or read-only scalar left in the same register by an
        // earlier frame (notably a literal signature argument).
        boolean retainForwardReference = scalar != null
                && scalar.retainMaterializedForwardReference
                && scalar.refCount != Integer.MIN_VALUE
                && !(scalar instanceof RuntimeScalarReadOnly)
                && scalar.type != RuntimeScalarType.READONLY_SCALAR;
        if (retainForwardReference) {
            scalar.retainMaterializedForwardReference = false;
            scalar.materializedBeforeLexicalDeclaration = false;
            return scalar;
        }
        return new RuntimeScalar();
    }

    /** Consume the one declaration-retention permit created by forward refgen. */
    public boolean consumeMaterializedForwardReference() {
        if (!retainMaterializedForwardReference) return false;
        retainMaterializedForwardReference = false;
        materializedBeforeLexicalDeclaration = false;
        return true;
    }

    public RuntimeScalar(long value) {
        initializeWithLong(value);
    }

    /** Construct an exact integer literal while retaining its source spelling. */
    public RuntimeScalar(long value, String numericLiteralText) {
        initializeWithLong(value);
        this.numericLiteralText = numericLiteralText;
    }

    public RuntimeScalar(Long value) {
        initializeWithLong(value);
    }

    public RuntimeScalar(BigInteger value) {
        this.tainted = false;
        this.numericContextSeen = false;
        setIntegerValue(value);
    }

    /** Construct an exact large integer literal while retaining its source spelling. */
    public RuntimeScalar(BigInteger value, String numericLiteralText) {
        this.tainted = false;
        this.numericContextSeen = false;
        setIntegerValue(value);
        this.numericLiteralText = numericLiteralText;
    }

    public RuntimeScalar(int value) {
        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
    }

    public RuntimeScalar(Integer value) {
        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
    }

    public RuntimeScalar(double value) {
        this.type = DOUBLE;
        this.value = value;
    }

    public RuntimeScalar(double value, String numericLiteralText) {
        this.type = DOUBLE;
        this.value = value;
        this.numericLiteralText = numericLiteralText;
    }

    public RuntimeScalar(Double value) {
        this.type = DOUBLE;
        this.value = value;
    }

    public RuntimeScalar(String value) {
        if (value == null) {
            this.type = UNDEF;
        } else {
            this.type = RuntimeScalarType.STRING;
        }
        this.value = value;
    }

    public RuntimeScalar(boolean value) {
        this.type = RuntimeScalarType.BOOLEAN;
        this.value = value;
    }

    /** Attach the source-level lexical name used by handle diagnostics. */
    public RuntimeScalar setLexicalDisplayName(String name) {
        this.lexicalDisplayName = name;
        return this;
    }

    public RuntimeScalar(Boolean value) {
        this.type = RuntimeScalarType.BOOLEAN;
        this.value = value;
    }

    public RuntimeScalar(RuntimeScalar scalar) {
        if (scalar instanceof ScalarSpecialVariable ssv) {
            scalar = ssv.getValueAsScalar();
        } else if (scalar instanceof OutputFormatVariable) {
            scalar = new RuntimeScalar(scalar.getInt());
        } else if (scalar instanceof CurrentFormatVariable) {
            scalar = scalar.getDefinedBoolean()
                    ? new RuntimeScalar(scalar.toString()) : new RuntimeScalar();
        } else if (scalar.type == TIED_SCALAR) {
            scalar = scalar.tiedFetch();
        } else if (scalar.type == READONLY_SCALAR) {
            scalar = (RuntimeScalar) scalar.value;
        }
        if (scalar.type == STRING || scalar.type == BYTE_STRING) {
            scalar.materializeGrowingString();
        }
        this.type = scalar.type;
        this.value = copyScalarPayload(scalar.type, scalar.value);
        this.utf8UncheckedOctets = scalar.utf8UncheckedOctets;
        this.utf8MalformedWarning = scalar.utf8MalformedWarning;
        this.tainted = scalar.tainted;
        this.numericLiteralText = scalar.numericLiteralText;
        this.numericContextSeen = scalar.numericContextSeen;
        this.firstClassRegexScalar = scalar.firstClassRegexScalar;
        this.firstClassRegexReferent = scalar.firstClassRegexReferent;
        this.firstClassRegexValue = scalar.firstClassRegexValue;
        this.formatPictureTainted = scalar.formatPictureTainted;
        this.lexicalSubName = scalar.lexicalSubName;
        this.lexicalDisplayName = scalar.lexicalDisplayName;
        this.lexicalSubPackageName = scalar.lexicalSubPackageName;
        this.lexicalSubPackageCodeDefinedAtDeclaration = scalar.lexicalSubPackageCodeDefinedAtDeclaration;
        Object argumentFrame = RuntimeCode.currentArgumentAliasFrame(scalar);
        this.copiedFromArgumentFrame = argumentFrame != null
                ? argumentFrame : scalar.copiedFromArgumentFrame;
        // A scalar detached from lexical and package registration and from a
        // durable container is an expression temporary. Move its socket ownership
        // token into the copy (notably RuntimeList return materialization)
        // instead of leaving an unreachable phantom holder behind.
        if (scalar.hasIoOwner() && scalar.containerOwner == null
                && !scalar.isPackageGlobalRoot
                && !MyVarCleanupStack.isRegistered(scalar)) {
            transferIoOwner(scalar, this);
        }
        refreshDetachedGlobValueOwner();
    }

    public RuntimeScalar(RuntimeCode value) {
        this.type = RuntimeScalarType.CODE;
        this.value = value;
    }

    public RuntimeScalar(RuntimeRegex value) {
        this.type = RuntimeScalarType.REGEX;
        this.value = value;
    }

    public RuntimeScalar(RuntimeIO value) {
        if (value == null) {
            this.type = UNDEF;
        } else {
            this.type = RuntimeScalarType.GLOB;
        }
        this.value = value;
    }

    public RuntimeScalar(RuntimeGlob value) {
        if (value == null) {
            this.type = UNDEF;
        } else {
            this.type = value.type;
            // Create a detached copy so that `local *GLOB` restore doesn't affect
            // scalars that captured the glob value during the local scope.
            // This implements Perl's behavior where `my $fh = *FH` inside a local
            // scope retains the IO even after the scope ends.
            // Skip detached copy for anonymous globs (null globName) since they
            // don't participate in local/global scope and we need to preserve
            // their local slots (e.g., from stash delete).
            if (value.globName != null && !value.isSlotSnapshot()) {
                RuntimeGlob source = value;
                value = source.createDetachedCopy();
                if (source.namespaceMove != null) {
                    source.releaseDetachedComponentsIfUnowned();
                }
            }
            }
        }
        this.value = value;
        this.scalarStoredGlob = value != null;
        refreshDetachedGlobValueOwner();
    }

    public RuntimeScalar(byte[] bytes) {
        this.value = new String(bytes, StandardCharsets.ISO_8859_1);
        this.type = BYTE_STRING;
    }

    /**
     * Returns a new RuntimeScalar instance with the given value.
     * Tries to store the value as a known type if possible, otherwise stores it as Object.
     *
     * @param value the value to store in the new RuntimeScalar instance
     */
    public RuntimeScalar(Object value) {
        // System.out.println("RuntimeScalar " + (value == null ? "null" : value.getClass()));
        switch (value) {
            case null -> {
                this.type = UNDEF;
                this.value = null;
            }
            case RuntimeGlob v -> {
                RuntimeScalar tmp = new RuntimeScalar(v);
                this.type = tmp.type;
                this.value = tmp.value;  // Use the detached copy from the constructor
            }
            case RuntimeIO v -> {
                RuntimeScalar tmp = new RuntimeScalar(v);
                this.type = tmp.type;
                this.value = v;
            }
            case RuntimeScalar scalar -> {
                this.type = scalar.type;
                this.value = copyScalarPayload(scalar.type, scalar.value);
                this.utf8UncheckedOctets = scalar.utf8UncheckedOctets;
                this.utf8MalformedWarning = scalar.utf8MalformedWarning;
                this.tainted = scalar.tainted;
                this.numericLiteralText = scalar.numericLiteralText;
                this.numericContextSeen = scalar.numericContextSeen;
                this.scalarStoredGlob = scalar.scalarStoredGlob;
                this.firstClassRegexScalar = scalar.firstClassRegexScalar;
                this.firstClassRegexReferent = scalar.firstClassRegexReferent;
                this.firstClassRegexValue = scalar.firstClassRegexValue;
                this.formatPictureTainted = scalar.formatPictureTainted;
                this.lexicalSubName = scalar.lexicalSubName;
                this.lexicalDisplayName = scalar.lexicalDisplayName;
                this.lexicalSubPackageName = scalar.lexicalSubPackageName;
                this.lexicalSubPackageCodeDefinedAtDeclaration = scalar.lexicalSubPackageCodeDefinedAtDeclaration;
            }
            case Long longValue -> initializeWithLong(longValue);
            case BigInteger integerValue -> setIntegerValue(integerValue);
            default -> {
                // Look for a known type, default to JAVAOBJECT if not found
                this.type = typeMap.getOrDefault(value.getClass(), RuntimeScalarType.JAVAOBJECT);
                this.value = value;
            }
        }
    }

    /**
     * Returns a new RuntimeScalar instance with the given value.
     * Tries to store the value as a known type if possible, otherwise stores it as String.
     *
     * @param value the value to store in the new RuntimeScalar instance
     * @return a new RuntimeScalar instance with the given value
     */
    public static RuntimeScalar newScalarOrString(Object value) {
        RuntimeScalar newScalar = new RuntimeScalar(value);
        if (newScalar.type == RuntimeScalarType.JAVAOBJECT) {
            // Unknown type, convert to string
            newScalar.type = RuntimeScalarType.STRING;
            newScalar.value = value.toString();
        }
        return newScalar;
    }

    public static RuntimeScalar undef() {
        return scalarUndef;
    }

    /**
     * Stores a value into the tied variable.
     * This method must be implemented by subclasses to provide the appropriate
     * arguments to the STORE method.
     *
     * @param v the value to store
     * @return the result of the STORE operation
     */
    public RuntimeScalar tiedStore(RuntimeScalar v) {
        if (value instanceof TieHandle) {
            // Tied handles don't support scalar STORE; handle operations
            // go through TieHandle's own methods (tiedPrint, etc.)
            return v;
        }
        return ((TiedVariableBase) value).tiedStore(v);
    }

    /**
     * Fetches the current value from the tied variable.
     * This method must be implemented by subclasses to provide the appropriate
     * arguments to the FETCH method.
     *
     * @return the fetched value
     */
    public RuntimeScalar tiedFetch() {
        if (value instanceof TieHandle tieHandle) {
            // Tied handles don't support scalar FETCH.
            // Return the tied object so callers get a usable blessed reference.
            return tieHandle.getSelf();
        }
        return ((TiedVariableBase) value).fetch();
    }

    /**
     * Resolves scalar tie magic exactly once for a single operand evaluation.
     *
     * <p>Operators must call this at their boundary before inspecting an
     * operand more than once.  Calling conversion helpers directly on a tied
     * scalar can otherwise dispatch FETCH separately for checks such as
     * definedness, overload eligibility, and numeric conversion.  Keeping
     * this explicit is important: two syntactic occurrences of the same tied
     * variable still each call FETCH.</p>
     */
    public static RuntimeScalar fetchTiedOnce(RuntimeScalar scalar) {
        if (scalar instanceof TiedVariableBase tiedVariable) {
            return tiedVariable.tiedFetch();
        }
        return scalar.type == TIED_SCALAR ? scalar.tiedFetch() : scalar;
    }

    /** Resolves a scalar reference, then its tied-scalar magic, once. */
    public static RuntimeScalar dereferenceAndFetchOnce(RuntimeScalar scalar) {
        if (scalar.type == REFERENCE) {
            scalar = scalar.scalarDeref();
        }
        return fetchTiedOnce(scalar);
    }

    /**
     * Forces the tied scalar reachable through a chain of scalar references
     * exactly once, preserving the original reference value.
     *
     * <p>Some list operators inspect reference operands without dereferencing
     * them for their result.  Perl still evaluates tied scalar slots reached
     * while forming those operands.  This helper provides that evaluation
     * without replacing the reference that the operator must retain.</p>
     */
    public static void fetchReferencedTiedScalarOnce(RuntimeScalar scalar) {
        boolean traversedReference = false;
        while (scalar.type == REFERENCE) {
            traversedReference = true;
            RuntimeScalar dereferenced = scalar.scalarDeref();
            if (dereferenced == scalar) {
                return;
            }
            scalar = dereferenced;
        }
        if (traversedReference) {
            fetchTiedOnce(scalar);
        }
    }

    public boolean isString() {
        int t = this.type;
        if (t == READONLY_SCALAR) return ((RuntimeScalar) this.value).isString();
        return t == STRING || t == BYTE_STRING || t == VSTRING;
    }

    private void markNumericContextSeen() {
        int t = this.type;
        if (t == STRING || t == BYTE_STRING || t == VSTRING) {
            this.numericContextSeen = true;
        } else if (t == READONLY_SCALAR) {
            ((RuntimeScalar) this.value).markNumericContextSeen();
        } else if (t == TIED_SCALAR) {
            this.tiedFetch().markNumericContextSeen();
        }
    }

    public boolean isDataDumperNumifiedSafeDecimal() {
        RuntimeScalar scalar = this;
        if (scalar instanceof ScalarSpecialVariable ssv) {
            scalar = ssv.getValueAsScalar();
        }
        if (scalar.type == READONLY_SCALAR) {
            scalar = (RuntimeScalar) scalar.value;
        }
        if (scalar.type != INTEGER && scalar.type != DOUBLE
                && (!scalar.numericContextSeen || !(scalar.type == STRING || scalar.type == BYTE_STRING))) {
            return false;
        }
        String s = scalar.toString();
        if (s.isEmpty() || s.length() > 10) return false;
        if (s.equals("0")) return true;
        int start = s.charAt(0) == '-' ? 1 : 0;
        if (start == 1 && s.length() == 1) return false;
        char first = s.charAt(0);
        if (start == 1) first = s.charAt(1);
        if (first < '1' || first > '9') return false;
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private void initializeWithLong(Long value) {
        this.tainted = false;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;
        setIntegerValue(value);
    }

    private void setIntegerValue(long integerValue) {
        this.type = RuntimeScalarType.INTEGER;
        if (integerValue >= Integer.MIN_VALUE && integerValue <= Integer.MAX_VALUE) {
            this.value = Integer.valueOf((int) integerValue);
        } else {
            this.value = Long.valueOf(integerValue);
        }
    }

    private void setIntegerValue(BigInteger integerValue) {
        if (integerValue.bitLength() < 64) {
            setIntegerValue(integerValue.longValue());
        } else {
            this.type = RuntimeScalarType.INTEGER;
            this.value = integerValue;
        }
    }

    public RuntimeScalar study() {
        if (type == TIED_SCALAR) {
            tiedFetch();
        }
        return scalarUndef;
    }

    public RuntimeScalar clone() {
        return new RuntimeScalar(this);
    }

    /** Capture a plain scalar payload for regex callback backtracking. */
    Object snapshotRegexMutationState() {
        if (type < INTEGER || type > BOOLEAN) return null;
        return new RegexMutationState(type, value, utf8UncheckedOctets, tainted,
                numericLiteralText, numericContextSeen, firstClassRegexScalar,
                firstClassRegexValue, formatPictureTainted, firstClassRegexReferent);
    }

    void restoreRegexMutationState(Object token) {
        if (!(token instanceof RegexMutationState state)) return;
        RuntimeScalar restored = new RuntimeScalar();
        restored.type = state.type;
        restored.value = state.value;
        restored.utf8UncheckedOctets = state.utf8UncheckedOctets;
        restored.tainted = state.tainted;
        restored.numericLiteralText = state.numericLiteralText;
        restored.numericContextSeen = state.numericContextSeen;
        restored.firstClassRegexScalar = state.firstClassRegexScalar;
        restored.firstClassRegexReferent = state.firstClassRegexReferent;
        restored.firstClassRegexValue = state.firstClassRegexValue;
        restored.formatPictureTainted = state.formatPictureTainted;
        set(restored);
    }

    private record RegexMutationState(int type, Object value,
                                      boolean utf8UncheckedOctets, boolean tainted,
                                      String numericLiteralText, boolean numericContextSeen,
                                      boolean firstClassRegexScalar,
                                      RuntimeRegex firstClassRegexValue,
                                      boolean formatPictureTainted,
                                      WeakReference<RuntimeRegex> firstClassRegexReferent) {}

    public int countElements() {
        return 1;
    }

    // Inlineable fast path for getNumber()
    public RuntimeScalar getNumber() {
        if (type == INTEGER || type == DOUBLE) {
            return this;
        }
        return getNumberLarge();
    }

    // Inlineable fast path for getNumber() with operation context for "isn't numeric" warnings
    public RuntimeScalar getNumber(String operation) {
        if (type == INTEGER || type == DOUBLE) {
            return this;
        }
        // For string types, pass operation context so warnings include "in <operation>"
        if (type == STRING || type == BYTE_STRING || type == VSTRING) {
            markNumericContextSeen();
            return NumberParser.parseNumber(this, operation);
        }
        if (type == GLOB) {
            return NumberParser.parseNumber(this, operation);
        }
        return getNumberLarge();
    }

    // Slow path for getNumber()
    public RuntimeScalar getNumberLarge() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER, DOUBLE -> this;
            case STRING, BYTE_STRING -> {
                markNumericContextSeen();
                yield NumberParser.parseNumber(this);
            }
            case UNDEF -> scalarZero;
            case VSTRING -> {
                markNumericContextSeen();
                yield NumberParser.parseNumber(this);
            }
            case BOOLEAN -> (boolean) value ? scalarOne : scalarZero;
            case GLOB -> {
                if (!scalarStoredGlob) {
                    throw new PerlCompilerException("Can't coerce GLOB to number in numeric context");
                }
                yield NumberParser.parseNumber(this);
            }
            case JAVAOBJECT -> value != null ? scalarOne : scalarZero;
            case TIED_SCALAR -> this.tiedFetch().getNumber();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getNumber();
            case DUALVAR -> ((DualVar) this.value).numericValue();
            default -> {
                RuntimeScalar result = Overload.numify(this);
                // Overload may return a string (e.g., "3.1" from 0+ handler);
                // ensure it's converted to a proper numeric type
                yield (result.type == INTEGER || result.type == DOUBLE)
                        ? result : result.getNumber();
            }
        };
    }

    /**
     * Convert to number without triggering overload dispatch.
     * Used by {@code no overloading} pragma to bypass the {@code 0+} overload.
     * For blessed references, returns the identity hash code as an integer.
     */
    public RuntimeScalar getNumberNoOverload() {
        return switch (type) {
            case INTEGER, DOUBLE -> this;
            case STRING, BYTE_STRING -> {
                markNumericContextSeen();
                yield NumberParser.parseNumber(this);
            }
            case UNDEF -> scalarZero;
            case VSTRING -> {
                markNumericContextSeen();
                yield NumberParser.parseNumber(this);
            }
            case BOOLEAN -> (boolean) value ? scalarOne : scalarZero;
            case GLOB -> {
                if (!scalarStoredGlob) {
                    throw new PerlCompilerException("Can't coerce GLOB to number in numeric context");
                }
                yield NumberParser.parseNumber(this);
            }
            case JAVAOBJECT -> value != null ? scalarOne : scalarZero;
            case TIED_SCALAR -> this.tiedFetch().getNumberNoOverload();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getNumberNoOverload();
            case DUALVAR -> ((DualVar) this.value).numericValue();
            default -> {
                // For references (blessed or not), return the identity hash code
                // of the referent, matching Scalar::Util::refaddr semantics.
                if (value != null) {
                    yield new RuntimeScalar(System.identityHashCode(value));
                }
                yield scalarZero;
            }
        };
    }

    /**
     * Converts scalar to number with uninitialized value warning.
     * Called when 'use warnings "uninitialized"' is in effect.
     *
     * @param operation The operation name for the warning message (e.g., "addition (+)")
     * @return A RuntimeScalar representing the numeric value
     */
    public RuntimeScalar getNumberWarn(String operation) {
        // Fast path for defined numeric types
        if (type == INTEGER || type == DOUBLE) {
            return this;
        }
        // Check for UNDEF and emit warning if warnings are enabled
        if (type == UNDEF) {
            String lexicalName = RuntimeCode.findActiveLexicalName(this);
            if (lexicalName == null) {
                lexicalName = GlobalVariable.findGlobalScalarName(this);
            }
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value"
                    + (lexicalName == null ? "" : " " + lexicalName)
                    + " in " + operation),
                    scalarEmptyString, "uninitialized");
            return scalarZero;
        }
        // For tied scalars, fetch first then check the fetched value
        if (type == TIED_SCALAR) {
            return this.tiedFetch().getNumberWarn(operation);
        }
        // For string types, pass operation context so "isn't numeric" warnings include it
        if (type == STRING || type == BYTE_STRING || type == VSTRING) {
            markNumericContextSeen();
            return NumberParser.parseNumber(this, operation);
        }
        // Perl permits a bare glob in warning-enabled arithmetic (with the
        // usual numeric warning).  The non-warning path remains stricter for
        // a direct glob used as a compound-assignment lvalue.
        if (type == GLOB) {
            return NumberParser.parseNumber(this, operation);
        }
        // All other types are defined, just convert to number
        return getNumberLarge();
    }

    /**
     * Postfix glob dereference helper used by the parser for `->**` and `->*{...}`.
     *
     * <p>In Perl, postfix glob deref is allowed to resolve plain strings as symbol names
     * even when strict refs is enabled (see perl5_t/t/op/postfixderef.t), but should still
     * reject non-glob references.
     */
    public RuntimeGlob globDerefPostfix(String packageName) {
        return switch (type) {
            case STRING, BYTE_STRING -> {
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalIO(varName);
            }
            default -> globDeref();
        };
    }

    // Inlineable fast path for getInt()
    public int getInt() {
        if (type == INTEGER) {
            return ((Number) this.value).intValue();
        }
        return getIntLarge();
    }

    // Slow path for getInt()
    private int getIntLarge() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> ((Number) value).intValue();
            case DOUBLE -> (int) ((double) value);
            case STRING, BYTE_STRING -> {
                markNumericContextSeen();
                // Avoid recursion when NumberParser.parseNumber() returns a cached scalar
                // that is also STRING. Add fast-path for plain integer strings.
                String s = materializeGrowingString();
                if (s != null) {
                    String t = s.trim();
                    if (mightBeInteger(t)) {
                        try {
                            yield (int) Long.parseLong(t);
                        } catch (NumberFormatException ignored) {
                            // Fall through to full numification (handles "1.5", overflow, etc.)
                        }
                    }
                }
                yield NumberParser.parseNumber(this).getInt();
            }
            case UNDEF -> 0;
            case VSTRING -> NumberParser.parseNumber(this).getInt();
            case BOOLEAN -> (boolean) value ? 1 : 0;
            case GLOB -> throw new PerlCompilerException("Can't coerce GLOB to integer in numeric context");
            case JAVAOBJECT -> value != null ? 1 : 0;
            case TIED_SCALAR -> this.tiedFetch().getInt();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getInt();
            case DUALVAR -> ((DualVar) this.value).numericValue().getInt();
            default -> Overload.numify(this).getInt();
        };
    }

    /**
     * Get the BigInteger value of this scalar for exact arithmetic operations.
     * This method preserves full precision for large integer strings.
     * Used primarily for pack/unpack checksum calculations.
     *
     * @return the value as a BigInteger with full precision
     */
    public BigInteger getBigint() {
        if (type == RuntimeScalarType.DOUBLE) {
            // For doubles, convert to BigInteger
            double d = (double) value;
            if (d < 0) {
                // Negative value interpreted as unsigned
                return new BigInteger(Long.toUnsignedString((long) d));
            } else {
                return BigInteger.valueOf((long) d);
            }
        } else if (type == RuntimeScalarType.INTEGER) {
            // For regular integers
            if (value instanceof BigInteger integerValue) return integerValue;
            if (value instanceof UnsignedWordValue unsignedValue) return unsignedValue.toBigInteger();
            return BigInteger.valueOf(((Number) value).longValue());
        } else if (type == RuntimeScalarType.UNDEF) {
            return BigInteger.ZERO;
        } else {
            markNumericContextSeen();
            // String types - parse exactly without precision loss
            String str = this.toString().trim();

            // Handle empty strings
            if (str.isEmpty()) {
                return BigInteger.ZERO;
            }

            try {
                // Check if it's a plain integer (most important case for checksums)
                // This is critical for preserving exact values like "9223372036854775807"
                if (str.matches("^-?\\d+$")) {
                    // Parse directly as BigInteger to preserve all digits
                    return new BigInteger(str);
                }

                // Handle scientific notation or decimal numbers
                // These require double conversion which may lose precision
                double d = Double.parseDouble(str);

                // Check for special values
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return BigInteger.ZERO;
                }

                // For very large values, try to preserve more precision
                if (Math.abs(d) > 9007199254740992.0) { // > 2^53
                    // Format without scientific notation to get more digits
                    String formatted = String.format("%.0f", d);
                    return new BigInteger(formatted);
                }

                // Convert to long then BigInteger
                return BigInteger.valueOf((long) d);
            } catch (NumberFormatException e) {
                // Not a number, return 0
                return BigInteger.ZERO;
            }
        }
    }

    /**
     * Return the scalar's signed integral value without interpreting a
     * negative floating value as an unsigned bit pattern. Index-like
     * operators (substr, vec, ranges) need this distinction; getBigint() keeps
     * its historical checksum-oriented unsigned conversion for negative NVs.
     */
    public BigInteger getSignedBigint() {
        if (type == RuntimeScalarType.DOUBLE) {
            double number = (double) value;
            if (!Double.isFinite(number)) {
                return BigInteger.ZERO;
            }
            return BigDecimal.valueOf(number).toBigInteger();
        }
        return getBigint();
    }

    /**
     * Get the unsigned long value of this scalar.
     * Used for unsigned integer formats like Q and J.
     *
     * @return the unsigned long value as a BigInteger
     */
    public BigInteger getUnsignedLong() {
        if (type == RuntimeScalarType.DOUBLE) {
            // For doubles, emulate 32-bit Perl behavior with precision loss
            double d = (double) value;
            // Just use the double value as-is, with precision loss
            // This emulates what 32-bit Perl does
            long lval = (long) d;
            if (lval < 0) {
                // Negative value interpreted as unsigned
                return new BigInteger(Long.toUnsignedString(lval));
            } else {
                return BigInteger.valueOf(lval);
            }
        } else if (type == RuntimeScalarType.INTEGER) {
            // For regular integers - need to handle as unsigned
            if (value instanceof BigInteger integerValue) {
                return integerValue;
            }
            if (value instanceof UnsignedWordValue unsignedValue) {
                return unsignedValue.toBigInteger();
            }
            long val = ((Number) value).longValue();
            if (val < 0) {
                // Convert negative to unsigned BigInteger
                return new BigInteger(Long.toUnsignedString(val));
            } else {
                return BigInteger.valueOf(val);
            }
        } else if (type == RuntimeScalarType.UNDEF) {
            return BigInteger.ZERO;
        } else {
            markNumericContextSeen();
            // String types - parse carefully to preserve precision
            String str = this.toString().trim();

            // Handle empty strings
            if (str.isEmpty()) {
                return BigInteger.ZERO;
            }

            try {
                // First, try to parse as an exact integer (no decimal point or scientific notation)
                // This preserves full precision for large integer strings
                if (str.matches("^-?\\d+$")) {
                    return new BigInteger(str);
                }

                // Handle scientific notation or decimal numbers
                // These require double conversion which may lose precision
                double d = Double.parseDouble(str);

                // Check for special values
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return BigInteger.ZERO;
                }

                // For large values > 2^53, precision is already lost in the double
                // Just convert directly to avoid further issues
                if (d < 0 && d < Long.MIN_VALUE) {
                    // Very large negative double, treat as unsigned
                    return new BigInteger(Long.toUnsignedString((long) d));
                } else if (d < 0) {
                    // Regular negative that fits in long
                    return new BigInteger(Long.toUnsignedString((long) d));
                } else {
                    // Positive value
                    return BigInteger.valueOf((long) d);
                }
            } catch (NumberFormatException e) {
                // Not a number, return 0
                return BigInteger.ZERO;
            }
        }
    }

    /** True when an integer uses arbitrary-precision or unsigned-word storage. */
    public static boolean isWideIntegerValue(Object value) {
        return value instanceof BigInteger || value instanceof UnsignedWordValue;
    }

    /** True when the payload uses a signed native-width integer representation. */
    public static boolean isNativeIntegerValue(Object value) {
        return value instanceof Integer || value instanceof Long
                || value instanceof MutableIntegerValue;
    }

    public long getLong() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> ((Number) value).longValue();
            case DOUBLE -> (long) ((double) value);
            case STRING, BYTE_STRING -> {
                markNumericContextSeen();
                // Avoid recursion when large integer strings are preserved as STRING to keep
                // precision (e.g. values > 2^53). NumberParser.parseNumber() may return a scalar
                // that is also STRING, and calling getLong() on it would recurse indefinitely.
                String s = materializeGrowingString();
                if (s != null) {
                    String t = s.trim();
                    if (mightBeInteger(t)) {
                        try {
                            yield Long.parseLong(t);
                        } catch (NumberFormatException ignored) {
                            // Fall through to full numification (handles "1.5", overflow, etc.)
                        }
                    }
                }
                yield NumberParser.parseNumber(this).getLong();
            }
            case UNDEF -> 0L;
            case VSTRING -> NumberParser.parseNumber(this).getLong();
            case BOOLEAN -> (boolean) value ? 1L : 0L;
            case GLOB -> 1L;
            case JAVAOBJECT -> value != null ? 1L : 0L;
            case TIED_SCALAR -> this.tiedFetch().getLong();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getLong();
            case DUALVAR -> ((DualVar) this.value).numericValue().getLong();
            default -> Overload.numify(this).getLong();
        };
    }

    // Inlineable fast path for getDouble()
    public double getDouble() {
        if (type == INTEGER) {
            return ((Number) this.value).doubleValue();
        }
        return getDoubleLarge();
    }

    // Slow path for getDouble()
    private double getDoubleLarge() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> ((Number) value).doubleValue();
            case DOUBLE -> (double) value;
            case STRING, BYTE_STRING -> {
                markNumericContextSeen();
                // Avoid recursion when numeric values are preserved as STRING and also stored in
                // NumberParser's numification cache. If parseNumber() returns a scalar whose
                // conversion path leads back to getDouble(), this can recurse indefinitely.
                String s = materializeGrowingString();
                if (s != null) {
                    String t = s.trim();
                    if (!t.isEmpty() && DECIMAL_PATTERN.matcher(t).matches()) {
                        try {
                            yield Double.parseDouble(t);
                        } catch (NumberFormatException ignored) {
                            // Fall through to full numification.
                        }
                    }
                }
                yield NumberParser.parseNumber(this).getDouble();
            }
            case UNDEF -> 0.0;
            case VSTRING -> NumberParser.parseNumber(this).getDouble();
            case BOOLEAN -> (boolean) value ? 1.0 : 0.0;
            case GLOB -> 1.0;
            case JAVAOBJECT -> value != null ? 1.0 : 0.0;
            case TIED_SCALAR -> this.tiedFetch().getDouble();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getDouble();
            case DUALVAR -> ((DualVar) this.value).numericValue().getDouble();
            default -> Overload.numify(this).getDouble();
        };
    }

    // Inlineable fast path for getBoolean()
    public boolean getBoolean() {
        if (type == INTEGER) {
            return ((Number) value).longValue() != 0;
        }
        return getBooleanLarge();
    }

    /** Perl truth without invoking a blessed value's bool/0+/"" overload. */
    @Override
    public boolean getBooleanNoOverload() {
        return switch (type) {
            case INTEGER -> ((Number) value).longValue() != 0;
            case DOUBLE -> (double) value != 0.0;
            case STRING, BYTE_STRING -> {
                String s = materializeGrowingString();
                yield !s.isEmpty() && !s.equals("0");
            }
            case UNDEF -> false;
            case BOOLEAN -> (boolean) value;
            case JAVAOBJECT -> value != null;
            case TIED_SCALAR -> this.tiedFetch().getBooleanNoOverload();
            case READONLY_SCALAR -> ((RuntimeScalar) value).getBooleanNoOverload();
            case DUALVAR -> ((DualVar) value).stringValue().getBooleanNoOverload();
            case CODE -> value != null && (((RuntimeCode) value).packageName != null
                    || ((RuntimeCode) value).subName != null || ((RuntimeCode) value).defined());
            default -> true;
        };
    }

    // Slow path for getBoolean()
    private boolean getBooleanLarge() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> ((Number) value).longValue() != 0;
            case DOUBLE -> (double) value != 0.0;
            case STRING, BYTE_STRING -> {
                String s = materializeGrowingString();
                yield !s.isEmpty() && !s.equals("0");
            }
            case UNDEF -> false;
            case VSTRING -> true;
            case BOOLEAN -> (boolean) value;
            case GLOB -> true;
            case JAVAOBJECT -> value != null;
            case TIED_SCALAR -> this.tiedFetch().getBoolean();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getBoolean();
            case DUALVAR -> ((DualVar) this.value).stringValue().getBoolean();
            case CODE -> {
                if (value == null) yield false;
                RuntimeCode code = (RuntimeCode) value;
                yield code.packageName != null || code.subName != null || code.defined();
            }
            default -> {
                RuntimeScalar overloaded = Overload.boolify(this);
                // A legal bool overload may return $_[0]. Perl treats that
                // self-result as a true object instead of recursively invoking
                // the same overload until the C stack is exhausted.
                if (isSameOverloadTarget(overloaded, this)) yield true;
                yield overloaded.getBoolean();
            }
        };
    }

    // Get the Scalar alias into an Array
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        arr.elementsAliased = true;
        arr.elements.add(this);
        if (type == RuntimeScalarType.CODE && value instanceof RuntimeCode code
                && !isPackageGlobalRoot && !localBindingExists
                && (!RuntimeCode.hasPersistentArgumentCell(this) || scopeExited)
                && (code.capturedScalars != null || code.capturedAggregates != null)) {
            // A temporary closure in @_ is a strong argument slot. The code's
            // count can belong to a different scalar cell, so decide whether
            // this cell needs an owner from its storage identity. Argument
            // cleanup can then retire this edge without a graph walk.
            incrementRefCountForContainerStore(this, PerlOwnerSlot.Kind.ARGUMENT_VALUE);
            arr.markOwnedAliasElement(this);
        }
        return arr;
    }

    // Get the list value of the Scalar
    public RuntimeList getList() {
        return RuntimeList.acquireScalarResult(this);
    }

    // Get the scalar value of the Scalar
    public RuntimeScalar scalar() {
        return this;
    }

    /**
     * Returns whether this scalar is tainted.
     *
     * @return true for tainted scalars
     */
    public boolean isTainted() {
        if (this instanceof ScalarSpecialVariable ssv) {
            return ssv.getValueAsScalar().isTainted();
        }
        if (type == TIED_SCALAR) {
            return tiedFetch().isTainted();
        }
        if (type == READONLY_SCALAR) {
            return ((RuntimeScalar) value).isTainted();
        }
        return tainted;
    }

    /** Mark a value returned by an external input source in Perl taint mode. */
    public RuntimeScalar taintFromExternalInput() {
        if (GlobalContext.isTaintModeActive()) {
            tainted = true;
        }
        return this;
    }

    /** Mark this scalar tainted when any scalar input to an operation is tainted. */
    public RuntimeScalar propagateTaint(RuntimeScalar input) {
        if (isPlainTaintInput(input) && (input == null || !input.tainted)) return this;
        if (!GlobalContext.isTaintModeActive()) return this;
        if (input != null && input.isTainted()) {
            if (isTainted()) return this;
            RuntimeScalar taintedResult = new RuntimeScalar(this);
            taintedResult.tainted = true;
            return taintedResult;
        }
        return this;
    }

    public RuntimeScalar propagateTaint(RuntimeScalar first, RuntimeScalar second) {
        boolean plainInputs = isPlainTaintInput(first) && isPlainTaintInput(second);
        if (plainInputs && (first == null || !first.tainted)
                && (second == null || !second.tainted)) {
            return this;
        }
        if (!GlobalContext.isTaintModeActive()) return this;
        if ((first != null && first.isTainted()) || (second != null && second.isTainted())) {
            if (isTainted()) return this;
            RuntimeScalar taintedResult = new RuntimeScalar(this);
            taintedResult.tainted = true;
            return taintedResult;
        }
        return this;
    }

    private static boolean isPlainTaintInput(RuntimeScalar input) {
        return input == null
                || ((input.getClass() == RuntimeScalar.class
                        || input.getClass() == RuntimeScalarReadOnly.class)
                    && input.type < TIED_SCALAR);
    }

    public RuntimeScalar propagateTaint(RuntimeScalar... inputs) {
        // Most compiled numeric operations have only ordinary scalar operands.
        // Their taint flags are directly available, so avoid looking up the
        // current Perl runtime when none of them is tainted. Keep magic values
        // on the original path because isTainted() can invoke tied FETCH.
        boolean plainInputs = inputs != null;
        boolean hasTaintedPlainInput = false;
        if (plainInputs) {
            for (RuntimeScalar input : inputs) {
                if (input == null) continue;
                if (isPlainTaintInput(input)) {
                    hasTaintedPlainInput |= input.tainted;
                } else {
                    plainInputs = false;
                    break;
                }
            }
        }
        if (plainInputs) {
            if (!hasTaintedPlainInput || !GlobalContext.isTaintModeActive()) return this;
        } else if (!GlobalContext.isTaintModeActive()) {
            return this;
        }
        // Outside taint mode there is no provenance to propagate.  In
        // particular, asking a tied scalar whether it is tainted would invoke
        // FETCH a second time after the operator has already fetched its
        // value.
        for (RuntimeScalar input : inputs) {
            if (input != null && input.isTainted()) {
                if (isTainted()) {
                    return this;
                }
                // Operator helpers sometimes return a shared scalar-cache value.
                // Never attach taint metadata to that shared instance.
                RuntimeScalar taintedResult = new RuntimeScalar(this);
                taintedResult.tainted = true;
                return taintedResult;
            }
        }
        return this;
    }

    /**
     * Reject a tainted value at a security-sensitive operation.  Taint is only
     * meaningful while the current Perl program is running with {@code -T}.
     */
    public static void checkTaint(RuntimeScalar scalar, String operation) {
        if (GlobalContext.isTaintModeActive() && scalar != null && scalar.isTainted()) {
            reportTaintViolation("Insecure dependency in " + operation);
        }
    }

    /** Report an insecure use according to strict (-T) or warning (-t) mode. */
    public static void reportTaintViolation(String message) {
        if (GlobalContext.isTaintWarningModeActive()) {
            if (!WarningFlags.isWarningSuppressedAtRuntime("taint")) {
                WarnDie.warn(new RuntimeScalar(message + " while running with -t switch"),
                        RuntimeScalarCache.scalarEmptyString);
            }
            return;
        }
        throw new PerlCompilerException(message + " while running with -T switch");
    }

    // Add itself to a RuntimeArray.
    //
    // ─── WARNING: refCount accounting is intentionally asymmetric here ───
    //
    // Do NOT add an `incrementRefCountForContainerStore(copy)` call to the
    // PLAIN_ARRAY branch below, even though its sister method
    // `RuntimeArray.add(RuntimeScalar)` (used for anon-array-literal
    // construction) *does* incref. There is a reason — but the asymmetry
    // is a workaround for a DEEPER architectural mismatch with real Perl.
    //
    // ─── The deeper issue: PerlOnJava COPIES where real Perl ALIASES ───
    //
    // In system (XS) Perl, arg passing uses SV aliasing: `f($g)` stores
    // the caller's $g SV pointer directly into @_. No copy, no SvREFCNT_inc,
    // no SvREFCNT_dec. The callee's @_[0] IS the caller's $g. Lifetime is
    // bound to the caller.
    //
    // Anon-array-literal construction in real Perl uses `newSVsv` which
    // DOES copy and DOES incref the referent — because the AV owns the
    // elements and must release them when the AV dies.
    //
    // Real Perl's "symmetry" is thus: each primitive has well-defined
    // refcount semantics; aliasing is used where ownership stays with
    // the caller, copying is used where ownership transfers to the
    // container. The two Perl-level operations (pass-by-value vs
    // anon-array-literal) use different primitives on purpose.
    //
    // PerlOnJava currently copies in both cases (this method, and
    // RuntimeArray.add). So the same *runtime primitive* needs to have
    // DIFFERENT ownership semantics depending on which Perl-level
    // operation called it — which is structurally awkward and the reason
    // for the asymmetric incref policy. The asymmetry is not a property
    // of Perl; it's a property of our copy-everywhere implementation
    // choice trying to emulate Perl's alias-vs-copy distinction.
    //
    // ─── What went wrong historically ───
    //
    // Commit c8f669b14 (Template fix — "incref anon-array elements on
    // add — fixes TT directive.t") added incref to BOTH sites. The
    // anon-array-literal path has a matching createReferenceWithTrackedElements
    // at the end of the literal so its incref is balanced. This method,
    // however, is also reached from arg-passing — and the args array has
    // NO matching decref: it's popped off argsStack without walking its
    // elements. A blessed referent's refCount leaked by +1 per function
    // call. Most tests didn't notice (process-exit GC catches it), but
    // anything relying on SYNCHRONOUS DESTROY — e.g. DBIC
    // `t/storage/txn_scope_guard.t#18` (zombie-ref double-DESTROY
    // detection via @DB::args capture) — broke.
    //
    // Minimal repro (before the narrow fix below, DESTROY fires late at
    // process exit; after the fix, it fires from `@capture = ()`):
    //
    //   package Guard;
    //   sub new { bless { id => $_[1] }, "Guard" }
    //   sub DESTROY { warn "DESTROY id=$_[0]->{id}\n" }
    //   package main;
    //   my @capture;
    //   sub inner {
    //       package DB;
    //       my $f = 0;
    //       while (my @fr = caller(++$f)) { push @capture, @DB::args }
    //   }
    //   sub call_with_guard { inner() }
    //   { my $g = Guard->new("A"); call_with_guard($g); }
    //   warn "--- before clear\n";
    //   @capture = ();
    //   warn "--- after clear (DESTROY must have fired by here)\n";
    //
    // ─── Fixes, in increasing order of cleanliness ───
    //
    //   1. [CURRENT — pragmatic workaround] Drop the incref from this
    //      method (arg-passing path), keep it in RuntimeArray.add
    //      (anon-array-literal path). Both Template and DBIC pass.
    //      But the two copy-sites have different ownership semantics,
    //      which is confusing and fragile.
    //
    //   2. [LOCAL FIX] Keep the incref here and teach popArgs() to
    //      walk the args array's elements and decref each one. Keeps
    //      the "arrays always own their elements" invariant clean.
    //      Per-call cost proportional to arg count — usually small.
    //
    //   3. [SEMANTICALLY CORRECT — bigger refactor] Implement
    //      alias-on-pass to match Perl's @_. Arg passing stores the
    //      caller's RuntimeScalar pointer directly; no copy, no
    //      refcount traffic. The scalar's lifetime is tied to the
    //      caller, as in Perl. @_-mutation semantics (shift/pop/slice)
    //      would need to reflect this, and everything reading @_ would
    //      need to handle aliased values. This is the RIGHT answer
    //      long-term but touches many files.
    //
    // If you're auditing for refcount symmetry, or you hit a leak
    // regression that seems to point here: prefer option 2 or 3
    // over re-adding the incref. See dev/design/perf-dbic-safe-port.md
    // and commit history for context.
    //
    // Regression test: t/destroy_zombie_captured_by_db_args.t
    public void addToArray(RuntimeArray runtimeArray) {
        switch (runtimeArray.type) {
            case PLAIN_ARRAY -> {
                runtimeArray.elements.add(new RuntimeScalar(this));
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                RuntimeArray.push(runtimeArray, this);
            }
            case TIED_ARRAY -> TieArray.tiedPush(runtimeArray, this);
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        }
    }

    /**
     * Add itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        RuntimeScalar result = scalar.set(this);
        // set() published and acquired the destination owner. Release this
        // exact postfix temporary through the shared slot/count transition.
        releasePostfixReferenceTransferOwner(
                "RuntimeScalar.addToScalar postfix temporary transfer");
        return result;
    }

    /**
     * Store a Perl expression value into an assignment target.
     *
     * <p>{@link #addToScalar(RuntimeScalar)} is also used internally while
     * releasing temporaries and restoring scopes.  The synthetic {@code $_[0]}
     * passed to {@code DESTROY} is read-only to Perl code, but those lifecycle
     * operations must still be able to copy and release it while another
     * exception unwinds.  Keep the language-visible readonly check here, at
     * compiler assignment sites only.</p>
     */
    public static RuntimeScalar assignTo(RuntimeBase value, RuntimeScalar target) {
        // During global destruction the runtime's argument/default-variable
        // aliases share this synthetic slot.  Perl permits a destructor to
        // use ordinary $_ there (op/ref.t's IO::Handle destructor does so),
        // while direct $_[0] assignment remains protected during normal
        // execution.
        if (target.destroySelfArgument && target != value) {
            PerlRuntime runtime = PerlRuntime.currentOrNull();
            boolean globalDestruction = runtime != null
                    && runtime.executionState.globalDestruction;
            if (!globalDestruction) {
                throw new PerlCompilerException("Modification of a read-only value attempted");
            }
        }
        return value.addToScalar(target);
    }

    /** Store a value and produce the scalar value of an assignment expression. */
    public static RuntimeScalar assignToExpression(RuntimeBase value, RuntimeScalar target) {
        RuntimeScalar assigned = assignTo(value, target);
        // A scalar assignment expression reads its lvalue result. For tied
        // scalars Perl therefore invokes FETCH after STORE; STORE's own return
        // value is ignored. Keep void assignments on assignTo() so they do
        // not introduce an observable fetch.
        return target.type == TIED_SCALAR ? target.tiedFetch() : assigned;
    }

    /** A final assignment inherits the caller's context, including void. */
    public static RuntimeScalar assignToExpression(RuntimeBase value, RuntimeScalar target, int context) {
        return context == RuntimeContextType.VOID
                ? assignTo(value, target) : assignToExpression(value, target);
    }

    /**
     * Vivifies this scalar as an lvalue. For plain scalars this is a no-op.
     * For hash/array element proxies (RuntimeBaseProxy subclasses), this creates
     * the actual entry in the parent container, matching Perl 5's behavior where
     * hash element lvalue access in ||=/&&=//= creates the entry before evaluating the RHS.
     */
    public void vivifyLvalue() {
        // No-op for plain scalars
    }

    /**
     * Vivify an lvalue before testing a logical compound assignment. Read-only
     * argument aliases must be testable without raising; a later assignment
     * still reaches set()/vivify() and reports the canonical read-only error.
     */
    public void vivifyLogicalAssignmentLvalue() {
        if (!(this instanceof RuntimeScalarReadOnly)) {
            vivifyLvalue();
        }
    }

    // Setters

    /**
     * Increment refCount for a scalar that was just stored in a container (array/hash).
     * Container stores use the copy constructor which doesn't increment refCount
     * (to avoid over-counting for temporary copies). This method should be called
     * after storing a tracked reference in a container, if MortalList is active.
     * <p>
     * Skips elements that already have {@code refCountOwned == true}, meaning they
     * were created via {@code set()} / {@code setLarge()} rather than the copy
     * constructor, and their refCount was already incremented at creation time.
     */
    public static void incrementRefCountForContainerStore(RuntimeScalar scalar) {
        incrementRefCountForContainerStore(scalar, null);
    }

    /** Acquire a counted edge for a destination slot before publishing that slot. */
    public static void incrementRefCountForContainerStore(
            RuntimeScalar scalar, PerlOwnerSlot.Kind intendedOwnerKind) {
        if (scalar == null) return;
        if (intendedOwnerKind != null && !PerlOwnerSlot.isCountedKind(intendedOwnerKind)) {
            throw new IllegalArgumentException("container stores require a counted owner kind");
        }
        if (scalar.type == REFERENCE
                && scalar.value instanceof RuntimeScalar
                && !scalar.hasScalarReferenceContentsOwner()) {
            scalar.retainScalarReferenceContents(scalar);
        }
        if (PerlRuntime.currentOrNull() != null && WeakRefRegistry.isweak(scalar)) return;
        if (scalar.refCountOwned) {
            PerlOwnerSlot slot = scalar.countedOwnerSlot;
            if (slot == null || !slot.isActive() || slot.referent() != scalar.value
                    || slot.countedScalarOwner() != scalar) {
                throw new IllegalStateException(
                        "counted scalar lost its active owner slot during container acquisition");
            }
            slot.reclassifyCountedIdentity(intendedOwnerKind == null
                    ? scalar.countedOwnerKind() : intendedOwnerKind);
            if (scalar.untrackedReferenceOwnerSlot != null) {
                scalar.releaseUntrackedReferenceOwner(
                        scalar.untrackedReferenceOwnerSlot.referent());
            }
            return;
        }
        if ((scalar.type & REFERENCE_BIT) == 0
                || !(scalar.value instanceof RuntimeBase base)) {
            return;
        }
        // Destroyed / weaken sentinel states must not be reactivated here.
        if (base.refCount == Integer.MIN_VALUE) {
            return;
        }
        if (base.refCount == WeakRefRegistry.WEAKLY_TRACKED) {
            scalar.refreshUntrackedReferenceOwner();
            return;
        }
        if (base.refCount < 0 && intendedOwnerKind == PerlOwnerSlot.Kind.ARGUMENT_CONTAINER) {
            // Selective counting has no ledger for the referent's pre-existing
            // owners. Keep this real argument edge as an untracked owner rather
            // than promoting a partial count that could reach zero too early.
            scalar.refreshUntrackedReferenceOwner();
            return;
        }
        // Anonymous nested containers start at refCount=-1 (untracked).
        // bless {}'s retroactive pass and normal container stores must still count
        // the hash slot as a strong ref, otherwise delete $h{k} / hash clear never
        // drops the child (PPI::Node->{children}; cpan PPI t/04_element.t).
        // Restrict promotion to real containers: doing this for arbitrary -1
        // referents (e.g. refs to readonly scalars) breaks Moo weak_ref accessors
        // (cpan Moo t/accessor-weaken.t).
        if (base.refCount < 0) {
            if (!(base instanceof RuntimeHash) && !(base instanceof RuntimeArray)) {
                scalar.refreshUntrackedReferenceOwner();
                return;
            }
            base.refCount = 0;
        }
        acquireCountedOwnerForCurrentValue(scalar, base, intendedOwnerKind,
                "RuntimeScalar.incrementRefCountForContainerStore");
        if (scalar.untrackedReferenceOwnerSlot != null) {
            scalar.releaseUntrackedReferenceOwner(
                    scalar.untrackedReferenceOwnerSlot.referent());
        }
        // Phase B1 (refcount_alignment_52leaks_plan.md): track the
        // container element so ReachabilityWalker can see it via
        // ScalarRefRegistry.
        if (PerlRuntime.currentOrNull() != null) ScalarRefRegistry.registerRef(scalar);
    }

    /**
     * Change container slots in Perl assignment order: acquire replacement
     * edges, publish the new slots, then release displaced edges. A scalar
     * cell that remains in the destination keeps its existing owner identity.
     * The publisher must perform only storage and owner bookkeeping; Perl
     * callbacks belong in the release operation after publication.
     */
    static void transitionContainerSlots(
            Collection<RuntimeScalar> displaced,
            Collection<RuntimeScalar> replacements,
            PerlOwnerSlot.Kind ownerKind,
            Runnable publish,
            Consumer<Collection<RuntimeScalar>> releaseDisplaced) {
        Set<RuntimeScalar> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        if (replacements != null) {
            for (RuntimeScalar replacement : replacements) {
                incrementRefCountForContainerStore(replacement, ownerKind);
                if (replacement != null) retained.add(replacement);
            }
        }
        publish.run();
        if (displaced == null || displaced.isEmpty() || releaseDisplaced == null) return;

        Set<RuntimeScalar> alreadyReleased = Collections.newSetFromMap(new IdentityHashMap<>());
        List<RuntimeScalar> retired = new ArrayList<>();
        for (RuntimeScalar scalar : displaced) {
            if (scalar != null && !retained.contains(scalar) && alreadyReleased.add(scalar)) {
                retired.add(scalar);
            }
        }
        if (!retired.isEmpty()) releaseDisplaced.accept(retired);
    }

    private record IoOwnerRelease(RuntimeGlob glob, int remainingHolders) {}

    private static PerlOwnerSlot acquireIoOwnerSlot(RuntimeGlob glob) {
        synchronized (glob) {
            if (glob.ioHolderCount() == Integer.MAX_VALUE) {
                throw new IllegalStateException("anonymous IO holder count overflow");
            }
            PerlOwnerSlot ownerSlot = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER);
            ownerSlot.acquireNativeOwner(glob);
            return ownerSlot;
        }
    }

    private static void attachIoOwnerSlot(RuntimeScalar scalar, PerlOwnerSlot ownerSlot) {
        if (scalar.ioOwnerSlot != null) {
            throw new IllegalStateException("scalar already owns an anonymous IO holder");
        }
        if (ownerSlot == null || !ownerSlot.isActive()
                || ownerSlot.kind() != PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER
                || !ownerSlot.isNativeOwner()
                || !(ownerSlot.referent() instanceof RuntimeGlob)) {
            throw new IllegalStateException("cannot attach an inactive anonymous IO holder");
        }
        scalar.ioOwnerSlot = ownerSlot;
        ownerSlot.associateResourceScalarOwner(scalar);
    }

    private static IoOwnerRelease releaseIoOwnerSlot(RuntimeScalar scalar) {
        if (scalar == null) return null;
        if (scalar.ioOwnerSlot == null) return null;
        PerlOwnerSlot ownerSlot = scalar.ioOwnerSlot;
        scalar.ioOwnerSlot = null;
        ownerSlot.associateResourceScalarOwner(null);
        return releaseIoOwnerSlot(ownerSlot);
    }

    static PerlOwnerSlot detachIoOwnerForDeferredRelease(RuntimeScalar scalar, RuntimeBase referent) {
        if (scalar == null || scalar.ioOwnerSlot == null) return null;
        PerlOwnerSlot ownerSlot = scalar.ioOwnerSlot;
        if (!ownerSlot.isActive() || ownerSlot.kind() != PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER
                || ownerSlot.referent() != referent) {
            return null;
        }
        scalar.ioOwnerSlot = null;
        ownerSlot.associateResourceScalarOwner(null);
        return ownerSlot;
    }

    static void releaseDeferredIoOwner(PerlOwnerSlot ownerSlot) {
        IoOwnerRelease ownerRelease = releaseIoOwnerSlot(ownerSlot);
        finishIoOwnerRelease(ownerRelease);
    }

    private static IoOwnerRelease releaseIoOwnerSlot(PerlOwnerSlot ownerSlot) {
        if (ownerSlot == null || !ownerSlot.isActive()
                || ownerSlot.kind() != PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER
                || !(ownerSlot.referent() instanceof RuntimeGlob glob)) {
            throw new IllegalStateException("scalar IO owner flag has no active owner identity");
        }
        synchronized (glob) {
            if (!ownerSlot.isNativeOwner() || glob.ioHolderCount() <= 0) {
                throw new IllegalStateException("anonymous IO holder release is unbalanced");
            }
            ownerSlot.release();
            return new IoOwnerRelease(glob, glob.ioHolderCount());
        }
    }

    static void transferIoOwner(RuntimeScalar source, RuntimeScalar destination) {
        if (source == null || destination == null || source == destination) return;
        if (source.ioOwnerSlot == null) return;
        PerlOwnerSlot ownerSlot = source.ioOwnerSlot;
        if (destination.ioOwnerSlot != null
                || ownerSlot == null || !ownerSlot.isActive()
                || ownerSlot.kind() != PerlOwnerSlot.Kind.IO_RESOURCE_HOLDER
                || !(ownerSlot.referent() instanceof RuntimeGlob glob)
                || source.type != GLOBREFERENCE || source.value != glob
                || destination.type != GLOBREFERENCE || destination.value != glob) {
            throw new IllegalStateException("invalid anonymous IO owner transfer");
        }
        synchronized (glob) {
            if (!ownerSlot.isNativeOwner()) {
                throw new IllegalStateException("transferred anonymous IO owner is not native");
            }
            source.ioOwnerSlot = null;
            destination.ioOwnerSlot = ownerSlot;
            ownerSlot.associateResourceScalarOwner(destination);
        }
    }

    public static void retainUnstashedIoForDurableSlot(RuntimeScalar scalar) {
        if (scalar == null || (PerlRuntime.currentOrNull() != null
                && WeakRefRegistry.isweak(scalar))) return;
        scalar.copiedFromArgumentFrame = null;
        if (scalar.ioOwnerSlot != null) {
            if (scalar.ioOwnerSlot == null
                    || !scalar.ioOwnerSlot.isActive()
                    || !(scalar.ioOwnerSlot.referent() instanceof RuntimeGlob ownedGlob)
                    || scalar.type != GLOBREFERENCE || scalar.value != ownedGlob) {
                throw new IllegalStateException("durable scalar has a stale anonymous IO owner");
            }
            return;
        }
        if (scalar.type != GLOBREFERENCE
                || !(scalar.value instanceof RuntimeGlob glob)
                || !isUnstashedIoGlob(glob)
                || !hasLiveIo(glob)) {
            return;
        }
        attachIoOwnerSlot(scalar, acquireIoOwnerSlot(glob));
    }

    /** Retain a non-socket anonymous IO handle copied into a hash element slot. */
    public static void retainUnstashedFileIoForHashSlot(RuntimeScalar scalar) {
        if (scalar == null || scalar.type != GLOBREFERENCE
                || !(scalar.value instanceof RuntimeGlob glob)
                || !isUnstashedIoGlob(glob) || !hasLiveIo(glob)) {
            return;
        }
        RuntimeScalar ioSlot = glob.getIO();
        if (ioSlot != null && ioSlot.value instanceof RuntimeIO io
                && !isSocketIOHandle(io.ioHandle)) {
            retainUnstashedIoForDurableSlot(scalar);
        }
    }

    /** Copy a returned anonymous-IO alias without stealing its source holder token. */
    static RuntimeScalar copyReturnedIoAlias(RuntimeScalar source) {
        if (source == null || source.type != GLOBREFERENCE
                || !(source.value instanceof RuntimeGlob glob)
                || !isUnstashedIoGlob(glob) || !hasLiveIo(glob)) {
            return null;
        }
        boolean sourceOwned = source.hasIoOwner();
        RuntimeScalar copy = new RuntimeScalar(source);
        if (sourceOwned && copy.hasIoOwner()) {
            transferIoOwner(copy, source);
        }
        copy.copiedFromArgumentFrame = null;
        copy.reconcileContainerOwners();
        return copy;
    }

    private static boolean scalarReferenceContentsNeedRetain(RuntimeScalar value) {
        return value != null
                && value.type == REFERENCE
                && value.value instanceof RuntimeScalar scalarReferent
                && !WeakRefRegistry.isweak(value)
                && !WeakRefRegistry.isweak(scalarReferent)
                && !scalarReferent.refCountOwned
                && (scalarReferent.type & REFERENCE_BIT) != 0
                && scalarReferent.value instanceof RuntimeBase inner
                && inner.refCount >= 0;
    }

    private void retainScalarReferenceContents(RuntimeScalar value) {
        if (this.scalarReferenceContentsOwner != null) {
            this.releaseOwnedScalarReferenceContents();
        }
        if (!scalarReferenceContentsNeedRetain(value)) return;
        RuntimeScalar scalarReferent = (RuntimeScalar) value.value;
        RuntimeBase inner = (RuntimeBase) scalarReferent.value;
        PerlOwnerSlot owner = new PerlOwnerSlot(PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS);
        owner.acquireScalarReferenceContents(inner, scalarReferent, this);
        this.scalarReferenceContentsOwner = owner;
    }

    public void releaseOwnedScalarReferenceContents() {
        PerlOwnerSlot owner = this.scalarReferenceContentsOwner;
        this.scalarReferenceContentsOwner = null;
        if (owner != null) owner.releaseScalarReferenceContents();
    }

    /**
     * Transfer this scalar's ownership of a scalar-reference's contents to the
     * mortal list. Explicit return tears down the callee before the caller has
     * stored the returned value, so releasing immediately can destroy the inner
     * referent too early. The deferred decrement lets the caller's assignment
     * retain it first and keeps the retain/release accounting balanced.
     */
    public void deferOwnedScalarReferenceContents() {
        PerlOwnerSlot owner = this.scalarReferenceContentsOwner;
        this.scalarReferenceContentsOwner = null;
        if (owner != null) owner.deferScalarReferenceContentsRelease();
    }

    // Keep this entry point small so HotSpot can inline it into generated
    // code. Less common scalar kinds retain the complete assignment path in
    // setSlow(); the numeric loop case copies directly between plain cells.
    public RuntimeScalar set(RuntimeScalar value) {
        if (keysLvalueContainer == null
                && !threadShared
                && value != null
                && value != this
                && getClass() == RuntimeScalar.class
                && (value.getClass() == RuntimeScalar.class
                    || value.getClass() == RuntimeScalarReadOnly.class)
                && type < TIED_SCALAR
                && value.type == INTEGER
                && !value.transferableGrowingString) {
            return setPlainInteger(value);
        }
        return setSlow(value);
    }

    private RuntimeScalar setPlainInteger(RuntimeScalar value) {
        if (isPackageGlobalRoot || containerOwner != null) {
            clearLastReadlineHandleIfGlobValue();
        }
        prepareDetachedGlobValueOwner(value.type, value.value);
        growingString = null;
        type = value.type;
        this.value = copyScalarPayload(value.type, value.value);
        refreshDetachedGlobValueOwner();
        refreshArgumentAliasOwnerSlots();
        utf8UncheckedOctets = value.utf8UncheckedOctets;
        utf8MalformedWarning = value.utf8MalformedWarning;
        tainted = value.tainted;
        numericLiteralText = value.numericLiteralText;
        numericContextSeen = value.numericContextSeen;
        scalarStoredGlob = value.scalarStoredGlob
                || (value.type == GLOB && value.value instanceof RuntimeGlob);
        firstClassRegexScalar = value.firstClassRegexScalar;
        firstClassRegexReferent = value.firstClassRegexReferent;
        firstClassRegexValue = value.firstClassRegexValue;
        formatPictureTainted = value.formatPictureTainted;
        globalCodeRefFqn = value.globalCodeRefFqn;
        if (posCacheEntryCreated) RuntimePosLvalue.invalidatePos(this);
        ExtendedState state = extendedState;
        if (state != null && state.substrLvalueObservers != null) refreshSubstrLvalues();
        if (state != null && state.modifiedWatchers != null && !state.modifiedWatchers.isEmpty()) {
            notifyModifiedWatchers();
        }
        return this;
    }

    /** Whether a warned integer arithmetic result can update this lexical cell directly. */
    public boolean canUpdateArithmeticIntegerInPlace() {
        return getClass() == RuntimeScalar.class
                && keysLvalueContainer == null
                && !threadShared
                && type < TIED_SCALAR;
    }

    /** Store an arithmetic IV result without allocating a temporary scalar. */
    public RuntimeScalar setArithmeticIntegerResult(long result, boolean resultTainted) {
        if (isPackageGlobalRoot || containerOwner != null) {
            clearLastReadlineHandleIfGlobValue();
        }
        prepareDetachedGlobValueOwner(RuntimeScalarType.INTEGER, null);
        growingString = null;
        transferableGrowingString = false;
        if (value instanceof MutableIntegerValue mutableInteger) {
            mutableInteger.set(result);
        } else if (result >= -128 && result <= 127) {
            value = Integer.valueOf((int) result);
        } else {
            value = new MutableIntegerValue(result);
        }
        type = RuntimeScalarType.INTEGER;
        refreshDetachedGlobValueOwner();
        refreshArgumentAliasOwnerSlots();
        utf8UncheckedOctets = false;
        utf8MalformedWarning = null;
        tainted = resultTainted;
        numericLiteralText = null;
        numericContextSeen = false;
        scalarStoredGlob = false;
        firstClassRegexScalar = false;
        firstClassRegexReferent = null;
        firstClassRegexValue = null;
        formatPictureTainted = false;
        globalCodeRefFqn = null;
        if (posCacheEntryCreated) RuntimePosLvalue.invalidatePos(this);
        ExtendedState state = extendedState;
        if (state != null && state.substrLvalueObservers != null) refreshSubstrLvalues();
        if (state != null && state.modifiedWatchers != null && !state.modifiedWatchers.isEmpty()) {
            notifyModifiedWatchers();
        }
        return this;
    }

    private RuntimeScalar setSlow(RuntimeScalar value) {
        if (keysLvalueContainer != null) {
            if (keysLvalueContainer instanceof RuntimeHash hash) {
                hash.preallocateCapacity(value.getInt());
                this.type = RuntimeScalarType.INTEGER;
                this.value = hash.keys(RuntimeContextType.SCALAR).scalar().getInt();
                refreshArgumentAliasOwnerSlots();
                return this;
            }
            throw new PerlCompilerException("Can't modify keys on array in list assignment");
        }
        if (value != this) {
            clearLastReadlineHandleIfGlobValue();
        }
        GlobalVariable.promotePseudoConstantForEvalCodeAssignment(value);
        boolean transferGrowingString = value != null && value != this
                && value.transferableGrowingString;
        if (transferGrowingString) {
            growingString = value.growingString;
            value.growingString = null;
            value.transferableGrowingString = false;
        } else {
            if (value != null && value != this
                    && (value.type == STRING || value.type == BYTE_STRING)
                    && value.growingString != null) {
                value.materializeGrowingString();
            }
            if (value != this) {
                growingString = null;
            }
        }
        if (value instanceof OutputFormatVariable) {
            value = new RuntimeScalar(value.getInt());
        } else if (value instanceof CurrentFormatVariable) {
            value = value.getDefinedBoolean()
                    ? new RuntimeScalar(value.toString()) : new RuntimeScalar();
        }
        if (threadShared && value != null
                && (RuntimeScalarType.isReference(value) || value.type == GLOB)) {
            SharedPerlStorage.validateStoredValue(value);
            // Assignment into a shared scalar publishes the referent's current
            // class. A local shared reference may be reblessed privately, but
            // storing it in shared scalar storage is the explicit publication
            // boundary used by threads::shared.
            SharedPerlStorage.publishBlessing(value);
        }
        // Perl clears pos() when assigning from another SV ($x = $y), but preserves it for
        // self-assignment ($x = $x). Hash/array element slots reuse one RuntimeScalar per key;
        // $h{k} = $str must reset pos on that slot (Data::SExpression set_input / lexer \G).
        // Invalidate only after the new value is stored so the pos cache records the correct
        // string hash, and refresh in place so existing PosLvalueScalar handles stay valid.
        if (value != null && this.type < TIED_SCALAR & value.type < TIED_SCALAR) {
            if (this != value) {
                prepareDetachedGlobValueOwner(value.type, value.value);
                this.type = value.type;
                this.value = copyScalarPayload(value.type, value.value);
                refreshDetachedGlobValueOwner();
                this.utf8UncheckedOctets = value.utf8UncheckedOctets;
                this.utf8MalformedWarning = value.utf8MalformedWarning;
                this.tainted = value.tainted;
                this.numericLiteralText = value.numericLiteralText;
                this.numericContextSeen = value.numericContextSeen;
                this.scalarStoredGlob = value.scalarStoredGlob
                        || (value.type == GLOB && value.value instanceof RuntimeGlob);
                this.firstClassRegexScalar = value.firstClassRegexScalar;
               this.firstClassRegexReferent = value.firstClassRegexReferent;
               this.firstClassRegexValue = value.firstClassRegexValue;
                this.formatPictureTainted = value.formatPictureTainted;
                this.globalCodeRefFqn = value.globalCodeRefFqn;
                RuntimePosLvalue.invalidatePos(this);
            } else {
                prepareDetachedGlobValueOwner(value.type, value.value);
                this.type = value.type;
                this.value = copyScalarPayload(value.type, value.value);
                refreshDetachedGlobValueOwner();
                this.utf8UncheckedOctets = value.utf8UncheckedOctets;
                this.utf8MalformedWarning = value.utf8MalformedWarning;
                this.tainted = value.tainted;
                this.numericLiteralText = value.numericLiteralText;
                this.numericContextSeen = value.numericContextSeen;
                this.scalarStoredGlob = value.scalarStoredGlob
                        || (value.type == GLOB && value.value instanceof RuntimeGlob);
                this.firstClassRegexScalar = value.firstClassRegexScalar;
               this.firstClassRegexReferent = value.firstClassRegexReferent;
               this.firstClassRegexValue = value.firstClassRegexValue;
                this.formatPictureTainted = value.formatPictureTainted;
                this.globalCodeRefFqn = value.globalCodeRefFqn;
            }
            refreshArgumentAliasOwnerSlots();
            refreshSubstrLvalues();
            notifyModifiedWatchers();
            return this;
        }
        if (this != value) {
            RuntimeScalar r = setLarge(value);
            RuntimePosLvalue.invalidatePos(this);
            refreshSubstrLvalues();
            notifyModifiedWatchers();
            return r;
        }
        RuntimeScalar result = setLarge(value);
        refreshSubstrLvalues();
        notifyModifiedWatchers();
        return result;
    }

    /** Compiler hook for experimental scalar refaliasing into an lvalue proxy. */
    public RuntimeScalar aliasLvalueReference(RuntimeScalar reference) {
        if (this instanceof RuntimeHashProxyEntry hashEntry) {
            return hashEntry.aliasToReference(reference);
        }
        if (this instanceof RuntimeArrayProxyEntry arrayEntry) {
            return arrayEntry.aliasToReference(reference);
        }
        throw new PerlCompilerException("Assignment to unsupported ref aliasing target");
    }

    /** Compiler hook for refaliasing an aggregate field through its owning slot. */
    public RuntimeScalar aliasAggregateLvalueReference(RuntimeScalar reference, boolean hashTarget) {
        int expectedType = hashTarget ? RuntimeScalarType.HASHREFERENCE : RuntimeScalarType.ARRAYREFERENCE;
        String expectedName = hashTarget ? "HASH" : "ARRAY";
        if (reference.type != expectedType) {
            throw new PerlCompilerException("Assigned value is not a " + expectedName + " reference");
        }
        if (this instanceof RuntimeHashProxyEntry hashEntry) {
            return hashEntry.aliasToAggregateReference(reference);
        }
        if (this instanceof RuntimeArrayProxyEntry arrayEntry) {
            return arrayEntry.aliasToAggregateReference(reference);
        }
        throw new PerlCompilerException("Assignment to unsupported ref aliasing target");
    }

    RuntimeScalar setFromSubstrLvalue(RuntimeScalar value) {
        if (this != value) {
            RuntimeScalar result = setLarge(value);
            RuntimePosLvalue.invalidatePos(this);
            return result;
        }
        return setLarge(value);
    }

    /**
     * Set value while preserving BYTE_STRING type when possible.
     * Used by .= (string concat-assign) to prevent UTF-8 flag contamination
     * of binary buffers. Only preserves BYTE_STRING when the concat result
     * itself is BYTE_STRING (both operands were non-UTF-8). When the concat
     * result is STRING (at least one operand was UTF-8), the UTF-8 flag is
     * preserved, matching Perl's behavior where concatenation with a UTF-8
     * string upgrades the result.
     */
    public RuntimeScalar setPreservingByteString(RuntimeScalar value) {
        boolean wasByteString = (this.type == BYTE_STRING);
        this.set(value);
        // Only preserve BYTE_STRING when the concat result was also BYTE_STRING.
        // If concat produced STRING (because an operand was UTF-8), don't downgrade.
        if (wasByteString && value.type == BYTE_STRING && this.type == STRING) {
            // Check if all chars fit in Latin-1 (single byte)
            String s = this.toString();
            boolean allLatin1 = true;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) > 255) {
                    allLatin1 = false;
                    break;
                }
            }
            if (allLatin1) {
                this.type = BYTE_STRING;
            }
        }
        return this;
    }

    // Slow path for set(RuntimeScalar) — kept small for JIT inlining of set().
    // Reference-type assignments are dispatched to setLargeRefCounted() which
    // handles refCount tracking, IO lifecycle, and weak ref bookkeeping.
    private RuntimeScalar setLarge(RuntimeScalar value) {
        if (value == null) {
            return undefine();
        }
        // Unwrap source special types via switch dispatcher
        switch (value.type) {
            case TIED_SCALAR -> {
                return set(value.tiedFetch());
            }
            case READONLY_SCALAR -> {
                return set((RuntimeScalar) value.value);
            }
        }
        // Resolve ScalarSpecialVariable
        if (value instanceof ScalarSpecialVariable ssv) {
            value = ssv.getValueAsScalar();
        }
        // Handle target special types via switch dispatcher
        switch (this.type) {
            case TIED_SCALAR -> {
                // A typeglob rejected in numeric context must fail before a
                // tied STORE callback can observe the tentative assignment.
                // This preserves eval's atomic assignment behavior for
                // expressions such as `$tied = select STDOUT`.
                if (value.type == GLOB && value.value instanceof RuntimeIO) {
                    value.getNumber();
                }
                return this.tiedStore(value);
            }
            case READONLY_SCALAR -> {
                if (this instanceof RuntimeScalarReadOnly readOnly) {
                    readOnly.restoreForeachBeforeMutation();
                }
                throw new PerlCompilerException("Modification of a read-only value attempted");
            }
        }

        // Reference types (or overwriting a reference) need refCount + IO tracking.
        // All reference types have REFERENCE_BIT (0x8000) set, so a single
        // bitwise OR + AND check covers both old and new values.
        if (((this.type | value.type) & REFERENCE_BIT) != 0) {
            // Keep caller temporaries below this operation's boundary while
            // the new slot is installed and its old edge is released. The
            // transition flushes only work added above this mark; flushing
            // again after popping it would also drain older caller releases.
            MortalList.pushMark();
            try {
                return transitionReferenceSlot(value, false);
            } finally {
                MortalList.popMark();
            }
        }

        // Simple non-reference assignment (no refCount tracking needed).
        // No MortalList.flush() here — neither old nor new value is a reference,
        // so no refCount was incremented/decremented, and no mortal entries were added.
        prepareDetachedGlobValueOwner(value.type, value.value);
        this.type = value.type;
        this.value = copyScalarPayload(value.type, value.value);
        refreshDetachedGlobValueOwner();
        refreshArgumentAliasOwnerSlots();
        this.utf8UncheckedOctets = value.utf8UncheckedOctets;
        this.utf8MalformedWarning = value.utf8MalformedWarning;
        this.tainted = value.tainted;
        this.numericLiteralText = value.numericLiteralText;
        this.numericContextSeen = value.numericContextSeen;
        this.scalarStoredGlob = value.scalarStoredGlob
                || (value.type == GLOB && value.value instanceof RuntimeGlob);
        this.firstClassRegexScalar = value.firstClassRegexScalar;
       this.firstClassRegexReferent = value.firstClassRegexReferent;
       this.firstClassRegexValue = value.firstClassRegexValue;
        this.formatPictureTainted = value.formatPictureTainted;
        return this;
    }

    /**
     * Apply one scalar-slot transition for reference assignment and explicit
     * undef. The transition retains a replacement before publishing it and
     * releases the previous referent against the already-updated scalar state.
     *
     * <p>Explicit undef shares this operation but keeps its Perl-specific weak
     * observer and mortal-flush behavior. CODE slots with symbol-table semantics
     * are handled by {@link #undefine()} before reaching this method.</p>
     */
    private RuntimeScalar transitionReferenceSlot(RuntimeScalar value, boolean explicitUndef) {
        prepareCodeSlotValue(value);
        String packageCodeRefName = isPackageGlobalRoot ? globalCodeRefFqn : null;
        if (isPackageGlobalRoot) {
            MortalList.invalidateExternalRootSnapshot();
        }
        if (this.value != value.value) {
            MortalList.invalidateFullRootSnapshotIfReachable(this);
            if (containerOwner != null) {
                MortalList.invalidateFullRootSnapshotIfReachable(containerOwner);
            }
        }
        noteClearedAggregateElement(value);
        // Fast path for untracked references (refCount == -1).
        // Most reference assignments involve untracked objects (named variables,
        // anonymous arrays/hashes that were never blessed). Skip all refCount
        // tracking, WeakRefRegistry checks, and MortalList flush.
        boolean scalarRefContentTrackingNeeded =
                this.hasScalarReferenceContentsOwner()
                    || scalarReferenceContentsNeedRetain(value);
        boolean droppingAggregateWithWeakRefs = value.type == UNDEF
                && WeakRefRegistry.weakRefsExist()
                && this.value instanceof RuntimeBase oldReferent
                && (oldReferent instanceof RuntimeHash || oldReferent instanceof RuntimeArray);
        if (!explicitUndef
                && !scalarRefContentTrackingNeeded
                && !this.refCountOwned && !this.hasClosureCaptureOwner()
                && this.captureRefCountOwned() == 0
                && !WeakRefRegistry.isweak(this)
                && !droppingAggregateWithWeakRefs
                && this.type != GLOBREFERENCE && value.type != GLOBREFERENCE) {
            // Both old and new are non-GLOB references. Check if referents are untracked.
            boolean oldUntracked = (this.type & REFERENCE_BIT) == 0
                    || this.value == null
                    || ((RuntimeBase) this.value).refCount == -1;
            boolean newUntracked = (value.type & REFERENCE_BIT) == 0
                    || value.value == null
                    || ((RuntimeBase) value.value).refCount == -1;
            if (oldUntracked && newUntracked) {
                // Hash/array element stores must count refs to RuntimeHash/RuntimeArray
                // at refCount==-1 (see incrementRefCountForContainerStore). Otherwise a
                // lone `$slot = $obj` assignment can bypass tracking while push() does not.
                if ((value.type & REFERENCE_BIT) != 0
                        && value.value instanceof RuntimeBase nb
                        && nb.refCount < 0
                        && (nb instanceof RuntimeHash || nb instanceof RuntimeArray)) {
                    // fall through to full refCounted assignment
                } else {
                    prepareDetachedGlobValueOwner(value.type, value.value);
                    this.type = value.type;
                    this.value = copyScalarPayload(value.type, value.value);
                    refreshDetachedGlobValueOwner();
                    refreshArgumentAliasOwnerSlots();
                    this.utf8UncheckedOctets = value.utf8UncheckedOctets;
                    this.utf8MalformedWarning = value.utf8MalformedWarning;
                    this.tainted = value.tainted;
                    this.numericLiteralText = value.numericLiteralText;
                    this.numericContextSeen = value.numericContextSeen;
                    updateUntrackedReferenceOwner(this.value instanceof RuntimeBase referent
                            && (this.type & RuntimeScalarType.REFERENCE_BIT) != 0
                            ? referent : null);
                    if (value != this && !stagedValueOwner
                            && this.value instanceof RuntimeBase referent) {
                        MortalList.consumeMortalReturnValue(value, referent);
                    }
                    if (packageCodeRefName != null) {
                        GlobalVariable.noteGlobalCodeRefSlotMutation(this);
                    }
                    return this;
                }
            }
        }

        // ──────────────────────────────────────────────────────────────────
        // closeIOOnDrop() was REMOVED from this assignment path.
        //
        // ROOT CAUSE (Capture::Tiny failure):
        //   Capture::Tiny's _copy_std() saves STDOUT/STDERR handles like this:
        //     my $h;
        //     $h = IO::Handle->new();  open($h, ">&STDOUT"); $old{stdout} = $h;
        //     $h = IO::Handle->new();  open($h, ">&STDERR"); $old{stderr} = $h;
        //
        //   When $h is reassigned on the second iteration, setLarge() was called.
        //   The old value of $h was a GLOBREFERENCE to a gensym'd glob (Symbol::GEN*)
        //   whose IO slot held the dup'd STDOUT handle.
        //
        //   closeIOOnDrop() saw that the glob was no longer in any stash (gensym
        //   deletes it) and closed the IO.  This set ioHandle = ClosedIOHandle on
        //   the RuntimeIO.  But $old{stdout} STILL referenced the same glob/IO —
        //   so fileno($old{stdout}) now returned undef, and the later restore
        //   open(*STDOUT, ">&" . fileno($old{stdout})) failed with "Bad fd".
        //
        // WHY WE CAN'T FIX THIS WITH closeIOOnDrop:
        //   Without reference counting we cannot know whether other variables
        //   still hold a GLOBREFERENCE to the same RuntimeGlob.  The stash check
        //   only catches named globs; anonymous (gensym'd) globs are invisible.
        //
        // TRADEOFF:
        //   Removing this call means anonymous file handles that are overwritten
        //   (not explicitly closed) will leak until JVM GC / program exit.
        //   Explicit close($fh), undef $fh, and program exit still close handles.
        //   This matches the pre-existing behavior of set(int/long/double/String)
        //   which also never called closeIOOnDrop.
        //
        // See also: closeIOOnDrop() javadoc, dev/design/io_handle_lifecycle.md
        // ──────────────────────────────────────────────────────────────────

        // A removed hash/array element is returned as the original scalar slot.
        // When that loose value is assigned (delete/pop/shift), move the sole IO
        // cleanup responsibility to the destination lexical instead of creating
        // an ownerless extra holder.  The detached source is only an expression
        // temporary and does not receive normal lexical scope cleanup.
        boolean durableIoDestination = hasIoOwner()
                || containerOwner != null
                || isPackageGlobalRoot
                || MyVarCleanupStack.isRegistered(this);
        boolean assignedFromArgumentAlias = RuntimeCode.isCurrentArgumentAlias(value)
                || (!value.hasIoOwner()
                    && RuntimeCode.isArgumentFrameActive(value.copiedFromArgumentFrame));
        boolean assignedFromAcceptedSocketArgument = assignedFromArgumentAlias
                && isAcceptedSocket(value);
        boolean preserveCurrentIoOwner = this == value && hasIoOwner()
                && ioOwnerSlot != null && ioOwnerSlot.isActive()
                && ioOwnerSlot.referent() == value.value;
        boolean transferDetachedIoOwner = durableIoDestination
                && this != value
                && !assignedFromArgumentAlias
                && value.hasIoOwner()
                && value.type == GLOBREFERENCE
                && value.value instanceof RuntimeGlob transferGlob
                && isUnstashedIoGlob(transferGlob)
                && (value.isDetachedFromContainerOwner()
                    || value.containerOwner instanceof RuntimeList
                    || (value.containerOwner instanceof RuntimeArray array
                        && array.transientListAssignmentRhs)
                    || value.containerOwner == RuntimeCode.getCurrentArgs());

        // Acquire a replacement owner before releasing this scalar's previous
        // holder. A detached value can transfer its existing identity instead
        // of creating a second owner.
        PerlOwnerSlot replacementIoOwnerSlot = null;
        if (value.type == GLOBREFERENCE && value.value instanceof RuntimeGlob newGlob
                && isUnstashedIoGlob(newGlob)
                && hasLiveIo(newGlob)
                && durableIoDestination
                && (!assignedFromArgumentAlias || assignedFromAcceptedSocketArgument)
                && !transferDetachedIoOwner
                && !preserveCurrentIoOwner) {
            replacementIoOwnerSlot = acquireIoOwnerSlot(newGlob);
        }
        if (hasIoOwner() && !preserveCurrentIoOwner) {
            IoOwnerRelease oldRelease = releaseIoOwnerSlot(this);
            finishIoOwnerRelease(oldRelease);
        }

        // NOTE: Do NOT release captures here on CODE overwrite.
        // releaseCaptures() must only fire when the CODE ref's refCount truly
        // reaches 0 (via DestroyDispatch.callDestroy). Releasing on every
        // overwrite is wrong because other variables may still hold the same
        // CODE ref — e.g., the stash entry *Foo::bar holds the constructor
        // while a local variable also holds it. Overwriting the local should
        // not release the captures that the stash's copy still needs.
        // For untracked CODE refs (refCount == -1), the JVM GC handles cleanup.

        // Track refCount for blessed objects with DESTROY.
        // Save old referent BEFORE the assignment (for correct DESTROY ordering —
        // Perl 5 semantics: DESTROY sees the new state of the variable, not the old)
        RuntimeBase oldBase = null;
        if ((this.type & RuntimeScalarType.REFERENCE_BIT) != 0 && this.value != null) {
            oldBase = (RuntimeBase) this.value;
        }
        boolean oldRefCountOwned = this.refCountOwned;
        // Scope unwind can clear a cell's payload after scheduling its array
        // edge release while leaving the stable storage token on the cell.
        // Retire that exact displaced edge from its token instead of treating
        // the missing payload as a lost owner and aborting the replacement.
        if (oldBase == null && oldRefCountOwned && countedOwnerSlot != null
                && countedOwnerSlot.isActive()
                && countedOwnerSlot.countedScalarOwner() == this
                && (countedOwnerSlot.kind() == PerlOwnerSlot.Kind.ARRAY_ELEMENT
                    || countedOwnerSlot.kind() == PerlOwnerSlot.Kind.HASH_ELEMENT)) {
            oldBase = countedOwnerSlot.referent();
        }
        boolean oldIdentifiedOwner = oldBase != null && oldRefCountOwned
                && countedOwnerSlot != null && countedOwnerSlot.isActive()
                && countedOwnerSlot.referent() == oldBase;
        boolean staleAggregateOwner = oldIdentifiedOwner
                && (countedOwnerSlot.kind() == PerlOwnerSlot.Kind.ARRAY_ELEMENT
                    || countedOwnerSlot.kind() == PerlOwnerSlot.Kind.HASH_ELEMENT)
                && !isCurrentAggregateElementOwner(oldBase, countedOwnerSlot);
        // A live scalar-reference contents slot is the ownership proof for an
        // uncounted scalar cell's current referent. Assigning through $$ref
        // retargets those exact slots below; the historical referencedByScalarReference
        // flag alone does not identify a live owner and must not authorize a decrement.
        List<PerlOwnerSlot> scalarContentsOwnerSnapshot =
                this.scalarReferenceContentsOwnersSnapshot();
        List<PerlOwnerSlot> scalarContentsOwners =
                new ArrayList<>(scalarContentsOwnerSnapshot);
        PerlOwnerSlot oldScalarReferenceContentsOwner = this.scalarReferenceContentsOwner;
        if (oldScalarReferenceContentsOwner != null) {
            scalarContentsOwners.remove(oldScalarReferenceContentsOwner);
        }
        boolean oldOwnedScalarReferenceContents = this.hasScalarReferenceContentsOwner();
        boolean scalarContentsOwnedByEscapedReference =
                !scalarContentsOwnerSnapshot.isEmpty() && !this.localBindingExists;
        boolean shouldReleaseUnrootedRescuedGraph = false;

        // If this scalar was a weak ref, remove from weak tracking before overwriting.
        // Weak refs don't count toward refCount, so skip refCount decrement later.
        boolean thisWasWeak = (oldBase != null && WeakRefRegistry.removeWeakRef(this, oldBase));
        boolean undefAssignmentOfDestroyableRef = oldBase != null
                && !thisWasWeak
                && value.type == UNDEF
                && oldBase.blessId != 0
                && WeakRefRegistry.weakRefsExist()
                && blessedClassHasDestroy(oldBase);
        if (oldBase != null && this.captureRefCountOwned() > 0) {
            releaseAllClosureCaptureReferents(oldBase);
        }
        // Decide whether the replacement is a counted edge before changing the
        // scalar. A new count and its slot identity are acquired together; the
        // old edge carries a separate release token through cleanup.
        boolean oldArgumentAliasReleaseQueued = false;
        boolean retireOldCountedOwnerAfterPublish = false;
        boolean oldOwnerTraceReleasedDuringReplacement = false;
        PerlOwnerSlot.PendingRelease oldCountedOwnerRelease = null;
        boolean newOwned = false;
        RuntimeBase newReferent = null;
        if (((value.type & RuntimeScalarType.REFERENCE_BIT) != 0
                || value.type == RuntimeScalarType.GLOB)
                && value.value != null) {
            RuntimeBase nb = (RuntimeBase) value.value;
            if (nb.refCount < 0
                    && (nb instanceof RuntimeHash || nb instanceof RuntimeArray)) {
                nb.refCount = 0;
            }
            if (nb.refCount >= 0 && !scalarContentsOwnedByEscapedReference) {
                if (nb.refCount == 0) nb.registerSharedFetchedView();
                newOwned = true;
                newReferent = nb;
            }
        }

        if (oldRefCountOwned && !thisWasWeak) {
            if (oldBase == null || !oldIdentifiedOwner) {
                throw new IllegalStateException(
                        "counted scalar lost its current owner slot during replacement"
                                + " (scalar=" + System.identityHashCode(this)
                                + ", referent=" + (oldBase == null ? "null"
                                    : System.identityHashCode(oldBase))
                                + ", ownerSlot=" + (countedOwnerSlot == null ? "null"
                                    : countedOwnerSlot.identity())
                                + ", ownerKind=" + (countedOwnerSlot == null ? "null"
                                    : countedOwnerSlot.kind())
                                + ", ownerActive=" + (countedOwnerSlot != null
                                    && countedOwnerSlot.isActive())
                                + ", slotReferent=" + (countedOwnerSlot == null
                                    || countedOwnerSlot.referent() == null ? "null"
                                    : System.identityHashCode(countedOwnerSlot.referent()))
                                + ", slotOwner=" + (countedOwnerSlot == null
                                    || countedOwnerSlot.countedScalarOwner() == null ? "null"
                                    : System.identityHashCode(
                                        countedOwnerSlot.countedScalarOwner())) + ")");
            }
            if (staleAggregateOwner && oldBase.refCount <= 0) {
                // A detached aggregate slot is no longer a Perl edge. Its
                // identity can outlive the storage mutation in compatibility
                // paths; retire that token without decrementing a count that
                // has already reached zero or the destroyed sentinel.
                retireOldCountedOwnerAfterPublish = true;
            } else if (oldBase.refCount <= 0) {
                throw new IllegalStateException(
                        "counted scalar replacement requires a positive old reference count");
            } else if (oldBase.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS) != 0) {
                MortalList.deferDecrementFromArraySlot(oldBase, this,
                        "RuntimeScalar.setLargeRefCounted overwrite");
                oldArgumentAliasReleaseQueued = true;
                if (newOwned) {
                    acquireCountedOwnerForPendingValue(this, newReferent,
                            countedOwnerKind(),
                            "RuntimeScalar.setLargeRefCounted store");
                }
            } else if (newOwned) {
                oldCountedOwnerRelease = countedOwnerSlot
                        .replaceCountedScalarOwnerAndAcquire(
                                oldBase, newReferent, this,
                                "RuntimeScalar.setLargeRefCounted store");
                oldOwnerTraceReleasedDuringReplacement = oldBase != newReferent;
            } else {
                retireOldCountedOwnerAfterPublish = true;
            }
        } else {
            if (thisWasWeak && oldIdentifiedOwner) {
                throw new IllegalStateException(
                        "weak scalar still owns a counted referent slot");
            }
            this.refCountOwned = false;
            if (newOwned) {
                acquireCountedOwnerForPendingValue(this, newReferent,
                        countedOwnerKind(),
                        "RuntimeScalar.setLargeRefCounted store");
            }
        }

        int preAssignType = this.type;
        Object preAssignValue = this.value;

        prepareDetachedGlobValueOwner(value.type, value.value);
        // Do the assignment
        this.type = value.type;
        this.value = copyScalarPayload(value.type, value.value);
        long referenceSlotMutationVersion = noteReferenceSlotMutation();
        this.refCountOwned = newOwned;
        this.scalarReferenceContentsOwner = null;
        if (retireOldCountedOwnerAfterPublish) {
            // The replacement is now visible. Retire the old slot only after
            // publication, while carrying its exact count obligation forward.
            oldCountedOwnerRelease = countedOwnerSlot.releaseCountedIdentity();
            if (oldCountedOwnerRelease == null) {
                throw new IllegalStateException(
                        "published scalar replacement lost its old release token");
            }
        }
        // The replacement count and identity are already paired before any
        // displaced-edge cleanup can call Perl code.
        if (isCurrentReferenceSlotMutation(referenceSlotMutationVersion)) {
            // Hash-element slots remain durable owners when their existing
            // scalar wrapper is assigned a new referent (the normal class-field
            // ADJUST path). RuntimeHash's map hook covers slot replacement; this
            // covers mutation of a slot that is already in the map.
            if (containerOwner instanceof RuntimeHash && oldBase != this.value) {
                if (oldBase != null && oldBase.hashSlotOwnerCount > 0) {
                    oldBase.hashSlotOwnerCount--;
                }
                if (this.value instanceof RuntimeBase newBase) {
                    newBase.hashSlotOwnerCount++;
                }
            }
            this.utf8UncheckedOctets = value.utf8UncheckedOctets;
            this.utf8MalformedWarning = value.utf8MalformedWarning;
            this.tainted = value.tainted;
            this.numericLiteralText = value.numericLiteralText;
            this.numericContextSeen = value.numericContextSeen;
            this.firstClassRegexScalar = value.firstClassRegexScalar;
            this.firstClassRegexReferent = value.firstClassRegexReferent;
            this.firstClassRegexValue = value.firstClassRegexValue;
            this.formatPictureTainted = value.formatPictureTainted;
            this.globalCodeRefFqn = packageCodeRefName != null
                    ? packageCodeRefName : value.globalCodeRefFqn;
            if (transferDetachedIoOwner) {
                transferIoOwner(value, this);
            } else if (replacementIoOwnerSlot != null) {
                attachIoOwnerSlot(this, replacementIoOwnerSlot);
            }
            // Hash and array assignment normally preserves the aggregate's existing
            // scalar slot. If that slot belongs to a tied-handler graph, propagate
            // its conservative marker to the newly installed reference graph too.
            propagateTiedHandlerMarkerToReferent();
            if (this.globalCodeRefFqn != null && this.value instanceof RuntimeCode code) {
                code.hadStashRef = true;
            }
            if (!updatingCompiledDirectCallCache
                    && (packageCodeRefName != null || hasCodeSlotBinding())
                    && (preAssignType != this.type || preAssignValue != this.value)) {
                GlobalVariable.noteGlobalCodeRefSlotMutation(this);
            }

            // Transfer a captured cell's value owner after publishing the new
            // contents. The slot transfer acquires the replacement first and
            // then releases the previous referent, so callbacks see the updated
            // cell and there is no ownership gap.
            if (this.hasClosureCaptureOwner()) {
                if ((this.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        && this.value instanceof RuntimeBase capturedBase
                        && !WeakRefRegistry.isweak(this)) {
                    if (this.type == RuntimeScalarType.CODE) {
                        // Capturing an undef cell precedes assignment in recursive
                        // closures. The new CODE value needs the capture's own edge,
                        // independently of the declaring lexical's store owner.
                        retainClosureCaptureReferent();
                    } else {
                        updateClosureOwnerSlot(capturedBase);
                    }
                } else {
                    updateClosureOwnerSlot(null);
                }
            }
        }

        if (isCurrentReferenceSlotMutation(referenceSlotMutationVersion)) {
            RuntimeBase currentReferent =
                    (this.type & RuntimeScalarType.REFERENCE_BIT) != 0
                            && this.value instanceof RuntimeBase referent ? referent : null;
            updateUntrackedReferenceOwner(currentReferent);
        }
        if (isCurrentReferenceSlotMutation(referenceSlotMutationVersion)) {
            refreshDetachedGlobValueOwner();
        }
        if (isCurrentReferenceSlotMutation(referenceSlotMutationVersion)) {
            refreshArgumentAliasOwnerSlots();
        }
        // During global destruction Perl warns when DESTROY stores its dying
        // object into durable Perl storage, even if that storage cell already
        // held the same reference before the callback. Owner-slot delta alone
        // cannot see that same-cell assignment, so record the store at its
        // scalar-slot producer while the lifecycle phase is still DESTRUCT.
        if (isCurrentReferenceSlotMutation(referenceSlotMutationVersion)
                && DestroyDispatch.currentDestroyTarget() != null
                && "DESTRUCT".equals(GlobalVariable
                        .getGlobalVariable(GlobalContext.GLOBAL_PHASE).toString())
                && isPackageGlobalRoot
                && !WeakRefRegistry.isweak(this)
                && this.value == DestroyDispatch.currentDestroyTarget()
                && hasLiveDurableStrongValueSlot()) {
            DestroyDispatch.markDestroyTargetRescued();
        }
        // An explicitly discarded closure can have an inflated CODE refcount,
        // so waiting for callDestroy() leaves its captures alive indefinitely.
        // Release them only when the owner ledger shows no other Perl owner
        // besides the scalar slot currently being overwritten.
        if (value.type == UNDEF
                && oldBase instanceof RuntimeCode oldCode
                && !oldCode.hasCountedCodeSlots
                && oldCode.capturedScalars != null
                && !oldCode.hasPendingReturnOwner()
                && !oldCode.hasPerlOwnerForCaptureOtherThan(countedOwnerSlot)) {
            oldCode.releaseCaptures();
        }

        // DESTROY rescue detection for reference types.
        // Only trigger when the OLD value was a reference to the DESTROY target
        // (e.g., a weak ref being overwritten by a strong ref to the same object).
        // This detects Schema::DESTROY's self-save pattern where:
        //   $source->{schema} = $self  (overwriting weak ref with strong ref)
        // But avoids false positives from:
        //   my $self = shift  (new local variable, oldBase is null)
        if (isCurrentReferenceSlotMutation(referenceSlotMutationVersion)
                && thisWasWeak
                && DestroyDispatch.currentDestroyTarget() != null
                && oldBase == DestroyDispatch.currentDestroyTarget()
                // The scalar must still occupy durable Perl storage after the
                // assignment. Use the exact storage proof here because the
                // fast container hint can be absent during lvalue dispatch.
                // Package-rooted aggregate slots count as durable owners too:
                // their explicit storage identity keeps a promoted value alive
                // until that exact slot is released.
                && hasLiveDurableStrongValueSlot()
                && this.value instanceof RuntimeBase base
                && base == DestroyDispatch.currentDestroyTarget()) {
            DestroyDispatch.markDestroyTargetRescued();
            // Transition from destroyed (MIN_VALUE) to tracked so that when the
            // rescuing reference is eventually released (e.g., source goes out of
            // scope at the end of DESTROY), cascading cleanup brings the refCount
            // back to 0 and triggers weak ref clearing. Without this, the rescued
            // object stays untracked (-1) and weak refs are never cleared, causing
            // leak detection failures (DBIC t/52leaks.t tests 12-20).
            //
            // The ordinary store above already counted and identified this
            // slot unless the scalar-reference-contents slot is its owner.
            // Rescue changes the lifecycle decision; it must not add a second
            // count for the same strong edge. Only install a count here when
            // the normal store path left this edge uncounted and no contents
            // owner already represents it.
            boolean rescueNeedsCountedOwner = !newOwned
                    && !scalarContentsOwnedByEscapedReference;
            if (rescueNeedsCountedOwner && base.refCount == Integer.MIN_VALUE) {
                // DESTROY resurrection makes the dying object eligible for a
                // new owner again. Reset only the lifecycle sentinel here;
                // the shared acquisition below installs the count and exact
                // scalar-slot identity together.
                base.refCount = 0;
            }
            if (rescueNeedsCountedOwner && base.refCount >= 0) {
                acquireCountedOwnerForPendingValue(
                        this, base, countedOwnerKind(),
                        "RuntimeScalar.setLargeRefCounted DESTROY rescue");
                newOwned = true;
            }
        }

        // Phase B1 (refcount_alignment_52leaks_plan.md): register this scalar
        // once, after rescue handling has had a chance to acquire ownership.
        // This used to run both before and after the assignment, causing two
        // WeakHashMap.put() calls for every ordinary tracked reference store.
        if (newOwned) {
            ScalarRefRegistry.registerRef(this);
        }

        // A returned blessed container may have no selective count even though
        // this scalar is its final Perl owner.  It will not enter the counted
        // decrement branch below, so release nested objects explicitly before
        // an undef overwrite discards the container.
        if (oldBase != null && oldBase.blessId != 0 && oldBase.refCount == 0
                && !thisWasWeak && value.type == UNDEF) {
            if (oldBase instanceof RuntimeArray array) {
                MortalList.deferDestroyForContainerClear(array.elements);
            } else if (oldBase instanceof RuntimeHash hash) {
                MortalList.deferDestroyForContainerClear(hash.elements.values());
            }
        }

        if (explicitUndef && oldBase != null
                && oldBase.refCount == WeakRefRegistry.WEAKLY_TRACKED) {
            boolean argumentAliasKeepsOldBase =
                    oldBase.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS) != 0;
            if (argumentAliasKeepsOldBase) {
                // The deferred alias is the remaining strong edge. Keep the
                // weak lifecycle obligation on that exact owner until it leaves.
                oldBase.deferFinalReleaseToUncountedOwnerSlots();
            } else if (!oldBase.hasPerlStrongOwner()) {
                oldBase.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(oldBase);
            }
        }

        // Decrement old value's refCount AFTER assignment (skip for weak refs
        // and for scalars that didn't own a refCount increment).
        if (oldBase != null && !thisWasWeak && oldRefCountOwned) {
            if (oldCountedOwnerRelease != null) {
                // The slot now describes the replacement edge. Drain the exact
                // displaced edge token so this release cannot retire or infer
                // ownership from the scalar's newer value.
                if (!oldOwnerTraceReleasedDuringReplacement
                        && (!newOwned || oldBase != newReferent)) {
                    oldBase.releaseOwner(this, "setLargeRefCounted overwrite");
                }
                if (staleAggregateOwner && oldBase.refCount <= 0) {
                    oldCountedOwnerRelease.complete();
                } else {
                    MortalList.releaseCountedOwnerSlotImmediately(oldBase,
                            oldCountedOwnerRelease,
                            "RuntimeScalar.setLargeRefCounted overwrite");
                }
            } else if (oldArgumentAliasReleaseQueued) {
                // The old edge was already queued before the active alias could
                // observe the scalar's replacement.
            } else {
                throw new IllegalStateException(
                        "counted scalar replacement lost its pending owner release");
            }
        }

        if (oldOwnedScalarReferenceContents) {
            // Releasing the previous scalar-reference owner may invoke a
            // guard's DESTROY.  Keep deferred return owners dormant until the
            // replacement has been fully installed in this scalar; otherwise
            // nested DESTROY cleanup can release the just-returned value.
            boolean flushWasSuppressed = MortalList.suppressFlush(true);
            try {
                if (oldScalarReferenceContentsOwner != null) {
                    oldScalarReferenceContentsOwner.releaseScalarReferenceContents();
                }
            } finally {
                MortalList.suppressFlush(flushWasSuppressed);
            }
        }
        if (undefAssignmentOfDestroyableRef
                && oldBase.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS) == 0
                && !DestroyDispatch.isInsideDestroy()) {
            shouldReleaseUnrootedRescuedGraph = true;
        }

        // WEAKLY_TRACKED objects: do NOT clear weak refs on overwrite.
        // These objects have refCount == -2 and their strong refs don't have
        // refCountOwned=true (they were set before tracking started).
        // Overwriting ONE reference doesn't mean no other strong refs exist —
        // closures may capture copies (e.g., Sub::Quote's $_QUOTED capture).
        // This is the same rationale as in scopeExitCleanup.
        // Final strong-owner release clears weak refs through the owner-slot
        // ledger; overwrite does not schedule a later reachability sweep.

        // A reentrant setter or weaken() may already have completed the slot's
        // ownership transition while old-value cleanup ran. Do not restore the
        // outer assignment's stale bookkeeping over that newer state.
        if (isCurrentReferenceSlotMutation(referenceSlotMutationVersion)) {
            this.refCountOwned = newOwned;
            if (newOwned && this.value instanceof RuntimeBase ownedReferent) {
                requireCountedOwner(ownedReferent, "RuntimeScalar.setLargeRefCounted store");
                if (untrackedReferenceOwnerSlot != null) {
                    releaseUntrackedReferenceOwner(untrackedReferenceOwnerSlot.referent());
                }
            } else {
                updateUntrackedReferenceOwner(this.value instanceof RuntimeBase referent
                        && (this.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        ? referent : null);
            }
        }
        if (isCurrentReferenceSlotMutation(referenceSlotMutationVersion)
                && !scalarContentsOwners.isEmpty()) {
            RuntimeBase nextContents = (this.type & REFERENCE_BIT) != 0
                    && this.value instanceof RuntimeBase base
                    && !WeakRefRegistry.isweak(this) ? base : null;
            for (PerlOwnerSlot owner : scalarContentsOwners) {
                RuntimeScalar scalarOwner = owner.scalarContentsOwner();
                owner.retargetScalarReferenceContents(nextContents);
                if (!owner.isActive() && scalarOwner != null) {
                    scalarOwner.clearScalarReferenceContentsOwner(owner);
                }
            }
        }
        if (isCurrentReferenceSlotMutation(referenceSlotMutationVersion)) {
            retainScalarReferenceContents(value);
        }

        if (value != this && !stagedValueOwner
                && this.value instanceof RuntimeBase referent) {
            MortalList.consumeMortalReturnValue(value, referent);
        }

        // Flush deferred mortal decrements from the current function scope.
        // Entries below the current MortalList mark belong to the caller, and
        // must survive through nested calls such as chained AUTOLOAD dispatch.
        // Cost when MortalList.active is false: one boolean check (trivially predicted).
        MortalList.pushTemporaryRoot(this);
        MortalList.pushTemporaryRoot(value);
        try {
            if (explicitUndef) MortalList.flush();
            else MortalList.flushAboveMark();
        } finally {
            MortalList.popTemporaryRoot(value);
            MortalList.popTemporaryRoot(this);
        }
        // An explicit undef can run DESTROY and let the object self-rescue.
        // Preserve real Perl resurrection when another package/closure root
        // still reaches that graph.  If the only remaining pin is the runtime's
        // rescued-object queue, release its nested weak callbacks now: DBIC's
        // retained DBI handle relies on its weak HandleError closure observing
        // that the owning Schema/Storage graph has gone away.
        if (shouldReleaseUnrootedRescuedGraph
                && DestroyDispatch.isRescued(oldBase)
                && !ModuleInitGuard.inModuleInit()
                && !DestroyDispatch.hasLivePerlOwnerIdentity(oldBase)) {
            DestroyDispatch.clearNestedWeakRefsForRescued(oldBase);
        }

        if (isPackageGlobalRoot && globalCodeRefFqn != null) {
            boolean invalidate = preAssignType != this.type || preAssignValue != this.value;
            if (!invalidate && this.type == CODE && this.value instanceof RuntimeCode nc
                    && preAssignType == CODE && preAssignValue instanceof RuntimeCode oc) {
                invalidate = oc.defined() != nc.defined();
            }
            if (invalidate) {
                InheritanceResolver.invalidateMethodLookupCachesForStashSubKey(globalCodeRefFqn);
            }
        }

        return this;
    }

    private void noteClearedAggregateElement(RuntimeScalar replacement) {
        if (!FutureAsyncAwaitRuntime.isExecutingAsyncSub()
                || !(containerOwner instanceof RuntimeHash owner)
                || !owner.elements.containsValue(this)
                || replacement == null) {
            return;
        }
        boolean clearedArray = this.value instanceof RuntimeArray displacedArray
                && !displacedArray.elements.isEmpty()
                && replacement.value instanceof RuntimeArray replacementArray
                && replacementArray.elements.isEmpty();
        boolean clearedHash = this.value instanceof RuntimeHash displacedHash
                && !displacedHash.elements.isEmpty()
                && replacement.value instanceof RuntimeHash replacementHash
                && replacementHash.elements.isEmpty();
        if (clearedArray || clearedHash) {
            owner.clearedOwnedAggregateElement = true;
        }
    }

    private static boolean blessedClassHasDestroy(RuntimeBase base) {
        String cn = NameNormalizer.getBlessStr(base.blessId);
        return cn != null && DestroyDispatch.classHasDestroy(base.blessId, cn);
    }

    /** Whether a primitive/string setter must retire an existing Perl owner edge. */
    private boolean requiresOwnerAwareScalarOverwrite() {
        return (type & REFERENCE_BIT) != 0
                || type == GLOB
                || type == CODE
                || refCountOwned
                || (countedOwnerSlot != null && countedOwnerSlot.isActive())
                || untrackedReferenceOwnerSlot != null
                || scalarReferenceContentsOwner != null
                || ioOwnerSlot != null;
    }

    public RuntimeScalar set(int value) {
        clearLastReadlineHandleIfGlobValue();
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
        }
        if (this.type == READONLY_SCALAR) {
            if (this instanceof RuntimeScalarReadOnly readOnly) {
                readOnly.restoreForeachBeforeMutation();
            }
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }
        if (requiresOwnerAwareScalarOverwrite()) return set(new RuntimeScalar(value));
        this.type = RuntimeScalarType.INTEGER;
        this.value = value;
        refreshArgumentAliasOwnerSlots();
        this.tainted = false;
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;
        notifyModifiedWatchers();
        return this;
    }

    public RuntimeScalar set(long value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
        }
        if (this.type == READONLY_SCALAR) {
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }
        if (requiresOwnerAwareScalarOverwrite()) return set(new RuntimeScalar(value));
        this.initializeWithLong(value);
        refreshArgumentAliasOwnerSlots();
        this.tainted = false;
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;
        notifyModifiedWatchers();
        return this;
    }

    /**
     * Set this scalar to a BigInteger value.
     * This method preserves full precision for large integers by storing them as strings.
     *
     * @param value the BigInteger value to set
     * @return this RuntimeScalar instance
     */
    public RuntimeScalar set(BigInteger value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value.toString()));
        }
        if (this.type == READONLY_SCALAR) {
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }
        if (requiresOwnerAwareScalarOverwrite()) return set(new RuntimeScalar(value));

        // Check if the value fits in an int
        if (value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0
                && value.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
            // Fits in int
            this.type = RuntimeScalarType.INTEGER;
            this.value = value.intValue();
        }
        // Check if the value can be exactly represented as a double (up to 2^53)
        else if (value.abs().compareTo(BigInteger.valueOf(9007199254740992L)) <= 0) { // 2^53
            // Can be exactly represented as double
            this.type = RuntimeScalarType.DOUBLE;
            this.value = value.doubleValue();
        } else {
            // Too large for exact numeric representation
            // Store as string to preserve precision (like 32-bit Perl does)
            this.type = RuntimeScalarType.STRING;
            this.value = value.toString();
        }
        refreshArgumentAliasOwnerSlots();
        this.tainted = false;
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;
        notifyModifiedWatchers();
        return this;
    }

    public RuntimeScalar set(boolean value) {
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
        }
        if (this.type == READONLY_SCALAR) {
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }
        if (requiresOwnerAwareScalarOverwrite()) return set(new RuntimeScalar(value));
        this.type = RuntimeScalarType.BOOLEAN;
        this.value = value;
        refreshArgumentAliasOwnerSlots();
        this.tainted = false;
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;
        notifyModifiedWatchers();
        return this;
    }

    public RuntimeScalar set(String value) {
        clearLastReadlineHandleIfGlobValue();
        growingString = null;
        if (this.type == TIED_SCALAR) {
            return this.tiedStore(new RuntimeScalar(value));
        }
        if (this.type == READONLY_SCALAR) {
            if (this instanceof RuntimeScalarReadOnly readOnly) {
                readOnly.restoreForeachBeforeMutation();
            }
            throw new PerlCompilerException("Modification of a read-only value attempted");
        }
        if (requiresOwnerAwareScalarOverwrite()) return set(new RuntimeScalar(value));
        if (value == null) {
            this.type = UNDEF;
        } else {
            this.type = RuntimeScalarType.STRING;
        }
        this.value = value;
        refreshArgumentAliasOwnerSlots();
        this.utf8UncheckedOctets = false;
        this.utf8MalformedWarning = null;
        this.tainted = false;
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;
        notifyModifiedWatchers();
        return this;
    }

    /** Set the private caller-package tracker without scalar magic checks. */
    RuntimeScalar setInternalPackageName(String value) {
        if (getClass() != RuntimeScalar.class || type >= TIED_SCALAR) {
            return set(value);
        }
        growingString = null;
        type = value == null ? UNDEF : RuntimeScalarType.STRING;
        this.value = value;
        refreshArgumentAliasOwnerSlots();
        utf8UncheckedOctets = false;
        utf8MalformedWarning = null;
        tainted = false;
        numericLiteralText = null;
        numericContextSeen = false;
        firstClassRegexScalar = false;
        formatPictureTainted = false;
        notifyModifiedWatchers();
        return this;
    }

    public RuntimeScalar set(RuntimeGlob value) {
        return set(new RuntimeScalar(value));
    }

    /** Clear Perl's implicit last-read handle when a scalar holding a glob is replaced. */
    private void clearLastReadlineHandleIfGlobValue() {
        // Statement-temporary register scalars routinely hold a glob while
        // compiling/evaluating a no-op expression such as `*fh if 0`.  They
        // are not the user-visible glob slot and must not disturb ${^LAST_FH}.
        // Lexical lifetime cleanup is handled separately by scopeExitCleanup.
        if (!isPackageGlobalRoot && containerOwner == null) {
            return;
        }
        if (!(value instanceof RuntimeGlob glob)) {
            return;
        }
        RuntimeIO last = RuntimeIO.getLastAccessedHandle();
        if (last != null && (glob.globName != null && glob.globName.equals(last.globName)
                || glob.IO != null && glob.IO.value == last)) {
            RuntimeIO.setLastAccessedHandle(null);
        }
    }

    public RuntimeScalar set(RuntimeIO value) {
        return set(new RuntimeScalar(value));
    }

    public RuntimeScalar set(Object value) {
        return set(new RuntimeScalar(value));
    }

    public RuntimeArray setFromList(RuntimeList value) {
        if (keysLvalueContainer != null) {
            rejectKeysLvalueListAssignment();
        }
        return new RuntimeArray(this.set(value.scalar()));
    }

    @Override
    // Inlineable fast path for toString()
    public String toString() {
        if (type == STRING || type == BYTE_STRING) {
            return materializeGrowingString();
        }
        if (type == INTEGER) {
            if (value instanceof Integer i) {
                return integerToString(i);
            }
            return value.toString();
        }
        return toStringLarge();
    }

    // Slow path for toString()
    private String toStringLarge() {
        // Cases 0-8 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> value.toString();
            case DOUBLE -> ScalarUtils.formatLikePerl((double) value);
            case STRING, BYTE_STRING -> (String) value;
            case UNDEF -> "";
            case VSTRING -> (String) value;
            case BOOLEAN -> (boolean) value ? "1" : "";
            case GLOB -> value == null ? "" : value.toString();
            case JAVAOBJECT -> value.toString();
            case TIED_SCALAR -> this.tiedFetch().toString();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).toString();
            case DUALVAR -> ((DualVar) this.value).stringValue().toString();
            case CODE -> Overload.stringify(this).toString();
            default -> {
                if (type == REGEX) {
                    RuntimeRegex regex = (RuntimeRegex) value;
                    if (!regex.isScalarized()) yield value.toString();
                    int regexBlessId = blessId != 0 ? blessId : regex.blessId;
                    String className = regexBlessId == 0
                            ? "Regexp" : NameNormalizer.getBlessStr(regexBlessId);
                    yield className + "=" + regex.toStringRef();
                }
                // Overload.stringify calls the ("" method. If it returns THIS
                // exact scalar (or another object whose ("" points back here),
                // naively calling .toString() on the result would recurse. Perl
                // falls back to the default reference form in that case; so do
                // we. Detect by identity first, then by depth via a ThreadLocal
                // guard inside Overload.stringify (handles the transitive case).
                RuntimeScalar overloaded = Overload.stringify(this);
                if (isSameOverloadTarget(overloaded, this)) yield toStringRef();
                yield overloaded.toString();
            }
        };
    }

    private static boolean isSameOverloadTarget(RuntimeScalar overloaded,
                                                RuntimeScalar original) {
        RuntimeScalar resolvedOverloaded = unwrapReadonlyScalar(overloaded);
        RuntimeScalar resolvedOriginal = unwrapReadonlyScalar(original);
        if (resolvedOverloaded == resolvedOriginal) return true;
        if (resolvedOverloaded == null || resolvedOriginal == null) return false;
        if ((resolvedOverloaded.type & REFERENCE_BIT) == 0
                || (resolvedOriginal.type & REFERENCE_BIT) == 0) {
            return false;
        }
        return resolvedOverloaded.value != null
                && resolvedOverloaded.value == resolvedOriginal.value;
    }

    private static RuntimeScalar unwrapReadonlyScalar(RuntimeScalar scalar) {
        while (scalar != null && scalar.type == READONLY_SCALAR) {
            scalar = (RuntimeScalar) scalar.value;
        }
        return scalar;
    }

    /**
     * Convert to string without triggering overload dispatch.
     * Used by {@code no overloading} pragma to bypass the {@code ""} overload.
     * For blessed references, returns the raw "Class=TYPE(0xADDR)" string directly.
     */
    public String toStringNoOverload() {
        if (type == STRING || type == BYTE_STRING) {
            return materializeGrowingString();
        }
        return switch (type) {
            case INTEGER -> value.toString();
            case DOUBLE -> ScalarUtils.formatLikePerl((double) value);
            case STRING, BYTE_STRING -> (String) value;
            case UNDEF -> "";
            case VSTRING -> (String) value;
            case BOOLEAN -> (boolean) value ? "1" : "";
            case GLOB -> value == null ? "" : value.toString();
            case JAVAOBJECT -> value.toString();
            case TIED_SCALAR -> this.tiedFetch().toStringNoOverload();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).toStringNoOverload();
            case DUALVAR -> ((DualVar) this.value).stringValue().toStringNoOverload();
            case CODE -> toStringRef();
            default -> toStringRef();
        };
    }

    /** Append to a plain UTF-8 scalar without repeatedly copying its prefix. */
    public void appendGrowingString(String suffix) {
        if (growingString == null) {
            growingString = new StringBuilder((String) value);
        }
        growingString.append(suffix);
        notifyModifiedWatchers();
    }

    /**
     * Produce the temporary consumed by compound string assignment.  The
     * append buffer is transferred into the destination by {@link #set}, so
     * consecutive {@code .=} operations do not materialize the full prefix.
     */
    public RuntimeScalar appendedStringAssignmentResult(String suffix, int resultType,
                                                         RuntimeScalar right) {
        RuntimeScalar result = new RuntimeScalar();
        result.type = resultType;
        result.value = value;
        result.utf8UncheckedOctets = utf8UncheckedOctets;
        result.tainted = tainted || right.isTainted();
        result.numericLiteralText = null;
        result.numericContextSeen = false;
        result.firstClassRegexScalar = false;
        result.formatPictureTainted = formatPictureTainted || right.formatPictureTainted;
        if (result.formatPictureTainted) result.tainted = true;
        result.growingString = growingString == null
                ? new StringBuilder((String) value) : growingString;
        result.growingString.append(suffix);
        result.transferableGrowingString = true;
        growingString = null;
        return result;
    }

    private String materializeGrowingString() {
        if (growingString == null) {
            return (String) value;
        }
        String result = growingString.toString();
        value = result;
        growingString = null;
        return result;
    }

    public String toStringRef() {
        if (value instanceof RuntimeBase referent) {
            BObjectRegistry.register(referent);
            PerlRuntime runtime = PerlRuntime.currentOrNull();
            if (runtime != null) {
                runtime.registerReferenceAddress(referent);
            }
        }
        String ref = switch (type) {
            case UNDEF -> "SCALAR(0x" + scalarUndef.hashCode() + ")";
            case CODE -> {
                if (value == null) {
                    yield "CODE(0x" + scalarUndef.hashCode() + ")";
                }
                yield ((RuntimeCode) value).toStringRef();
            }
            case GLOB -> {
                if (value == null) {
                    yield "GLOB(0x" + scalarUndef.hashCode() + ")";
                }
                yield ((RuntimeGlob) value).toStringRef();
            }
            case VSTRING -> "VSTRING(0x" + value.hashCode() + ")";
            case ARRAYREFERENCE -> {
                if (value == null) {
                    yield "ARRAY(0x" + scalarUndef.hashCode() + ")";
                }
                yield ((RuntimeArray) value).toStringRef();
            }
            case HASHREFERENCE -> {
                if (value == null) {
                    yield "HASH(0x" + scalarUndef.hashCode() + ")";
                }
                yield ((RuntimeHash) value).toStringRef();
            }
            case GLOBREFERENCE -> {
                if (value == null) {
                    yield "GLOB(0x" + scalarUndef.hashCode() + ")";
                }
                yield ((RuntimeBase) value).toStringRef();
            }
            case REGEX -> {
                if (value == null) {
                    yield "Regexp=REGEXP(0x" + scalarUndef.hashCode() + ")";
                }
                RuntimeRegex regex = (RuntimeRegex) value;
                int regexBlessId = blessId != 0 ? blessId : regex.blessId;
                yield regex.isScalarized()
                        ? (regexBlessId == 0 ? "Regexp="
                                : NameNormalizer.getBlessStr(regexBlessId) + "=")
                                + regex.toStringRef()
                        : "Regexp=" + regex.toStringRef();
            }
            case REFERENCE -> {
                // Determine the proper type name for the reference
                // References to references show as "REF", references to plain scalars show as "SCALAR"
                String typeName = "SCALAR";
                int valueBlessId = value instanceof RuntimeBase base
                        ? base.blessId : 0;
                if (value instanceof RuntimeRegex regex && regex.isScalarized()) {
                    String refStr = "SCALAR(0x" + regex.referenceAddressHex() + ")";
                    int regexBlessId = valueBlessId != 0 ? valueBlessId
                            : blessId != 0 ? blessId : regex.blessId;
                    yield regexBlessId == 0 ? refStr
                            : NameNormalizer.getBlessStr(regexBlessId) + "=" + refStr;
                }
                if (value instanceof RuntimeScalar scalar) {
                    RuntimeScalar regexScalar = scalar;
                    int nestedBlessId = 0;
                    for (int depth = 0; depth < 8; depth++) {
                        if (regexScalar.blessId != 0) nestedBlessId = regexScalar.blessId;
                        if (regexScalar.type == REGEX
                                && regexScalar.value instanceof RuntimeRegex regex
                                && regex.isScalarized()) {
                            int regexBlessId = nestedBlessId != 0 ? nestedBlessId
                                    : valueBlessId != 0 ? valueBlessId
                                    : blessId != 0 ? blessId : regex.blessId;
                            String regexRef = "SCALAR(0x" + regex.referenceAddressHex() + ")";
                            yield regexBlessId == 0 ? regexRef
                                    : NameNormalizer.getBlessStr(regexBlessId) + "=" + regexRef;
                        }
                        if (regexScalar.type != REFERENCE
                                || !(regexScalar.value instanceof RuntimeScalar next)) break;
                        regexScalar = next;
                    }
                    if (scalar.type == REGEX && scalar.value instanceof RuntimeRegex regex
                            && regex.isScalarized()) {
                        int regexBlessId = valueBlessId != 0 ? valueBlessId
                                : blessId != 0 ? blessId : regex.blessId;
                        String regexRef = "SCALAR(0x" + regex.referenceAddressHex() + ")";
                        yield regexBlessId == 0 ? regexRef
                                : NameNormalizer.getBlessStr(regexBlessId) + "=" + regexRef;
                    }
                    if (scalar.firstClassRegexScalar) {
                        String regexRef = scalar.toString();
                        yield valueBlessId == 0 ? regexRef
                                : NameNormalizer.getBlessStr(valueBlessId) + "=" + regexRef;
                    }
                    if (scalar.isLvalueScalar()) {
                        String lvalueRef = "LVALUE(0x" + ((RuntimeBase) value).referenceAddressHex() + ")";
                        yield valueBlessId == 0
                                ? lvalueRef
                                : NameNormalizer.getBlessStr(valueBlessId) + "=" + lvalueRef;
                    }
                    typeName = switch (scalar.type) {
                        case VSTRING -> "VSTRING";
                        case REGEX, ARRAYREFERENCE, HASHREFERENCE, CODE, GLOBREFERENCE, REFERENCE -> "REF";
                        case GLOB -> "GLOB";
                        default -> "SCALAR";
                    };
                }
                String refStr = typeName + "(0x" + ((RuntimeBase) value).referenceAddressHex() + ")";
                // For REFERENCE type, the blessId is on the value (referent), not on the
                // reference itself. We handle it here; the outer blessId check is skipped
                // for REFERENCE type to avoid double-prepending the class name for circular
                // self-references like: $x = bless \$x, 'Foo'
                yield (valueBlessId == 0 ? refStr : NameNormalizer.getBlessStr(valueBlessId) + "=" + refStr);
            }
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).toStringRef();
            default -> "SCALAR(0x" + Integer.toHexString(value.hashCode()) + ")";
        };
        // Only apply outer blessId for non-REFERENCE types.
        // REFERENCE type already handles blessing through valueBlessId above.
        if (type == REFERENCE) {
            int effectiveBlessId = RuntimeScalarType.blessedId(this);
            if (effectiveBlessId == 0) return ref;
            String className = NameNormalizer.getBlessStr(effectiveBlessId);
            return ref.startsWith(className + "=") ? ref : className + "=" + ref;
        }
        return (blessId == 0 ? ref : NameNormalizer.getBlessStr(blessId) + "=" + ref);
    }

    public int getIntRef() {
        return this.hashCode();
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    public boolean getBooleanRef() {
        return true;
    }

    // Method to implement `$v->{key}`
    public RuntimeScalar hashDerefGet(RuntimeScalar index) {
        return this.hashDeref().get(index);
    }

    /**
     * Fetch a literal key after the generated method's argument guard has
     * proved that this scalar points to an ordinary plain hash without a
     * {@code %\{\}} overload. Keep the defensive type check here so an
     * incorrect compiler guard falls back to the full Perl dereference path.
     */
    public static RuntimeScalar hashDerefGetForVerifiedPlainHashMethod(
            RuntimeScalar target, RuntimeScalar index) {
        if (target.type == HASHREFERENCE
                && target.value != null
                && target.value.getClass() == RuntimeHash.class
                && ((RuntimeHash) target.value).type == RuntimeHash.PLAIN_HASH) {
            return ((RuntimeHash) target.value).get(index);
        }
        return target.hashDerefGet(index);
    }

    /** Fetch an existing literal field cell for a compiler-proven guarded method body. */
    public static RuntimeScalar getReadOnlyPlainHashMethodField(
            RuntimeScalar target, String key) {
        if (target != null && target.type == HASHREFERENCE
                && target.value != null
                && target.value.getClass() == RuntimeHash.class) {
            RuntimeHash hash = (RuntimeHash) target.value;
            if (hash.type == RuntimeHash.PLAIN_HASH
                    && !hash.threadShared && !hash.isEnvironmentHash) {
                return hash.elements.get(key);
            }
        }
        return null;
    }

    /** Fetch an existing literal array cell for a compiler-proven guarded method body. */
    public static RuntimeScalar getReadOnlyPlainArrayMethodField(
            RuntimeScalar target, int index) {
        if (target != null && target.type == ARRAYREFERENCE
                && target.value != null
                && target.value.getClass() == RuntimeArray.class) {
            RuntimeArray array = (RuntimeArray) target.value;
            if (array.type == RuntimeArray.PLAIN_ARRAY && !array.threadShared
                    && index >= 0 && index < array.elements.size()) {
                return array.elements.get(index);
            }
        }
        return null;
    }

    /** Whether a field cell can be reused without bypassing scalar magic. */
    public static boolean isCacheableReadOnlyPlainHashMethodField(RuntimeScalar value) {
        return value != null
                && value.canUpdateArithmeticIntegerInPlace()
                && value.type == INTEGER
                && !value.hasWatchers();
    }

    /** Fast literal lookup for an ASCII key proven safe by the generated method guard. */
    public static RuntimeScalar hashDerefGetForVerifiedPlainHashMethodAsciiKey(
            RuntimeScalar target, RuntimeScalar index, String asciiKey) {
        if (target.type == HASHREFERENCE
                && target.value != null
                && target.value.getClass() == RuntimeHash.class
                && ((RuntimeHash) target.value).type == RuntimeHash.PLAIN_HASH) {
            RuntimeHash hash = (RuntimeHash) target.value;
            if (!hash.threadShared && !hash.isEnvironmentHash) {
                RuntimeScalar value = hash.elements.get(asciiKey);
                if (value != null) return value;
            }
            return hash.get(asciiKey);
        }
        return target.hashDerefGet(index);
    }

    public static RuntimeScalar hashDerefGetForVerifiedPlainHashMethodNonStrict(
            RuntimeScalar target, RuntimeScalar index, String packageName) {
        if (target.type == HASHREFERENCE
                && target.value != null
                && target.value.getClass() == RuntimeHash.class
                && ((RuntimeHash) target.value).type == RuntimeHash.PLAIN_HASH) {
            return ((RuntimeHash) target.value).get(index);
        }
        return target.hashDerefGetNonStrict(index, packageName);
    }

    /** Non-strict fallback counterpart for a verified ASCII literal hash key. */
    public static RuntimeScalar hashDerefGetForVerifiedPlainHashMethodAsciiKeyNonStrict(
            RuntimeScalar target, RuntimeScalar index, String asciiKey, String packageName) {
        if (target.type == HASHREFERENCE
                && target.value != null
                && target.value.getClass() == RuntimeHash.class
                && ((RuntimeHash) target.value).type == RuntimeHash.PLAIN_HASH) {
            RuntimeHash hash = (RuntimeHash) target.value;
            if (!hash.threadShared && !hash.isEnvironmentHash) {
                RuntimeScalar value = hash.elements.get(asciiKey);
                if (value != null) return value;
            }
            return hash.get(asciiKey);
        }
        return target.hashDerefGetNonStrict(index, packageName);
    }

    // Method to implement `$v->{key}`, when "no strict refs" is in effect
    public RuntimeScalar hashDerefGetNonStrict(RuntimeScalar index, String packageName) {
        return this.hashDerefNonStrict(packageName).get(index);
    }

    // Method to implement `local $v->{key}` - returns a proxy that survives hash reassignment
    public RuntimeScalar hashDerefGetForLocal(RuntimeScalar index) {
        return this.hashDeref().getForLocal(index);
    }

    // Method to implement `local $v->{key}`, when "no strict refs" is in effect
    public RuntimeScalar hashDerefGetForLocalNonStrict(RuntimeScalar index, String packageName) {
        return this.hashDerefNonStrict(packageName).getForLocal(index);
    }

    // Method to implement `delete $v->{key}`
    public RuntimeScalar hashDerefDelete(RuntimeScalar index) {
        return this.hashDeref().delete(index);
    }

    public RuntimeScalar hashDerefDeleteInContext(RuntimeScalar index, int callContext) {
        return this.hashDeref().deleteInContext(index, callContext);
    }

    // Method to implement `delete $v->{key}`, when "no strict refs" is in effect
    public RuntimeScalar hashDerefDeleteNonStrict(RuntimeScalar index, String packageName) {
        return this.hashDerefNonStrict(packageName).delete(index);
    }

    public RuntimeScalar hashDerefDeleteInContextNonStrict(RuntimeScalar index, String packageName, int callContext) {
        return this.hashDerefNonStrict(packageName).deleteInContext(index, callContext);
    }

    // Method to implement `delete local $v->{key}`
    public RuntimeScalar hashDerefDeleteLocal(RuntimeScalar index) {
        return this.hashDeref().deleteLocal(index);
    }

    // Method to implement `delete local $v->{key}`, when "no strict refs" is in effect
    public RuntimeScalar hashDerefDeleteLocalNonStrict(RuntimeScalar index, String packageName) {
        return this.hashDerefNonStrict(packageName).deleteLocal(index);
    }

    // Method to implement `exists $v->{key}`
    public RuntimeScalar hashDerefExists(RuntimeScalar index) {
        return this.hashDeref().exists(index);
    }

    // Method to implement `exists $v->{key}`, when "no strict refs" is in effect
    public RuntimeScalar hashDerefExistsNonStrict(RuntimeScalar index, String packageName) {
        return this.hashDerefNonStrict(packageName).exists(index);
    }

    // Method to implement `$v->[10]`
    public RuntimeScalar arrayDerefGet(RuntimeScalar index) {
        return this.arrayDeref().get(index);
    }

    /** Read an array element using a simple integer index adjustment without
     * materializing the intermediate arithmetic result scalar. */
    public RuntimeScalar arrayDerefGetAdjusted(RuntimeScalar index, int adjustment,
                                               boolean subtract, boolean warnUninitialized,
                                               boolean integerMode) {
        if ((index.getClass() == RuntimeScalar.class
                    || index.getClass() == RuntimeScalarReadOnly.class)
                && index.type == INTEGER
                && !isWideIntegerValue(index.value)) {
            long nativeIndex = index.getLong();
            if (integerMode) {
                long adjusted = subtract ? nativeIndex - adjustment : nativeIndex + adjustment;
                return arrayDeref().get((int) adjusted);
            }
            try {
                long adjusted = subtract
                        ? Math.subtractExact(nativeIndex, adjustment)
                        : Math.addExact(nativeIndex, adjustment);
                return arrayDeref().get((int) adjusted);
            } catch (ArithmeticException ignored) {
                // Preserve Perl's overflow promotion through the normal operator path.
            }
        }
        RuntimeScalar adjustedIndex;
        if (integerMode) {
            RuntimeScalar offset = RuntimeScalarCache.getScalarInt(adjustment);
            adjustedIndex = subtract
                    ? warnUninitialized
                        ? MathOperators.integerSubtractWarn(index, offset)
                        : MathOperators.integerSubtract(index, offset)
                    : warnUninitialized
                        ? MathOperators.integerAddWarn(index, offset)
                        : MathOperators.integerAdd(index, offset);
        } else {
            adjustedIndex = subtract
                    ? warnUninitialized
                        ? MathOperators.subtractWarn(index, adjustment)
                        : MathOperators.subtract(index, adjustment)
                    : warnUninitialized
                        ? MathOperators.addWarn(index, adjustment)
                        : MathOperators.add(index, adjustment);
        }
        return arrayDeref().get(adjustedIndex);
    }

    /** Non-strict-ref variant of {@link #arrayDerefGetAdjusted}. */
    public RuntimeScalar arrayDerefGetAdjustedNonStrict(RuntimeScalar index, int adjustment,
                                                        boolean subtract, boolean warnUninitialized,
                                                        boolean integerMode,
                                                        String packageName) {
        if ((index.getClass() == RuntimeScalar.class
                    || index.getClass() == RuntimeScalarReadOnly.class)
                && index.type == INTEGER
                && !isWideIntegerValue(index.value)) {
            long nativeIndex = index.getLong();
            if (integerMode) {
                long adjusted = subtract ? nativeIndex - adjustment : nativeIndex + adjustment;
                return arrayDerefNonStrict(packageName).get((int) adjusted);
            }
            try {
                long adjusted = subtract
                        ? Math.subtractExact(nativeIndex, adjustment)
                        : Math.addExact(nativeIndex, adjustment);
                return arrayDerefNonStrict(packageName).get((int) adjusted);
            } catch (ArithmeticException ignored) {
                // Preserve Perl's overflow promotion through the normal operator path.
            }
        }
        RuntimeScalar adjustedIndex;
        if (integerMode) {
            RuntimeScalar offset = RuntimeScalarCache.getScalarInt(adjustment);
            adjustedIndex = subtract
                    ? warnUninitialized
                        ? MathOperators.integerSubtractWarn(index, offset)
                        : MathOperators.integerSubtract(index, offset)
                    : warnUninitialized
                        ? MathOperators.integerAddWarn(index, offset)
                        : MathOperators.integerAdd(index, offset);
        } else {
            adjustedIndex = subtract
                    ? warnUninitialized
                        ? MathOperators.subtractWarn(index, adjustment)
                        : MathOperators.subtract(index, adjustment)
                    : warnUninitialized
                        ? MathOperators.addWarn(index, adjustment)
                        : MathOperators.add(index, adjustment);
        }
        return arrayDerefNonStrict(packageName).get(adjustedIndex);
    }

    // Method to implement `$v->[10]`, when "no strict refs" is in effect
    public RuntimeScalar arrayDerefGetNonStrict(RuntimeScalar index, String packageName) {
        return this.arrayDerefNonStrict(packageName).get(index);
    }

    // Method to implement `$v->[10, 20]` (slice)
    public RuntimeList arrayDerefGetSlice(RuntimeList indices) {
        return this.arrayDeref().getSlice(indices);
    }

    // Method to implement `$v->[10, 20]` (slice), when "no strict refs" is in effect
    public RuntimeList arrayDerefGetSliceNonStrict(RuntimeList indices, String packageName) {
        return this.arrayDerefNonStrict(packageName).getSlice(indices);
    }

    // Method to implement `delete $v->[10]`
    public RuntimeScalar arrayDerefDelete(RuntimeScalar index) {
        return this.arrayDeref().delete(index);
    }

    // Method to implement `delete $v->[10]`, when "no strict refs" is in effect
    public RuntimeScalar arrayDerefDeleteNonStrict(RuntimeScalar index, String packageName) {
        return this.arrayDerefNonStrict(packageName).delete(index);
    }

    // Method to implement `delete local $v->[10]`
    public RuntimeScalar arrayDerefDeleteLocal(RuntimeScalar index) {
        return this.arrayDeref().deleteLocal(index);
    }

    // Method to implement `delete local $v->[10]`, when "no strict refs" is in effect
    public RuntimeScalar arrayDerefDeleteLocalNonStrict(RuntimeScalar index, String packageName) {
        return this.arrayDerefNonStrict(packageName).deleteLocal(index);
    }

    // Method to implement `exists $v->[10]`
    public RuntimeScalar arrayDerefExists(RuntimeScalar index) {
        return this.arrayDeref().exists(index);
    }

    // Method to implement `exists $v->[10]`, when "no strict refs" is in effect
    public RuntimeScalar arrayDerefExistsNonStrict(RuntimeScalar index, String packageName) {
        return this.arrayDerefNonStrict(packageName).exists(index);
    }

    // Method to implement `@$v`
    public RuntimeArray arrayDeref() {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(@{}", unaryDerefOverloadArgs());
                // If the subroutine returns the object itself then it will not be called again
                if (overloadReturnedDifferentObject(result)) {
                    return result.arrayDeref();
                }
            }
        }

        // ARRAYREFERENCE is first as the most common case
        if (type == ARRAYREFERENCE) {
            return (RuntimeArray) value;
        }

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> // 0
                // Under strict refs, dereferencing a non-readonly numeric scalar as an ARRAY ref
                // is a strict-refs violation. (Read-only constants like `1->[0]` take the
                // RuntimeScalarReadOnly.arrayDerefGet() override instead and stay quiet.)
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as an ARRAY ref while \"strict refs\" in use");
            case DOUBLE -> // 1
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as an ARRAY ref while \"strict refs\" in use");
            case STRING -> // 2
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as an ARRAY ref while \"strict refs\" in use");
            case BYTE_STRING -> // 3
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as an ARRAY ref while \"strict refs\" in use");
            case UNDEF -> { // 4
                // Don't autovivify read-only scalars (like constants)
                // This matches Perl's behavior where 1->[0] returns undef without error
                if (this instanceof RuntimeScalarReadOnly) {
                    yield new RuntimeArray();
                }
                RuntimeArray arr = AutovivificationArray.createAutovivifiedArray(this);
                arr.strictAutovivify = true;
                yield arr;
            }
            case VSTRING -> // 5
                    throw new PerlCompilerException("Not an ARRAY reference");
            case BOOLEAN -> // 6
                    throw new PerlCompilerException("Not an ARRAY reference");
            case GLOB -> { // 7
                // When dereferencing a typeglob as an array, return the array slot.
                // PVIO (e.g. *STDOUT{IO}) is also represented with type GLOB but holds a RuntimeIO.
                if (value instanceof RuntimeIO) {
                    throw new PerlCompilerException("Not an ARRAY reference");
                }
                RuntimeGlob glob = (RuntimeGlob) value;
                // For anonymous globs, use the getGlobArray method which handles local slots
                yield glob.getGlobArray();
            }
            case JAVAOBJECT -> // 8
                    throw new PerlCompilerException("Not an ARRAY reference");
            case TIED_SCALAR -> { // 9
                RuntimeScalar fetched = tiedFetch();
                if (fetched.type == RuntimeScalarType.UNDEF) {
                    // Autovivify: create an array ref, store it, then use the
                    // handler's canonical value. STORE may have copied/replaced it.
                    RuntimeArray arr = new RuntimeArray();
                    arr.strictAutovivify = true;
                    tiedStore(arr.createAnonymousReference());
                    yield tiedFetch().arrayDeref();
                }
                yield fetched.arrayDeref();
            }
            case DUALVAR -> // 10
                    throw new PerlCompilerException("Not an ARRAY reference");
            case FORMAT -> // 11
                    throw new PerlCompilerException("Not an ARRAY reference");
            case READONLY_SCALAR -> // 12
                    ((RuntimeScalar) this.value).arrayDeref();
            default -> throw new PerlCompilerException("Not an ARRAY reference");
        };
    }

    /**
     * Dereferences this scalar as a hash reference, bypassing any Perl-level
     * {@code %{}} overload. This is used internally by Java-backed code (e.g.
     * XML::LibXML's {@code getNode()}) that needs to access the raw object hash
     * even when the class has a {@code %{}} overload installed.
     */
    public RuntimeHash hashDerefRaw() {
        if (type == HASHREFERENCE) {
            return (RuntimeHash) value;
        }
        // Fall back to the normal path for non-HASHREFERENCE types
        // (autovivification, string refs under no-strict, etc.)
        return hashDeref();
    }

    /**
     * Dereferences this scalar as a hash reference using the `%$v` operator.
     *
     * <p>This method implements Perl's hash dereference operator `%$v`, which treats
     * the scalar as a hash reference and returns the underlying hash. If the scalar
     * is undefined, it performs autovivification by creating a new hash and converting
     * this scalar into a reference to that hash.
     *
     * <p><b>Autovivification:</b> When dereferencing an undefined scalar as a hash,
     * Perl automatically creates a new hash and makes the scalar a reference to it.
     * This is implemented using a {@link AutovivificationHash} that intercepts the first write
     * operation to trigger the conversion.
     *
     * <p><b>Overloading:</b> If the scalar is a blessed object, this method first
     * checks for overloaded hash dereference behavior using the `(%{}` overload key.
     *
     * @return The dereferenced RuntimeHash object
     * @throws PerlCompilerException if the scalar contains a string (when strict refs
     *                               is in use) or any other non-hash-reference value
     */
    public RuntimeHash hashDeref() {
        // Check if object is eligible for overloading (blessed objects)
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try to call the overloaded hash dereference method `(%{}`
                RuntimeScalar result = ctx.tryOverload("(%{}", unaryDerefOverloadArgs());
                // If the overload method returns a different object (not self),
                // recursively dereference the returned value
                // This prevents infinite recursion when the overload returns the same object
                if (overloadReturnedDifferentObject(result)) {
                    return result.hashDeref();
                }
            }
        }

        // HASHREFERENCE is first as the most common case
        if (type == HASHREFERENCE) {
            return (RuntimeHash) value;
        }

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> // 0
                // Under strict refs, dereferencing a non-readonly numeric scalar as a HASH ref
                // is a strict-refs violation. (Read-only constants like `1->{a}` take the
                // RuntimeScalarReadOnly.hashDerefGet() override instead.)
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref while \"strict refs\" in use");
            case DOUBLE -> // 1
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref while \"strict refs\" in use");
            case STRING -> // 2
                // Strict refs violation: attempting to use a string as a hash ref
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref while \"strict refs\" in use");
            case BYTE_STRING -> // 3
                // Strict refs violation: attempting to use a string as a hash ref
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a HASH ref while \"strict refs\" in use");
            case UNDEF -> // 4
                    AutovivificationHash.createAutovivifiedHash(this);
            case VSTRING -> // 5
                    throw new PerlCompilerException("Not a HASH reference");
            case BOOLEAN -> // 6
                    throw new PerlCompilerException("Not a HASH reference");
            case GLOB -> { // 7
                // When dereferencing a typeglob as a hash, return the hash slot.
                // PVIO (e.g. *STDOUT{IO}) is also represented with type GLOB but holds a RuntimeIO.
                if (value instanceof RuntimeIO) {
                    throw new PerlCompilerException("Not a HASH reference");
                }
                // A glob's scalar slot can itself carry the hash payload while
                // Perl code traverses a glob reference with ${*{$glob}}{key}.
                // In that representation the value is already the hash; it is
                // not a RuntimeGlob wrapper.
                if (value instanceof RuntimeHash hash) {
                    yield hash;
                }
                if (!(value instanceof RuntimeGlob glob)) {
                    throw new PerlCompilerException("Not a HASH reference");
                }
                // For anonymous globs, use the getGlobHash method which handles local slots
                yield glob.getGlobHash();
            }
            case JAVAOBJECT -> // 8
                    throw new PerlCompilerException("Not a HASH reference");
            case TIED_SCALAR -> { // 9
                RuntimeScalar fetched = tiedFetch();
                if (fetched.type == RuntimeScalarType.UNDEF) {
                    // Autovivify: use the handler's canonical post-STORE value.
                    RuntimeHash hash = new RuntimeHash();
                    tiedStore(hash.createAnonymousReference());
                    yield tiedFetch().hashDeref();
                }
                yield fetched.hashDeref();
            }
            case FORMAT -> // 11
                    throw new PerlCompilerException("Not a HASH reference");
            case READONLY_SCALAR -> // 12
                    ((RuntimeScalar) this.value).hashDeref();
            default -> throw new PerlCompilerException("Not a HASH reference");
        };
    }

    // Method to implement `$$v`
    public RuntimeScalar scalarDeref() {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(${}", unaryDerefOverloadArgs());
                // If the subroutine returns the object itself then it will not be called again
                if (overloadReturnedDifferentObject(result)) {
                    return result.scalarDeref();
                }
            }
        }

        return switch (type) {
            case UNDEF -> {
                // Autovivify: create a new scalar reference for undefined values
                RuntimeScalar newScalar = new RuntimeScalar();
                this.value = newScalar;
                this.type = RuntimeScalarType.REFERENCE;
                refreshArgumentAliasOwnerSlots();
                this.numericLiteralText = null;
                this.numericContextSeen = false;
                this.firstClassRegexScalar = false;
                this.formatPictureTainted = false;
                yield newScalar;
            }
            case REFERENCE -> {
                yield (RuntimeScalar) value;
            }
            case REGEX -> dereferencedRegexScalar();
            case GLOB -> {
                // Dereferencing a glob as scalar returns the scalar slot
                // e.g., ${*Foo::VERSION} or ${$glob} where $glob is a glob
                if (value instanceof RuntimeGlob glob) {
                    yield glob.getGlobScalarSlot();
                }
                throw new PerlCompilerException("Not a SCALAR reference");
            }
            case STRING, BYTE_STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a SCALAR ref while \"strict refs\" in use");
            case TIED_SCALAR -> tiedFetch().scalarDeref();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).scalarDeref();
            case GLOBREFERENCE -> {
                // Dereferencing a glob reference (e.g., $${\*Foo::bar}) returns the glob itself
                // This is Perl semantics: $$glob_ref returns the glob, not the scalar slot
                if (value instanceof RuntimeGlob glob) {
                    RuntimeScalar result = new RuntimeScalar();
                    result.type = RuntimeScalarType.GLOB;
                    result.value = glob;
                    yield result;
                }
                // For IO handles stored as GLOBREFERENCE (like *STDOUT{IO})
                throw new PerlCompilerException("Not a SCALAR reference");
            }
            default -> throw new PerlCompilerException("Not a SCALAR reference");
        };
    }

    /**
     * Dereference a foreach declared-reference iterator value without the
     * ordinary dereference operators' autovivification semantics.
     */
    public RuntimeScalar foreachScalarReference() {
        return refAliasScalarReference();
    }

    /**
     * Validate and unwrap a scalar reference used as a ref-aliasing target.
     * Unlike an ordinary scalar dereference, this neither autovivifies an
     * undef value nor turns a glob reference into a glob value: ref aliasing
     * installs the glob's scalar slot.
     */
    public RuntimeScalar refAliasScalarReference() {
        // Ref-aliasing accepts a glob reference for a scalar target. The
        // target becomes a scalar holding that glob (\$x = \*glob), rather
        // than an alias of the glob's existing SCALAR slot.
        if (type == GLOBREFERENCE && value instanceof RuntimeGlob glob) {
            // LOAD_GLOB deliberately returns a detached copy for local
            // filehandle lifetimes. A named typeglob alias, however, must
            // retain the canonical stash identity observed by *name{GLOB}.
            return glob.globName == null ? glob : GlobalVariable.getGlobalIO(glob.globName);
        }
        if (type == GLOB) {
            return scalarDeref();
        }
        requireForeachReference(REFERENCE, "SCALAR");
        RuntimeScalar referent = (RuntimeScalar) value;
        // Keep the one-shot declaration permit only for a cell materialized
        // before its lexical declaration. Ordinary refaliases to an already
        // declared lexical must not let a later same-named declaration reuse
        // and overwrite the escaped cell.
        if (referent.materializedBeforeLexicalDeclaration) {
            referent.retainMaterializedForwardReference = true;
        }
        return referent;
    }

    public RuntimeArray foreachArrayReference() {
        requireForeachReference(ARRAYREFERENCE, "ARRAY");
        return (RuntimeArray) value;
    }

    public RuntimeHash foreachHashReference() {
        requireForeachReference(HASHREFERENCE, "HASH");
        return (RuntimeHash) value;
    }

    /** Validate a CODE reference before installing it through ref aliasing. */
    public RuntimeScalar refAliasCodeReference() {
        requireForeachReference(CODE, "CODE");
        return this;
    }

    private void requireForeachReference(int expectedType, String expectedName) {
        if (!RuntimeScalarType.isReference(this)) {
            throw new PerlCompilerException("Assigned value is not a reference");
        }
        if (type != expectedType) {
            String article = expectedName.equals("ARRAY") ? "an" : "a";
            throw new PerlCompilerException("Assigned value is not " + article + " "
                    + expectedName + " reference");
        }
    }

    // Method to implement `$$v`, when "no strict refs" is in effect
    public RuntimeScalar scalarDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(${}", unaryDerefOverloadArgs());
                // If the subroutine returns the object itself then it will not be called again
                if (overloadReturnedDifferentObject(result)) {
                    return result.scalarDerefNonStrict(packageName);
                }
            }
        }

        return switch (type) {
            case REFERENCE -> {
                yield (RuntimeScalar) value;
            }
            case REGEX -> dereferencedRegexScalar();
            case GLOB -> {
                // Dereferencing a glob as scalar returns the scalar slot
                if (value instanceof RuntimeGlob glob) {
                    yield glob.getGlobScalarSlot();
                }
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalVariable(varName);
            }
            case GLOBREFERENCE -> {
                // Dereferencing a glob reference (e.g., $${\*Foo::bar}) returns the glob itself
                // This is Perl semantics: $$glob_ref returns the glob, not the scalar slot
                if (value instanceof RuntimeGlob glob) {
                    RuntimeScalar result = new RuntimeScalar();
                    result.type = RuntimeScalarType.GLOB;
                    result.value = glob;
                    yield result;
                }
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalVariable(varName);
            }
            case TIED_SCALAR -> tiedFetch().scalarDerefNonStrict(packageName);
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).scalarDerefNonStrict(packageName);
            default -> {
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalVariable(varName);
            }
        };
    }

    private RuntimeScalar dereferencedRegexScalar() {
        RuntimeRegex regex = (RuntimeRegex) this.value;
        int regexBlessId = this.blessId != 0 ? this.blessId
                : blessedId(this) != 0 ? blessedId(this) : regex.blessId;
        if (regexBlessId != 0) regex.setBlessId(regexBlessId);
        return new RegexScalarLvalue(regex, regexBlessId).propagateTaint(this);
    }

    /** Mutable scalar PV view used by Perl's dereference of an SVt_REGEXP. */
    private static final class RegexScalarLvalue extends RuntimeScalar {
        private final RuntimeRegex regex;

        private RegexScalarLvalue(RuntimeRegex regex, int blessId) {
            super(regex.scalarValueString());
            this.regex = regex;
            this.blessId = blessId;
            if (blessId != 0) regex.setBlessId(blessId);
            this.firstClassRegexScalar = true;
            this.firstClassRegexValue = regex;
            this.firstClassRegexReferent = new WeakReference<>(regex);
        }

        @Override
        public RuntimeRegex firstClassRegexValue() {
            return regex;
        }

        private RuntimeScalar store(RuntimeScalar value) {
            int savedBlessId = blessId;
            RuntimeScalar result = super.set(value);
            blessId = savedBlessId;
            regex.setScalarValue(super.toStringNoOverload());
            firstClassRegexScalar = true;
            return result;
        }

        @Override public RuntimeScalar set(RuntimeScalar value) { return store(value); }
        @Override public RuntimeScalar set(String value) {
            int savedBlessId = blessId;
            RuntimeScalar result = super.set(value);
            blessId = savedBlessId;
            regex.setScalarValue(super.toStringNoOverload());
            firstClassRegexScalar = true;
            return result;
        }
        @Override public RuntimeScalar set(int value) {
            int savedBlessId = blessId;
            RuntimeScalar result = super.set(value);
            blessId = savedBlessId;
            regex.setScalarValue(super.toStringNoOverload());
            firstClassRegexScalar = true;
            return result;
        }
        @Override public RuntimeScalar set(long value) {
            int savedBlessId = blessId;
            RuntimeScalar result = super.set(value);
            blessId = savedBlessId;
            regex.setScalarValue(super.toStringNoOverload());
            firstClassRegexScalar = true;
            return result;
        }
        @Override public RuntimeScalar set(BigInteger value) {
            int savedBlessId = blessId;
            RuntimeScalar result = super.set(value);
            blessId = savedBlessId;
            regex.setScalarValue(super.toStringNoOverload());
            firstClassRegexScalar = true;
            return result;
        }
        @Override public RuntimeScalar set(boolean value) {
            int savedBlessId = blessId;
            RuntimeScalar result = super.set(value);
            blessId = savedBlessId;
            regex.setScalarValue(super.toStringNoOverload());
            firstClassRegexScalar = true;
            return result;
        }
    }

    // Method to implement `%$v`, when "no strict refs" is in effect
    public RuntimeHash hashDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(%{}", unaryDerefOverloadArgs());
                // If the subroutine returns the object itself then it will not be called again
                if (overloadReturnedDifferentObject(result)) {
                    return result.hashDerefNonStrict(packageName);
                }
            }
        }

        // HASHREFERENCE is first as the most common case
        if (type == HASHREFERENCE) {
            return (RuntimeHash) value;
        }

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER, DOUBLE, BOOLEAN, DUALVAR -> { // 0, 1, 6, 10
                // Symbolic reference: convert number to string and treat as variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalHash(varName);
            }
            case STRING -> { // 2
                // Symbolic reference: treat the scalar's string value as a variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalHash(varName);
            }
            case BYTE_STRING -> { // 3
                // Symbolic reference: treat the scalar's string value as a variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalHash(varName);
            }
            case UNDEF -> { // 4
                // Don't autovivify read-only scalars (like constants)
                if (this instanceof RuntimeScalarReadOnly) {
                    yield new RuntimeHash();
                }
                yield AutovivificationHash.createAutovivifiedHash(this);
            }
            case VSTRING -> // 5
                    throw new PerlCompilerException("Not a HASH reference");
            case GLOB -> { // 7
                // When dereferencing a typeglob as a hash, return the hash slot
                RuntimeGlob glob = (RuntimeGlob) value;
                // For anonymous globs, use the getGlobHash method which handles local slots
                yield glob.getGlobHash();
            }
            case JAVAOBJECT -> // 8
                    throw new PerlCompilerException("Not a HASH reference");
            case TIED_SCALAR -> { // 9
                RuntimeScalar fetched = tiedFetch();
                if (fetched.type == RuntimeScalarType.UNDEF) {
                    RuntimeHash hash = new RuntimeHash();
                    tiedStore(hash.createAnonymousReference());
                    yield tiedFetch().hashDerefNonStrict(packageName);
                }
                yield fetched.hashDerefNonStrict(packageName);
            }
            case FORMAT -> // 11
                    throw new PerlCompilerException("Not a HASH reference");
            case READONLY_SCALAR -> // 12
                    ((RuntimeScalar) this.value).hashDerefNonStrict(packageName);
            default -> throw new PerlCompilerException("Not a HASH reference");
        };
    }

    // Method to implement `@$v`, when "no strict refs" is in effect
    public RuntimeArray arrayDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(@{}", unaryDerefOverloadArgs());
                // If the subroutine returns the object itself then it will not be called again
                if (overloadReturnedDifferentObject(result)) {
                    return result.arrayDerefNonStrict(packageName);
                }
            }
        }

        // ARRAYREFERENCE is first as the most common case
        if (type == ARRAYREFERENCE) {
            return (RuntimeArray) value;
        }

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER, DOUBLE, BOOLEAN, DUALVAR -> { // 0, 1, 6, 10
                // Symbolic reference: convert number to string and treat as variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalArray(varName);
            }
            case STRING -> { // 2
                // Symbolic reference: treat the scalar's string value as a variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalArray(varName);
            }
            case BYTE_STRING -> { // 3
                // Symbolic reference: treat the scalar's string value as a variable name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                yield GlobalVariable.getGlobalArray(varName);
            }
            case UNDEF -> { // 4
                // Don't autovivify read-only scalars (like constants)
                // This matches Perl's behavior where 1->[0] returns undef without error
                if (this instanceof RuntimeScalarReadOnly) {
                    yield new RuntimeArray();
                }
                yield AutovivificationArray.createAutovivifiedArray(this);
            }
            case VSTRING -> // 5
                    throw new PerlCompilerException("Not an ARRAY reference");
            case GLOB -> { // 7
                // When dereferencing a typeglob as an array, return the array slot
                RuntimeGlob glob = (RuntimeGlob) value;
                // For anonymous globs, use the getGlobArray method which handles local slots
                yield glob.getGlobArray();
            }
            case JAVAOBJECT -> // 8
                    throw new PerlCompilerException("Not an ARRAY reference");
            case TIED_SCALAR -> { // 9
                RuntimeScalar fetched = tiedFetch();
                if (fetched.type == RuntimeScalarType.UNDEF) {
                    RuntimeArray arr = new RuntimeArray();
                    arr.strictAutovivify = true;
                    tiedStore(arr.createAnonymousReference());
                    yield tiedFetch().arrayDerefNonStrict(packageName);
                }
                yield fetched.arrayDerefNonStrict(packageName);
            }
            case READONLY_SCALAR -> // 12
                    ((RuntimeScalar) this.value).arrayDerefNonStrict(packageName);
            default -> throw new PerlCompilerException("Not an ARRAY reference");
        };
    }

    // Method to implement `*$v`
    public RuntimeGlob globDeref() {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(*{}", unaryDerefOverloadArgs());
                // If the subroutine returns the object itself then it will not be called again
                if (overloadReturnedDifferentObject(result)) {
                    return result.globDeref();
                }
            }
        }

        return switch (type) {
            case TIED_SCALAR -> tiedFetch().globDeref();
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).globDeref();
            case REFERENCE -> {
                // `\do { local *FH }` is represented as a generic scalar
                // reference whose referent is a RuntimeGlob.  It is still a
                // single Perl glob reference and remains dereferenceable after
                // blessing (the IO::Scalar construction idiom).  Do not unwrap
                // GLOBREFERENCE here: that is a true reference-to-reference.
                if (value instanceof RuntimeScalar scalar && scalar.type == GLOB) {
                    yield scalar.globDeref();
                }
                throw new PerlCompilerException("Not a GLOB reference");
            }
            case UNDEF -> throw new PerlCompilerException("Can't use an undefined value as a GLOB reference");
            case GLOBREFERENCE -> {
                // Some internal representations store PVIO as GLOBREFERENCE with a RuntimeIO value.
                if (value instanceof RuntimeIO io) {
                    RuntimeGlob owner = io.getOwnerGlob();
                    if (owner != null) {
                        yield owner;
                    }
                    if (io.globName != null) {
                        RuntimeGlob actual = GlobalVariable.getExistingGlobalIO(io.globName);
                        if (actual != null) {
                            yield actual;
                        }
                    }
                    RuntimeGlob tmp = new RuntimeGlob("__ANON__::__ANONIO__");
                    tmp.setIO(io);
                    yield tmp;
                }
                yield (RuntimeGlob) value;
            }
            case GLOB -> {
                // PVIO (like *STDOUT{IO}) is stored as type GLOB with a RuntimeIO value.
                // Perl allows postfix glob deref (->**) of PVIO by creating a temporary glob
                // with the IO slot set to that handle.
                if (value instanceof RuntimeIO io) {
                    RuntimeGlob owner = io.getOwnerGlob();
                    if (owner != null) {
                        yield owner;
                    }
                    if (io.globName != null) {
                        RuntimeGlob actual = GlobalVariable.getExistingGlobalIO(io.globName);
                        if (actual != null) {
                            yield actual;
                        }
                    }
                    RuntimeGlob tmp = new RuntimeGlob("__ANON__::__ANONIO__");
                    tmp.setIO(io);
                    yield tmp;
                }
                // When glob-dereferencing a stash entry, return a plain RuntimeGlob.
                // This prevents *{$stash->{name}} = \$scalar from creating PCS constant subs.
                // PCS (Proxy Constant Subroutine) creation should only happen via direct
                // stash hash assignment ($stash->{name} = \$scalar), handled by RuntimeStashEntry.set().
                if (value instanceof RuntimeStashEntry stashEntry) {
                    yield GlobalVariable.getGlobalIO(stashEntry.globName);
                }
                yield (RuntimeGlob) value;
            }
            case STRING, BYTE_STRING ->
                    throw new PerlCompilerException("Can't use string (\"" + this + "\") as a symbol ref while \"strict refs\" in use");
            default -> throw new PerlCompilerException("Not a GLOB reference");
        };
    }

    // Method to implement `*$v`, when "no strict refs" is in effect
    public RuntimeGlob globDerefNonStrict(String packageName) {
        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId < 0) {
            // Prepare overload context and check if object is eligible for overloading
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try overload method
                RuntimeScalar result = ctx.tryOverload("(*{}", unaryDerefOverloadArgs());
                // If the subroutine returns the object itself then it will not be called again
                if (overloadReturnedDifferentObject(result)) {
                    return result.globDerefNonStrict(packageName);
                }
            }
        }

        return switch (type) {
            case TIED_SCALAR -> tiedFetch().globDerefNonStrict(packageName);
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).globDerefNonStrict(packageName);
            case UNDEF -> throw new PerlCompilerException(
                    "Can't use an undefined value as a symbol reference");
            case REFERENCE -> {
                if (value instanceof RuntimeScalar scalar && scalar.type == GLOB) {
                    yield scalar.globDerefNonStrict(packageName);
                }
                throw new PerlCompilerException("Not a GLOB reference");
            }
            case GLOBREFERENCE -> {
                // Some internal representations store PVIO as GLOBREFERENCE with a RuntimeIO value.
                if (value instanceof RuntimeIO io) {
                    RuntimeGlob owner = io.getOwnerGlob();
                    if (owner != null) {
                        yield owner;
                    }
                    RuntimeGlob tmp = new RuntimeGlob("__ANON__::__ANONIO__");
                    tmp.setIO(io);
                    yield tmp;
                }
                yield (RuntimeGlob) value;
            }
            case GLOB -> {
                // PVIO (like *STDOUT{IO}) is stored as type GLOB with a RuntimeIO value.
                // Perl allows postfix glob deref (->**) of PVIO by creating a temporary glob
                // with the IO slot set to that handle.
                if (value instanceof RuntimeIO io) {
                    RuntimeGlob owner = io.getOwnerGlob();
                    if (owner != null) {
                        yield owner;
                    }
                    RuntimeGlob tmp = new RuntimeGlob("__ANON__::__ANONIO__");
                    tmp.setIO(io);
                    yield tmp;
                }
                // When glob-dereferencing a stash entry, return a plain RuntimeGlob.
                // This prevents *{$stash->{name}} = \$scalar from creating PCS constant subs.
                if (value instanceof RuntimeStashEntry stashEntry) {
                    yield GlobalVariable.getGlobalIO(stashEntry.globName);
                }
                yield (RuntimeGlob) value;
            }
            // `no strict 'refs'` permits symbolic names, not references of
            // the wrong kind.  In particular, an earlier array/hash
            // autovivification must not be stringified as a glob name.
            case REGEX, CODE, ARRAYREFERENCE, HASHREFERENCE ->
                    throw new PerlCompilerException("Not a GLOB reference");
            default -> {
                String symbolName = this.toString();
                // A symbolic glob lookup of `::` names the main stash,
                // regardless of the package where the lookup runs.
                String varName = symbolName.equals("::")
                        ? "main::"
                        : NameNormalizer.normalizeVariableName(symbolName, packageName);
                // Use the canonical glob object for this symbol name.
                // This ensures the IO slot is shared/visible across operations like:
                //   *{"\3"} = *DATA; readline v3
                // where readline resolves the handle via GlobalVariable.getGlobalIO("main::\x03").
                yield GlobalVariable.getGlobalIO(varName);
            }
        };
    }

    // Method to implement `&$v`, when "no strict refs" is in effect
    // This looks up the CODE slot from a glob when the scalar contains a string
    public RuntimeScalar codeDerefNonStrict(String packageName) {
        // DB::sub displays a symbolic name in $DB::sub, but its `goto
        // &$DB::sub` must retain the original callable even when that name
        // has been removed from the stash.
        RuntimeScalar debuggerTarget = org.perlonjava.runtime.debugger.DebugHooks.debuggerTargetCode(this);
        if (debuggerTarget != null) {
            return debuggerTarget;
        }
        // CODE values are already references, including blessed CODE values.
        // Do this before generic blessing/overload handling, matching
        // RuntimeCode.createCodeReference().
        if (type == CODE) {
            return this;
        }

        // Check if object is eligible for overloading
        int blessId = blessedId(this);
        if (blessId != 0) {
            if (blessId < 0) {
                // Prepare overload context and check if object is eligible for overloading
                OverloadContext ctx = OverloadContext.prepare(blessId);
                if (ctx != null) {
                    // Try overload method
                    RuntimeScalar result = ctx.tryOverload("(&{}", unaryDerefOverloadArgs());
                    // If the subroutine returns the object itself then it will not be called again
                    if (overloadReturnedDifferentObject(result)) {
                        return result.codeDerefNonStrict(packageName);
                    }
                }
            }
            throw new PerlCompilerException("Not a subroutine reference");
        }

        return switch (type) {
            case TIED_SCALAR -> tiedFetch().codeDerefNonStrict(packageName);
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).codeDerefNonStrict(packageName);
            case UNDEF -> lexicalSubName != null
                    ? RuntimeCode.createCodeReference(this, packageName)
                    : this; // Preserve ordinary undef error behavior.
            case REFERENCE -> {
                // Dereference and check if it's a CODE reference
                RuntimeScalar deref = (RuntimeScalar) this.value;
                if (deref.type == RuntimeScalarType.CODE) {
                    yield deref;
                }
                throw new PerlCompilerException("Not a subroutine reference");
            }
            case ARRAYREFERENCE, HASHREFERENCE, REGEX ->
                    throw new PerlCompilerException("Not a subroutine reference");
            case FORMAT -> throw new PerlCompilerException("Not a CODE reference");
            case GLOB, GLOBREFERENCE -> {
                // Get the CODE slot from the glob
                if (!(value instanceof RuntimeGlob glob)) {
                    throw new PerlCompilerException("Not a CODE reference");
                }
                // A typeglob copied into a scalar retains the CODE slot that
                // was visible at copy time, even after `undef *name` removes
                // the named stash entry.
                RuntimeScalar savedCode = glob.getSavedCodeSlot();
                if (savedCode != null && savedCode.type == CODE) {
                    yield savedCode;
                }
                // For detached globs (null globName, from stash delete), use local code slot
                if (glob.globName == null && glob.codeSlot != null
                        && glob.codeSlot.type == CODE) {
                    yield glob.codeSlot;
                }
                RuntimeScalar code = glob.globName == null
                        ? null : GlobalVariable.getGlobalCodeRef(glob.globName);
                if (code != null && code.type == CODE) {
                    yield code;
                }
                throw new PerlCompilerException("Not a CODE reference");
            }
            default -> {
                // Symbolic reference: treat the scalar's string value as a subroutine name
                String varName = NameNormalizer.normalizeVariableName(this.toString(), packageName);
                RuntimeScalar pseudoCode = GlobalVariable.createPseudoConstantCodeRef(varName);
                yield pseudoCode != null ? pseudoCode : GlobalVariable.getGlobalCodeRef(varName);
            }
        };
    }

    /**
     * Implement {@code &{$value}} while {@code strict 'refs'} is active.
     * A CODE value (and a glob that owns a CODE slot) remains callable, but a
     * string must not be treated as a symbolic subroutine name.
     */
    public RuntimeScalar codeDerefStrict() {
        if (type == CODE) {
            return this;
        }
        int blessId = blessedId(this);
        if (blessId != 0) {
            if (blessId < 0) {
                OverloadContext ctx = OverloadContext.prepare(blessId);
                if (ctx != null) {
                    RuntimeScalar result = ctx.tryOverload("(&{}", unaryDerefOverloadArgs());
                    if (overloadReturnedDifferentObject(result)) {
                        return result.codeDerefStrict();
                    }
                }
            }
            throw new PerlCompilerException("Not a subroutine reference");
        }
        return switch (type) {
            case TIED_SCALAR -> tiedFetch().codeDerefStrict();
            case READONLY_SCALAR -> ((RuntimeScalar) value).codeDerefStrict();
            // Perl stringifies ordinary scalar values before rejecting them as
            // symbolic CODE references under strict refs.  Numeric loop aliases
            // therefore report the same diagnostic as an explicit string.
            case INTEGER, DOUBLE, STRING, BYTE_STRING, VSTRING, BOOLEAN, DUALVAR -> throw new PerlCompilerException(
                    "Can't use string (\"" + toString()
                            + "\") as a subroutine ref while \"strict refs\" in use");
            case REFERENCE -> {
                RuntimeScalar deref = (RuntimeScalar) value;
                if (deref.type == RuntimeScalarType.CODE) {
                    yield deref;
                }
                throw new PerlCompilerException("Not a CODE reference");
            }
            case ARRAYREFERENCE, HASHREFERENCE, REGEX ->
                    throw new PerlCompilerException("Not a subroutine reference");
            case FORMAT -> throw new PerlCompilerException("Not a CODE reference");
            case GLOB, GLOBREFERENCE -> {
                // A glob-typed scalar may hold a direct IO/FORMAT slot rather
                // than a RuntimeGlob.  It is not callable; importantly, do
                // not cast the slot before producing Perl's CODE diagnostic.
                if (!(value instanceof RuntimeGlob glob)) {
                    throw new PerlCompilerException("Not a CODE reference");
                }
                RuntimeScalar savedCode = glob.getSavedCodeSlot();
                if (savedCode != null && savedCode.type == CODE) {
                    yield savedCode;
                }
                if (glob.globName == null && glob.codeSlot != null
                        && glob.codeSlot.type == CODE) {
                    yield glob.codeSlot;
                }
                RuntimeScalar code = glob.globName == null
                        ? null : GlobalVariable.getGlobalCodeRef(glob.globName);
                if (code != null && code.type == CODE) {
                    yield code;
                }
                throw new PerlCompilerException("Not a CODE reference");
            }
            default -> throw new PerlCompilerException("Not a subroutine reference");
        };
    }

    // Return a reference to this scalar.
    //
    // Special case for GLOB-typed scalars: when a glob passes through @_,
    // array storage, or other copy operations, the RuntimeGlob is wrapped
    // inside a RuntimeScalar (type=GLOB, value=RuntimeGlob).  Java virtual
    // dispatch then calls THIS method instead of RuntimeGlob.createReference().
    //
    // In Perl 5, \*glob always produces a glob reference:
    //   ref(\*FH)                      → "GLOB"
    //   UNIVERSAL::isa(\*FH, 'GLOB')   → 1
    //
    // We return type=REFERENCE with value=this (the RuntimeScalar).
    // ReferenceOperators.ref() already handles this: when a REFERENCE points
    // to a RuntimeScalar with type GLOB, it returns "GLOB".
    // Universal.isa() also handles this for unblessed refs.
    //
    // We deliberately do NOT set type=GLOBREFERENCE here because that would
    // store the RuntimeGlob directly, losing the reference to this container.
    // Internals::SvREADONLY needs the container to set/get readonly status.
    public RuntimeScalar createReference() {
        if (materializedBeforeLexicalDeclaration) {
            retainMaterializedForwardReference = true;
        }
        referencedByScalarReference = true;
        boolean isRegisteredLexical =
                this.refCount == -1 && MyVarCleanupStack.isRegistered(this);
        if (this.refCount == -1 && isRegisteredLexical) {
            this.refCount = 0;
            this.localBindingExists = true;
        } else if (this.refCount == -1
                && !isRegisteredLexical
                && !hasClosureCaptureOwner()
                && (!isPackageGlobalRoot || GlobalVariable.isTemporaryGlobalAliasValue(this))
                && containerOwner == null
                && !RuntimeCode.isInstalledPadConstant(this)) {
            // An unbound scalar value returned from a subroutine is an
            // ephemeral Perl SV. Birth-track it so weaken(\(sub_return()))
            // consumes its sole owner immediately. Bound lexicals, closure
            // captures, globals, aggregate elements, active @_ arguments, and
            // constants owned by an installed subroutine's optree retain the
            // established untracked/WEAKLY_TRACKED behavior.
            this.refCount = 0;
            this.ephemeralScalarReferenceReferent = true;
        }
        if (MyVarCleanupStack.isRegistered(this)) {
            RuntimeCode.noteMyVarRegistered(this);
        }
        RuntimeScalar result = new RuntimeScalar();
        result.type = RuntimeScalarType.REFERENCE;
        result.value = this;
        return result;
    }

    public RuntimeScalar undefine() {
        releaseDetachedGlobValueOwner();
        if (isPackageGlobalRoot) {
            MortalList.invalidateExternalRootSnapshot();
        }
        if ((type & REFERENCE_BIT) != 0 && value != null) {
            MortalList.invalidateFullRootSnapshotForScalarClear();
        }
        // Special handling for CODE type - don't set the ref to undef,
        // just clear the code from the global symbol table.
        //
        // Do not release closure captures unconditionally here. CODE refs can
        // be copied into another lexical/container while a temporary argument
        // scalar is later undef'd; releasing captures from that temporary would
        // make the still-live callback forget the variables it closed over.
        // Captures are released when the CODE object's counted references
        // truly reach zero.
        if (type == RuntimeScalarType.CODE
                && value instanceof RuntimeCode code
                && code.isConstantCv
                && code.lexicalSubDisplayName
                && code.subName != null) {
            String location = code.cvStartFile != null && !code.cvStartFile.isEmpty()
                    ? " at " + code.cvStartFile + " line " + Math.max(1, code.cvStartLine)
                    : org.perlonjava.runtime.operators.WarnDie.getPerlLocationFromStack();
            if (location == null || location.isEmpty()) {
                location = " at unknown line 1";
            }
            org.perlonjava.runtime.operators.WarnDie.warnWithCategory(
                    new RuntimeScalar("Constant subroutine " + code.subName + " undefined"),
                    new RuntimeScalar(location),
                    "redefine");
        }
        if (type == RuntimeScalarType.CODE && value instanceof RuntimeCode code
                && !(code.isConstantCv && code.lexicalSubDisplayName)) {
            return RuntimeCode.undefineCodeReference(this);
        }
        if (type == RuntimeScalarType.CODE && value instanceof RuntimeCode code && globalCodeRefFqn != null) {
            releaseAllClosureCaptureReferents(code);
            // Clear the body but keep a declared CODE slot. Perl preserves the
            // glob's CODE entry after both `undef &existing` and
            // `undef &previously_unknown`: exists(&name) remains true while
            // defined(&name) becomes false.
            RuntimeCode undefinedCode = new RuntimeCode((String) null, null);
            undefinedCode.isDeclared = true;
            int separator = globalCodeRefFqn.lastIndexOf("::");
            if (separator > 0) {
                undefinedCode.packageName = globalCodeRefFqn.substring(0, separator);
                undefinedCode.subName = globalCodeRefFqn.substring(separator + 2);
            } else {
                undefinedCode.packageName = "main";
                undefinedCode.subName = globalCodeRefFqn;
            }

            PerlOwnerSlot.PendingRelease oldCodeRelease = null;
            if (this.refCountOwned) {
                if (code.refCount == Integer.MIN_VALUE) {
                    throw new IllegalStateException(
                            "counted global CODE slot refers to an already destroyed CV");
                }
                PerlOwnerSlot ownerSlot = this.countedOwnerSlot;
                if (ownerSlot == null || !ownerSlot.isActive()
                        || ownerSlot.referent() != code
                        || ownerSlot.countedScalarOwner() != this) {
                    throw new IllegalStateException(
                            "global CODE undefine lost its counted stash owner slot");
                }
                undefinedCode.refCount = 0;
                oldCodeRelease = ownerSlot.replaceCountedScalarOwnerAndAcquire(
                        code, undefinedCode, this,
                        "RuntimeScalar.undefine global CODE replacement");
            }

            this.value = undefinedCode;
            this.tainted = false;
            this.numericLiteralText = null;
            this.numericContextSeen = false;
            this.firstClassRegexScalar = false;
            this.formatPictureTainted = false;
            refreshCodeSlotOwner();
            if (oldCodeRelease != null) {
                MortalList.releaseCountedOwnerSlotImmediately(
                        code, oldCodeRelease,
                        "RuntimeScalar.undefine global CODE replacement");
            }
            // Invalidate the method resolution cache
            InheritanceResolver.invalidateCache();
            return this;
        }

        // Retire non-CODE values through the same reference-slot transition as
        // assignment. Explicit undef closes the handle first and asks the shared
        // operation to apply undef's weak-observer and full-mortal-flush rules.
        closeIOOnDrop();
        return transitionReferenceSlot(new RuntimeScalar(), true);
    }

    /**
     * Close any IO handle associated with a GLOBREFERENCE value being dropped.
     * <p>
     * This is called ONLY from explicit user actions ({@code undef $fh} and
     * {@code $fh = undef}), NOT from automatic scope-exit cleanup. For automatic
     * cleanup of abandoned handles, see {@link RuntimeIO#registerGlobForFdRecycling}
     * which uses PhantomReference-based GC tracking.
     * <p>
     * <b>Where this is called:</b>
     * <ul>
     *   <li>{@code undefine()} — when {@code undef $fh} is called explicitly</li>
     *   <li>{@code setLarge(null)} — when a variable is set to null/undef</li>
     * </ul>
     * <p>
     * <b>Where this is NOT called (by design):</b>
     * <ul>
     *   <li>{@code setLarge(value)} for non-null values — variable reassignment
     *       cannot safely close the old IO when other variables may reference
     *       the same glob (see the Capture::Tiny comment in setLarge())</li>
     *   <li>Scope exit — removed because without reference counting there is
     *       no way to know if the glob is shared. See {@code scopeExitCleanup()}
     *       javadoc for the full explanation.</li>
     * </ul>
     * <p>
     * <b>Safety heuristic:</b> We only close IO for globs that are NOT currently
     * in any stash (symbol table). Named globs still in the stash (like *MYFILE)
     * may have other references (including detached copies created by
     * {@code \*MYFILE}) and should not be closed.
     */
    private void closeIOOnDrop() {
        if (type == GLOBREFERENCE && value instanceof RuntimeGlob glob) {
            // If the glob has a name and a stash entry still exists for that name,
            // don't close — the IO may be shared with the stash glob or its copies.
            // Note: \*MYFILE creates a detached copy (different Java object) that
            // shares the IO slot, so identity checks don't work here.
            if (glob.globName != null && GlobalVariable.existsGlobalIO(glob.globName)) {
                return; // Glob name is still in the stash — don't close
            }
            RuntimeScalar ioSlot = glob.getIO();
            if (ioSlot != null && ioSlot.value instanceof RuntimeIO io
                    && !(io.ioHandle instanceof ClosedIOHandle)) {
                io.close();
            }
        }
    }

    /** Performs scalar-specific scope cleanup, including owned anonymous IO fds. */
    public static void scopeExitCleanup(RuntimeScalar scalar) {
        scopeExitCleanup(scalar, false);
    }

    /** Performs final cleanup for a scalar referent that has reached DESTROY. */
    static void scopeExitCleanupDestroyed(RuntimeScalar scalar) {
        scopeExitCleanup(scalar, true);
    }

    private static void scopeExitCleanup(RuntimeScalar scalar, boolean finalRelease) {
        if (scalar == null) return;
        if (scalar.hasCodeSlotBinding()) return;
        if (scalar instanceof RuntimeGlob glob) glob.releaseDetachedComponentsIfUnowned();
        if (finalRelease || (!scalar.hasClosureCaptureOwner() && !scalar.hasOwnerSlots()
                && !scalar.hasScalarCellOwner())) {
            scalar.releaseDetachedGlobValueOwner();
        }
        RuntimeBase untrackedValueAtScopeExit = scalar.untrackedReferenceOwnerSlot == null
                ? null : scalar.untrackedReferenceOwnerSlot.referent();
        scalar.refreshUntrackedReferenceOwner();
        if (finalRelease) scalar.localBindingExists = false;
        // ${^LAST_FH} is tied to the lexical handle that performed the last
        // tell/read.  A lexical coercible glob must stop being observable once
        // that lexical leaves scope, even though the named STDOUT glob remains
        // alive in the symbol table.
        if (RuntimeCode.getEvalDepth() == 0
                && (scalar.type == RuntimeScalarType.GLOB || scalar.type == RuntimeScalarType.GLOBREFERENCE)
                && scalar.value instanceof RuntimeGlob glob
                && RuntimeIO.getLastAccessedHandle() != null) {
            RuntimeIO last = RuntimeIO.getLastAccessedHandle();
            if ((glob.globName != null && glob.globName.equals(last.globName))
                    || (glob.IO != null && glob.IO.value == last)) {
                // A borrowed anonymous handle alias can leave scope while the
                // caller still owns the same IO glob. Keep its $. context until
                // that durable owner leaves; clearing on the borrow breaks
                // getline/input_line_number calls inside wrapper subroutines.
                boolean borrowedLiveIo = isUnstashedIoGlob(glob)
                        && !scalar.hasIoOwner() && glob.ioHolderCount() > 0;
                if (!borrowedLiveIo) {
                    RuntimeIO.setLastAccessedHandle(null);
                    RuntimeIO.setLastReadlineHandleName(null);
                }
            }
        }

        if (!finalRelease && scalar.referencedByScalarReference
                && !scalar.hasClosureCaptureOwner()
                && (scalar.localBindingExists || scalar.refCount > 0
                    || scalar.hasScalarCellOwner())) {
            cleanupScalarReferenceBinding(scalar);
            // A reference to the lexical escaped its declaring scope.  The
            // RuntimeScalar is now the Perl SV container, so its current value
            // must live until the final scalar reference is released.
            // A tied scalar must still release its tie wrapper below.  In a
            // scalar self-tie that wrapper owns the remaining counted
            // reference to this same scalar; returning here would leave the
            // cycle intact and suppress the handler's DESTROY.
            if ((scalar.refCount > 0 || scalar.hasScalarCellOwner())
                    && scalar.type != RuntimeScalarType.TIED_SCALAR) return;
        }

        // A captured lexical or an SV with an escaped scalar reference is still
        // alive after its declaring scope exits. Its destruction watcher fires
        // when the final owner later releases it, not at lexical scope exit.
        if (!scalar.hasClosureCaptureOwner()) {
            scalar.notifyDestroyedWatchers();
        }

        // Fast path: skip if no special state (most common case for integer/string vars).
        // When all three conditions are true, the entire method body is a no-op:
        // - refCountOwned=false → deferDecrementIfTracked returns immediately
        // - captureCount=0 → capture handling branch not taken
        if (!scalar.refCountOwned && !scalar.hasClosureCaptureOwner()
                && !scalar.hasIoOwner()
                && !scalar.hasScalarReferenceContentsOwner()
                && scalar.untrackedReferenceOwnerSlot == null
                && scalar.type != RuntimeScalarType.TIED_SCALAR) {
            // A temporary CODE scalar may reach scope exit without passing
            // through setLargeRefCounted. Ask the CODE owner ledger to retire
            // its captures; the request waits for active, pending, or durable
            // owners and does not trust the compatibility count.
            if (scalar.type == RuntimeScalarType.CODE
                    && scalar.value instanceof RuntimeCode code
                    && (code.capturedScalars != null || code.capturedAggregates != null)) {
                code.requestCaptureReleaseWhenUnowned();
            }
            return;
        }

        scalar.releaseOwnedScalarReferenceContents();
        scalar.releaseUnownedClosureCaptures();

        // RuntimeCode captures carry one SCALAR_CAPTURE owner identity each.
        // Retire any remaining compatibility-only capture counts here: they
        // have no CODE slot that could keep the pad alive or release the
        // count later. Live closures remain protected by their explicit slots.
        while (scalar.legacyCaptureOwnerCount() > 0) {
            scalar.releaseClosureCapture();
        }
        scalar.verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE);

        // If this variable is captured by a closure, mark it so releaseCaptures
        // knows the scope has exited. But still proceed with refCount cleanup below
        // so that blessed ref refCounts and weak refs are handled properly.
        if (scalar.hasClosureCaptureOwner()) {
            // Mark that this variable's scope has exited. When releaseCaptures
            // later decrements captureCount to 0, it will know the scope is gone.
            scalar.scopeExited = true;
            if (scalar.type != RuntimeScalarType.CODE) {
                MortalList.noteDeferredScopeExitCaptureCandidate(scalar);
            }
            // For CODE refs: still decrement the VALUE's refCount so the RuntimeCode
            // is eventually destroyed and its releaseCaptures fires (decrementing
            // captureCount on all the variables IT captured). This is critical for
            // eval STRING closures that capture all visible lexicals — without this,
            // the inner sub's captures (including $got in cmp_ok) are never released,
            // preventing weak refs from being cleared.
            // For non-CODE refs: do NOT decrement. The closure holds a strong reference
            // to this variable's value, and decrementing would prematurely clear weak
            // refs (breaks Sub::Quote where closures legitimately keep values alive).
            if (scalar.type == RuntimeScalarType.CODE
                    && scalar.value instanceof RuntimeCode) {
                // Fall through to deferDecrementIfTracked below
            } else {
                // For non-CODE blessed refs with DESTROY: register for deferred
                // cleanup after the main script returns. The interpreter captures
                // ALL visible lexicals for eval STRING support, inflating
                // captureCount on variables that closures don't actually use.
                // At inner scope exit we can't decrement (closures in outer scopes
                // may legitimately keep the object alive), but after the main
                // script finishes ALL scopes have exited, so it's safe.
                if (scalar.refCountOwned
                        && ((scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                            || scalar.type == RuntimeScalarType.GLOB)
                        && scalar.value instanceof RuntimeBase rb
                        && rb.blessId != 0) {
                    MortalList.addDeferredCapture(scalar);
                }
                return;
            }
        }

        // NOTE: Do NOT call releaseCaptures() on CODE refs here.
        // When a local variable holding a CODE ref goes out of scope, the
        // RuntimeCode may still be alive in other locations (e.g., a glob's
        // CODE slot installed via *glob = $code, or another variable).
        // Premature releaseCaptures() would decrement captureCount on captured
        // variables, causing those variables' scope exit to add birth-tracked
        // objects to the mortal list and prematurely clear weak refs.
        // Captures are properly released when the CODE ref is overwritten
        // (via setLarge) or undef'd (via undefine).

        if (scalar.type == RuntimeScalarType.TIED_SCALAR
                && scalar.value instanceof TiedVariableBase tiedVariable) {
            tiedVariable.releaseTiedObject();
            return;
        }

        releaseIoOwner(scalar);

        // Defer refCount decrement for blessed references with DESTROY.
        // Uses MortalList to defer the decrement until the next safe point
        // (setLarge or RuntimeCode.apply). This prevents premature DESTROY
        // when the same referent is on the JVM stack as a return value.
        MortalList.deferDecrementIfTracked(scalar);

        // The lexical may have owned this cell when the initial refresh ran.
        // Recheck after scope-exit state changes so its untracked value leaves
        // with the final scalar-cell owner instead of waiting for a sweep.
        scalar.finalizeUntrackedValueAfterScopeExit(untrackedValueAtScopeExit);

        // WEAKLY_TRACKED objects: do NOT clear weak refs on scope exit.
        // These objects transitioned from untracked (-1) to WEAKLY_TRACKED (-2) in
        // weaken(), but scope exit of ONE reference doesn't mean no other strong
        // references exist — closures may capture copies of the same reference
        // (e.g., Sub::Quote's $_QUOTED capture keeps $quoted_info alive even after
        // unquote_sub's local exits scope). Clearing weak refs here would break
        // Sub::Quote/Moo constructor inlining.
        // Weak refs for WEAKLY_TRACKED objects are cleared only via:
        //   - explicit undefine() of a strong reference
        // Since unblessed objects have no DESTROY, delayed clearing is safe.
    }

    /** Release one durable scalar slot's anonymous IO resource owner identity. */
    public static void releaseIoOwner(RuntimeScalar scalar) {
        IoOwnerRelease ownerRelease = releaseIoOwnerSlot(scalar);
        finishIoOwnerRelease(ownerRelease);
    }

    private static void finishIoOwnerRelease(IoOwnerRelease ownerRelease) {
        if (ownerRelease != null) {
            RuntimeGlob glob = ownerRelease.glob();
            if (ownerRelease.remainingHolders() <= 0 && isUnstashedIoGlob(glob)) {
                RuntimeScalar ioSlot = glob.getIO();
                if (ioSlot != null && ioSlot.value instanceof RuntimeIO io
                        && !(io.ioHandle instanceof ClosedIOHandle)) {
                    // Closing is required for stream sockets: merely dropping
                    // the synthetic fileno does not send EOF to the peer.
                    // Ordinary handles can still have transient IO::File
                    // aliases whose Perl ownership is not represented by this
                    // resource lease, so they keep the unregister-only path.
                    if (isSocketIOHandle(io.ioHandle)) io.close();
                    else io.unregisterFileno();
                }
                GlobalVariable.retireHiddenIORef(glob.globName);
            }
        }
    }

    /** Release an IO owner only when its live handle is a stream/datagram socket. */
    public static void releaseSocketIoOwner(RuntimeScalar scalar) {
        if (scalar == null || !scalar.hasIoOwner() || scalar.type != GLOBREFERENCE
                || !(scalar.value instanceof RuntimeGlob glob)
                || !isUnstashedIoGlob(glob)) {
            return;
        }
        RuntimeScalar ioSlot = glob.getIO();
        if (ioSlot != null && ioSlot.value instanceof RuntimeIO io
                && isSocketIOHandle(io.ioHandle)) {
            releaseIoOwner(scalar);
        }
    }

    /**
     * End a lexical socket owner's lifetime at explicit return. If the return
     * value contains a scalar for the same glob, move the ownership token to
     * that scalar so the caller controls the remaining lifetime.
     */
    public static void releaseOrTransferSocketIoOwnerOnReturn(
            RuntimeScalar owner, RuntimeBase returned) {
        if (owner == null || owner.hasClosureCaptureOwner()
                || !owner.hasIoOwner() || owner.type != GLOBREFERENCE
                || !(owner.value instanceof RuntimeGlob glob)
                || !isUnstashedIoGlob(glob)) {
            return;
        }
        RuntimeScalar ioSlot = glob.getIO();
        if (ioSlot == null || !(ioSlot.value instanceof RuntimeIO io)
                || !isStreamSocketIOHandle(io.ioHandle)) {
            return;
        }

        RuntimeScalar recipient = returnedSocketScalar(returned, glob);
        if (recipient == owner) {
            owner.copiedFromArgumentFrame = null;
            owner.reconcileContainerOwners();
            return;
        }
        if (recipient != null && !recipient.hasIoOwner()) {
            transferIoOwner(owner, recipient);
            recipient.copiedFromArgumentFrame = null;
            // RuntimeCode.returnList wraps scalar returns in a temporary list.
            // The returned scalar is a movable value, not a durable element of
            // that temporary container; let the caller assignment take over.
            recipient.reconcileContainerOwners();
            return;
        }
        releaseIoOwner(owner);
    }

    /**
     * End a lexical IO owner's lifetime while preserving any aliases carried by
     * the materialized return value.  JVM scope cleanup can otherwise release
     * the only holder before the caller has installed the returned scalar.
     */
    public static void releaseIoOwnerPreservingReturned(
            RuntimeScalar owner, RuntimeBase returned) {
        if (owner == null || !owner.hasIoOwner()
                || owner.type != GLOBREFERENCE
                || !(owner.value instanceof RuntimeGlob glob)
                || !isUnstashedIoGlob(glob)) {
            return;
        }
        boolean capturedOwner = owner.hasClosureCaptureOwner();
        boolean returnedOwner = false;
        if (returned != null) {
            for (RuntimeScalar alias : returnedIoAliases(returned, glob)) {
                if (alias == owner) {
                    returnedOwner = true;
                } else {
                    retainUnstashedIoForDurableSlot(alias);
                }
            }
        }
        if (!returnedOwner && !capturedOwner) {
            clearStaleDiagnosticContextForUnaliasedIO(owner);
            releaseIoOwner(owner);
        }
    }

    /** Move an anonymous-IO holder token to the final scalar copy returned to a caller. */
    public static void transferIoOwnerToReturnedCopy(
            RuntimeScalar source, RuntimeScalar returnedCopy) {
        if (source == null || returnedCopy == null || !source.hasIoOwner()
                || source.type != GLOBREFERENCE
                || returnedCopy.type != GLOBREFERENCE
                || source.value != returnedCopy.value
                || !(source.value instanceof RuntimeGlob glob)
                || !isUnstashedIoGlob(glob)) {
            return;
        }
        transferIoOwner(source, returnedCopy);
        returnedCopy.copiedFromArgumentFrame = null;
        returnedCopy.reconcileContainerOwners();
    }

    private static java.util.List<RuntimeScalar> returnedIoAliases(
            RuntimeBase value, RuntimeGlob glob) {
        java.util.List<RuntimeScalar> aliases = new java.util.ArrayList<>();
        collectReturnedIoAliases(value, glob, aliases);
        return aliases;
    }

    private static void collectReturnedIoAliases(
            RuntimeBase value, RuntimeGlob glob, java.util.List<RuntimeScalar> aliases) {
        if (value instanceof RuntimeScalar scalar) {
            if (scalar.type == GLOBREFERENCE && scalar.value == glob) aliases.add(scalar);
        } else if (value instanceof RuntimeList list) {
            for (RuntimeBase element : list.elements) {
                collectReturnedIoAliases(element, glob, aliases);
            }
        } else if (value instanceof RuntimeArray array) {
            for (RuntimeScalar element : array.elements) {
                collectReturnedIoAliases(element, glob, aliases);
            }
        }
    }

    private static RuntimeScalar returnedSocketScalar(RuntimeBase returned, RuntimeGlob glob) {
        if (returned instanceof RuntimeScalar scalar) {
            return scalar.type == GLOBREFERENCE && scalar.value == glob ? scalar : null;
        }
        if (returned instanceof RuntimeList list) {
            for (RuntimeBase element : list.elements) {
                RuntimeScalar found = returnedSocketScalar(element, glob);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isSocketIOHandle(IOHandle handle) {
        while (handle instanceof LayeredIOHandle layered) {
            handle = layered.getDelegate();
        }
        return handle instanceof SocketIO || handle instanceof NativeSocketIOHandle;
    }

    private static boolean isStreamSocketIOHandle(IOHandle handle) {
        while (handle instanceof LayeredIOHandle layered) {
            handle = layered.getDelegate();
        }
        if (handle instanceof SocketIO socket) {
            return !socket.isDatagramSocket();
        }
        return handle instanceof NativeSocketIOHandle socket
                && socket.socketType() != org.perlonjava.runtime.perlmodule.Socket.SOCK_DGRAM;
    }

    private static boolean isAcceptedSocket(RuntimeScalar scalar) {
        if (scalar == null || scalar.type != GLOBREFERENCE
                || !(scalar.value instanceof RuntimeGlob glob)
                || !glob.acceptedSocket) {
            return false;
        }
        return hasLiveIo(glob) && isSocketIOHandle(((RuntimeIO) glob.getIO().value).ioHandle);
    }

    private static boolean isUnstashedIoGlob(RuntimeGlob glob) {
        // Symbol::gensym deliberately keeps a hidden lookup entry while
        // scalar IO holders are live. That entry is a lifetime index, not a
        // Perl stash owner; the final holder must still close the resource and
        // retire the hidden entry.
        return glob.symbolGensym
                || glob.globName == null
                || !GlobalVariable.existsGlobalIO(glob.globName);
    }

    private static boolean hasLiveIo(RuntimeGlob glob) {
        RuntimeScalar ioSlot = glob == null ? null : glob.getIO();
        return ioSlot != null && ioSlot.value instanceof RuntimeIO io
                && !(io.ioHandle instanceof ClosedIOHandle);
    }

    public RuntimeScalar defined() {
        return getScalarBoolean(getDefinedBoolean());
    }

    public boolean getDefinedBoolean() {
        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        return switch (type) {
            case INTEGER -> true;       // 0
            case DOUBLE -> true;        // 1
            case STRING -> true;        // 2
            case BYTE_STRING -> true;   // 3
            case UNDEF -> false;        // 4
            case VSTRING -> true;       // 5
            case BOOLEAN -> true;       // 6
            case GLOB -> true;          // 7
            case JAVAOBJECT -> true;    // 8
            case TIED_SCALAR -> this.tiedFetch().getDefinedBoolean(); // 9
            case DUALVAR -> ((DualVar) this.value).stringValue().getDefinedBoolean(); // 10
            case FORMAT -> ((RuntimeFormat) value).getDefinedBoolean(); // 11
            case READONLY_SCALAR -> ((RuntimeScalar) this.value).getDefinedBoolean(); // 12
            case CODE -> true;
            // Reference types (with REFERENCE_BIT) fall through to default
            default -> true;
        };
    }

    public RuntimeScalar preAutoIncrement() {
        watcherMutationDepth++;
        RuntimeScalar result;
        try {
            result = preAutoIncrementWithoutWatcherNotification();
        } finally {
            watcherMutationDepth--;
            refreshArgumentAliasOwnerSlots();
        }
        notifyModifiedWatchers();
        return result;
    }

    private RuntimeScalar preAutoIncrementWithoutWatcherNotification() {
        warnImprecisionForAutoOperation(1);
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;
        if (this.type != RuntimeScalarType.TIED_SCALAR
                && this.type != RuntimeScalarType.STRING
                && this.type != RuntimeScalarType.BYTE_STRING
                && this.type != RuntimeScalarType.VSTRING) {
            double numericValue = getDouble();
            if (Double.isInfinite(numericValue) || Double.isNaN(numericValue)) {
                this.type = RuntimeScalarType.DOUBLE;
                this.value = numericValue;
                return this;
            }
        }
        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        switch (type) {
            case INTEGER -> { // 0
                // The interpreter can retain a non-finite NV payload through
                // an integer-tagged lexical assignment. Preserve NV semantics.
                if (this.value instanceof Double doubleValue) {
                    this.type = RuntimeScalarType.DOUBLE;
                    this.value = doubleValue + 1;
                    break;
                }
                if (this.value instanceof UnsignedWordValue unsignedValue) {
                    setIntegerValue(unsignedValue.toBigInteger().add(BigInteger.ONE));
                    break;
                }
                if (this.value instanceof BigInteger integerValue) {
                    setIntegerValue(integerValue.add(BigInteger.ONE));
                    break;
                }
                long integerValue = ((Number) this.value).longValue();
                if (integerValue == Long.MAX_VALUE) {
                    this.type = RuntimeScalarType.DOUBLE;
                    this.value = (double) integerValue + 1;
                } else {
                    setIntegerValue(integerValue + 1);
                }
            }
            case DOUBLE -> // 1
                    this.value = (double) this.value + 1;
            case STRING, BYTE_STRING -> // 2
                    ScalarUtils.stringIncrement(this);
            case UNDEF -> { // 3
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case VSTRING -> { // 4
                ScalarUtils.stringIncrement(this);
                this.type = RuntimeScalarType.STRING;  // ++ flattens vstrings
            }
            case BOOLEAN -> { // 5
                int intVal = (boolean) this.value ? 1 : 0;
                this.type = RuntimeScalarType.INTEGER;
                this.value = intVal + 1;
            }
            case GLOB -> { // 6
                if (this instanceof RuntimeGlob) {
                    throw new PerlCompilerException("Modification of a read-only value attempted");
                }
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case JAVAOBJECT -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case TIED_SCALAR -> { // 8
                RuntimeScalar variable = this.tiedFetch();
                variable.preAutoIncrement();
                this.tiedStore(variable);
                return variable;
            }
            case DUALVAR -> { // 9
                int dualVal = this.getInt();
                this.type = RuntimeScalarType.INTEGER;
                this.value = dualVal + 1;
            }
            case FORMAT -> { // 10
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case READONLY_SCALAR -> // 12
                    throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> { // All reference types (CODE, REFERENCE, ARRAYREFERENCE, etc.)
                // Check if object is eligible for overloading
                int blessId = blessedId(this);
                if (blessId != 0) {
                    // Prepare overload context and check if object is eligible for overloading
                    OverloadContext ctx = OverloadContext.prepare(blessId);
                    if (ctx != null) {
                        boolean copiedToPlainScalar = false;
                        // Copy-on-write: If the object has the = overload, call it to create
                        // a copy BEFORE any mutation. This implements Perl's COW semantics
                        // where shared references are copied before modification.
                        // Example: my $b = $a; $b++; should NOT modify $a
                        RuntimeScalar copyResult = ctx.tryOverload("(=", new RuntimeArray(this));
                        if (copyResult != null) {
                            set(copyResult);
                            copiedToPlainScalar = !RuntimeScalarType.isReference(this);
                        }

                        // Try direct overload method for ++
                        RuntimeScalar result = ctx.tryOverload("(++", new RuntimeArray(this));
                        if (result != null) {
                            // The ++ operator has already modified this operand
                            // Just return this (which has been modified)
                            return this;
                        }

                        // A copy overload may deliberately turn the lvalue
                        // into a native scalar.  Do its native mutation
                        // before considering conversion methods from the
                        // original object's overload table.
                        if (copiedToPlainScalar) {
                            return preAutoIncrementWithoutWatcherNotification();
                        }

                        // With fallback enabled, Perl autogenerates ++ from
                        // numeric conversion when no ++ or + overload exists.
                        result = ctx.tryOverloadFallback(this, "(0+");
                        if (result != null) {
                            set(result);
                            return preAutoIncrementWithoutWatcherNotification();
                        }

                        // Try fallback to + operator with undef as third argument (mutator indicator)
                        result = ctx.tryOverload("(+", new RuntimeArray(this, scalarOne, scalarUndef));
                        if (result != null) {
                            // For fallback, + should NOT modify operand, so we handle assignment
                            set(result);
                            return this;
                        }

                    }
                }

                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
        }
        return this;
    }

    // Inlineable fast path for $v++
    public RuntimeScalar postAutoIncrement() {
        watcherMutationDepth++;
        RuntimeScalar result;
        try {
            result = postAutoIncrementWithoutWatcherNotification();
        } finally {
            watcherMutationDepth--;
            refreshArgumentAliasOwnerSlots();
        }
        notifyModifiedWatchers();
        return result;
    }

    /** Postfix ++ in void context for a proven lexical lvalue. */
    public void postAutoIncrementVoid() {
        watcherMutationDepth++;
        try {
            if (getClass() == RuntimeScalar.class
                    && type == INTEGER
                    && !isWideIntegerValue(value)) {
                long integerValue = ((Number) value).longValue();
                if (integerValue < Long.MAX_VALUE) {
                    warnImprecisionForAutoOperation(1);
                    setIntegerValue(integerValue + 1);
                    return;
                }
            }
            postAutoIncrementWithoutWatcherNotification();
        } finally {
            watcherMutationDepth--;
            refreshArgumentAliasOwnerSlots();
            notifyModifiedWatchers();
        }
    }

    /** Implements ++ while lexical {@code use integer} is active. */
    public RuntimeScalar integerPreAutoIncrement() {
        watcherMutationDepth++;
        RuntimeScalar result;
        try {
            result = integerAutoIncrement(false, 1);
        } finally {
            watcherMutationDepth--;
            refreshArgumentAliasOwnerSlots();
        }
        notifyModifiedWatchers();
        return result;
    }

    /** Implements postfix ++ while lexical {@code use integer} is active. */
    public RuntimeScalar integerPostAutoIncrement() {
        watcherMutationDepth++;
        RuntimeScalar result;
        try {
            result = integerAutoIncrement(true, 1);
        } finally {
            watcherMutationDepth--;
            refreshArgumentAliasOwnerSlots();
        }
        notifyModifiedWatchers();
        return result;
    }

    /** Implements -- while lexical {@code use integer} is active. */
    public RuntimeScalar integerPreAutoDecrement() {
        watcherMutationDepth++;
        RuntimeScalar result;
        try {
            result = integerAutoIncrement(false, -1);
        } finally {
            watcherMutationDepth--;
            refreshArgumentAliasOwnerSlots();
        }
        notifyModifiedWatchers();
        return result;
    }

    /** Implements postfix -- while lexical {@code use integer} is active. */
    public RuntimeScalar integerPostAutoDecrement() {
        watcherMutationDepth++;
        RuntimeScalar result;
        try {
            result = integerAutoIncrement(true, -1);
        } finally {
            watcherMutationDepth--;
            refreshArgumentAliasOwnerSlots();
        }
        notifyModifiedWatchers();
        return result;
    }

    private RuntimeScalar integerAutoIncrement(boolean postfix, int delta) {
        // Objects and tied variables retain the normal mutation protocol: it
        // performs overload dispatch and FETCH/STORE before any native-IV
        // coercion could be considered.
        if (blessedId(this) != 0 || this.type == RuntimeScalarType.TIED_SCALAR
                || this.type == RuntimeScalarType.GLOB
                || this.type == RuntimeScalarType.READONLY_SCALAR) {
            return delta > 0
                    ? (postfix ? postAutoIncrementWithoutWatcherNotification()
                            : preAutoIncrementWithoutWatcherNotification())
                    : (postfix ? postAutoDecrementWithoutWatcherNotification()
                            : preAutoDecrementWithoutWatcherNotification());
        }

        // Perl gives postfix ++ on undef the old numeric zero, but postfix --
        // returns the original undef before coercing the lvalue to -1.
        RuntimeScalar old = this.type == RuntimeScalarType.UNDEF && delta > 0
                ? new RuntimeScalar(0) : new RuntimeScalar(this);
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;
        setIntegerValue(getLong() + delta);
        return postfix ? old : this;
    }

    private RuntimeScalar postAutoIncrementWithoutWatcherNotification() {
        warnImprecisionForAutoOperation(1);
        if (this.type != RuntimeScalarType.TIED_SCALAR
                && this.type != RuntimeScalarType.STRING
                && this.type != RuntimeScalarType.BYTE_STRING
                && this.type != RuntimeScalarType.VSTRING) {
            double numericValue = getDouble();
            if (Double.isInfinite(numericValue) || Double.isNaN(numericValue)) {
                RuntimeScalar old = new RuntimeScalar(this);
                this.type = RuntimeScalarType.DOUBLE;
                this.value = numericValue;
                return old;
            }
        }
        if (this.type == INTEGER && !isWideIntegerValue(this.value)) {
            long integerValue = ((Number) this.value).longValue();
            if (integerValue < Long.MAX_VALUE) {
                setIntegerValue(integerValue + 1);
                return new RuntimeScalar(integerValue).propagateTaint(this);
            }
        }
        return postAutoIncrementLarge();
    }

    // Slow path for $v++
    private RuntimeScalar postAutoIncrementLarge() {
        // For undef, the old value should be 0, not undef
        RuntimeScalar old = this.type == RuntimeScalarType.UNDEF ?
                new RuntimeScalar(0) : new RuntimeScalar(this);
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        switch (type) {
            case INTEGER -> { // 0
                if (this.value instanceof Double doubleValue) {
                    this.type = RuntimeScalarType.DOUBLE;
                    this.value = doubleValue + 1;
                    break;
                }
                if (this.value instanceof UnsignedWordValue unsignedValue) {
                    setIntegerValue(unsignedValue.toBigInteger().add(BigInteger.ONE));
                    break;
                }
                if (this.value instanceof BigInteger integerValue) {
                    setIntegerValue(integerValue.add(BigInteger.ONE));
                    break;
                }
                long integerValue = ((Number) this.value).longValue();
                if (integerValue == Long.MAX_VALUE) {
                    this.type = RuntimeScalarType.DOUBLE;
                    this.value = (double) integerValue + 1;
                } else {
                    setIntegerValue(integerValue + 1);
                }
            }
            case DOUBLE -> // 1
                    this.value = (double) this.value + 1;
            case STRING, BYTE_STRING -> // 2
                    ScalarUtils.stringIncrement(this);
            case UNDEF -> { // 3
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case VSTRING -> { // 4
                ScalarUtils.stringIncrement(this);
                this.type = RuntimeScalarType.STRING;  // ++ flattens vstrings
            }
            case BOOLEAN -> { // 5
                this.type = RuntimeScalarType.INTEGER;
                this.value = old.getInt() + 1;
            }
            case GLOB -> { // 6
                if (this instanceof RuntimeGlob) {
                    throw new PerlCompilerException("Modification of a read-only value attempted");
                }
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case JAVAOBJECT -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case TIED_SCALAR -> { // 8
                RuntimeScalar variable = new RuntimeScalar(old);
                variable.preAutoIncrement();
                this.tiedStore(variable);
            }
            case DUALVAR -> { // 9
                this.type = RuntimeScalarType.INTEGER;
                this.value = old.getInt() + 1;
            }
            case FORMAT -> { // 10
                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
            case READONLY_SCALAR -> // 12
                    throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> { // All reference types
                // A postfix operation returns the original reference while
                // replacing this lvalue with a number.  Move (rather than
                // duplicate) this scalar's counted ownership to that return
                // value so its eventual assignment/scope cleanup can run
                // DESTROY.  The copy constructor intentionally has no owner
                // token of its own.
                // The source may be a borrowed register alias even though the
                // returned reference becomes the first durable owner.  The
                // assignment receiving this scalar will balance the temporary
                // copy, so retain one cleanup token for that returned value.
                old.postfixReferenceOwnershipTransfer = true;
                transferPostfixReferenceOwnerTo(old);
                // Check if object is eligible for overloading
                int blessId = blessedId(this);
                if (blessId != 0) {
                    // Prepare overload context and check if object is eligible for overloading
                    OverloadContext ctx = OverloadContext.prepare(blessId);
                    if (ctx != null) {
                        boolean copiedToPlainScalar = false;
                        // Copy-on-write: If the object has the = overload, call it to create
                        // a copy BEFORE any mutation. This implements Perl's COW semantics
                        // where shared references are copied before modification.
                        RuntimeScalar copyResult = ctx.tryOverload("(=", new RuntimeArray(this));
                        if (copyResult != null) {
                            set(copyResult);
                            copiedToPlainScalar = !RuntimeScalarType.isReference(this);
                        }

                        // Try direct overload method for ++
                        RuntimeScalar result = ctx.tryOverload("(++", new RuntimeArray(this));
                        if (result != null) {
                            // The ++ operator has already modified this operand
                            // Return the old value
                            return old;
                        }

                        if (copiedToPlainScalar) {
                            preAutoIncrementWithoutWatcherNotification();
                            return old;
                        }

                        // Numeric conversion is the final autogeneration
                        // route for an overloaded postfix increment.
                        result = ctx.tryOverloadFallback(this, "(0+");
                        if (result != null) {
                            set(result);
                            preAutoIncrementWithoutWatcherNotification();
                            return old;
                        }

                        // Try fallback to + operator with undef as third argument (mutator indicator)
                        result = ctx.tryOverload("(+", new RuntimeArray(this, scalarOne, scalarUndef));
                        if (result != null) {
                            // For fallback, + should NOT modify operand, so we handle assignment
                            set(result);
                            return old;
                        }

                    }
                }

                this.type = RuntimeScalarType.INTEGER;
                this.value = 1;
            }
        }
        return old;
    }

    public RuntimeScalar preAutoDecrement() {
        watcherMutationDepth++;
        RuntimeScalar result;
        try {
            result = preAutoDecrementWithoutWatcherNotification();
        } finally {
            watcherMutationDepth--;
            refreshArgumentAliasOwnerSlots();
        }
        notifyModifiedWatchers();
        return result;
    }

    private RuntimeScalar preAutoDecrementWithoutWatcherNotification() {
        warnImprecisionForAutoOperation(-1);
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;
        if (this.type != RuntimeScalarType.TIED_SCALAR) {
            double numericValue = getDouble();
            if (Double.isInfinite(numericValue) || Double.isNaN(numericValue)) {
                this.type = RuntimeScalarType.DOUBLE;
                this.value = numericValue;
                return this;
            }
        }
        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        switch (type) {
            case INTEGER -> { // 0
                if (this.value instanceof UnsignedWordValue unsignedValue) {
                    setIntegerValue(unsignedValue.toBigInteger().subtract(BigInteger.ONE));
                    break;
                }
                if (this.value instanceof BigInteger integerValue) {
                    setIntegerValue(integerValue.subtract(BigInteger.ONE));
                    break;
                }
                long integerValue = ((Number) this.value).longValue();
                if (integerValue == Long.MIN_VALUE) {
                    this.type = RuntimeScalarType.DOUBLE;
                    this.value = (double) integerValue - 1;
                } else {
                    setIntegerValue(integerValue - 1);
                }
            }
            case DOUBLE -> // 1
                    this.value = (double) this.value - 1;
            case STRING, BYTE_STRING -> { // 2
                // Handle numeric decrement
                this.set(NumberParser.parseNumber(this));
                return this.preAutoDecrementWithoutWatcherNotification();
            }
            case UNDEF -> { // 3
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case VSTRING -> { // 4
                // Handle as numeric
                this.set(NumberParser.parseNumber(this));
                return this.preAutoDecrementWithoutWatcherNotification();
            }
            case BOOLEAN -> { // 5
                int intVal = (boolean) this.value ? 1 : 0;
                this.type = RuntimeScalarType.INTEGER;
                this.value = intVal - 1;
            }
            case GLOB -> { // 6
                if (this instanceof RuntimeGlob) {
                    throw new PerlCompilerException("Modification of a read-only value attempted");
                }
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case JAVAOBJECT -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case TIED_SCALAR -> { // 8
                RuntimeScalar variable = this.tiedFetch();
                variable.preAutoDecrement();
                this.tiedStore(variable);
                return variable;
            }
            case DUALVAR -> { // 9
                int dualVal = this.getInt();
                this.type = RuntimeScalarType.INTEGER;
                this.value = dualVal - 1;
            }
            case FORMAT -> { // 10
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case READONLY_SCALAR -> // 12
                    throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> { // All reference types
                // Check if object is eligible for overloading
                int blessId = blessedId(this);
                if (blessId != 0) {
                    // Prepare overload context and check if object is eligible for overloading
                    OverloadContext ctx = OverloadContext.prepare(blessId);
                    if (ctx != null) {
                        boolean copiedToPlainScalar = false;
                        // Copy-on-write: If the object has the = overload, call it to create
                        // a copy BEFORE any mutation. This implements Perl's COW semantics
                        // where shared references are copied before modification.
                        RuntimeScalar copyResult = ctx.tryOverload("(=", new RuntimeArray(this));
                        if (copyResult != null) {
                            // Copy the cloned object's fields into this
                            this.type = copyResult.type;
                            this.value = copyScalarPayload(copyResult.type, copyResult.value);
                            copiedToPlainScalar = !RuntimeScalarType.isReference(this);
                        }

                        // Try direct overload method for --
                        RuntimeScalar result = ctx.tryOverload("(--", new RuntimeArray(this));
                        if (result != null) {
                            // The -- operator has already modified this operand
                            // Just return this (which has been modified)
                            return this;
                        }

                        if (copiedToPlainScalar) {
                            return preAutoDecrementWithoutWatcherNotification();
                        }

                        // With fallback enabled, Perl autogenerates -- from
                        // numeric conversion when no -- or - overload exists.
                        result = ctx.tryOverloadFallback(this, "(0+");
                        if (result != null) {
                            set(result);
                            return preAutoDecrementWithoutWatcherNotification();
                        }

                        // Try fallback to - operator with undef as third argument (mutator indicator)
                        result = ctx.tryOverload("(-", new RuntimeArray(this, scalarOne, scalarUndef));
                        if (result != null) {
                            // For fallback, - should NOT modify operand, so we handle assignment
                            set(result);
                            return this;
                        }
                    }
                }

                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
        }
        return this;
    }

    public boolean isBlessed() {
        return blessedId(this) != 0;
    }

    public RuntimeScalar postAutoDecrement() {
        watcherMutationDepth++;
        RuntimeScalar result;
        try {
            result = postAutoDecrementWithoutWatcherNotification();
        } finally {
            watcherMutationDepth--;
            refreshArgumentAliasOwnerSlots();
        }
        notifyModifiedWatchers();
        return result;
    }

    private RuntimeScalar postAutoDecrementWithoutWatcherNotification() {
        RuntimeScalar old = new RuntimeScalar(this);
        warnImprecisionForAutoOperation(-1);
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
        this.formatPictureTainted = false;
        if (this.type != RuntimeScalarType.TIED_SCALAR) {
            double numericValue = getDouble();
            if (Double.isInfinite(numericValue) || Double.isNaN(numericValue)) {
                this.type = RuntimeScalarType.DOUBLE;
                this.value = numericValue;
                return old;
            }
        }

        // Cases 0-11 are listed in order from RuntimeScalarType, and compile to fast tableswitch
        switch (type) {
            case INTEGER -> { // 0
                if (this.value instanceof UnsignedWordValue unsignedValue) {
                    setIntegerValue(unsignedValue.toBigInteger().subtract(BigInteger.ONE));
                    break;
                }
                if (this.value instanceof BigInteger integerValue) {
                    setIntegerValue(integerValue.subtract(BigInteger.ONE));
                    break;
                }
                long integerValue = ((Number) this.value).longValue();
                if (integerValue == Long.MIN_VALUE) {
                    this.type = RuntimeScalarType.DOUBLE;
                    this.value = (double) integerValue - 1;
                } else {
                    setIntegerValue(integerValue - 1);
                }
            }
            case DOUBLE -> // 1
                    this.value = (double) this.value - 1;
            case STRING, BYTE_STRING -> { // 2
                // Handle numeric decrement
                this.set(NumberParser.parseNumber(this));
                this.preAutoDecrement();
            }
            case UNDEF -> { // 3
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case VSTRING -> { // 4
                // Handle as numeric
                this.set(NumberParser.parseNumber(this));
                this.preAutoDecrement();
            }
            case BOOLEAN -> { // 5
                this.type = RuntimeScalarType.INTEGER;
                this.value = old.getInt() - 1;
            }
            case GLOB -> { // 6
                if (this instanceof RuntimeGlob) {
                    throw new PerlCompilerException("Modification of a read-only value attempted");
                }
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case JAVAOBJECT -> { // 7
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case TIED_SCALAR -> { // 8
                RuntimeScalar variable = new RuntimeScalar(old);
                variable.preAutoDecrement();
                this.tiedStore(variable);
            }
            case DUALVAR -> { // 9
                this.type = RuntimeScalarType.INTEGER;
                this.value = old.getInt() - 1;
            }
            case FORMAT -> { // 10
                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
            case READONLY_SCALAR -> // 12
                    throw new PerlCompilerException("Modification of a read-only value attempted");
            default -> { // All reference types
                // See postAutoIncrementLarge(): the returned pre-mutation
                // reference inherits this lvalue's ownership token.
                old.postfixReferenceOwnershipTransfer = true;
                transferPostfixReferenceOwnerTo(old);
                // Check if object is eligible for overloading
                int blessId = blessedId(this);
                if (blessId != 0) {
                    // Prepare overload context and check if object is eligible for overloading
                    OverloadContext ctx = OverloadContext.prepare(blessId);
                    if (ctx != null) {
                        boolean copiedToPlainScalar = false;
                        // Copy-on-write: If the object has the = overload, call it to create
                        // a copy BEFORE any mutation. This implements Perl's COW semantics
                        // where shared references are copied before modification.
                        RuntimeScalar copyResult = ctx.tryOverload("(=", new RuntimeArray(this));
                        if (copyResult != null) {
                            // Copy the cloned object's fields into this
                            this.type = copyResult.type;
                            this.value = copyScalarPayload(copyResult.type, copyResult.value);
                            copiedToPlainScalar = !RuntimeScalarType.isReference(this);
                        }

                        // Try direct overload method for --
                        RuntimeScalar result = ctx.tryOverload("(--", new RuntimeArray(this));
                        if (result != null) {
                            // The -- operator has already modified this operand
                            // Return the old value
                            return old;
                        }

                        if (copiedToPlainScalar) {
                            preAutoDecrementWithoutWatcherNotification();
                            return old;
                        }

                        result = ctx.tryOverloadFallback(this, "(0+");
                        if (result != null) {
                            set(result);
                            preAutoDecrementWithoutWatcherNotification();
                            return old;
                        }

                        // Try fallback to - operator with undef as third argument (mutator indicator)
                        result = ctx.tryOverload("(-", new RuntimeArray(this, scalarOne, scalarUndef));
                        if (result != null) {
                            // For fallback, - should NOT modify operand, so we handle assignment
                            set(result);
                            return old;
                        }
                    }
                }

                this.type = RuntimeScalarType.INTEGER;
                this.value = -1;
            }
        }
        return old;
    }

    /** Emit Perl's lexical imprecision warning when an NV cannot represent a unit step. */
    private void warnImprecisionForAutoOperation(int delta) {
        if (this.type != INTEGER && this.type != DOUBLE) return;
        if (imprecisionAutoWarningCount >= 2) return;
        // Perl retains exact integer *literals* without issuing an NV warning
        // when they cross the IEEE-754 precision boundary. A computed integer
        // (including UV/IV boundary arithmetic) has no literal spelling and
        // must still take the NV probe below; core op/inc.t expects the two
        // warnings produced as it crosses that boundary.
        if (this.type == INTEGER && numericLiteralText != null) return;
        double numericValue = getDouble();
        if (!Double.isFinite(numericValue) || numericValue + delta != numericValue) return;
        BigInteger exact = getSignedBigint();
        WarnDie.warnWithCategory(new RuntimeScalar("Lost precision when "
                + (delta > 0 ? "incrementing " : "decrementing ") + exact),
                scalarEmptyString, "imprecision");
        imprecisionAutoWarningCount++;
    }

    public RuntimeScalar chop() {
        return StringOperators.chopScalar(this);
    }

    public RuntimeScalar chomp() {
        return StringOperators.chompScalar(this);
    }

    public RuntimeScalar pos() {
        return RuntimePosLvalue.pos(this);
    }

    /** Return {@code pos()} using the byte-oriented view selected by {@code use bytes}. */
    public RuntimeScalar posBytes() {
        return RuntimePosLvalue.pos(this, true);
    }

    /** Canonical scalar identity that owns match-position magic. */
    RuntimeScalar posStorage() {
        return this;
    }

    // keys() operator
    public RuntimeArray keys() {
        throw PerlCompilerException.atCurrentExecutionLocation(
                "Experimental keys on scalar is now forbidden at line 1.\n"
                + "Type of arg 1 to keys must be hash or array (not scalar) at line 1,");
    }

    // values() operator
    public RuntimeArray values() {
        throw PerlCompilerException.atCurrentExecutionLocation(
                "Experimental values on scalar is now forbidden at line 1.\n"
                + "Type of arg 1 to values must be hash or array (not scalar) at line 1,");
    }

    public RuntimeList each(int ctx) {
        throw PerlCompilerException.atCurrentExecutionLocation(
                "Experimental each on scalar is now forbidden at line 1.\n"
                + "Type of arg 1 to each must be hash or array (not scalar) at line 1,");
    }

    // Method to return an iterator
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeScalarIterator(this);
    }

    public RuntimeIO getRuntimeIO() {
        return RuntimeIO.getRuntimeIO(this);
    }

    /**
     * Saves the current state of the RuntimeScalar instance.
     *
     * <p>This method creates a snapshot of the current type and value of the scalar,
     * and pushes it onto a static stack for later restoration.
     */
    @Override
    public void dynamicSaveState() {
        RuntimeArray owner = localArrayOwner;
        int ownerIndex = localArrayIndex;
        boolean ownerSlot = owner != null && ownerIndex >= 0
                && ownerIndex < owner.elements.size()
                && owner.elements.get(ownerIndex) == this;
        ExecutionRuntimeState state = PerlRuntime.current().executionState();
        state.scalarLocalOwners.push(ownerSlot ? owner : null);
        state.scalarLocalOwnerIndices.push(ownerSlot ? ownerIndex : -1);
        state.scalarLocalOwnerSizes.push(ownerSlot ? owner.elements.size() : -1);
        state.scalarLocalOwnerExisted.push(ownerSlot);
        if (ownerSlot) owner.beginScalarLocalElement();
        // Create a new RuntimeScalar to save the current state
        RuntimeScalar currentState = new RuntimeScalar();
        // Copy the current type and value to the new state
        currentState.type = this.type;
        currentState.value = copyScalarPayload(this.type, this.value);
        currentState.blessId = this.blessId;
        currentState.scalarReferenceContentsOwner = this.scalarReferenceContentsOwner;
        if (currentState.scalarReferenceContentsOwner != null) {
            currentState.scalarReferenceContentsOwner.transferScalarReferenceContentsOwner(
                    currentState);
        }
        currentState.referencedByScalarReference = this.referencedByScalarReference;
        currentState.tainted = this.tainted;
        currentState.numericLiteralText = this.numericLiteralText;
        currentState.numericContextSeen = this.numericContextSeen;
        currentState.firstClassRegexScalar = this.firstClassRegexScalar;
        currentState.firstClassRegexReferent = this.firstClassRegexReferent;
        currentState.firstClassRegexValue = this.firstClassRegexValue;
        currentState.formatPictureTainted = this.formatPictureTainted;
        if (refCountOwned && countedOwnerSlot != null
                && countedOwnerSlot.isActive()
                && countedOwnerSlot.referent() == currentState.value) {
            // local() changes the value in this same scalar cell. Carry the
            // old counted edge with the saved state before a store can reuse
            // this scalar's slot for the localized replacement.
            currentState.localizedSavedCountedOwner = true;
            transferCountedOwnerTo(currentState);
        }
        if (untrackedReferenceOwnerSlot != null
                && untrackedReferenceOwnerSlot.isActive()
                && untrackedReferenceOwnerSlot.referent() == currentState.value) {
            transferUntrackedOwnerTo(currentState);
        }
        // Push the current state onto the stack
        dynamicStateStack().push(currentState);
        // Clear the current type and value
        this.type = UNDEF;
        this.value = null;
        this.blessId = 0;
        this.scalarReferenceContentsOwner = null;
        this.tainted = false;
        this.numericLiteralText = null;
        this.numericContextSeen = false;
        this.firstClassRegexScalar = false;
       this.firstClassRegexReferent = null;
       this.firstClassRegexValue = null;
        this.formatPictureTainted = false;
        refreshArgumentAliasOwnerSlots();
        refreshUntrackedReferenceOwner();
    }

    /**
     * Restores the most recently saved state of the RuntimeScalar instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the type and value to the current scalar. If no state is saved, it does nothing.
     */
    @Override
    public void dynamicRestoreState() {
        Stack<RuntimeScalar> dynamicStateStack = dynamicStateStack();
        if (!dynamicStateStack.isEmpty()) {
            // Pop the most recent saved state from the stack
            RuntimeScalar previousState = dynamicStateStack.pop();
            ExecutionRuntimeState executionState = PerlRuntime.current().executionState();
            RuntimeArray owner = executionState.scalarLocalOwners.pop();
            int ownerIndex = executionState.scalarLocalOwnerIndices.pop();
            int ownerSize = executionState.scalarLocalOwnerSizes.pop();
            boolean ownerExisted = executionState.scalarLocalOwnerExisted.pop();
            if (owner != null && ownerExisted) {
                for (int i = 0; i < owner.elements.size(); i++) {
                    if (i != ownerIndex && owner.elements.get(i) == this) {
                        // shift/unshift moved the localized SV. Detach the
                        // current localized value before restoring this SV.
                        RuntimeScalar movedValue = new RuntimeScalar(this);
                        if (refCountOwned && countedOwnerSlot != null
                                && countedOwnerSlot.isActive()
                                && countedOwnerSlot.referent() == value) {
                            transferCountedOwnerTo(movedValue);
                        }
                        if (untrackedReferenceOwnerSlot != null
                                && untrackedReferenceOwnerSlot.isActive()
                                && untrackedReferenceOwnerSlot.referent() == value) {
                            transferUntrackedOwnerTo(movedValue);
                        }
                        owner.elements.set(i, movedValue);
                        break;
                    }
                }
            }
            boolean referencedDuringLocal = this.referencedByScalarReference;

            RuntimeBase displacedBase = null;
            if (((this.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        || this.type == RuntimeScalarType.GLOB
                        || this.type == RuntimeScalarType.CODE)
                    && this.value instanceof RuntimeBase base) {
                displacedBase = base;
            }
            boolean releaseDisplacedCountedOwner = displacedBase != null && refCountOwned;
            if (releaseDisplacedCountedOwner
                    && (countedOwnerSlot == null || !countedOwnerSlot.isActive()
                        || countedOwnerSlot.referent() != displacedBase)) {
                throw new IllegalStateException(
                        "localized scalar lost its counted owner before restore");
            }
            if (releaseDisplacedCountedOwner) MortalList.pushMark();
            try {
                if (releaseDisplacedCountedOwner) {
                    MortalList.deferDecrementFromScalarSlot(
                            displacedBase, this, "RuntimeScalar.dynamicRestoreState");
                }
                PerlOwnerSlot displacedUntrackedOwner = this.untrackedReferenceOwnerSlot;
                PerlOwnerSlot displacedScalarReferenceContentsOwner =
                        this.scalarReferenceContentsOwner;

                // Restore the type, value from the saved state
                this.type = previousState.type;
                this.value = copyScalarPayload(previousState.type, previousState.value);
                this.refCountOwned = previousState.refCountOwned;
                if (previousState.refCountOwned && previousState.countedOwnerSlot != null) {
                    previousState.transferCountedOwnerTo(this);
                    previousState.localizedSavedCountedOwner = false;
                }
                previousState.transferUntrackedOwnerTo(this);
                this.blessId = previousState.blessId;
                this.scalarReferenceContentsOwner = previousState.scalarReferenceContentsOwner;
                previousState.scalarReferenceContentsOwner = null;
                if (this.scalarReferenceContentsOwner != null) {
                    this.scalarReferenceContentsOwner.transferScalarReferenceContentsOwner(this);
                }
                this.referencedByScalarReference =
                        previousState.referencedByScalarReference || referencedDuringLocal;
                this.tainted = previousState.tainted;
                this.numericLiteralText = previousState.numericLiteralText;
                this.numericContextSeen = previousState.numericContextSeen;
                this.firstClassRegexScalar = previousState.firstClassRegexScalar;
                this.firstClassRegexReferent = previousState.firstClassRegexReferent;
                this.firstClassRegexValue = previousState.firstClassRegexValue;
                this.formatPictureTainted = previousState.formatPictureTainted;
                refreshArgumentAliasOwnerSlots();
                refreshUntrackedReferenceOwner();
                if (owner != null) {
                    if (owner.scalarLocalContainerCleared()) {
                        while (owner.elements.size() < ownerSize) owner.elements.add(null);
                    }
                    if (ownerExisted && ownerIndex < owner.elements.size()
                            && owner.elements.get(ownerIndex) != this) {
                        // Array mutations such as shift move the localized SV;
                        // restore its original slot while retaining the moved
                        // localized value at its current position.
                        owner.elements.set(ownerIndex, this);
                    }
                    owner.endScalarLocalElement();
                }

                if (displacedScalarReferenceContentsOwner != null
                        && displacedScalarReferenceContentsOwner
                            != this.scalarReferenceContentsOwner) {
                    displacedScalarReferenceContentsOwner.releaseScalarReferenceContents();
                }
                if (displacedUntrackedOwner != null
                        && displacedUntrackedOwner != this.untrackedReferenceOwnerSlot) {
                    releaseDetachedUntrackedOwner(displacedUntrackedOwner);
                }

                if (releaseDisplacedCountedOwner) MortalList.flushAboveMark();
            } finally {
                if (releaseDisplacedCountedOwner) MortalList.popMark();
            }
        }
    }

    @Override
    public Object dynamicSuspendState() {
        RuntimeScalar activeState = new RuntimeScalar(this);
        if (refCountOwned && countedOwnerSlot != null
                && countedOwnerSlot.isActive()
                && countedOwnerSlot.referent() == value) {
            activeState.localizedSavedCountedOwner = true;
            transferCountedOwnerTo(activeState);
        }
        if (untrackedReferenceOwnerSlot != null
                && untrackedReferenceOwnerSlot.isActive()
                && untrackedReferenceOwnerSlot.referent() == value) {
            transferUntrackedOwnerTo(activeState);
        }
        activeState.scalarReferenceContentsOwner = this.scalarReferenceContentsOwner;
        if (activeState.scalarReferenceContentsOwner != null) {
            activeState.scalarReferenceContentsOwner.transferScalarReferenceContentsOwner(activeState);
        }
        this.scalarReferenceContentsOwner = null;
        dynamicRestoreState();
        return activeState;
    }

    @Override
    public void dynamicResumeState(Object token) {
        dynamicSaveState();
        if (token instanceof RuntimeScalar activeState) {
            this.type = activeState.type;
            this.value = copyScalarPayload(activeState.type, activeState.value);
            this.refCountOwned = activeState.refCountOwned;
            if (activeState.refCountOwned && activeState.countedOwnerSlot != null) {
                activeState.transferCountedOwnerTo(this);
                activeState.localizedSavedCountedOwner = false;
            }
            activeState.transferUntrackedOwnerTo(this);
            this.blessId = activeState.blessId;
            this.scalarReferenceContentsOwner = activeState.scalarReferenceContentsOwner;
            activeState.scalarReferenceContentsOwner = null;
            if (this.scalarReferenceContentsOwner != null) {
                this.scalarReferenceContentsOwner.transferScalarReferenceContentsOwner(this);
            }
            this.referencedByScalarReference = activeState.referencedByScalarReference;
            this.tainted = activeState.tainted;
            this.numericLiteralText = activeState.numericLiteralText;
            this.numericContextSeen = activeState.numericContextSeen;
            refreshArgumentAliasOwnerSlots();
            refreshUntrackedReferenceOwner();
        }
    }

    public static void scopeExitCleanupPreservingReturnedLvalue(RuntimeScalar scalar, RuntimeBase returnedLvalue) {
        if (scalar != null
                && scalar == returnedLvalue
                && scalar.type == RuntimeScalarType.TIED_SCALAR
                && scalar.value instanceof TiedVariableBase tiedVariable) {
            MortalList.deferTiedObjectRelease(tiedVariable);
            return;
        }

        // EmitBlock materializes anonymous-IO aliases only for list-like
        // implicit returns, so the returned list contains independent scalar
        // containers before this lexical owner is cleaned up.  Hand the owner
        // token to that container first; ordinary scopeExitCleanup then sees
        // that the lexical no longer owns the socket and cannot close it.
        // A scalar return deliberately stays on the historical path: it is
        // not materialized here and its final ownership transfer happens in
        // RuntimeCode's normal return coercion.
        if (returnedLvalue instanceof RuntimeList || returnedLvalue instanceof RuntimeArray) {
            releaseIoOwnerPreservingReturned(scalar, returnedLvalue);
        }
        scopeExitCleanup(scalar);
    }

    /**
     * Drop readline diagnostic state when an unaliased lexical handle leaves a
     * subroutine through the JVM return cleanup path.
     *
     * <p>This deliberately does not release IO ownership, change
     * {@link RuntimeGlob#ioHolderCount()}, unregister the descriptor, or close the
     * handle. The general return path may still have a returned or otherwise
     * copied scalar pointing at the anonymous glob; those aliases are reflected
     * by a holder count greater than the lexical owner's single holder.</p>
     */
    public static void clearStaleDiagnosticContextForUnaliasedIO(RuntimeScalar scalar) {
        if (scalar == null || !scalar.hasIoOwner() || scalar.type != GLOBREFERENCE
                || !(scalar.value instanceof RuntimeGlob glob)
                || !isUnstashedIoGlob(glob) || glob.ioHolderCount() > 1) {
            return;
        }
        RuntimeScalar ioSlot = glob.getIO();
        if (ioSlot != null && ioSlot.value instanceof RuntimeIO io
                && RuntimeIO.getLastAccessedHandle() == io) {
            RuntimeIO.setLastAccessedHandle(null);
            RuntimeIO.setLastReadlineHandleName(null);
        }
    }

    private static void cleanupScalarReferenceBinding(RuntimeScalar scalar) {
        if (scalar == null || !scalar.referencedByScalarReference || !scalar.localBindingExists) {
            return;
        }
        scalar.localBindingExists = false;
        if (scalar.refCount == 0 && !scalar.hasScalarCellOwner()
                && !scalar.hasUncountedPerlOwnerSlotIdentity()) {
            scalar.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroy(scalar);
        }
    }

    private static class RuntimeScalarIterator implements Iterator<RuntimeScalar> {
        private final RuntimeScalar scalar;
        private boolean hasNext = true;

        public RuntimeScalarIterator(RuntimeScalar scalar) {
            this.scalar = scalar;
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public RuntimeScalar next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            hasNext = false;
            return scalar;
        }

        public void reset() {
            hasNext = true;
        }
    }
}
