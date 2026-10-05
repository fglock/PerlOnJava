package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.lang.ref.WeakReference;

/**
 * Phase 4 (refcount_alignment_plan.md): On-demand reachability walker.
 * <p>
 * Walks the live object graph from Perl-visible roots and identifies which
 * objects in the weak-ref registry are unreachable. Clears weak refs for
 * those objects, simulating Perl 5's refcount-based collection when
 * PerlOnJava's selective refCount has drifted due to JVM temporaries.
 * <p>
 * Roots:
 * <ul>
 *   <li>{@link GlobalVariable#globalVariables} — package scalars ($pkg::name)</li>
 *   <li>{@link GlobalVariable#globalArrays} — package arrays (@pkg::name)</li>
 *   <li>{@link GlobalVariable#globalHashes} — package hashes (%pkg::name)</li>
 *   <li>{@link GlobalVariable#globalCodeRefs} — package subs</li>
 *   <li>Rescued objects from {@link DestroyDispatch}</li>
 * </ul>
 * <p>
 * Not yet walked (TODO):
 * <ul>
 *   <li>Live lexicals in the call stack — JVM doesn't easily expose these.
 *       Mitigated by assuming a short-lived sweep runs during/after Perl code
 *       completes a unit of work (e.g., every N flushes).</li>
 *   <li>Closures that capture lexicals — we walk them via their CODE refs but
 *       not into the captured variables directly.</li>
 * </ul>
 */
public class ReachabilityWalker {

    private Set<RuntimeBase> weakWitnessTargets;
    private final IdentityHashMap<RuntimeBase, RuntimeCode> weakWitnessCaptureRoots =
            new IdentityHashMap<>();
    private final IdentityHashMap<RuntimeBase, WeakRootWitness> discoveredWeakRootWitnesses =
            new IdentityHashMap<>();
    private int discoveredLexicalCodeWitnesses;

    record WeakRootWitness(WeakReference<RuntimeCode> rootCode,
                           WeakReference<RuntimeBase> capturedAggregate,
                           WeakReference<RuntimeScalar> ownerScalar,
                           WeakReference<RuntimeBase> referent,
                           boolean liveLexicalScalar) {}

    private record ReflectiveCaptureFields(
            java.lang.reflect.Field[] scalars,
            java.lang.reflect.Field[] bases) {}

    // Generated closure implementations have a stable class layout. Cache
    // the captured scalar/base fields per class so repeated weak-reference
    // sweeps do not redo reflective discovery on the callback path.
    private static final ClassValue<ReflectiveCaptureFields> REFLECTIVE_CAPTURE_FIELDS =
            new ClassValue<>() {
                @Override
                protected ReflectiveCaptureFields computeValue(Class<?> type) {
                    java.util.ArrayList<java.lang.reflect.Field> scalars = new java.util.ArrayList<>();
                    java.util.ArrayList<java.lang.reflect.Field> bases = new java.util.ArrayList<>();
                    for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                        Class<?> fieldType = field.getType();
                        if (fieldType == RuntimeScalar.class
                                && !"__SUB__".equals(field.getName())) {
                            scalars.add(field);
                        } else if (fieldType != RuntimeScalar.class
                                && RuntimeBase.class.isAssignableFrom(fieldType)) {
                            bases.add(field);
                        }
                    }
                    return new ReflectiveCaptureFields(
                            scalars.toArray(java.lang.reflect.Field[]::new),
                            bases.toArray(java.lang.reflect.Field[]::new));
                }
            };

    // Re-use the weak-ref registry's internal map (we add a getter)
    private final Set<RuntimeBase> reachable =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>(512));

    // Whether to follow RuntimeCode.capturedScalars edges. Off by default
    // because Sub::Quote/Moo-generated accessors over-capture instances,
    // which would mark DBIC Schema/ResultSource instances as reachable
    // even after they should be GC'd. Native Perl doesn't hit this pitfall
    // because its refcount already tracks the captures accurately.
    private boolean walkCodeCaptures = false;

    // Whether to seed active Perl lexicals. The live-variable table supplies
    // these roots directly; scanning ScalarRefRegistry as well would duplicate
    // the same live scalar slots.
    private boolean useLexicalSeeds = true;

    // Ordinary sweeps must retain values that are still in expression
    // temporaries. A targeted sweep requested by destruction of that very
    // temporary must be able to exclude those stale JVM register roots: Perl's
    // FREETMPS has already released them at the statement boundary.
    private boolean useTemporaryRoots = true;

    /** Enable walking closures' captured scalars. */
    public ReachabilityWalker withCodeCaptures(boolean v) {
        this.walkCodeCaptures = v;
        return this;
    }

    /** Disable live-lexical roots (globals-only walk). */
    public ReachabilityWalker withLexicalSeeds(boolean v) {
        this.useLexicalSeeds = v;
        return this;
    }

    public ReachabilityWalker withTemporaryRoots(boolean v) {
        this.useTemporaryRoots = v;
        return this;
    }

    ReachabilityWalker withWeakWitnessTargets(Set<RuntimeBase> targets) {
        this.weakWitnessTargets = targets;
        return this;
    }

    private static boolean isNonOwningDebugArgsArray(String name) {
        // @DB::args aliases caller arguments for debugger/Carp introspection.
        // It is not an owning root after the source frame has unwound; live
        // frames are already represented by RuntimeCode.snapshotArgsStack().
        return "DB::args".equals(name);
    }

    /**
     * Walk from Perl-visible roots and mark reachable objects.
     * <p>
     * Phase I (refcount_alignment_52leaks_plan.md): Two-phase walk.
     * <ol>
     *   <li>Phase 1: seed from {@code globalCodeRefs}, BFS WITH closure-
     *       capture walking. Stash-installed closures (Sub::Defer
     *       deferred subs, Moo/Sub::Quote accessors) capture lexicals
     *       that represent real live-data paths (e.g.
     *       {@code $deferred_info} ARRAY, {@code $quoted_info} HASH,
     *       {@code $unquoted} scalar slot). Following captures here
     *       ensures Sub::Defer's %DEFERRED / Sub::Quote's %QUOTED
     *       entries are seen as reachable.</li>
     *   <li>Phase 2: seed remaining roots (globalVariables,
     *       globalArrays, globalHashes, rescuedObjects, lexical seeds),
     *       BFS without capture walking by default. Anon closures held
     *       by instance hashes (DBIC handler callbacks) stay opaque
     *       so instances captured only by them can be marked
     *       unreachable — letting 52leaks detect real Schema leaks.</li>
     * </ol>
     *
     * @return the set of reachable RuntimeBase instances
     */
    public Set<RuntimeBase> walk() {
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        // Phase 1: seed globalCodeRefs, walk WITH captures.
        for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefEdgeRootsView()) {
            addGlobalCodeRoot(codeRef, todo);
        }
        // An executing anonymous closure is a live Perl root even when no
        // package/global slot refers to its CODE value. Statement-boundary
        // sweeps run inside RuntimeCode.apply(); follow that active closure's
        // captures before deciding that a blessed referent is unreachable.
        // Test2::AsyncSubtest exposes this with a weak hub back-reference: the
        // child entry closure is the sole strong owner of $self while it runs.
        for (RuntimeCode active : new ArrayList<>(
                PerlRuntime.current().executionState().activeCodeStack)) {
            addReachable(active, todo);
        }
        // Active call arguments are live even after a callee shifts @_. Walk
        // them with captures enabled: an in-flight Future commonly owns a
        // callback closure whose captured slot array owns its child Futures.
        // This root disappears as soon as the call frame returns.
        for (java.util.List<RuntimeScalar> args : RuntimeCode.snapshotPristineArgsStack()) {
            for (RuntimeScalar arg : args) {
                if (arg == null || WeakRefRegistry.isweak(arg)) continue;
                addReachable(arg, todo);
                visitScalar(arg, todo);
            }
        }
        bfs(todo, /*walkCaptures=*/ true);

        // Phase 2: seed remaining roots.
        for (RuntimeScalar scalar : GlobalVariable.globalVariableValuesView()) {
            visitScalar(scalar, todo);
        }
        for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrayEntriesView()) {
            if (isNonOwningDebugArgsArray(e.getKey())) continue;
            addReachable(e.getValue(), todo);
        }
        for (RuntimeHash hash : GlobalVariable.globalHashValuesView()) {
            addReachable(hash, todo);
        }
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForWalk()) {
            addReachable(rescued, todo);
        }
        for (RuntimeBase suspended : MortalList.snapshotSuspendedRoots()) {
            addReachable(suspended, todo);
        }
        if (useLexicalSeeds) {
            // Phase D-W1 (walker_gate_dbic_minimal.t): seed from live
            // my-vars themselves (RuntimeArray / RuntimeHash that the
            // user declared with `my @arr` / `my %hash`). Without this,
            // the auto-sweep's reachability check misses top-level
            // arrays/hashes — their elements end up flagged unreachable
            // and DESTROY fires on still-held blessed objects.
            //
            // Mirrors the seeding already in `isReachableFromRoots()`.
            // Order matters: RuntimeScalar IS-A RuntimeBase, so the
            // RuntimeScalar branch must come first to walk through its
            // reference bit. Otherwise the BFS only steps into hashes
            // and arrays, missing the scalar's referent. The previous
            // ScalarRefRegistry seed was redundant: every scalar accepted
            // by its MyVarCleanupStack.isLive check is present in this list.
            // Use this one live-slot snapshot to avoid copying and rescanning
            // the ref-scalar registry during every complete weak sweep.
            for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
                if (liveVar instanceof RuntimeScalar sc) {
                    if (WeakRefRegistry.isweak(sc)) continue;
                    recordWeakLiveLexicalScalarWitness(sc);
                    addReachable(sc, todo);
                    visitScalar(sc, todo);
                } else if (liveVar instanceof RuntimeBase rb) {
                    addReachable(rb, todo);
                }
            }
            for (RuntimeArray args : RuntimeCode.snapshotArgsStack()) {
                addReachable(args, todo);
            }
            if (useTemporaryRoots) {
                for (RuntimeBase tempRoot : MortalList.snapshotTemporaryRoots()) {
                    addReachable(tempRoot, todo);
                }
            }
        }

        bfs(todo, walkCodeCaptures);

        return withInstalledGlobalCodeRoots();
    }

    /**
     * Return the graph that must remain live while END blocks execute.
     *
     * Package CODE slots and queued END blocks are the only seeds. Captures
     * are followed from both: they are real Perl ownership paths at this
     * lifecycle boundary, unlike the conservative lexical seeds used by the
     * general weak-reference sweep.
     */
    static Set<RuntimeBase> walkEndBlockRoots() {
        ReachabilityWalker walker = new ReachabilityWalker();
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefValuesView()) {
            if (codeRef == null || !(codeRef.value instanceof RuntimeCode code)) continue;

            // The registry also contains anonymous/eval compilation artifacts.
            // Those are implementation caches, not Perl package CODE roots;
            // following them would retain every lexical conservatively captured
            // for eval STRING. A genuinely callable package sub has a stable
            // package/sub name and remains a valid path for END to invoke.
            if (code.subName == null || code.subName.isEmpty()
                    || code.subName.equals("__ANON__")
                    || code.packageName == null || code.packageName.isEmpty()
                    || code.packageName.startsWith("(eval")
                    || (code.cvStartFile != null && code.cvStartFile.startsWith("(eval"))) {
                continue;
            }
            walker.visitScalar(codeRef, todo);
        }
        walker.addReachable(SpecialBlock.getEndBlocks(), todo);
        walker.bfsEndBlockRoots(todo);
        return walker.reachable;
    }

    /**
     * Traverse only semantic closure edges for END lifetime decisions.
     * RuntimeCode.capturedScalars and reflective capture arrays also contain
     * transient eval STRING captures attached while named code executes; those
     * implementation edges must not extend a file lexical's Perl lifetime.
     * closedOverVariables is populated from the closure's explicit lexical
     * environment by both generated and interpreted backends.
     */
    private void bfsEndBlockRoots(java.util.ArrayDeque<RuntimeBase> todo) {
        while (!todo.isEmpty()) {
            RuntimeBase cur = todo.removeFirst();
            if (cur instanceof RuntimeHash hash) {
                if (hash.elements instanceof HashSpecialVariable) continue;
                for (RuntimeScalar value : hash.elements.values()) {
                    addReachable(value, todo);
                    visitScalar(value, todo);
                }
            } else if (cur instanceof RuntimeArray array) {
                for (RuntimeScalar value : array.elements) {
                    addReachable(value, todo);
                    visitScalar(value, todo);
                }
            } else if (cur instanceof RuntimeCode code) {
                visitCodePadConstants(code, todo);
                visitCodeStateVariables(code, todo);
                if (code.closedOverVariables != null) {
                    for (RuntimeBase captured : code.closedOverVariables.values()) {
                        addReachable(captured, todo);
                        if (captured instanceof RuntimeScalar scalar) {
                            visitScalar(scalar, todo);
                        }
                    }
                }
            } else if (cur instanceof RuntimeScalar scalar) {
                visitScalar(scalar, todo);
            }
        }
    }

    private void bfs(java.util.ArrayDeque<RuntimeBase> todo, boolean walkCaptures) {
        while (!todo.isEmpty()) {
            RuntimeBase cur = todo.removeFirst();
            // Phase D-W2 (perf): skip RuntimeStash. A stash's `elements`
            // is a HashSpecialVariable that eagerly copies all global
            // keys via entrySet() — O(globals) per visit, quadratic
            // in number of packages × per-flush gate fires.
            // Stash entries (the per-package code/var/array/hash) are
            // already directly seeded from GlobalVariable.global*Refs,
            // so iterating them here is redundant work.
            if (cur instanceof RuntimeStash) {
                continue;
            }
            if (cur instanceof RuntimeHash h) {
                if (h.elements instanceof HashSpecialVariable) {
                    continue;
                }
                if (h.elements instanceof TieHash tieHash) {
                    visitScalar(tieHash.getSelf(), todo);
                }
                for (RuntimeScalar v : h.elements.values()) {
                    addReachable(v, todo);
                    visitScalar(v, todo);
                }
            } else if (cur instanceof RuntimeArray a) {
                if (a.elements instanceof TieArray tieArray) {
                    visitScalar(tieArray.getSelf(), todo);
                }
                for (RuntimeScalar v : a.elements) {
                    recordWeakArraySlotWitness(a, v);
                    addReachable(v, todo);
                    visitScalar(v, todo);
                }
            } else if (cur instanceof RuntimeCode code) {
                if (!hasWalkableCodeEdges(code, walkCaptures)) {
                    continue;
                }
                visitCodePadConstants(code, todo);
                visitCodeStateVariables(code, todo);
                // Phase 2 normally keeps closure captures opaque to avoid
                // over-rescuing DBIC objects through internal callbacks.
                // Exception: Sub::Defer/Sub::Quote deferred wrappers keep a
                // weak backref to the CODE object itself. If such a CODE ref
                // is reachable through package metadata (not a direct stash
                // entry), its captured info array is a real strong Perl edge
                // and must survive the weak sweep.
                if (walkCaptures || WeakRefRegistry.hasWeakRefsTo(code)) {
                    visitCodeCaptures(code, todo);
                }
            } else if (cur instanceof RuntimeScalar s) {
                visitScalar(s, todo);
            }
        }
    }

    private void visitCodePadConstants(RuntimeCode code,
                                       java.util.ArrayDeque<RuntimeBase> todo) {
        if (code.padConstants == null) return;
        for (RuntimeBase constant : code.padConstants) {
            addReachable(constant, todo);
        }
    }

    /**
     * Global CODE slots themselves are roots, but most named subs have no
     * object-valued pad, state, or capture edges. Keep those CODE refs in the
     * returned reachable set without sending them through the BFS queue.
     */
    private void addGlobalCodeRoot(RuntimeScalar slot, java.util.ArrayDeque<RuntimeBase> todo) {
        if (slot != null
                && (slot.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && slot.value instanceof RuntimeCode code) {
            if (hasWalkableCodeEdges(code, /*walkCaptures=*/ true) && reachable.add(code)) {
                todo.addLast(code);
            }
            return;
        }
        visitScalar(slot, todo);
    }

    static boolean hasWalkableCodeEdges(RuntimeCode code, boolean walkCaptures) {
        if ((code.padConstants != null && code.padConstants.length != 0)
                || !code.stateVariable.isEmpty()
                || !code.stateArray.isEmpty()
                || !code.stateHash.isEmpty()) {
            return true;
        }
        if (!walkCaptures && !WeakRefRegistry.hasWeakRefsTo(code)) return false;
        if ((code.capturedScalars != null && code.capturedScalars.length != 0)
                || (code.capturedAggregates != null && code.capturedAggregates.length != 0)
                || (code.closedOverVariables != null && !code.closedOverVariables.isEmpty())) {
            return true;
        }
        if (!code.captureFieldsRecorded) {
            Object closureObject = code.codeObject != null ? code.codeObject : code.subroutine;
            if (closureObject != null) {
                ReflectiveCaptureFields fields = REFLECTIVE_CAPTURE_FIELDS.get(closureObject.getClass());
                if (fields.scalars().length != 0 || fields.bases().length != 0) return true;
            }
        }
        return code instanceof org.perlonjava.backend.bytecode.InterpretedCode interpreted
                && interpreted.capturedVars != null
                && interpreted.capturedVars.length != 0;
    }

    /**
     * Terminal installed CODE refs remain roots, but storing each one in the
     * per-sweep identity set is redundant. Expose them through a union view so
     * ordinary reachability checks still see the exact complete root set.
     */
    private Set<RuntimeBase> withInstalledGlobalCodeRoots() {
        Set<RuntimeCode> installedCodes = GlobalVariable.installedGlobalCodeRefsView();
        if (installedCodes.isEmpty()) return reachable;
        return new AbstractSet<>() {
            @Override
            public boolean contains(Object value) {
                return reachable.contains(value)
                        || (value instanceof RuntimeCode code && installedCodes.contains(code));
            }

            @Override
            public int size() {
                int size = reachable.size();
                for (RuntimeCode code : installedCodes) {
                    if (!reachable.contains(code)) size++;
                }
                return size;
            }

            @Override
            public Iterator<RuntimeBase> iterator() {
                Iterator<RuntimeBase> reached = reachable.iterator();
                Iterator<RuntimeCode> installed = installedCodes.iterator();
                return new Iterator<>() {
                    private RuntimeBase next;
                    private boolean hasNext;

                    @Override
                    public boolean hasNext() {
                        if (hasNext) return true;
                        if (reached.hasNext()) return true;
                        while (installed.hasNext()) {
                            RuntimeCode candidate = installed.next();
                            if (!reachable.contains(candidate)) {
                                next = candidate;
                                hasNext = true;
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override
                    public RuntimeBase next() {
                        if (reached.hasNext()) return reached.next();
                        if (!hasNext()) throw new NoSuchElementException();
                        RuntimeBase result = next;
                        next = null;
                        hasNext = false;
                        return result;
                    }
                };
            }
        };
    }

    /**
     * Follow persistent {@code state} storage owned by a subroutine.
     *
     * <p>State variables are semantic strong Perl roots for as long as their
     * owning CODE remains installed. They are not closure-capture metadata,
     * so they must be traversed even when capture walking is deliberately
     * disabled. Omitting these edges lets a weak-reference sweep destroy a
     * still-live state singleton, as used by Mojo::IOLoop.</p>
     */
    private void visitCodeStateVariables(RuntimeCode code,
                                         java.util.ArrayDeque<RuntimeBase> todo) {
        for (RuntimeScalar state : code.stateVariable.values()) {
            addReachable(state, todo);
            visitScalar(state, todo);
        }
        for (RuntimeArray state : code.stateArray.values()) {
            addReachable(state, todo);
        }
        for (RuntimeHash state : code.stateHash.values()) {
            addReachable(state, todo);
        }
    }

    private void visitCodeCaptures(RuntimeCode code,
                                   java.util.ArrayDeque<RuntimeBase> todo) {
        if (code.capturedScalars != null) {
            for (RuntimeScalar cap : code.capturedScalars) {
                addReachable(cap, todo);
                visitScalar(cap, todo);
            }
        }
        if (code.capturedAggregates != null) {
            for (RuntimeBase cap : code.capturedAggregates) addReachable(cap, todo);
        }
        visitReflectiveCodeScalars(code, cap -> {
            addReachable(cap, todo);
            visitScalar(cap, todo);
        });
        visitReflectiveCodeBases(code, base -> {
            if (weakWitnessTargets != null
                    && code.captureFieldsRecorded
                    && GlobalVariable.installedGlobalCodeRefsView().contains(code)
                    && base instanceof RuntimeArray array
                    && isSupportedWitnessArray(array)) {
                weakWitnessCaptureRoots.putIfAbsent(array, code);
            }
            addReachable(base, todo);
        });
        // InterpretedCode.capturedVars is immutable execution-frame metadata.
        // Captured entries can remain there after their lexical lifetime has
        // ended and releaseCaptures has removed them from the semantic owner
        // lists. Only capturedScalars/capturedAggregates describe live owners.
    }

    private void recordWeakArraySlotWitness(RuntimeArray array, RuntimeScalar ownerScalar) {
        if (weakWitnessTargets == null || ownerScalar == null
                || !isSupportedWitnessArray(array)
                || WeakRefRegistry.isweak(ownerScalar)
                || (ownerScalar.type & RuntimeScalarType.REFERENCE_BIT) == 0
                || !(ownerScalar.value instanceof RuntimeBase referent)
                || !weakWitnessTargets.contains(referent)) {
            return;
        }
        RuntimeCode rootCode = weakWitnessCaptureRoots.get(array);
        if (rootCode != null) {
            discoveredWeakRootWitnesses.putIfAbsent(referent,
                    new WeakRootWitness(new WeakReference<>(rootCode),
                            new WeakReference<>(array), new WeakReference<>(ownerScalar),
                            new WeakReference<>(referent), false));
        }
    }

    private void recordWeakLiveLexicalScalarWitness(RuntimeScalar ownerScalar) {
        if (weakWitnessTargets == null || ownerScalar.scopeExited
                || (ownerScalar.type & RuntimeScalarType.REFERENCE_BIT) == 0
                || !(ownerScalar.value instanceof RuntimeBase referent)
                || (!weakWitnessTargets.contains(referent)
                && !(referent instanceof RuntimeCode))) {
            return;
        }
        if (!weakWitnessTargets.contains(referent)
                && !discoveredWeakRootWitnesses.containsKey(referent)
                && discoveredLexicalCodeWitnesses >= 128) {
            return;
        }
        if (discoveredWeakRootWitnesses.putIfAbsent(referent,
                new WeakRootWitness(new WeakReference<>(null),
                        new WeakReference<>(null), new WeakReference<>(ownerScalar),
                        new WeakReference<>(referent), true)) == null
                && referent instanceof RuntimeCode
                && !weakWitnessTargets.contains(referent)) {
            discoveredLexicalCodeWitnesses++;
        }
    }

    private static boolean isSupportedWitnessArray(RuntimeArray array) {
        return array != null
                && !array.threadShared
                && array.type != RuntimeArray.TIED_ARRAY
                && !(array.elements instanceof TieArray);
    }

    /**
     * Diagnostic: walk from roots and return the first path found to the
     * specified target object. Returns null if unreachable. Used for
     * debugging DBIC 52leaks-style issues where an object that should be
     * collectible is found reachable.
     * <p>
     * When {@code skipLexicalSeeds} is true, omits the ScalarRefRegistry
     * seed loop so the path is forced through Perl-semantic roots
     * (globals, stashes, rescued objects) — useful for understanding
     * what data structure keeps an object alive at the Perl level.
     */
    public static java.util.List<String> findPathTo(RuntimeBase target) {
        return findPathTo(target, false);
    }

    public static java.util.List<String> findPathTo(RuntimeBase target, boolean skipLexicalSeeds) {
        java.util.IdentityHashMap<RuntimeBase, String> howReached = new java.util.IdentityHashMap<>();
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        // Seed from roots with labels
        for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalVariables.entrySet()) {
            seedPath(e.getValue(), "$" + e.getKey(), howReached, todo);
        }
        for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrays.entrySet()) {
            if (isNonOwningDebugArgsArray(e.getKey())) continue;
            if (howReached.putIfAbsent(e.getValue(), "@" + e.getKey()) == null) todo.add(e.getValue());
        }
        for (Map.Entry<String, RuntimeHash> e : GlobalVariable.globalHashes.entrySet()) {
            if (howReached.putIfAbsent(e.getValue(), "%" + e.getKey()) == null) todo.add(e.getValue());
        }
        for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalCodeRefs.entrySet()) {
            seedPath(e.getValue(), "&" + e.getKey(), howReached, todo);
        }
        int rescuedIdx = 0;
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForWalk()) {
            if (howReached.putIfAbsent(rescued, "<rescued#" + (rescuedIdx++) + ">") == null) {
                todo.add(rescued);
            }
        }
        // Phase I: seed from WarningBitsRegistry.callerHintHashStack —
        // %^H snapshots can preserve scalars from earlier scopes and are
        // NOT accounted for by Perl-level walker roots.
        int hhIdx = 0;
        for (RuntimeScalar sc : org.perlonjava.runtime.WarningBitsRegistry.snapshotHintHashStackScalars()) {
            seedPath(sc, "<hint-hash#" + (hhIdx++) + ">", howReached, todo);
        }
        // Phase B1: seed from ScalarRefRegistry (same as walk()) so the
        // trace matches what sweepWeakRefs sees.
        // Phase I: force GC before snapshotting so stale
        // (already-Java-unreachable) entries don't produce misleading
        // "live-lexical" paths in diagnostic traces.
        // skipLexicalSeeds=true omits this — produces a path that goes
        // through Perl-semantic data (globals/stash/rescued) only.
        int scIdx = 0;
        if (!skipLexicalSeeds) {
            for (RuntimeScalar sc : ScalarRefRegistry.forceGcAndSnapshot()) {
                if (sc == null) continue;
                if (sc.captureCount > 0) continue;
                if (WeakRefRegistry.isweak(sc)) continue;
                if (MortalList.isDeferredCapture(sc)) continue;
                if (!MyVarCleanupStack.isLive(sc)) continue;
                if ((sc.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        && sc.value instanceof RuntimeBase b) {
                    String label = "<live-lexical#" + (scIdx++)
                            + " scId=" + System.identityHashCode(sc)
                            + " type=" + sc.type
                            + " rcO=" + sc.refCountOwned + ">";
                    if (howReached.putIfAbsent(b, label) == null) {
                        todo.add(b);
                    }
                }
            }
            int liveVarIdx = 0;
            for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
                String label = "<my-var#" + (liveVarIdx++)
                        + " id=" + System.identityHashCode(liveVar)
                        + " type=" + liveVar.getClass().getSimpleName() + ">";
                if (liveVar instanceof RuntimeScalar sc) {
                    seedPath(sc, label, howReached, todo);
                } else if (liveVar instanceof RuntimeBase rb) {
                    if (howReached.putIfAbsent(rb, label) == null) todo.add(rb);
                }
            }
            int argsIdx = 0;
            for (RuntimeArray args : RuntimeCode.snapshotArgsStack()) {
                if (howReached.putIfAbsent(args, "<args#" + (argsIdx++) + ">") == null) {
                    todo.add(args);
                }
            }
            int tempIdx = 0;
            for (RuntimeBase tempRoot : MortalList.snapshotTemporaryRoots()) {
                if (howReached.putIfAbsent(tempRoot, "<temp-root#" + (tempIdx++) + ">") == null) {
                    todo.add(tempRoot);
                }
            }
        }
        while (!todo.isEmpty()) {
            RuntimeBase cur = todo.removeFirst();
            String curPath = howReached.get(cur);
            if (cur == target) {
                java.util.List<String> r = new java.util.ArrayList<>();
                r.add(curPath);
                return r;
            }
            if (cur instanceof RuntimeHash h) {
                for (Map.Entry<String, RuntimeScalar> ent : h.elements.entrySet()) {
                    visitScalarPath(ent.getValue(), curPath + "{" + ent.getKey() + "}", howReached, todo);
                }
            } else if (cur instanceof RuntimeArray a) {
                int idx = 0;
                for (RuntimeScalar v : a.elements) {
                    visitScalarPath(v, curPath + "[" + (idx++) + "]", howReached, todo);
                }
            } else if (cur instanceof RuntimeCode code) {
                int stateIdx = 0;
                for (Map.Entry<String, RuntimeScalar> state : code.stateVariable.entrySet()) {
                    visitScalarPath(state.getValue(), curPath + "<state " + state.getKey()
                            + "#" + (stateIdx++) + ">", howReached, todo);
                }
                for (Map.Entry<String, RuntimeArray> state : code.stateArray.entrySet()) {
                    String path = curPath + "<state " + state.getKey() + ">";
                    if (howReached.putIfAbsent(state.getValue(), path) == null) todo.add(state.getValue());
                }
                for (Map.Entry<String, RuntimeHash> state : code.stateHash.entrySet()) {
                    String path = curPath + "<state " + state.getKey() + ">";
                    if (howReached.putIfAbsent(state.getValue(), path) == null) todo.add(state.getValue());
                }
                // Phase I: mirror the main walker — follow closure captures
                // so findPathTo traces through the same graph as sweepWeakRefs.
                if (code.capturedScalars != null) {
                    int i = 0;
                    String name = code.packageName == null ? "?" : code.packageName;
                    String sub = code.subName == null ? "(anon)" : code.subName;
                    for (RuntimeScalar cap : code.capturedScalars) {
                        visitScalarPath(cap, curPath + "<closure " + name + "::" + sub + " cap#" + (i++) + ">", howReached, todo);
                    }
                }
                if (code.capturedAggregates != null) {
                    int i = 0;
                    for (RuntimeBase cap : code.capturedAggregates) {
                        String path = curPath + "<closure aggregate cap#" + (i++) + ">";
                        if (howReached.putIfAbsent(cap, path) == null) todo.add(cap);
                    }
                }
                String name = code.packageName == null ? "?" : code.packageName;
                String sub = code.subName == null ? "(anon)" : code.subName;
                final int[] reflectiveIdx = {0};
                visitReflectiveCodeScalars(code, cap ->
                        visitScalarPath(cap, curPath + "<closure " + name + "::" + sub
                                + " field-cap#" + (reflectiveIdx[0]++) + ">", howReached, todo));
            }
        }
        return null;
    }

    private static void seedPath(RuntimeScalar s, String label,
                                  java.util.IdentityHashMap<RuntimeBase, String> howReached,
                                  java.util.ArrayDeque<RuntimeBase> todo) {
        if (s == null) return;
        if (WeakRefRegistry.isweak(s)) return;
        if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && s.value instanceof RuntimeBase b) {
            if (howReached.putIfAbsent(b, label) == null) todo.add(b);
        }
    }

    private static void visitScalarPath(RuntimeScalar s, String path,
                                         java.util.IdentityHashMap<RuntimeBase, String> howReached,
                                         java.util.ArrayDeque<RuntimeBase> todo) {
        if (s == null) return;
        if (WeakRefRegistry.isweak(s)) return;
        if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && s.value instanceof RuntimeBase b) {
            if (howReached.putIfAbsent(b, path) == null) todo.add(b);
        }
    }

    private void visitScalar(RuntimeScalar s, java.util.ArrayDeque<RuntimeBase> todo) {
        if (s == null) return;
        // Weak refs are not counted as strong edges in reachability
        if (WeakRefRegistry.isweak(s)) return;
        if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && s.value instanceof RuntimeBase b) {
            addReachable(b, todo);
        }
    }

    private void addReachable(RuntimeBase b, java.util.ArrayDeque<RuntimeBase> todo) {
        if (b == null) return;
        if (reachable.add(b)) {
            todo.addLast(b);
        }
    }

    /**
     * Return true if {@code target} can reach itself through strong Perl
     * references. Weak scalars are ignored. This models Perl's refcount
     * behavior for self-cycles: they remain alive even when no package/global
     * root points at them.
     */
    public static boolean hasStrongCycle(RuntimeBase target) {
        if (target == null) return false;
        final int MAX_VISITS = 50_000;
        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        if (enqueueStrongEdges(target, target, seen, todo)) {
            return true;
        }

        int visits = 0;
        while (!todo.isEmpty() && visits < MAX_VISITS) {
            RuntimeBase cur = todo.removeFirst();
            visits++;
            if (enqueueStrongEdges(cur, target, seen, todo)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return whether {@code target} is strongly reachable from an explicit
     * set of roots. Runtime snapshots use this before the child entry CODE is
     * installed on an execution stack: its captures are already real Perl
     * owners, but the ordinary live-root queries cannot see them yet.
     */
    static boolean isReachableFromStrongRoots(
            RuntimeBase target, java.util.List<? extends RuntimeBase> roots) {
        if (target == null || roots == null || roots.isEmpty()) return false;
        final int MAX_VISITS = 50_000;
        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        for (RuntimeBase root : roots) {
            if (root == null) continue;
            if (root == target) return true;
            if (seen.add(root)) todo.addLast(root);
        }
        int visits = 0;
        while (!todo.isEmpty() && visits++ < MAX_VISITS) {
            if (enqueueStrongEdges(todo.removeFirst(), target, seen, todo)) {
                return true;
            }
        }
        return false;
    }

    private static boolean enqueueStrongEdges(RuntimeBase cur, RuntimeBase target,
                                              Set<RuntimeBase> seen,
                                              java.util.ArrayDeque<RuntimeBase> todo) {
        if (cur instanceof RuntimeHash h) {
            // Shared hashes are backed by Collections.synchronizedMap. Its
            // iterators still require the caller to hold the map monitor;
            // otherwise a concurrently running Perl thread can invalidate a
            // destruction-time reachability walk.
            java.util.List<RuntimeScalar> values;
            if (h.threadShared) {
                synchronized (h.elements) {
                    values = new ArrayList<>(h.elements.values());
                }
            } else {
                values = h.elements.values().stream().toList();
            }
            for (RuntimeScalar v : values) {
                if (enqueueStrongScalar(v, target, seen, todo)) return true;
            }
        } else if (cur instanceof RuntimeArray a) {
            // See the hash case: iterating a synchronized list without
            // taking its monitor is explicitly unsafe.
            java.util.List<RuntimeScalar> values;
            if (a.threadShared) {
                synchronized (a.elements) {
                    values = new ArrayList<>(a.elements);
                }
            } else {
                values = a.elements;
            }
            for (RuntimeScalar v : values) {
                if (enqueueStrongScalar(v, target, seen, todo)) return true;
            }
        } else if (cur instanceof RuntimeCode code) {
            for (RuntimeScalar state : code.stateVariable.values()) {
                if (enqueueStrongScalar(state, target, seen, todo)) return true;
            }
            for (RuntimeArray state : code.stateArray.values()) {
                if (state == target) return true;
                if (seen.add(state)) todo.addLast(state);
            }
            for (RuntimeHash state : code.stateHash.values()) {
                if (state == target) return true;
                if (seen.add(state)) todo.addLast(state);
            }
            if (code.capturedScalars != null) {
                for (RuntimeScalar cap : code.capturedScalars) {
                    if (enqueueStrongScalar(cap, target, seen, todo)) return true;
                }
            }
            if (code.capturedAggregates != null) {
                for (RuntimeBase cap : code.capturedAggregates) {
                    if (cap == null) continue;
                    if (cap == target) return true;
                    if (seen.add(cap)) todo.addLast(cap);
                }
            }
            // Do not traverse InterpretedCode.capturedVars here. It is retained
            // for execution after capture owners have been retired; walking it
            // would turn dead pad metadata into a strong Perl reference.
            Object closureObject = code.codeObject != null ? code.codeObject : code.subroutine;
            if (closureObject != null) {
                try {
                    for (java.lang.reflect.Field field : closureObject.getClass().getDeclaredFields()) {
                        if (field.getType() == RuntimeScalar.class && !"__SUB__".equals(field.getName())) {
                            RuntimeScalar cap = (RuntimeScalar) field.get(closureObject);
                            if (enqueueStrongScalar(cap, target, seen, todo)) return true;
                        }
                    }
                } catch (IllegalAccessException ignored) {
                    // Generated closure fields are public. If another
                    // implementation denies access, fall back to the explicit
                    // capturedScalars / capturedVars metadata above.
                }
            }
        } else if (cur instanceof RuntimeScalar s) {
            return enqueueStrongScalar(s, target, seen, todo);
        }
        return false;
    }

    private record BoundedArrayCycleResult(boolean cycle, boolean complete, int edges) {}

    /**
     * Cheap cycle proof for the witness fast path. Unsupported edge forms and
     * budget exhaustion are inconclusive and require the ordinary full walk.
     */
    private static BoundedArrayCycleResult hasBoundedStrongArrayCycle(
            RuntimeBase target, int maxEdges) {
        if (!isSupportedCycleContainer(target)) {
            return new BoundedArrayCycleResult(false, false, 0);
        }
        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        seen.add(target);
        todo.add(target);
        int edges = 0;
        while (!todo.isEmpty()) {
            RuntimeBase current = todo.removeFirst();
            if (current instanceof RuntimeScalar scalar) {
                if (++edges > maxEdges) {
                    return new BoundedArrayCycleResult(false, false, edges);
                }
                if (followBoundedCycleScalar(scalar, target, seen, todo)) {
                    return new BoundedArrayCycleResult(true, true, edges);
                }
                continue;
            }
            if (current instanceof RuntimeCode code) {
                for (RuntimeScalar state : code.stateVariable.values()) {
                    if (++edges > maxEdges) return new BoundedArrayCycleResult(false, false, edges);
                    if (followBoundedCycleScalar(state, target, seen, todo)) {
                        return new BoundedArrayCycleResult(true, true, edges);
                    }
                }
                for (RuntimeArray state : code.stateArray.values()) {
                    if (++edges > maxEdges) return new BoundedArrayCycleResult(false, false, edges);
                    if (followBoundedCycleBase(state, target, seen, todo)) {
                        return new BoundedArrayCycleResult(true, true, edges);
                    }
                }
                for (RuntimeHash state : code.stateHash.values()) {
                    if (++edges > maxEdges) return new BoundedArrayCycleResult(false, false, edges);
                    if (followBoundedCycleBase(state, target, seen, todo)) {
                        return new BoundedArrayCycleResult(true, true, edges);
                    }
                }
                if (code.capturedScalars != null) {
                    for (RuntimeScalar capture : code.capturedScalars) {
                        if (++edges > maxEdges) return new BoundedArrayCycleResult(false, false, edges);
                        if (followBoundedCycleScalar(capture, target, seen, todo)) {
                            return new BoundedArrayCycleResult(true, true, edges);
                        }
                    }
                }
                if (code.captureFieldsRecorded && code.capturedAggregates != null) {
                    for (RuntimeBase capture : code.capturedAggregates) {
                        if (++edges > maxEdges) return new BoundedArrayCycleResult(false, false, edges);
                        if (followBoundedCycleBase(capture, target, seen, todo)) {
                            return new BoundedArrayCycleResult(true, true, edges);
                        }
                    }
                }
                if (code instanceof org.perlonjava.backend.bytecode.InterpretedCode interpreted
                        && interpreted.capturedVars != null) {
                    for (RuntimeBase capture : interpreted.capturedVars) {
                        if (++edges > maxEdges) return new BoundedArrayCycleResult(false, false, edges);
                        boolean found = capture instanceof RuntimeScalar scalar
                                ? followBoundedCycleScalar(scalar, target, seen, todo)
                                : followBoundedCycleBase(capture, target, seen, todo);
                        if (found) return new BoundedArrayCycleResult(true, true, edges);
                    }
                }
                continue;
            }
            Iterable<RuntimeScalar> slots;
            if (current instanceof RuntimeArray array) {
                if (!isSupportedWitnessArray(array)) {
                    return new BoundedArrayCycleResult(false, false, edges);
                }
                slots = array.elements;
            } else if (current instanceof RuntimeHash hash) {
                if (!isSupportedCycleContainer(hash)) {
                    return new BoundedArrayCycleResult(false, false, edges);
                }
                slots = hash.elements.values();
            } else {
                return new BoundedArrayCycleResult(false, false, edges);
            }
            for (RuntimeScalar slot : slots) {
                if (++edges > maxEdges) {
                    return new BoundedArrayCycleResult(false, false, edges);
                }
                if (followBoundedCycleScalar(slot, target, seen, todo)) {
                    return new BoundedArrayCycleResult(true, true, edges);
                }
            }
        }
        return new BoundedArrayCycleResult(false, true, edges);
    }

    private static boolean followBoundedCycleScalar(
            RuntimeScalar scalar, RuntimeBase target, Set<RuntimeBase> seen,
            java.util.ArrayDeque<RuntimeBase> todo) {
        if (scalar == null || WeakRefRegistry.isweak(scalar)
                || (scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0
                || !(scalar.value instanceof RuntimeBase child)) return false;
        return followBoundedCycleBase(child, target, seen, todo);
    }

    private static boolean followBoundedCycleBase(
            RuntimeBase child, RuntimeBase target, Set<RuntimeBase> seen,
            java.util.ArrayDeque<RuntimeBase> todo) {
        if (child == null) return false;
        if (child == target) return true;
        if (isSupportedCycleNode(child) && seen.add(child)) todo.addLast(child);
        return false;
    }

    private static boolean isSupportedCycleContainer(RuntimeBase value) {
        if (value instanceof RuntimeArray array) return isSupportedWitnessArray(array);
        if (value instanceof RuntimeHash hash) {
            return !hash.threadShared
                    && hash.type != RuntimeHash.TIED_HASH
                    && !(hash.elements instanceof TieHash)
                    && !(hash.elements instanceof HashSpecialVariable);
        }
        return false;
    }

    private static boolean isSupportedCycleNode(RuntimeBase value) {
        if (value instanceof RuntimeScalar scalar) return !WeakRefRegistry.isweak(scalar);
        return value instanceof RuntimeCode || isSupportedCycleContainer(value);
    }

    /**
     * True when a CODE cycle strongly retains an object that has an external
     * weak reference. Perl refcounting keeps this graph alive; AnyEvent uses
     * it for weak event-loop registries whose watcher callbacks own themselves
     * through captured state.
     */
    public static boolean strongCycleRetainsWeakReferent(RuntimeCode code) {
        if (code == null || !hasStrongCycle(code)) return false;
        final int MAX_VISITS = 50_000;
        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        seen.add(code);
        todo.addLast(code);
        int visits = 0;
        while (!todo.isEmpty() && visits++ < MAX_VISITS) {
            RuntimeBase current = todo.removeFirst();
            if (WeakRefRegistry.hasWeakRefsTo(current)) return true;
            enqueueStrongEdges(current, null, seen, todo);
        }
        return false;
    }

    private static boolean enqueueStrongScalar(RuntimeScalar s, RuntimeBase target,
                                               Set<RuntimeBase> seen,
                                               java.util.ArrayDeque<RuntimeBase> todo) {
        if (s == null || WeakRefRegistry.isweak(s)) return false;
        if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && s.value instanceof RuntimeBase b) {
            if (b == target) return true;
            if (seen.add(b)) todo.addLast(b);
        }
        return false;
    }

    /**
     * Lightweight per-object reachability query: walk from Perl-visible
     * roots and return {@code true} as soon as {@code target} is found,
     * without enumerating the full live set.
     * <p>
     * Used by {@link MortalList#flush} to avoid prematurely firing
     * DESTROY on a blessed object whose selective refCount dipped to
     * 0 transiently while the object is still held by a container the
     * walker can see (globals, hash/array elements registered in
     * {@link ScalarRefRegistry}). Concrete failure mode without this
     * check: Class::MOP self-bootstrap weakens ~10 attribute back-refs
     * to a single metaclass; refCount drift under heavy reference
     * shuffling drops the count to 0; flush fires DESTROY; weak refs
     * clear; bootstrap dies.
     * <p>
     * BFS with a hard step cap so the cost stays bounded (the worst
     * case is the same nodes-visited bound as a full sweep, which is
     * fine because flush is already O(pending) per call).
     *
     * @param target the object to check for reachability
     * @return true iff target is reachable from roots through strong refs
     */
    public static boolean isReachableFromRoots(RuntimeBase target) {
        return isReachableFromRoots(target, false);
    }

    /**
     * Collect the same bounded root graph as {@link #isReachableFromRoots},
     * once for all weak referents in a mortal drain. The existing query's
     * target-specific early returns become identity membership in this set.
     */
    record RootReachabilitySnapshot(Set<RuntimeBase> reachable, boolean complete) {
        boolean isReachable(RuntimeBase target) {
            // A visit-cap hit cannot prove an unresolved target dead.
            return !complete || reachable.contains(target);
        }
    }

    static Set<RuntimeBase> reachableFromRootsSnapshot() {
        return reachableFromRootsSnapshotWithStatus().reachable();
    }

    static RootReachabilitySnapshot reachableFromRootsSnapshotWithStatus() {
        ReachabilityQueryStats stats = MortalList.activeReachabilityQueryStats();
        if (stats != null) stats.snapshotsBuilt++;
        final int maxVisits = 50_000;
        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>(512));
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefValuesView()) {
            seedTarget(codeRef, null, seen, todo);
            if (codeRef != null && codeRef.value instanceof RuntimeCode code) {
                followGlobalCodeCaptures(code, null, seen, todo);
            }
        }
        for (RuntimeScalar scalar : GlobalVariable.globalVariableValuesView()) {
            seedTarget(scalar, null, seen, todo);
        }
        for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrayEntriesView()) {
            if (!isNonOwningDebugArgsArray(e.getKey()) && e.getValue() != null) {
                if (seen.add(e.getValue())) todo.addLast(e.getValue());
            }
        }
        for (RuntimeHash hash : GlobalVariable.globalHashValuesView()) {
            if (hash != null && seen.add(hash)) todo.addLast(hash);
        }
        for (RuntimeScalar scalar : ScalarRefRegistry.snapshot()) {
            if (scalar == null || scalar.captureCount > 0 || WeakRefRegistry.isweak(scalar)
                    || (!MyVarCleanupStack.isLive(scalar) && !scalar.refCountOwned)
                    || scalar.scopeExited) continue;
            seedTarget(scalar, null, seen, todo);
        }
        for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
            if (liveVar instanceof RuntimeScalar scalar) {
                seen.add(scalar);
                seedTarget(scalar, null, seen, todo);
            } else if (liveVar instanceof RuntimeBase base && seen.add(base)) {
                todo.addLast(base);
            }
        }
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForWalk()) {
            if (seen.add(rescued)) todo.addLast(rescued);
        }

        int visits = 0;
        while (!todo.isEmpty() && visits < maxVisits) {
            visits++;
            RuntimeBase cur = todo.removeFirst();
            if (cur instanceof RuntimeStash) continue;
            if (cur instanceof RuntimeHash hash) {
                if (hash.elements instanceof TieHash tieHash) {
                    followScalar(tieHash.getSelf(), null, seen, todo);
                }
                for (RuntimeScalar value : hash.elements.values()) {
                    followScalar(value, null, seen, todo);
                }
            } else if (cur instanceof RuntimeArray array) {
                for (RuntimeScalar value : array.elements) {
                    followScalar(value, null, seen, todo);
                }
            }
        }
        return new RootReachabilitySnapshot(seen, todo.isEmpty());
    }

    /**
     * Lightweight fallback for {@link WeakRefRegistry#weaken}: check whether a
     * target is still reachable from a JVM-live scalar even when that scalar is
     * not a counted owner. Test2::Tools::Refcount weakens a local probe copy
     * before reading B::REFCNT; if selective refcounting has drifted to zero,
     * clearing every weak callback at that point is premature while the caller's
     * lexical still points at the object.
     *
     * <p>This is deliberately separate from the normal sweep/root query. It
     * force-prunes stale weak-map keys first, only follows non-weak scalars, and
     * is used only at the immediate weaken() zero-count decision.</p>
     */
    public static boolean isReachableFromLiveScalarRegistry(RuntimeBase target) {
        return isReachableFromLiveScalarRegistry(target, ScalarRefRegistry.forceGcAndSnapshot());
    }

    public static boolean isReachableFromLiveCodeCaptures(RuntimeBase target) {
        if (target == null) return false;
        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
            if (!(liveVar instanceof RuntimeScalar sc)) continue;
            if (WeakRefRegistry.isweak(sc)) continue;
            if (sc.value instanceof RuntimeCode code
                    && followGlobalCodeCaptures(code, target, seen, todo)) return true;
        }
        // A closure CODE scalar can outlive the lexical register that created
        // it while the caller is suspended.  ScalarRefRegistry retains a weak
        // key for such reference-holding scalars; use those live keys as
        // additional code roots when the cleanup-stack snapshot has detached
        // the caller's frame.
        for (RuntimeScalar sc : ScalarRefRegistry.snapshot()) {
            if (sc == null || sc.scopeExited || WeakRefRegistry.isweak(sc)) continue;
            if (sc.value instanceof RuntimeCode code
                    && followGlobalCodeCaptures(code, target, seen, todo)) return true;
        }
        return false;
    }

    public static boolean hasLiveStrongScalarReferent(RuntimeBase target) {
        return hasLiveStrongScalarReferentOtherThan(target, null);
    }

    public static boolean hasLiveStrongScalarReferentOtherThan(
            RuntimeBase target, RuntimeScalar excluded) {
        if (target == null) return false;
        // snapshotLiveVars() is the authoritative set for any later scalar
        // registry entry that passes MyVarCleanupStack.isLive(). Scanning
        // ScalarRefRegistry.snapshot() below used to revisit the same live
        // lexical slots and repeatedly copy the weak-key registry while a
        // callback-boundary weak sweep was clearing CODE refs.
        for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
            if (liveVar instanceof RuntimeScalar sc
                    && sc != excluded
                    && !WeakRefRegistry.isweak(sc)
                    && !sc.scopeExited
                    && sc.value == target) {
                return true;
            }
        }
        return false;
    }

    public static boolean isReachableFromGlobalCodeCaptures(RuntimeBase target) {
        if (target == null) return false;
        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<RuntimeCode> followed = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        for (RuntimeScalar sc : GlobalVariable.globalCodeRefEdgeRootsView()) {
            if (sc != null && sc.value instanceof RuntimeCode code
                    && followed.add(code)
                    && followGlobalCodeCaptures(code, target, seen, todo)) return true;
        }
        return false;
    }

    static boolean isReachableFromLiveScalarRegistry(RuntimeBase target,
                                                     java.util.List<RuntimeScalar> roots) {
        if (target == null) return false;
        final int MAX_VISITS = 50_000;

        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        for (RuntimeScalar sc : roots) {
            if (sc == null) continue;
            if (sc.captureCount > 0) continue;
            if (WeakRefRegistry.isweak(sc)) continue;
            if (sc.scopeExited) continue;
            if (followScalar(sc, target, seen, todo)) return true;
        }

        int visits = 0;
        while (!todo.isEmpty() && visits < MAX_VISITS) {
            RuntimeBase cur = todo.removeFirst();
            visits++;
            if (cur == target) return true;
            if (cur instanceof RuntimeStash) continue;
            if (cur instanceof RuntimeHash h) {
                if (h.elements instanceof TieHash tieHash
                        && followScalar(tieHash.getSelf(), target, seen, todo)) {
                    return true;
                }
                for (RuntimeScalar v : h.elements.values()) {
                    if (followScalar(v, target, seen, todo)) return true;
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
                    if (followScalar(v, target, seen, todo)) return true;
                }
            }
        }
        return false;
    }

    /**
     * D-W6.14: check if a specific RuntimeScalar instance is reachable
     * from package globals or live lexical roots. Used at refCount→0
     * transitions to verify that surviving "owner" scalars in the
     * activeOwners set are actually live (not phantoms).
     *
     * Walks containers and verifies whether the specific scalar
     * identity can be reached. Critical: walks INTO scalars (so
     * a my-var holding a hash-ref leads us into the hash to find
     * its element scalars).
     */
    public static boolean isScalarReachable(RuntimeScalar target) {
        if (target == null) return false;

        // Most calls come from reference assignment's weak-owner guard. Avoid
        // rebuilding and traversing the complete package-root graph when the
        // owning scalar has a directly verifiable root.
        if (!target.scopeExited && MyVarCleanupStack.isRegistered(target)) {
            return true;
        }
        if (target.isStoredInRegisteredContainerOwner()) {
            return true;
        }
        RuntimeBase container = target.containerOwner;
        if (container instanceof RuntimeHash hash
                && hash.isPackageRootedHash()
                && hash.elements.containsValue(target)) {
            return true;
        }
        if (container instanceof RuntimeArray array
                && array.isPackageGlobalRoot
                && array.elements.contains(target)) {
            return true;
        }
        if (target.isPackageGlobalRoot
                && (GlobalVariable.globalVariables.containsValue(target)
                    || GlobalVariable.globalCodeRefs.containsValue(target))) {
            return true;
        }
        final int MAX_VISITS = 50_000;

        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        // D-W6.16 strict: PACKAGE-GLOBAL ROOTS ONLY.
        //
        // The walker's purpose at MortalList.flush refCount→0 is to
        // distinguish "object held by a real long-lived root" (e.g.,
        // `our %METAS` cache, package method tables) from "object's
        // reachability is via expiring lexicals or temporaries".
        //
        // Including my-vars / ScalarRefRegistry as seeds over-rescues
        // DBIC row objects whose only owners are intermediate my-vars
        // in DBIC's internal method dispatches (still in MyVarCleanupStack
        // until the dispatch returns, but logically should be released
        // for refcount accounting).
        //
        // Restricting seeds to package globals matches what
        // Class::MOP/Moose actually need: their metaclasses live in
        // `our %METAS`, accessible through globalHashes. DBIC's per-row
        // cycles aren't reachable via package globals → not rescued
        // → DESTROY fires correctly.
        for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefValuesView()) {
            if (codeRef == target) return true;
            if (codeRef != null && seen.add(codeRef)) todo.addLast(codeRef);
        }
        for (RuntimeScalar scalar : GlobalVariable.globalVariableValuesView()) {
            if (scalar == target) return true;
            if (scalar != null && seen.add(scalar)) todo.addLast(scalar);
        }
        for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrayEntriesView()) {
            if (isNonOwningDebugArgsArray(e.getKey())) continue;
            if (e.getValue() != null && seen.add(e.getValue())) todo.addLast(e.getValue());
        }
        for (RuntimeHash hash : GlobalVariable.globalHashValuesView()) {
            if (hash != null && seen.add(hash)) todo.addLast(hash);
        }

        // D-W6.16: live my-vars (currently-active lexical scopes).
        // These represent persistent scalar references in ACTIVE
        // execution scopes — the "my $schema = ..." at file scope is
        // here; transient method-call argument scalars are NOT
        // (they get unwound when the method returns).
        for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
            if (liveVar == target) return true;
            if (liveVar instanceof RuntimeBase rb && seen.add(rb)) todo.addLast(rb);
        }

        int visits = 0;
        while (!todo.isEmpty() && visits < MAX_VISITS) {
            RuntimeBase cur = todo.removeFirst();
            visits++;
            // A RuntimeStash is a synthetic view across the global slot maps,
            // all of which were seeded above. Walking its values both creates
            // transient glob proxies and rescans every global symbol for each
            // refcount transition.
            if (cur instanceof RuntimeStash) {
                continue;
            } else if (cur instanceof RuntimeHash hash) {
                if (hash.elements instanceof HashSpecialVariable) continue;
                // A tied hash's Java Map facade does not expose the values
                // retained by its pure-Perl handler.  Follow the handler
                // object, then its arrays/hashes, so an owner scalar stored by
                // implementations such as Tie::IxHash is recognized as a real
                // strong path.  Without this edge, undefining a different
                // strong scalar can clear weak back-references prematurely.
                if (hash.elements instanceof TieHash tieHash) {
                    RuntimeScalar self = tieHash.getSelf();
                    if (self == target) return true;
                    if (self != null && !WeakRefRegistry.isweak(self)
                            && seen.add(self)) {
                        todo.addLast(self);
                    }
                }
                for (RuntimeScalar val : hash.elements.values()) {
                    if (val == null) continue;
                    // Skip weak refs — they don't keep their referent alive.
                    if (WeakRefRegistry.isweak(val)) continue;
                    if (val == target) return true;
                    if (seen.add(val)) todo.addLast(val);
                }
            } else if (cur instanceof RuntimeArray arr) {
                for (RuntimeScalar elem : arr.elements) {
                    if (elem == null) continue;
                    if (WeakRefRegistry.isweak(elem)) continue;
                    if (elem == target) return true;
                    if (seen.add(elem)) todo.addLast(elem);
                }
            } else if (cur instanceof RuntimeScalar sc) {
                if (WeakRefRegistry.isweak(sc)) continue;
                if ((sc.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        && sc.value instanceof RuntimeBase rb
                        && seen.add(rb)) todo.addLast(rb);
            } else if (cur instanceof RuntimeCode code) {
                if (code.closedOverVariables != null) {
                    for (RuntimeBase captured : code.closedOverVariables.values()) {
                        if (captured == null) continue;
                        if (captured instanceof RuntimeScalar scalar
                                && WeakRefRegistry.isweak(scalar)) {
                            continue;
                        }
                        if (captured == target) return true;
                        if (seen.add(captured)) todo.addLast(captured);
                    }
                }
                if (code.capturedScalars != null) {
                    for (RuntimeScalar cap : code.capturedScalars) {
                        if (cap == null) continue;
                        if (WeakRefRegistry.isweak(cap)) continue;
                        if (cap == target) return true;
                        if (seen.add(cap)) todo.addLast(cap);
                    }
                }
                if (code.capturedAggregates != null) {
                    for (RuntimeBase cap : code.capturedAggregates) {
                        if (cap == target) return true;
                        if (seen.add(cap)) todo.addLast(cap);
                    }
                }
                final boolean[] foundReflectiveCapture = {false};
                visitReflectiveCodeScalars(code, cap -> {
                    if (foundReflectiveCapture[0]) return;
                    if (cap == null || WeakRefRegistry.isweak(cap)) return;
                    if (cap == target) {
                        foundReflectiveCapture[0] = true;
                    } else if (seen.add(cap)) {
                        todo.addLast(cap);
                    }
                });
                if (foundReflectiveCapture[0]) return true;
                visitReflectiveCodeBases(code, base -> {
                    if (seen.add(base)) {
                        todo.addLast(base);
                    }
                });
            }
        }
        return false;
    }

    /**
     * Phase D-W2c: distinguish reachability via package globals
     * (`our %METAS`, `our @ISA`, `our $...`, `&Class::MOP::class_of`)
     * from reachability via local lexicals (`my $x`, MyVarCleanupStack
     * entries, `ScalarRefRegistry`).
     *
     * The walker gate uses {@code globalOnly=true}: an object is
     * "really" reachable only if a package global path leads to it.
     * Stack-local my-vars don't count — those are transient holders
     * that should release at scope exit. This matches Perl 5
     * semantics: `weaken($foo)` clears when no STRONG package-level
     * or lexical-still-holding-strong path exists, and cycle-break
     * tests rely on stack-local refs releasing properly.
     *
     * The default {@code globalOnly=false} is preserved for
     * diagnostic / debugging callers.
     */
    public static boolean isReachableFromRoots(RuntimeBase target, boolean globalOnly) {
        if (target == null) return false;
        ReachabilityQueryStats stats = MortalList.activeReachabilityQueryStats();
        if (stats != null) stats.rootQueries++;
        // Hard cap to prevent pathological worst-case walks. Class::MOP
        // bootstrap touches ~thousands of nodes; pick a generous limit
        // that still bounds cost.
        final int MAX_VISITS = 50_000;

        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        // Seed: package globals (scalars, arrays, hashes, code refs).
        for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefValuesView()) {
            seedTarget(codeRef, target, seen, todo, stats);
            if (seen.contains(target)) return true;
            if (!globalOnly
                    && codeRef != null
                    && codeRef.value instanceof RuntimeCode code) {
                if (followGlobalCodeCaptures(code, target, seen, todo)) return true;
            }
        }
        for (RuntimeScalar scalar : GlobalVariable.globalVariableValuesView()) {
            seedTarget(scalar, target, seen, todo, stats);
            if (seen.contains(target)) return true;
        }
        for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrayEntriesView()) {
            if (isNonOwningDebugArgsArray(e.getKey())) continue;
            if (e.getValue() == target) {
                if (stats != null) stats.rootsSeeded++;
                return true;
            }
            if (e.getValue() != null && seen.add(e.getValue())) {
                todo.addLast(e.getValue());
                if (stats != null) stats.rootsSeeded++;
            }
        }
        for (RuntimeHash hash : GlobalVariable.globalHashValuesView()) {
            if (hash == target) {
                if (stats != null) stats.rootsSeeded++;
                return true;
            }
            if (hash != null && seen.add(hash)) {
                todo.addLast(hash);
                if (stats != null) stats.rootsSeeded++;
            }
        }
        // Seed: ScalarRefRegistry-tracked scalars whose declaration
        // scope is still live (per MyVarCleanupStack). This is what
        // makes hash elements like $METAS{HasMethods} act as roots —
        // their enclosing my %METAS hash is on the live-vars stack.
        //
        // The MyVarCleanupStack filter is critical: ScalarRefRegistry
        // alone holds stale entries (scope-exited scalars that haven't
        // been JVM-GC'd yet). Without filtering, cycle-broken-via-weaken
        // tests would falsely consider the cycle members reachable
        // through their own (scope-exited) scalars.
        if (!globalOnly) {
            for (RuntimeScalar sc : ScalarRefRegistry.snapshot()) {
                if (sc == null) continue;
                if (sc.captureCount > 0) continue;
                if (WeakRefRegistry.isweak(sc)) continue;
                if (!MyVarCleanupStack.isLive(sc) && !sc.refCountOwned) continue;
                if (sc.scopeExited) continue;
                seedTarget(sc, target, seen, todo, stats);
                if (seen.contains(target)) return true;
            }
            // Seed: live my-vars themselves (RuntimeHash / RuntimeArray /
            // RuntimeScalar instances currently registered in
            // MyVarCleanupStack). Walking INTO these picks up hash/array
            // elements that hold strong refs to the target — e.g.
            // `our %METAS = ();` registers the RuntimeHash, and walking
            // its values surfaces the metaclass.
            for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
                // Order matters: RuntimeScalar IS a RuntimeBase, so the
                // RuntimeScalar branch must come first to walk through its
                // reference bit. Otherwise we'd add the scalar to todo but
                // the BFS only follows hashes/arrays, missing the scalar's
                // referent (e.g. `my $schema = DBICTest->init_schema()`).
                if (liveVar instanceof RuntimeScalar sc) {
                    if (sc == target) {
                        if (stats != null) stats.rootsSeeded++;
                        return true;
                    }
                    seedTarget(sc, target, seen, todo, stats);
                    if (seen.contains(target)) return true;
                } else if (liveVar instanceof RuntimeBase rb) {
                    if (rb == target) {
                        if (stats != null) stats.rootsSeeded++;
                        return true;
                    }
                    if (seen.add(rb)) {
                        todo.addLast(rb);
                        if (stats != null) stats.rootsSeeded++;
                    }
                }
            }
        }
        // Seed: rescued objects.
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForWalk()) {
            if (rescued == target) {
                if (stats != null) stats.rootsSeeded++;
                return true;
            }
            if (seen.add(rescued)) {
                todo.addLast(rescued);
                if (stats != null) stats.rootsSeeded++;
            }
        }

        // BFS, short-circuiting on target.
        int visits = 0;
        while (!todo.isEmpty() && visits < MAX_VISITS) {
            RuntimeBase cur = todo.removeFirst();
            visits++;
            if (stats != null) stats.nodesVisited++;
            if (cur == target) return true;
            // Phase D-W2 (perf): skip RuntimeStash — see bfs().
            if (cur instanceof RuntimeStash) continue;
            if (cur instanceof RuntimeHash h) {
                if (h.elements instanceof TieHash) {
                    if (stats != null) stats.edgesInspected++;
                }
                if (h.elements instanceof TieHash tieHash
                        && followScalar(tieHash.getSelf(), target, seen, todo)) {
                    return true;
                }
                for (RuntimeScalar v : h.elements.values()) {
                    if (stats != null) stats.edgesInspected++;
                    if (followScalar(v, target, seen, todo)) return true;
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
                    if (stats != null) stats.edgesInspected++;
                    if (followScalar(v, target, seen, todo)) return true;
                }
            }
            // Note: we deliberately don't follow RuntimeCode.capturedScalars
            // here — closure captures are NOT considered strong reachability
            // edges for this query (matches the default of
            // ReachabilityWalker.walk() which has walkCodeCaptures=false
            // for the second-phase BFS). Without this discipline, DBIC's
            // leak detector (t/52leaks.t) reports false positives because
            // the walker would keep things alive that user code released.
            //
            // For Class::MOP's %METAS hash (which we also need to find for
            // the Moose bootstrap), we don't need closure-capture walking
            // because %METAS is declared `our %METAS` (package global) so
            // it appears directly in GlobalVariable.globalHashes.
        }        return false;
    }

    /**
     * Return true when {@code target} is held by a live reference scalar other
     * than its own named my-variable slot. This is deliberately narrower than a
     * full root walk: scope cleanup calls it often, and DBIC can have thousands
     * of live roots by the time its leak tests initialize.
     */
    public static boolean isReachableFromExternalRoot(RuntimeBase target) {
        return new ExternalRootSnapshot().isReachable(target);
    }

    public static boolean isReachableFromExternalRootExcludingRescued(RuntimeBase target) {
        return new ExternalRootSnapshot(false).isReachable(target);
    }

    public static boolean isReachableFromTemporaryRoots(RuntimeBase target) {
        if (target == null) return false;
        ReachabilityWalker walker = new ReachabilityWalker();
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        for (RuntimeBase tempRoot : MortalList.snapshotTemporaryRoots()) {
            walker.addReachable(tempRoot, todo);
        }
        walker.bfs(todo, true);
        return walker.reachable.contains(target);
    }

    /**
     * Return true when a strong path from a package or live lexical root to
     * {@code target} crosses a tied hash handler.  This is intentionally more
     * specific than the ordinary root queries: objects with DESTROY normally
     * use strict selective-refcount handling, but a pure-Perl tie handler can
     * hold a real owner that the count temporarily misses.
     */
    public static boolean isReachableThroughTiedHash(RuntimeBase target) {
        return target != null && reachableThroughTiedHashes().contains(target);
    }

    /**
     * Return the objects reached after crossing a tied hash handler from a
     * live Perl root. A mortal drain can ask this question for many weak
     * referents, so collecting the complete bounded snapshot once avoids an
     * O(targets x roots) traversal and its corresponding temporary objects.
     */
    static Set<RuntimeBase> reachableThroughTiedHashes() {
        final int maxVisits = 50_000;
        Set<RuntimeBase> seenPlain = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<RuntimeBase> seenTied = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<RuntimeBase> reachableTied = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<TiedPathStep> todo = new java.util.ArrayDeque<>();

        for (RuntimeScalar scalar : GlobalVariable.globalCodeRefValuesView()) {
            addTiedPathScalar(scalar, false, seenPlain, seenTied, todo);
        }
        for (RuntimeScalar scalar : GlobalVariable.globalVariableValuesView()) {
            addTiedPathScalar(scalar, false, seenPlain, seenTied, todo);
        }
        for (RuntimeArray array : GlobalVariable.globalArrayValuesView()) {
            addTiedPath(array, false, seenPlain, seenTied, todo);
        }
        for (RuntimeHash hash : GlobalVariable.globalHashValuesView()) {
            addTiedPath(hash, false, seenPlain, seenTied, todo);
        }
        for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
            if (liveVar instanceof RuntimeBase base) {
                addTiedPath(base, false, seenPlain, seenTied, todo);
            }
        }

        int visits = 0;
        while (!todo.isEmpty() && visits++ < maxVisits) {
            TiedPathStep step = todo.removeFirst();
            RuntimeBase cur = step.base;
            if (step.crossedTie) reachableTied.add(cur);
            if (cur instanceof RuntimeStash) continue;
            if (cur instanceof RuntimeHash hash) {
                if (hash.elements instanceof HashSpecialVariable) continue;
                if (hash.elements instanceof TieHash tieHash) {
                    addTiedPathScalar(tieHash.getSelf(), true,
                            seenPlain, seenTied, todo);
                }
                for (RuntimeScalar value : hash.elements.values()) {
                    addTiedPathScalar(value, step.crossedTie,
                            seenPlain, seenTied, todo);
                }
            } else if (cur instanceof RuntimeArray array) {
                for (RuntimeScalar value : array.elements) {
                    addTiedPathScalar(value, step.crossedTie,
                            seenPlain, seenTied, todo);
                }
            } else if (cur instanceof RuntimeScalar scalar) {
                addTiedPathScalar(scalar, step.crossedTie,
                        seenPlain, seenTied, todo);
            }
        }
        return reachableTied;
    }

    private static void addTiedPathScalar(
            RuntimeScalar scalar, boolean crossedTie,
            Set<RuntimeBase> seenPlain, Set<RuntimeBase> seenTied,
            java.util.ArrayDeque<TiedPathStep> todo) {
        if (scalar == null || WeakRefRegistry.isweak(scalar)) return;
        addTiedPath(scalar, crossedTie, seenPlain, seenTied, todo);
        if ((scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && scalar.value instanceof RuntimeBase base) {
            addTiedPath(base, crossedTie, seenPlain, seenTied, todo);
        }
    }

    private static void addTiedPath(
            RuntimeBase base, boolean crossedTie,
            Set<RuntimeBase> seenPlain, Set<RuntimeBase> seenTied,
            java.util.ArrayDeque<TiedPathStep> todo) {
        if (base == null) return;
        Set<RuntimeBase> seen = crossedTie ? seenTied : seenPlain;
        if (seen.add(base)) {
            todo.addLast(new TiedPathStep(base, crossedTie));
        }
    }

    private record TiedPathStep(RuntimeBase base, boolean crossedTie) {}

    /**
     * Target-specific reachability query for scope-exit cleanup of a named
     * lexical container. The container's own my-variable slot is still present
     * in {@link MyVarCleanupStack} while cleanup runs, so this deliberately
     * skips that direct root and only returns true for another root path.
     */
    public static boolean isReachableFromExternalRootExcludingDirectLexical(RuntimeBase target) {
        if (target == null) return false;
        final int MAX_VISITS = 50_000;

        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        for (RuntimeScalar sc : GlobalVariable.globalCodeRefValuesView()) {
            seedTarget(sc, target, seen, todo);
            if (seen.contains(target)) return true;
            if (sc != null && sc.value instanceof RuntimeCode code
                    && followGlobalCodeCaptures(code, target, seen, todo)) {
                return true;
            }
        }
        for (RuntimeScalar sc : GlobalVariable.globalVariableValuesView()) {
            seedTarget(sc, target, seen, todo);
            if (seen.contains(target)) return true;
        }
        for (RuntimeArray array : GlobalVariable.globalArrayValuesView()) {
            if (array == target) return true;
            if (seen.add(array)) todo.addLast(array);
        }
        for (RuntimeHash hash : GlobalVariable.globalHashValuesView()) {
            if (hash == target) return true;
            if (seen.add(hash)) todo.addLast(hash);
        }
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForWalk()) {
            if (rescued == target) return true;
            if (seen.add(rescued)) todo.addLast(rescued);
        }
        for (RuntimeScalar sc : ScalarRefRegistry.snapshot()) {
            if (sc == null) continue;
            if (sc.captureCount > 0) continue;
            if (WeakRefRegistry.isweak(sc)) continue;
            if (sc.scopeExited) continue;
            if (!MyVarCleanupStack.isLive(sc) && !sc.refCountOwned) continue;
            if (followScalar(sc, target, seen, todo)) return true;
        }
        for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
            if (liveVar == target) continue;
            if (liveVar instanceof RuntimeScalar sc) {
                if (WeakRefRegistry.isweak(sc)) continue;
                if (followScalar(sc, target, seen, todo)) return true;
            } else if (liveVar instanceof RuntimeBase rb) {
                if (seen.add(rb)) todo.addLast(rb);
            }
        }

        int visits = 0;
        while (!todo.isEmpty() && visits < MAX_VISITS) {
            RuntimeBase cur = todo.removeFirst();
            visits++;
            if (cur == target) return true;
            if (cur instanceof RuntimeStash) continue;
            if (cur instanceof RuntimeHash h) {
                if (h.elements instanceof HashSpecialVariable) continue;
                if (h.elements instanceof TieHash tieHash
                        && followScalar(tieHash.getSelf(), target, seen, todo)) {
                    return true;
                }
                for (RuntimeScalar v : h.elements.values()) {
                    if (followScalar(v, target, seen, todo)) return true;
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
                    if (followScalar(v, target, seen, todo)) return true;
                }
            } else if (cur instanceof RuntimeScalar sc) {
                if (followScalar(sc, target, seen, todo)) return true;
            }
        }
        return false;
    }

    /**
     * Cached equivalent of {@link #isReachableFromExternalRoot(RuntimeBase)}.
     * <p>
     * The package/rescued root graph is snapshotted separately from live
     * lexical roots so callers can invalidate the live-lexical snapshot when
     * scope registrations change without forcing a package-root rebuild.
     */
    public static final class ExternalRootSnapshot {
        private static final int MAX_VISITS = 50_000;

        private final Set<RuntimeBase> nonLexicalReachable =
                Collections.newSetFromMap(new IdentityHashMap<>(512));

        public ExternalRootSnapshot() {
            this(true);
        }

        public ExternalRootSnapshot(boolean includeRescued) {
            buildNonLexicalRoots(includeRescued);
        }

        public boolean isReachable(RuntimeBase target) {
            if (target == null) return false;
            if (nonLexicalReachable.contains(target)) return true;
            return new LiveRootSnapshot().isReachable(target);
        }

        public boolean isReachableFromNonLexicalRoot(RuntimeBase target) {
            return target != null && nonLexicalReachable.contains(target);
        }

        private void buildNonLexicalRoots(boolean includeRescued) {
            java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

            // Every installed CODE slot is itself a root, but only the
            // cached subset with capture/state edges needs reflective capture
            // traversal. Named subs with empty pads otherwise force this
            // sweep to inspect every generated implementation object.
            for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefValuesView()) {
                seedNonLexicalScalar(codeRef, todo);
            }
            for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefEdgeRootsView()) {
                if (codeRef != null && codeRef.value instanceof RuntimeCode code) {
                    seedGlobalCodeCaptures(code, todo);
                }
            }
            for (RuntimeScalar scalar : GlobalVariable.globalVariableValuesView()) {
                seedNonLexicalScalar(scalar, todo);
            }
            for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrayEntriesView()) {
                if (isNonOwningDebugArgsArray(e.getKey())) continue;
                addNonLexical(e.getValue(), todo);
            }
            for (RuntimeHash hash : GlobalVariable.globalHashValuesView()) {
                addNonLexical(hash, todo);
            }
            if (includeRescued) {
                for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForWalk()) {
                    addNonLexical(rescued, todo);
                }
            }

            int visits = 0;
            while (!todo.isEmpty() && visits < MAX_VISITS) {
                RuntimeBase cur = todo.removeFirst();
                visits++;
                walkSnapshotNode(cur, todo);
            }
        }

        private void walkSnapshotNode(RuntimeBase cur,
                                      java.util.ArrayDeque<RuntimeBase> nonLexicalTodo) {
            if (cur instanceof RuntimeStash) return;
            if (cur instanceof RuntimeHash h) {
                if (h.elements instanceof HashSpecialVariable) return;
                if (h.elements instanceof TieHash tieHash) {
                    seedNonLexicalScalar(tieHash.getSelf(), nonLexicalTodo);
                }
                for (RuntimeScalar v : h.elements.values()) {
                    seedNonLexicalScalar(v, nonLexicalTodo);
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
                    seedNonLexicalScalar(v, nonLexicalTodo);
                }
            } else if (cur instanceof RuntimeScalar sc) {
                seedNonLexicalScalar(sc, nonLexicalTodo);
            }
        }

        private void seedNonLexicalScalar(RuntimeScalar s,
                                          java.util.ArrayDeque<RuntimeBase> todo) {
            if (s == null) return;
            if (WeakRefRegistry.isweak(s)) return;
            if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && s.value instanceof RuntimeBase b) {
                addNonLexical(b, todo);
            }
        }

        private void seedGlobalCodeCaptures(RuntimeCode code,
                                            java.util.ArrayDeque<RuntimeBase> todo) {
            if (code.capturedScalars != null) {
                for (RuntimeScalar cap : code.capturedScalars) {
                    seedNonLexicalScalar(cap, todo);
                }
            }
            if (code.capturedAggregates != null) {
                for (RuntimeBase cap : code.capturedAggregates) addNonLexical(cap, todo);
            }
            visitReflectiveCodeScalars(code, cap -> seedNonLexicalScalar(cap, todo));
        }

        private void addNonLexical(RuntimeBase b,
                                   java.util.ArrayDeque<RuntimeBase> todo) {
            if (b == null) return;
            if (nonLexicalReachable.add(b)) {
                todo.addLast(b);
            }
        }

    }

    /**
     * Snapshot of reachability from currently-live lexical roots only.
     * Direct lexical origins are tracked so a named lexical container does not
     * count as an external root for itself.
     */
    public static final class LiveRootSnapshot {
        private static final int MAX_VISITS = 50_000;
        private static final Object MIXED_DIRECT_LEXICAL_ORIGIN = new Object();

        private final Set<RuntimeBase> directLexicalRoots =
                Collections.newSetFromMap(new IdentityHashMap<>(512));
        private final IdentityHashMap<RuntimeBase, Object> directLexicalOrigins =
                new IdentityHashMap<>(512);

        public LiveRootSnapshot() {
            build();
        }

        public boolean isReachable(RuntimeBase target) {
            if (target == null) return false;
            Object origin = directLexicalOrigins.get(target);
            if (origin == null) return false;
            if (!directLexicalRoots.contains(target)) return true;
            return origin != target;
        }

        private void build() {
            java.util.ArrayDeque<DirectRootStep> todo = new java.util.ArrayDeque<>();

            for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
                if (!(liveVar instanceof RuntimeBase root)) continue;
                directLexicalRoots.add(root);
                if (root instanceof RuntimeScalar sc) {
                    seedDirectScalar(sc, root, todo);
                } else {
                    addDirect(root, root, todo);
                }
            }

            int visits = 0;
            while (!todo.isEmpty() && visits < MAX_VISITS) {
                DirectRootStep step = todo.removeFirst();
                visits++;
                walkLiveNode(step.base, todo, step.origin);
            }
        }

        private void walkLiveNode(RuntimeBase cur,
                                  java.util.ArrayDeque<DirectRootStep> todo,
                                  Object origin) {
            if (cur instanceof RuntimeStash) return;
            if (cur instanceof RuntimeHash h) {
                if (h.elements instanceof HashSpecialVariable) return;
                if (h.elements instanceof TieHash tieHash) {
                    seedDirectScalar(tieHash.getSelf(), origin, todo);
                }
                for (RuntimeScalar v : h.elements.values()) {
                    seedDirectScalar(v, origin, todo);
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
                    seedDirectScalar(v, origin, todo);
                }
            } else if (cur instanceof RuntimeScalar sc) {
                seedDirectScalar(sc, origin, todo);
            }
        }

        private void seedDirectScalar(RuntimeScalar s, Object origin,
                                      java.util.ArrayDeque<DirectRootStep> todo) {
            if (s == null) return;
            if (WeakRefRegistry.isweak(s)) return;
            if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && s.value instanceof RuntimeBase b) {
                addDirect(b, origin, todo);
            }
        }

        private void addDirect(RuntimeBase b, Object origin,
                               java.util.ArrayDeque<DirectRootStep> todo) {
            if (b == null || origin == null) return;
            Object existing = directLexicalOrigins.get(b);
            if (existing == null) {
                directLexicalOrigins.put(b, origin);
                todo.addLast(new DirectRootStep(b, origin));
                return;
            }
            if (existing == MIXED_DIRECT_LEXICAL_ORIGIN || existing == origin) {
                return;
            }
            directLexicalOrigins.put(b, MIXED_DIRECT_LEXICAL_ORIGIN);
            todo.addLast(new DirectRootStep(b, MIXED_DIRECT_LEXICAL_ORIGIN));
        }

        private static final class DirectRootStep {
            private final RuntimeBase base;
            private final Object origin;

            private DirectRootStep(RuntimeBase base, Object origin) {
                this.base = base;
                this.origin = origin;
            }
        }
    }

    private static void seedTarget(RuntimeScalar s, RuntimeBase target,
                                   Set<RuntimeBase> seen,
                                   java.util.ArrayDeque<RuntimeBase> todo) {
        seedTarget(s, target, seen, todo, null);
    }

    private static void seedTarget(RuntimeScalar s, RuntimeBase target,
                                   Set<RuntimeBase> seen,
                                   java.util.ArrayDeque<RuntimeBase> todo,
                                   ReachabilityQueryStats stats) {
        if (s == null) return;
        if (stats != null) stats.edgesInspected++;
        if (WeakRefRegistry.isweak(s)) return;
        if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && s.value instanceof RuntimeBase b) {
            if (b == target) {
                if (seen.add(target) && stats != null) stats.rootsSeeded++;
                return;
            }
            if (seen.add(b)) {
                todo.addLast(b);
                if (stats != null) stats.rootsSeeded++;
            }
        }
    }

    private static boolean followScalar(RuntimeScalar s, RuntimeBase target,
                                        Set<RuntimeBase> seen,
                                        java.util.ArrayDeque<RuntimeBase> todo) {
        if (s == null) return false;
        if (WeakRefRegistry.isweak(s)) return false;
        if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && s.value instanceof RuntimeBase b) {
            if (b == target) return true;
            if (seen.add(b)) todo.addLast(b);
        }
        return false;
    }

    private static boolean followGlobalCodeCaptures(RuntimeCode code, RuntimeBase target,
                                                    Set<RuntimeBase> seen,
                                                    java.util.ArrayDeque<RuntimeBase> todo) {
        for (RuntimeScalar state : code.stateVariable.values()) {
            if (followScalar(state, target, seen, todo)) return true;
        }
        for (RuntimeArray state : code.stateArray.values()) {
            if (state == target) return true;
            if (seen.add(state)) todo.addLast(state);
        }
        for (RuntimeHash state : code.stateHash.values()) {
            if (state == target) return true;
            if (seen.add(state)) todo.addLast(state);
        }
        // Interpreted closures keep their semantic lexical environment in
        // closedOverVariables.  This edge must be followed when deciding
        // whether a suspended async sub's returning Future is still owned by
        // a live closure; reflective/capture arrays are not guaranteed to
        // expose that ownership on every backend.
        if (code.closedOverVariables != null) {
            for (RuntimeBase captured : code.closedOverVariables.values()) {
                if (captured == null) continue;
                if (captured instanceof RuntimeScalar scalar) {
                    if (followScalar(scalar, target, seen, todo)) return true;
                } else if (captured == target) {
                    return true;
                } else if (seen.add(captured)) {
                    todo.addLast(captured);
                }
            }
        }
        if (code.capturedScalars != null) {
            for (RuntimeScalar cap : code.capturedScalars) {
                if (followScalar(cap, target, seen, todo)) return true;
            }
        }
        if (code.capturedAggregates != null) {
            for (RuntimeBase cap : code.capturedAggregates) {
                if (cap == target) return true;
                if (seen.add(cap)) todo.addLast(cap);
            }
        }
        final boolean[] foundReflectiveCapture = {false};
        visitReflectiveCodeScalars(code, cap -> {
            if (!foundReflectiveCapture[0] && followScalar(cap, target, seen, todo)) {
                foundReflectiveCapture[0] = true;
            }
        });
        if (foundReflectiveCapture[0]) return true;
        final boolean[] foundReflectiveBase = {false};
        visitReflectiveCodeBases(code, base -> {
            if (foundReflectiveBase[0]) return;
            if (base == target) {
                foundReflectiveBase[0] = true;
            } else if (seen.add(base)) {
                todo.addLast(base);
            }
        });
        if (foundReflectiveBase[0]) return true;
        return false;
    }

    private static void visitReflectiveCodeScalars(RuntimeCode code,
                                                   java.util.function.Consumer<RuntimeScalar> visitor) {
        if (code.captureFieldsRecorded) return;
        Object closureObject = code.codeObject != null ? code.codeObject : code.subroutine;
        if (closureObject == null) return;
        try {
            for (java.lang.reflect.Field field :
                    REFLECTIVE_CAPTURE_FIELDS.get(closureObject.getClass()).scalars()) {
                RuntimeScalar cap = (RuntimeScalar) field.get(closureObject);
                if (cap != null) {
                    visitor.accept(cap);
                }
            }
        } catch (IllegalAccessException ignored) {
            // Generated closure fields are public. If another implementation
            // denies reflective access, callers still have capturedScalars and
            // interpreter capturedVars metadata as fallbacks.
        }
    }

    private static void visitReflectiveCodeBases(RuntimeCode code,
                                                 java.util.function.Consumer<RuntimeBase> visitor) {
        if (code.captureFieldsRecorded) {
            if (code.capturedAggregates != null) {
                for (RuntimeBase captured : code.capturedAggregates) {
                    if (captured != null) visitor.accept(captured);
                }
            }
            return;
        }
        Object closureObject = code.codeObject != null ? code.codeObject : code.subroutine;
        if (closureObject == null) return;
        try {
            for (java.lang.reflect.Field field :
                    REFLECTIVE_CAPTURE_FIELDS.get(closureObject.getClass()).bases()) {
                RuntimeBase cap = (RuntimeBase) field.get(closureObject);
                if (cap != null) {
                    visitor.accept(cap);
                }
            }
        } catch (IllegalAccessException ignored) {
            // Generated closure fields are public. If another implementation
            // denies reflective access, callers still have capturedScalars and
            // interpreter capturedVars metadata as fallbacks.
        }
    }

    /**
     * Run a reachability sweep and clear weak refs for unreachable objects.
     * Called from {@code Internals::jperl_gc()} explicitly.
     * <p>
     * Rescued objects (pinned by Schema-style DESTROY self-save) are NOT
     * treated as roots here. jperl_gc is opt-in and the caller is asking
     * for aggressive cleanup — if the user wanted to keep a phantom chain
     * alive, they would not call jperl_gc. The rescued pins are cleared
     * via DestroyDispatch.clearRescuedWeakRefs() as part of the sweep.
     *
     * @return number of weak-ref entries cleared
     */
    public static int sweepWeakRefs() {
        return sweepWeakRefs(false);
    }

    public static int sweepWeakRefs(boolean quiet) {
        return sweepWeakRefs(quiet, true);
    }

    /**
     * Run a reachability sweep. When {@code quiet} is true, only clear
     * weak refs for unreachable objects — do NOT fire DESTROY or drain
     * rescuedObjects. Used by auto-triggered sweeps from common hot
     * paths where firing DESTROY mid-execution would corrupt state
     * (e.g. module loading chains that weaken() intermediate values).
     *
     * @param quiet if true, skip DESTROY invocations
     * @return number of weak-ref entries cleared
     */
    public static int sweepWeakRefs(boolean quiet, boolean forceJvmGc) {
        return sweepWeakRefs(quiet, forceJvmGc, Collections.emptySet(), null);
    }

    static int sweepWeakRefs(boolean quiet, boolean forceJvmGc,
                             Set<RuntimeBase> releasedTargets,
                             ReleasedWeakSweepResult releasedSweepResult) {
        return sweepWeakRefsPass(quiet, forceJvmGc, releasedTargets,
                releasedSweepResult).cleared();
    }

    private record WeakSweepPass(int cleared, boolean graphChanged) {}

    private static final int MAX_WITNESS_SWEEP_TARGETS = 64;
    private static final int MAX_WITNESS_CYCLE_EDGES = 256;

    private static boolean tryWitnessQuietSweep(
            boolean quiet, boolean forceJvmGc,
            Set<RuntimeBase> releasedTargets,
            ReleasedWeakSweepResult releasedSweepResult,
            List<RuntimeBase> weakCandidates,
            List<RuntimeBase> destroyableCandidates,
            LifecycleRuntimeState runtimeState) {
        if (!quiet || forceJvmGc || releasedSweepResult != null || !releasedTargets.isEmpty()) {
            return false;
        }
        if (DestroyDispatch.hasRescuedObjects()
                || runtimeState.currentDestroyTarget != null
                || runtimeState.sweepPendingAfterOuterDestroy) {
            return false;
        }
        Set<RuntimeBase> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        targets.addAll(weakCandidates);
        targets.addAll(destroyableCandidates);
        if (targets.size() > MAX_WITNESS_SWEEP_TARGETS) return false;

        for (RuntimeBase referent : weakCandidates) {
            if (referent == null) continue;
            if (hasDirectCodeRootWitness(referent)) {
                continue;
            }
            ReachabilityWalker.WeakRootWitness witness =
                    runtimeState.weakSweepRootWitnesses.get(referent);
            if (witness != null) {
                if (isCurrentWeakRootWitness(witness)) continue;
            }
            if (hasExistingWeakRetentionRule(referent)) continue;
            BoundedArrayCycleResult cycle = hasBoundedStrongArrayCycle(
                    referent, MAX_WITNESS_CYCLE_EDGES);
            if (!cycle.cycle()) return false;
        }
        for (RuntimeBase referent : destroyableCandidates) {
            if (referent == null || referent.destroyFired || referent.currentlyDestroying
                    || referent.refCount == Integer.MIN_VALUE
                    || hasExistingDestroyRetentionRule(referent)) {
                continue;
            }
            if (hasDirectCodeRootWitness(referent)) {
                continue;
            }
            ReachabilityWalker.WeakRootWitness witness =
                    runtimeState.weakSweepRootWitnesses.get(referent);
            if (witness == null || !isCurrentWeakRootWitness(witness)) return false;
        }
        if (!sameIdentityContents(weakCandidates,
                    WeakRefRegistry.snapshotWeakRefReferents())
                || !sameIdentityContents(destroyableCandidates,
                    DestroyDispatch.snapshotDestroyableObjects())) {
            return false;
        }
        runtimeState.weakSweepRootWitnesses.entrySet().removeIf(entry ->
                !targets.contains(entry.getKey())
                        && !(entry.getKey() instanceof RuntimeCode
                        && entry.getValue().liveLexicalScalar()
                        && isCurrentWeakRootWitness(entry.getValue())));
        return true;
    }

    private static boolean sameIdentityContents(
            List<RuntimeBase> first, List<RuntimeBase> second) {
        if (first.size() != second.size()) return false;
        Set<RuntimeBase> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        identities.addAll(first);
        for (RuntimeBase value : second) {
            if (!identities.remove(value)) return false;
        }
        return identities.isEmpty();
    }

    private static boolean hasExistingWeakRetentionRule(RuntimeBase referent) {
        if (referent.hasSemanticCaptureOwner()) return true;
        if ((referent instanceof RuntimeHash || referent instanceof RuntimeArray)
                && referent.localBindingExists) return true;
        if (referent instanceof RuntimeScalar scalar) {
            return scalar.type == RuntimeScalarType.UNDEF
                    || ((scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && scalar.value instanceof RuntimeCode);
        }
        return false;
    }

    private static boolean hasExistingDestroyRetentionRule(RuntimeBase referent) {
        return referent.hasSemanticCaptureOwner()
                || ((referent instanceof RuntimeHash || referent instanceof RuntimeArray)
                && referent.localBindingExists);
    }

    private static boolean hasDirectCodeRootWitness(RuntimeBase referent) {
        if (!(referent instanceof RuntimeCode code)) return false;
        if (GlobalVariable.installedGlobalCodeRefsView().contains(code)) return true;
        for (RuntimeCode active : PerlRuntime.current().executionState().activeCodeStack) {
            if (active == code) return true;
        }
        return false;
    }

    private static boolean isCurrentWeakRootWitness(WeakRootWitness witness) {
        RuntimeCode rootCode = witness == null ? null : witness.rootCode().get();
        RuntimeBase capturedAggregate = witness == null ? null : witness.capturedAggregate().get();
        RuntimeScalar ownerScalar = witness == null ? null : witness.ownerScalar().get();
        RuntimeBase referent = witness == null ? null : witness.referent().get();
        if (witness != null && witness.liveLexicalScalar()) {
            return ownerScalar != null && referent != null
                    && MyVarCleanupStack.isLive(ownerScalar)
                    && !ownerScalar.scopeExited
                    && !WeakRefRegistry.isweak(ownerScalar)
                    && (ownerScalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && ownerScalar.value == referent;
        }
        if (witness == null
                || rootCode == null || capturedAggregate == null || ownerScalar == null
                || referent == null
                || !GlobalVariable.installedGlobalCodeRefsView().contains(rootCode)
                || !rootCode.captureFieldsRecorded
                || !(capturedAggregate instanceof RuntimeArray array)
                || !isSupportedWitnessArray(array)
                || WeakRefRegistry.isweak(ownerScalar)
                || (ownerScalar.type & RuntimeScalarType.REFERENCE_BIT) == 0
                || ownerScalar.value != referent) {
            return false;
        }
        boolean captureStillInstalled = false;
        if (rootCode.capturedAggregates != null) {
            for (RuntimeBase captured : rootCode.capturedAggregates) {
                if (captured == array) {
                    captureStillInstalled = true;
                    break;
                }
            }
        }
        if (!captureStillInstalled) return false;
        for (RuntimeScalar slot : array.elements) {
            if (slot == ownerScalar) return true;
        }
        return false;
    }

    private static WeakSweepPass sweepWeakRefsPass(
            boolean quiet, boolean forceJvmGc,
            Set<RuntimeBase> releasedTargets,
            ReleasedWeakSweepResult releasedSweepResult) {
        if (!WeakRefRegistry.weakRefsExist()) return new WeakSweepPass(0, false);
        if (forceJvmGc) {
            ScalarRefRegistry.forceGcAndSnapshot();
        }
        if (!quiet) {
            // Explicit sweeps drain rescued objects. Quiet auto-sweeps run
            // during user workflows and must not clear DBIC Schema rescue
            // links while later chained calls still rely on them.
            DestroyDispatch.clearRescuedWeakRefs();
        }
        LifecycleRuntimeState runtimeState = PerlRuntime.current().lifecycleState;
        ArrayList<RuntimeBase> weakWitnessCandidates = null;
        ArrayList<RuntimeBase> destroyableWitnessCandidates = null;
        if (quiet) {
            weakWitnessCandidates =
                    new ArrayList<>(WeakRefRegistry.snapshotWeakRefReferents());
            destroyableWitnessCandidates = DestroyDispatch.snapshotDestroyableObjects();
            if (tryWitnessQuietSweep(quiet, forceJvmGc, releasedTargets,
                    releasedSweepResult, weakWitnessCandidates,
                    destroyableWitnessCandidates, runtimeState)) {
                return new WeakSweepPass(0, false);
            }
        }
        boolean reuseReleasedSweep = !forceJvmGc
                && releasedSweepResult != null
                && releasedSweepResult.live() != null;
        ReachabilityWalker fullWalker = null;
        Set<RuntimeBase> live;
        if (reuseReleasedSweep) {
            live = releasedSweepResult.live();
            runtimeState.weakSweepRootWitnesses.clear();
        } else if (quiet) {
            Set<RuntimeBase> witnessTargets =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            witnessTargets.addAll(weakWitnessCandidates);
            witnessTargets.addAll(destroyableWitnessCandidates);
            fullWalker = new ReachabilityWalker().withWeakWitnessTargets(witnessTargets);
            live = fullWalker.walk();
            runtimeState.weakSweepRootWitnesses.clear();
            runtimeState.weakSweepRootWitnesses.putAll(fullWalker.discoveredWeakRootWitnesses);
        } else {
            runtimeState.weakSweepRootWitnesses.clear();
            fullWalker = new ReachabilityWalker();
            live = fullWalker.walk();
        }
        ArrayList<RuntimeBase> toClear = new ArrayList<>();
        Set<RuntimeBase> strongCycleProtected = quiet
                ? (reuseReleasedSweep
                    ? releasedSweepResult.strongCycleProtected()
                    : collectStrongCycleProtected(live))
                : Collections.emptySet();
        Set<RuntimeBase> countedStrongOwners = releasedSweepResult == null
                ? Collections.emptySet()
                : releasedSweepResult.countedStrongOwners();
        for (RuntimeBase referent : WeakRefRegistry.snapshotWeakRefReferents()) {
            boolean liveReferent = live.contains(referent);
            // Semantic closure ownership is an explicit Perl edge.  It is not
            // necessarily visible to this conservative graph walk because
            // generated closure fields and metadata are intentionally opaque.
            if (!liveReferent && referent.hasSemanticCaptureOwner()) {
                continue;
            }
            boolean localBinding = (referent instanceof RuntimeHash || referent instanceof RuntimeArray)
                    && referent.localBindingExists;
            boolean cycleProtected = quiet && strongCycleProtected.contains(referent);
            if (!liveReferent) {
                // A named hash/array lexical (`my %h`, `my @a`) is NOT a
                // walker root — the walker only seeds from globals and
                // ScalarRefRegistry (scalars). If `\%h` was weakened,
                // `%h` itself does not appear in any walker seed set,
                // so it is trivially "unreachable" — but the lexical
                // slot is still alive. Guard: localBindingExists=true
                // means the named Perl lexical still holds this
                // container alive; skip clearing weak refs to it.
                // Scope exit (scopeExitCleanupHash/Array) will clear
                // the flag and let a later sweep reap it if truly dead.
                // Fixes op/hashassign.t 218 (bug #76716).
                if (localBinding) {
                    continue;
                }
                // Phase I (52leaks/60core): skip clearing weak refs to
                // scalars that hold CODE refs, or scalars that are already
                // UNDEF. These are commonly Sub::Quote/Sub::Defer
                // `$unquoted` / `$undeferred` lexical slots — empty
                // scalars to be filled with a compiled sub on first
                // invocation, OR already holding the compiled sub.
                // Clearing their weak refs breaks the re-dispatch chain
                // (`$$_UNQUOTED = sub { ... }` loses its slot, producing
                // "Not a CODE reference" at later dispatch points).
                // clearWeakRefsTo(RuntimeCode) is already a no-op for
                // CODE values themselves, but a weak ref pointing AT a
                // scalar that holds a CODE is a different target and
                // needs this explicit skip.
                if (referent instanceof RuntimeScalar s) {
                    if (s.type == RuntimeScalarType.UNDEF) continue;
                    if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                            && s.value instanceof RuntimeCode) {
                        continue;
                    }
                }
                // Quiet auto-sweeps can run while a deferred CODE ref is being
                // returned to its caller. Sub::Quote stores weak registry
                // entries in hashes/arrays that are strongly captured by that
                // CODE, but the caller's lexical may not be visible as a root
                // yet. Keep this exception limited to unblessed metadata
                // containers and only for quiet statement-boundary sweeps;
                // explicit jperl_gc() keeps the stricter root-based behavior.
                if (quiet
                        && referent.blessId == 0
                        && (referent instanceof RuntimeHash || referent instanceof RuntimeArray)
                        && isCapturedByWeakBackrefCode(referent)) {
                    continue;
                }
                // Quiet auto-sweeps run at statement boundaries. Do not clear
                // weak refs into, or strongly reachable from, a strong cycle:
                // Perl's refcounting keeps such cycle islands alive. This
                // covers callback-retained futures where the outer future is
                // cyclic and inner sequence futures are strong children.
                if (cycleProtected) {
                    continue;
                }
                // The released-referent pass has a narrower guard for strong
                // counted owners which the lexical/root walk cannot see. When
                // that pass and the periodic global sweep share a snapshot,
                // preserve the same guard for those requested targets.
                if (releasedTargets.contains(referent)
                        && referent.refCount > 0
                        && countedStrongOwners.contains(referent)) {
                    continue;
                }
                toClear.add(referent);
            }
        }
        for (RuntimeBase referent : DestroyDispatch.snapshotDestroyableObjects()) {
            if (referent == null
                    || referent.destroyFired
                    || referent.currentlyDestroying
                    || referent.refCount == Integer.MIN_VALUE) {
                continue;
            }
            if (live.contains(referent)) {
                continue;
            }
            if (referent.hasSemanticCaptureOwner()) {
                continue;
            }
            if ((referent instanceof RuntimeHash || referent instanceof RuntimeArray)
                    && referent.localBindingExists) {
                continue;
            }
            if (!toClear.contains(referent)) {
                toClear.add(referent);
            }
        }
        int cleared = 0;
        boolean graphChanged = false;
        Set<RuntimeBase> previousSweepLiveReferents = runtimeState.weakSweepLiveReferents;
        runtimeState.weakSweepLiveReferents = live;
        try {
            for (RuntimeBase referent : toClear) {
                // Phase I: auto-sweep (quiet) now fires DESTROY on blessed
                // unreachable objects and sets refCount=MIN_VALUE — matching
                // non-quiet jperl_gc behaviour. Previously quiet mode was
                // more conservative to avoid mid-module-init DESTROY cascades,
                // but Phase B2a's ModuleInitGuard already protects against
                // that, and Phase I's walker seed filters ensure we only
                // DESTROY genuinely unreachable objects. Without this,
                // DBICTest::Artist and similar rows held only by
                // Sub::Quote-generated internal caches never clear their
                // weak refs between auto-sweeps.
                if (referent.blessId != 0 && !referent.destroyFired
                        && referent.refCount != Integer.MIN_VALUE) {
                    referent.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(referent);
                    graphChanged = true;
                } else {
                    WeakRefRegistry.clearWeakRefsTo(referent);
                    if (referent.refCount != Integer.MIN_VALUE) {
                        referent.refCount = Integer.MIN_VALUE;
                    }
                }
                cleared++;
            }
        } finally {
            runtimeState.weakSweepLiveReferents = previousSweepLiveReferents;
        }
        return new WeakSweepPass(cleared, graphChanged);
    }

    /**
     * Reconsider only referents explicitly released by {@code undef}. This is
     * safe to run at a nested statement boundary because it cannot clear weak
     * references belonging to unrelated in-flight return values.
     */
    record ReleasedWeakSweepResult(
            Set<RuntimeBase> live,
            Set<RuntimeBase> strongCycleProtected,
            Set<RuntimeBase> countedStrongOwners,
            int cleared) {}

    public static ReleasedWeakSweepResult sweepReleasedWeakReferents(
            Set<RuntimeBase> referents) {
        if (referents == null || referents.isEmpty()) {
            return new ReleasedWeakSweepResult(null, Collections.emptySet(),
                    Collections.emptySet(), 0);
        }
        Set<RuntimeBase> pending = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RuntimeBase referent : referents) {
            if (referent == null || referent.currentlyDestroying) continue;
            // Normal decrement-to-zero destruction already clears the
            // referent's weak observers and recursively releases contained
            // objects in DestroyDispatch.callDestroy().  Re-walking every
            // root after that completed path is both redundant and quadratic
            // for object trees such as PPI's AST.  A rescued object is handled
            // by the rescue-specific cleanup path after the assignment.
            if (referent.destroyFired || referent.refCount == Integer.MIN_VALUE) continue;
            pending.add(referent);
        }
        if (pending.isEmpty()) {
            return new ReleasedWeakSweepResult(null, Collections.emptySet(),
                    Collections.emptySet(), 0);
        }

        // A registered, live, nonweak scalar with an owned direct reference is
        // itself a root in walk(). If every released target has such an owner,
        // no graph traversal is needed to preserve those targets. This is a
        // deliberately narrow proof: nested container slots, exited captures,
        // and uncounted references still use the full reachability walk below.
        Set<RuntimeBase> directlyOwned =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (RuntimeScalar owner : ScalarRefRegistry.snapshot()) {
            if (owner == null || !owner.refCountOwned || owner.scopeExited
                    || !MyVarCleanupStack.isLive(owner) || WeakRefRegistry.isweak(owner)
                    || (owner.type & RuntimeScalarType.REFERENCE_BIT) == 0
                    || !(owner.value instanceof RuntimeBase target)) {
                continue;
            }
            if (pending.contains(target)) directlyOwned.add(target);
        }
        if (directlyOwned.containsAll(pending)) {
            return new ReleasedWeakSweepResult(null, Collections.emptySet(),
                    directlyOwned, 0);
        }

        // A single released target that forms its own strong cycle is known
        // alive by Perl's refcount semantics. The full weak sweep would keep
        // this referent through collectStrongCycleProtected() after traversing
        // every runtime root. Resolve this exact target directly and leave
        // unrelated registry entries for their normal sweep cadence.
        if (pending.size() == 1) {
            RuntimeBase only = pending.iterator().next();
            if (only.blessId == 0 && hasStrongCycle(only)) {
                Set<RuntimeBase> cycleProtected =
                        Collections.newSetFromMap(new IdentityHashMap<>());
                collectStrongReachable(only, cycleProtected);
                return new ReleasedWeakSweepResult(null, cycleProtected,
                        Collections.emptySet(), 0);
            }
        }
        Set<RuntimeBase> live = new ReachabilityWalker()
                .withTemporaryRoots(false)
                .walk();
        Set<RuntimeBase> countedStrongOwners =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (RuntimeScalar owner : ScalarRefRegistry.snapshot()) {
            if (owner == null || !owner.refCountOwned || WeakRefRegistry.isweak(owner)) continue;
            // Hash/array slots in a discarded aggregate are internal owners of
            // that graph, not independent roots. Keep their referents only when
            // the containing aggregate is still reachable or was not part of
            // this explicitly released graph.
            if (owner.containerOwner != null
                    && referents.contains(owner.containerOwner)
                    && !live.contains(owner.containerOwner)) {
                continue;
            }
            if (owner.value instanceof RuntimeBase referent && referents.contains(referent)) {
                countedStrongOwners.add(referent);
            }
        }
        // Targeted release sweeps run at the same quiet statement boundary as
        // sweepWeakRefs(true). Preserve strong cycle islands here too: Perl's
        // reference counting intentionally keeps an unreachable strong cycle
        // alive, including weak diagnostic references into that cycle.
        Set<RuntimeBase> strongCycleProtected = collectStrongCycleProtected(live);
        int cleared = 0;
        boolean releasedObjectNeedsCascade = false;
        boolean releasedGraphChanged = false;
        for (RuntimeBase referent : pending) {
            if (referent == null || referent.currentlyDestroying) {
                continue;
            }
            // A targeted release only means that one explicit owner went
            // away. Other counted owners can still hold the referent through
            // local aggregate slots that are not represented in the lexical
            // root walk (notably file-scope my arrays and hashes). Preserve
            // those objects until their counted owners release them.
            if (referent.refCount > 0 && countedStrongOwners.contains(referent)) continue;
            if (live.contains(referent)) continue;
            if (strongCycleProtected.contains(referent)) continue;
            if ((referent instanceof RuntimeHash || referent instanceof RuntimeArray)
                    && referent.localBindingExists) {
                continue;
            }
            if (referent.blessId != 0) {
                referent.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(referent);
                releasedGraphChanged = true;
            } else {
                WeakRefRegistry.clearWeakRefsTo(referent);
                referent.refCount = Integer.MIN_VALUE;
            }
            cleared++;
            releasedObjectNeedsCascade = true;
        }

        // Destruction can remove the last strong edge to another weakly
        // observed object (a tied hash wrapper -> handler -> external hash is
        // one example). Reachability snapshots are intentionally immutable,
        // so iterate to a fixed point after this explicit release.
        if (releasedObjectNeedsCascade) {
            for (int pass = 0; pass < 8; pass++) {
                // This is still an automatic, statement-boundary sweep. Keep
                // DESTROY-rescued objects pinned; the targeted release above
                // must not drain unrelated (or newly rescued) object graphs.
                // Clearing only unblessed weak aliases cannot change the
                // strong root graph, so reuse the targeted pass's snapshot.
                // A DESTROY call can mutate that graph; only then rebuild it.
                ReleasedWeakSweepResult reusableSnapshot = releasedGraphChanged
                        ? null
                        : new ReleasedWeakSweepResult(live, strongCycleProtected,
                                countedStrongOwners, cleared);
                WeakSweepPass passResult = sweepWeakRefsPass(
                        true, false, referents, reusableSnapshot);
                cleared += passResult.cleared();
                releasedGraphChanged |= passResult.graphChanged();
                if (passResult.cleared() == 0 || !passResult.graphChanged()) break;
            }
        }
        // A clear or DESTROY can change the root graph. Only share these
        // snapshots with a same-boundary global sweep when the targeted pass
        // made no such change.
        return new ReleasedWeakSweepResult(
                releasedGraphChanged ? null : live,
                releasedGraphChanged ? null : strongCycleProtected,
                countedStrongOwners,
                cleared);
    }

    /**
     * Sweep only objects registered by {@link DestroyDispatch} as needing
     * deterministic DESTROY side effects even when they are not weak-ref
     * referents. This is intentionally narrower than {@link #sweepWeakRefs}:
     * it does not clear unrelated weak references and it can be used from hot
     * DBI statement-polling paths without forcing a JVM GC.
     */
    public static int sweepDestroyableObjects(boolean forceJvmGc) {
        if (!DestroyDispatch.hasDestroyableObjects()) return 0;
        if (forceJvmGc) {
            ScalarRefRegistry.forceGcAndSnapshot();
        }
        ReachabilityWalker w = new ReachabilityWalker();
        return destroyUnreachableDestroyables(w.walk());
    }

    /** Reconcile File::Temp-style external resources at a safe statement boundary. */
    static int sweepStatementBoundaryDestroyableObjects() {
        java.util.List<RuntimeBase> candidates =
                DestroyDispatch.snapshotStatementBoundaryDestroyableObjects();
        if (candidates.isEmpty()) return 0;
        Set<RuntimeBase> live = new ReachabilityWalker().walk();
        return destroyUnreachableDestroyables(candidates, live);
    }

    private static int destroyUnreachableDestroyables(Set<RuntimeBase> live) {
        return destroyUnreachableDestroyables(
                DestroyDispatch.snapshotDestroyableObjects(), live);
    }

    private static int destroyUnreachableDestroyables(
            java.util.List<RuntimeBase> candidates, Set<RuntimeBase> live) {
        int destroyed = 0;
        for (RuntimeBase referent : candidates) {
            if (referent == null
                    || referent.destroyFired
                    || referent.currentlyDestroying
                    || referent.refCount == Integer.MIN_VALUE) {
                continue;
            }
            if (live.contains(referent)) {
                continue;
            }
            if ((referent instanceof RuntimeHash || referent instanceof RuntimeArray)
                    && referent.localBindingExists) {
                continue;
            }
            referent.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroy(referent);
            destroyed++;
        }
        return destroyed;
    }

    private static Set<RuntimeBase> collectStrongCycleProtected(Set<RuntimeBase> live) {
        java.util.List<RuntimeBase> referents = WeakRefRegistry.snapshotWeakRefReferents();
        if (referents.isEmpty()) return Collections.emptySet();

        Set<RuntimeBase> protectedSet =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (RuntimeBase referent : referents) {
            // A root-reachable referent and its strong descendants are already
            // represented by the main sweep walk. Cycle checks are only needed
            // for weak targets that would otherwise be mistaken for dead
            // cycle islands.
            if (referent == null || live.contains(referent) || protectedSet.contains(referent)) {
                continue;
            }
            if (hasStrongCycle(referent)) {
                collectStrongReachable(referent, protectedSet);
            }
        }
        return protectedSet;
    }

    private static void collectStrongReachable(RuntimeBase root, Set<RuntimeBase> seen) {
        final int MAX_VISITS = 50_000;
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        if (seen.add(root)) {
            todo.addLast(root);
        }

        int visits = 0;
        while (!todo.isEmpty() && visits < MAX_VISITS) {
            RuntimeBase cur = todo.removeFirst();
            visits++;
            enqueueStrongEdges(cur, null, seen, todo);
        }
    }

    private static boolean isCapturedByWeakBackrefCode(RuntimeBase target) {
        for (RuntimeBase referent : WeakRefRegistry.snapshotWeakRefReferents()) {
            if (referent instanceof RuntimeCode code
                    && WeakRefRegistry.hasWeakRefsTo(code)
                    && isReachableThroughCodeCaptures(code, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReachableThroughCodeCaptures(RuntimeCode code, RuntimeBase target) {
        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        if (followGlobalCodeCaptures(code, target, seen, todo)) return true;

        int visits = 0;
        final int MAX_VISITS = 10_000;
        while (!todo.isEmpty() && visits++ < MAX_VISITS) {
            RuntimeBase cur = todo.removeFirst();
            if (cur == target) return true;
            if (cur instanceof RuntimeStash) {
                continue;
            } else if (cur instanceof RuntimeHash h) {
                if (h.elements instanceof HashSpecialVariable) continue;
                for (RuntimeScalar v : h.elements.values()) {
                    if (followScalar(v, target, seen, todo)) return true;
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
                    if (followScalar(v, target, seen, todo)) return true;
                }
            } else if (cur instanceof RuntimeCode nestedCode) {
                if (followGlobalCodeCaptures(nestedCode, target, seen, todo)) return true;
            } else if (cur instanceof RuntimeScalar s) {
                if (followScalar(s, target, seen, todo)) return true;
            }
        }
        return false;
    }
}
