package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.regex.RuntimeRegex;

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

/** Read-only root and owner diagnostics. Lifecycle decisions use owner slots. */
public class ReachabilityWalker {
    private static final String WALKER_TRACE_CLASS =
            System.getenv("JPERL_WALKER_TRACE_CLASS");

    private record ReflectiveCaptureFields(
            java.lang.reflect.Field[] scalars,
            java.lang.reflect.Field[] bases) {}

    // Generated closure implementations have a stable class layout. Cache
    // captured fields per class so repeated diagnostic queries avoid reflective
    // discovery.
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

    // Root diagnostics ordinarily retain expression temporaries. A targeted
    // query can exclude stale runtime temporary roots after Perl's FREETMPS
    // boundary has released them.
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

    private static boolean isNonOwningDebugArgsArray(String name) {
        // @DB::args aliases caller arguments for debugger/Carp introspection.
        // It is not an owning root after the source frame has unwound; live
        // frames are already represented by RuntimeCode.snapshotArgsStack().
        return "DB::args".equals(name);
    }

    private static boolean hasCapturedAggregateElementOwner(RuntimeBase referent) {
        for (RuntimeScalar owner : ScalarRefRegistry.snapshot()) {
            if (owner == null || !owner.refCountOwned || owner.value != referent) continue;
            RuntimeBase container = owner.containerOwner;
            if (container instanceof RuntimeArray array
                    && array.hasClosureCaptureOwner() && array.elements.contains(owner)) {
                return true;
            }
            if (container instanceof RuntimeHash hash
                    && hash.hasClosureCaptureOwner() && hash.elements.containsValue(owner)) {
                return true;
            }
        }
        return false;
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
        // Diagnostics can run inside RuntimeCode.apply(); include that active
        // closure's captures when tracing its current roots.
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
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForDiagnostics()) {
            addReachable(rescued, todo);
        }
        // Explicit owner slots carry semantic Perl ownership even when the
        // owning pad cell is intentionally absent from conservative lexical
        // walker roots. Captured aggregate pads use this path so their
        // elements remain reachable until the closure releases the slot.
        for (RuntimeBase ownerReferent : PerlRuntime.snapshotOwnerSlotReferents()) {
            addReachable(ownerReferent, todo);
        }
        if (useLexicalSeeds) {
            // Seed from live my-vars themselves (RuntimeArray / RuntimeHash
            // declared with `my @arr` / `my %hash`) so a diagnostic query
            // reports the current Perl roots.
            //
            // Mirrors the seeding already in `isReachableFromRoots()`.
            // Order matters: RuntimeScalar IS-A RuntimeBase, so the
            // RuntimeScalar branch must come first to walk through its
            // reference bit. Otherwise the BFS only steps into hashes
            // and arrays, missing the scalar's referent. The previous
            // ScalarRefRegistry seed was redundant: every scalar accepted
            // by its MyVarCleanupStack.isLive check is present in this list.
            // Use this one live-slot snapshot instead of copying and rescanning
            // the scalar registry for this diagnostic query.
            for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
                if (liveVar instanceof RuntimeScalar sc) {
                    if (WeakRefRegistry.isweak(sc)) continue;
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
                // This broader weak-reference walk can also run during a
                // child thread's teardown while another thread mutates a
                // shared array. Never expose ArrayList's fail-fast iterator
                // to concurrent Perl updates.
                for (RuntimeScalar v : a.elements.toArray(RuntimeScalar[]::new)) {
                    addReachable(v, todo);
                    visitScalar(v, todo);
                }
            } else if (cur instanceof RuntimeList list) {
                // RuntimeList values are expression results rather than Perl
                // containers, but a returned list can be held as a temporary
                // root while its caller finishes scope cleanup. Follow its
                // strong values for that lease just like aggregate elements.
                if (list instanceof RuntimeControlFlowList flow
                        && flow.getReturnValue() != null) {
                    addReachable(flow.getReturnValue(), todo);
                    if (flow.getReturnValue() instanceof RuntimeScalar scalar) {
                        visitScalar(scalar, todo);
                    }
                }
                for (RuntimeBase value : list.elements) {
                    addReachable(value, todo);
                    if (value instanceof RuntimeScalar scalar) {
                        visitScalar(scalar, todo);
                    }
                }
            } else if (cur instanceof RuntimeCode code) {
                if (!hasWalkableCodeEdges(code, walkCaptures)) {
                    continue;
                }
                visitCodeStateVariables(code, todo);
                // Phase 2 normally keeps closure captures opaque to avoid
                // over-rescuing DBIC objects through internal callbacks.
                // Exception: Sub::Defer/Sub::Quote deferred wrappers keep a
                // weak backref to the CODE object itself. If such a CODE ref
                // is reachable through package metadata (not a direct stash
                // entry), its captured info array belongs in the diagnostic
                // root set.
                if (walkCaptures || WeakRefRegistry.hasWeakRefsTo(code)) {
                    visitCodeCaptures(code, todo);
                } else if (code.capturedAggregates != null) {
                    // Explicit aggregate capture entries are semantic owner
                    // edges even when conservative scalar/reflection capture
                    // walking is disabled. Unlike retired interpreter capture
                    // metadata, this list is cleared by releaseCaptures().
                    for (RuntimeBase aggregate : code.capturedAggregates) {
                        addReachable(aggregate, todo);
                    }
                }
            } else if (cur instanceof RuntimeRegex regex) {
                for (RuntimeCode callback : regex.executableCallbackCodes()) {
                    addReachable(callback, todo);
                    visitCodeStateVariables(callback, todo);
                    visitCodeCaptures(callback, todo);
                }
            } else if (cur instanceof RuntimeScalar s) {
                visitScalar(s, todo);
            }
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
        if (!code.stateVariable.isEmpty()
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
     * per-query identity set is redundant. Expose them through a union view so
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
     * disabled. Omitting these edges would make diagnostics miss a live state
     * singleton, as used by Mojo::IOLoop.</p>
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
        visitReflectiveCodeBases(code, base -> addReachable(base, todo));
        // InterpretedCode.capturedVars is immutable execution-frame metadata.
        // Captured entries can remain there after their lexical lifetime has
        // ended and releaseCaptures has removed them from the semantic owner
        // lists. Only capturedScalars/capturedAggregates describe live owners.
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
        return findPathTo(target, false, true);
    }

    public static java.util.List<String> findPathTo(RuntimeBase target, boolean skipLexicalSeeds) {
        return findPathTo(target, skipLexicalSeeds, true);
    }

    private static java.util.List<String> findPathTo(
            RuntimeBase target, boolean skipLexicalSeeds, boolean forceJvmGc) {
        java.util.IdentityHashMap<RuntimeBase, String> howReached = new java.util.IdentityHashMap<>();
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        // Seed from roots with labels
        for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.scalarSlots().entrySet()) {
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
        int activeCodeIdx = 0;
        for (RuntimeCode active : PerlRuntime.current().executionState().activeCodeStack) {
            String label = "<active-code#" + (activeCodeIdx++) + ">";
            if (howReached.putIfAbsent(active, label) == null) todo.add(active);
        }
        int argsFrameIdx = 0;
        for (List<RuntimeScalar> args : RuntimeCode.snapshotPristineArgsStack()) {
            for (RuntimeScalar arg : args) {
                seedPath(arg, "<args-frame#" + argsFrameIdx + ">", howReached, todo);
            }
            argsFrameIdx++;
        }
        int rescuedIdx = 0;
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForDiagnostics()) {
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
        // trace matches the other diagnostic root queries.
        // Phase I: force GC before snapshotting so stale
        // (already-Java-unreachable) entries don't produce misleading
        // "live-lexical" paths in diagnostic traces.
        // skipLexicalSeeds=true omits this — produces a path that goes
        // through Perl-semantic data (globals/stash/rescued) only.
        int scIdx = 0;
        if (!skipLexicalSeeds) {
            java.util.List<RuntimeScalar> lexicalScalars = forceJvmGc
                    ? ScalarRefRegistry.forceGcAndSnapshot()
                    : ScalarRefRegistry.snapshot();
            for (RuntimeScalar sc : lexicalScalars) {
                if (sc == null) continue;
                if (sc.hasClosureCaptureOwner()) continue;
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
                if (h.elements instanceof TieHash tieHash) {
                    visitScalarPath(tieHash.getSelf(), curPath + "<tie-self>", howReached, todo);
                }
                for (Map.Entry<String, RuntimeScalar> ent : h.elements.entrySet()) {
                    visitScalarPath(ent.getValue(), curPath + "{" + ent.getKey() + "}", howReached, todo);
                }
            } else if (cur instanceof RuntimeArray a) {
                int idx = 0;
                if (a.elements instanceof TieArray tieArray) {
                    visitScalarPath(tieArray.getSelf(), curPath + "<tie-self>", howReached, todo);
                }
                for (RuntimeScalar v : a.elements) {
                    visitScalarPath(v, curPath + "[" + (idx++) + "]", howReached, todo);
                }
            } else if (cur instanceof RuntimeList list) {
                int idx = 0;
                if (list instanceof RuntimeControlFlowList flow
                        && flow.getReturnValue() != null) {
                    String path = curPath + "<return-value>";
                    RuntimeBase returnValue = flow.getReturnValue();
                    if (returnValue instanceof RuntimeScalar scalar) {
                        visitScalarPath(scalar, path, howReached, todo);
                    } else if (howReached.putIfAbsent(returnValue, path) == null) {
                        todo.add(returnValue);
                    }
                }
                for (RuntimeBase value : list.elements) {
                    String path = curPath + "[" + (idx++) + "]";
                    if (value instanceof RuntimeScalar scalar) {
                        visitScalarPath(scalar, path, howReached, todo);
                    } else if (value != null
                            && howReached.putIfAbsent(value, path) == null) {
                        todo.add(value);
                    }
                }
            } else if (cur instanceof RuntimeCode code) {
                if (code.closedOverVariables != null) {
                    for (Map.Entry<String, RuntimeBase> captured
                            : code.closedOverVariables.entrySet()) {
                        String path = curPath + "<closed-over " + captured.getKey() + ">";
                        RuntimeBase value = captured.getValue();
                        if (value instanceof RuntimeScalar scalar) {
                            visitScalarPath(scalar, path, howReached, todo);
                        } else if (howReached.putIfAbsent(value, path) == null) {
                            todo.add(value);
                        }
                    }
                }
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
                // so findPathTo traces through the same graph as root queries.
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
                final int[] reflectiveBaseIdx = {0};
                visitReflectiveCodeBases(code, captured -> {
                    String path = curPath + "<closure " + name + "::" + sub
                            + " base-cap#" + (reflectiveBaseIdx[0]++) + ">";
                    if (howReached.putIfAbsent(captured, path) == null) todo.add(captured);
                });
            } else if (cur instanceof RuntimeScalar scalar
                    && !WeakRefRegistry.isweak(scalar)
                    && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && scalar.value instanceof RuntimeBase referent) {
                if (howReached.putIfAbsent(referent, curPath + "->") == null) {
                    todo.add(referent);
                }
            } else if (cur instanceof RuntimeRegex regex) {
                int callbackIdx = 0;
                for (RuntimeCode callback : regex.executableCallbackCodes()) {
                    String path = curPath + "<regex callback#" + (callbackIdx++) + ">";
                    if (howReached.putIfAbsent(callback, path) == null) todo.add(callback);
                }
            }
        }
        return null;
    }

    private static void seedPath(RuntimeScalar s, String label,
                                  java.util.IdentityHashMap<RuntimeBase, String> howReached,
                                  java.util.ArrayDeque<RuntimeBase> todo) {
        if (s == null) return;
        if (WeakRefRegistry.isweak(s)) return;
        if (howReached.putIfAbsent(s, label) == null) todo.add(s);
    }

    private static void visitScalarPath(RuntimeScalar s, String path,
                                         java.util.IdentityHashMap<RuntimeBase, String> howReached,
                                         java.util.ArrayDeque<RuntimeBase> todo) {
        if (s == null) return;
        if (WeakRefRegistry.isweak(s)) return;
        if (howReached.putIfAbsent(s, path) == null) todo.add(s);
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
        } else if (cur instanceof RuntimeRegex regex) {
            for (RuntimeCode callback : regex.executableCallbackCodes()) {
                if (callback == target) return true;
                if (seen.add(callback)) todo.addLast(callback);
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
     * case is the same nodes-visited bound as a full diagnostic walk, which is
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
     * Node and edge budgets bound both wide and deep root graphs; an incomplete
     * snapshot conservatively treats unresolved targets as reachable.
     */
    record RootReachabilitySnapshot(Set<RuntimeBase> reachable, boolean complete,
                                    int edgesVisited) {
        boolean isReachable(RuntimeBase target) {
            // A node- or edge-cap hit cannot prove an unresolved target dead.
            return !complete || reachable.contains(target);
        }
    }

    private static final class RootWalkBudget {
        private final int maxEdges;
        private int edgesVisited;
        private boolean complete = true;

        private RootWalkBudget(int maxEdges) {
            this.maxEdges = maxEdges;
        }

        private boolean visitEdge() {
            if (edgesVisited >= maxEdges) {
                complete = false;
                return false;
            }
            edgesVisited++;
            return true;
        }
    }

    static final int MAX_ROOT_SNAPSHOT_EDGES = 50_000;
    static final int MAX_ROOT_SNAPSHOT_VISITS = 50_000;

    static Set<RuntimeBase> reachableFromRootsSnapshot() {
        return reachableFromRootsSnapshotWithStatus().reachable();
    }

    static RootReachabilitySnapshot reachableFromRootsSnapshotWithStatus() {
        return reachableFromRootsSnapshotWithStatus(true);
    }

    /**
     * Collect the bounded root graph needed to answer reachability for
     * unblessed aggregates. Installed CODE slot identities cannot own an
     * aggregate by themselves; their captured values are followed separately
     * through the cached CODE roots with capture edges. Omitting terminal CODE
     * slots avoids seeding every installed subroutine during aggregate cleanup.
     */
    static RootReachabilitySnapshot unblessedAggregateRootsSnapshotWithStatus() {
        return reachableFromRootsSnapshotWithStatus(false);
    }

    private static RootReachabilitySnapshot reachableFromRootsSnapshotWithStatus(
            boolean includeInstalledCodeSlots) {
        ReachabilityQueryStats stats = MortalList.activeReachabilityQueryStats();
        if (stats != null) stats.snapshotsBuilt++;
        final int maxVisits = MAX_ROOT_SNAPSHOT_VISITS;
        RootWalkBudget budget = new RootWalkBudget(MAX_ROOT_SNAPSHOT_EDGES);
        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>(512));
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        rootScan: {
            if (includeInstalledCodeSlots) {
                for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefValuesView()) {
                    if (!budget.visitEdge()) break rootScan;
                    seedTarget(codeRef, null, seen, todo, stats);
                }
            }
            for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefEdgeRootsView()) {
                if (codeRef != null && codeRef.value instanceof RuntimeCode code) {
                    if (stats != null) stats.globalCodeCaptureRootsWalked++;
                    followGlobalCodeCaptures(code, null, seen, todo, budget);
                    if (!budget.complete) break rootScan;
                }
            }
            for (RuntimeScalar scalar : GlobalVariable.globalVariableValuesView()) {
                if (!budget.visitEdge()) break rootScan;
                seedTarget(scalar, null, seen, todo, stats);
            }
            for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrayEntriesView()) {
                if (!budget.visitEdge()) break rootScan;
                if (!isNonOwningDebugArgsArray(e.getKey()) && e.getValue() != null) {
                    if (seen.add(e.getValue())) {
                        todo.addLast(e.getValue());
                        if (stats != null) stats.rootsSeeded++;
                    }
                }
            }
            for (RuntimeHash hash : GlobalVariable.globalHashValuesView()) {
                if (!budget.visitEdge()) break rootScan;
                if (hash != null && seen.add(hash)) {
                    todo.addLast(hash);
                    if (stats != null) stats.rootsSeeded++;
                }
            }
            for (RuntimeScalar scalar : ScalarRefRegistry.snapshot()) {
                if (!budget.visitEdge()) break rootScan;
                if (scalar == null || scalar.hasClosureCaptureOwner()
                        || WeakRefRegistry.isweak(scalar)
                        || (!MyVarCleanupStack.isLive(scalar) && !scalar.refCountOwned)
                        || scalar.scopeExited) continue;
                seedTarget(scalar, null, seen, todo, stats);
            }
            for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
                if (!budget.visitEdge()) break rootScan;
                if (liveVar instanceof RuntimeScalar scalar) {
                    if (seen.add(scalar) && stats != null) stats.rootsSeeded++;
                    seedTarget(scalar, null, seen, todo, stats);
                } else if (liveVar instanceof RuntimeBase base && seen.add(base)) {
                    todo.addLast(base);
                    if (stats != null) stats.rootsSeeded++;
                }
            }
            for (Object registeredVar : MyVarCleanupStack.snapshotRegisteredVars()) {
                if (!budget.visitEdge()) break rootScan;
                if (registeredVar instanceof RuntimeScalar scalar
                        && scalar.type == RuntimeScalarType.REGEX
                        && !scalar.scopeExited
                        && !WeakRefRegistry.isweak(scalar)) {
                    seedTarget(scalar, null, seen, todo, stats);
                }
            }
            for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForDiagnostics()) {
                if (!budget.visitEdge()) break rootScan;
                if (seen.add(rescued)) {
                    todo.addLast(rescued);
                    if (stats != null) stats.rootsSeeded++;
                }
            }
            for (RuntimeBase ownerReferent : PerlRuntime.snapshotOwnerSlotReferents()) {
                if (!budget.visitEdge()) break rootScan;
                if (seen.add(ownerReferent)) {
                    todo.addLast(ownerReferent);
                    if (stats != null) stats.rootsSeeded++;
                }
            }
        }

        int visits = 0;
        walk: while (!todo.isEmpty() && visits < maxVisits) {
            visits++;
            RuntimeBase cur = todo.removeFirst();
            if (stats != null) stats.nodesVisited++;
            if (cur instanceof RuntimeStash) continue;
            if (cur instanceof RuntimeHash hash) {
                if (hash.elements instanceof TieHash tieHash) {
                    if (!budget.visitEdge()) break walk;
                    if (stats != null) stats.edgesInspected++;
                    followScalar(tieHash.getSelf(), null, seen, todo);
                }
                for (RuntimeScalar value : hash.elements.values()) {
                    if (!budget.visitEdge()) break walk;
                    if (stats != null) stats.edgesInspected++;
                    followScalar(value, null, seen, todo);
                }
            } else if (cur instanceof RuntimeArray array) {
                for (RuntimeScalar value : array.elements) {
                    if (!budget.visitEdge()) break walk;
                    if (stats != null) stats.edgesInspected++;
                    followScalar(value, null, seen, todo);
                }
            } else if (cur instanceof RuntimeRegex regex) {
                for (RuntimeCode callback : regex.executableCallbackCodes()) {
                    if (!budget.visitEdge()) break walk;
                    if (stats != null) stats.edgesInspected++;
                    followGlobalCodeCaptures(callback, null, seen, todo, budget);
                }
            }
        }
        return new RootReachabilitySnapshot(seen, budget.complete && todo.isEmpty(),
                budget.edgesVisited);
    }

    public static boolean hasLiveStrongScalarReferent(RuntimeBase target) {
        return hasLiveStrongScalarReferentOtherThan(target, null);
    }

    public static boolean hasLiveStrongScalarReferentOtherThan(
            RuntimeBase target, RuntimeScalar excluded) {
        return target != null && target.hasLiveStrongScalarOwnerSlotOtherThan(excluded);
    }

    public static boolean isReachableFromRoots(RuntimeBase target, boolean globalOnly) {
        if (target == null) return false;
        ReachabilityQueryStats stats = MortalList.activeReachabilityQueryStats();
        if (stats != null) stats.rootQueries++;
        // An active owner slot is already a direct Perl-ownership proof for
        // ordinary root checks. Use it before scanning package containers;
        // global-only callers still need to distinguish package roots from
        // lexical and runtime-owned slots.
        if (!globalOnly && target.hasOwnerSlots()) return true;
        // Node and edge caps bound both graph traversal and root seeding. An
        // incomplete proof conservatively retains an unresolved target.
        final int MAX_VISITS = MAX_ROOT_SNAPSHOT_VISITS;
        RootWalkBudget budget = new RootWalkBudget(MAX_ROOT_SNAPSHOT_EDGES);

        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        // Seed: package globals (scalars, arrays, hashes, code refs).
        for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefValuesView()) {
            if (!budget.visitEdge()) return true;
            seedTarget(codeRef, target, seen, todo, stats);
            if (seen.contains(target)) return true;
            if (!globalOnly
                    && codeRef != null
                    && codeRef.value instanceof RuntimeCode code) {
                if (followGlobalCodeCaptures(code, target, seen, todo, budget)) return true;
                if (!budget.complete) return true;
            }
        }
        for (RuntimeScalar scalar : GlobalVariable.globalVariableValuesView()) {
            if (!budget.visitEdge()) return true;
            seedTarget(scalar, target, seen, todo, stats);
            if (seen.contains(target)) return true;
        }
        for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrayEntriesView()) {
            if (isNonOwningDebugArgsArray(e.getKey())) continue;
            if (!budget.visitEdge()) return true;
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
            if (!budget.visitEdge()) return true;
            if (hash == target) {
                if (stats != null) stats.rootsSeeded++;
                return true;
            }
            if (hash != null && seen.add(hash)) {
                todo.addLast(hash);
                if (stats != null) stats.rootsSeeded++;
            }
        }
        for (RuntimeBase ownerReferent : PerlRuntime.snapshotOwnerSlotReferents()) {
            if (!budget.visitEdge()) return true;
            if (ownerReferent == target) return true;
            if (ownerReferent != null && seen.add(ownerReferent)) {
                todo.addLast(ownerReferent);
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
                if (!budget.visitEdge()) return true;
                if (sc == null) continue;
                if (sc.hasClosureCaptureOwner()) continue;
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
                if (!budget.visitEdge()) return true;
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
            // Live regex lexicals own executable callback captures. Seed only
            // regex scalars from the actual cleanup stack so callback captures
            // remain reachable without widening ordinary lexical reachability
            // for the refcount walk.
            for (Object registeredVar : MyVarCleanupStack.snapshotRegisteredVars()) {
                if (!budget.visitEdge()) return true;
                if (!(registeredVar instanceof RuntimeScalar scalar)
                        || scalar.type != RuntimeScalarType.REGEX
                        || scalar.scopeExited
                        || WeakRefRegistry.isweak(scalar)) continue;
                seedTarget(scalar, target, seen, todo, stats);
                if (seen.contains(target)) return true;
            }
        }
        // Seed: rescued objects.
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForDiagnostics()) {
            if (!budget.visitEdge()) return true;
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
        walk: while (!todo.isEmpty() && visits < MAX_VISITS) {
            RuntimeBase cur = todo.removeFirst();
            visits++;
            if (stats != null) stats.nodesVisited++;
            if (cur == target) return true;
            // Phase D-W2 (perf): skip RuntimeStash — see bfs().
            if (cur instanceof RuntimeStash) continue;
            if (cur instanceof RuntimeHash h) {
                if (h.elements instanceof TieHash) {
                    if (!budget.visitEdge()) return true;
                    if (stats != null) stats.edgesInspected++;
                }
                if (h.elements instanceof TieHash tieHash
                        && followScalar(tieHash.getSelf(), target, seen, todo)) {
                    return true;
                }
                for (RuntimeScalar v : h.elements.values()) {
                    if (!budget.visitEdge()) break walk;
                    if (stats != null) stats.edgesInspected++;
                    if (followScalar(v, target, seen, todo)) return true;
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
                    if (!budget.visitEdge()) break walk;
                    if (stats != null) stats.edgesInspected++;
                    if (followScalar(v, target, seen, todo)) return true;
                }
            } else if (cur instanceof RuntimeRegex regex) {
                // Executable regex callbacks are strong owners of their
                // lexical captures while a qr// scalar remains live. Follow
                // only these callback capture edges; ordinary closure edges
                // remain opaque to this refcount reachability query.
                for (RuntimeCode callback : regex.executableCallbackCodes()) {
                    if (!budget.visitEdge()) break walk;
                    if (callback.capturedScalars != null) {
                        for (RuntimeScalar captured : callback.capturedScalars) {
                            if (!budget.visitEdge()) break walk;
                            if (stats != null) stats.edgesInspected++;
                            if (captured == target) return true;
                        }
                    }
                    if (callback.closedOverVariables != null) {
                        for (RuntimeBase captured : callback.closedOverVariables.values()) {
                            if (!budget.visitEdge()) break walk;
                            if (stats != null) stats.edgesInspected++;
                            if (captured == target) return true;
                        }
                    }
                    if (followGlobalCodeCaptures(callback, target, seen, todo, budget)) return true;
                    if (!budget.complete) break walk;
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
        }
        return !budget.complete || !todo.isEmpty();
    }

    /**
     * Return true when {@code target} is held by a live reference scalar other
     * than its own named my-variable slot. This is deliberately narrower than a
     * full root walk: it is a focused diagnostic query and DBIC can have
     * thousands of live roots by the time its leak tests initialize.
     */
    public static boolean isReachableFromExternalRoot(RuntimeBase target) {
        return new ExternalRootSnapshot().isReachable(target);
    }

    public static boolean isReachableFromExternalRootExcludingRescued(RuntimeBase target) {
        return new ExternalRootSnapshot(false).isReachable(target);
    }

    /**
     * Return the objects reached after crossing a tied hash handler from a
     * live Perl root. A mortal drain can ask this question for many weak
     * referents, so collecting one bounded snapshot avoids an O(targets x
     * roots) traversal and its corresponding temporary objects. If the visit
     * budget truncates the walk, unresolved targets are conservatively treated
     * as reachable.
     */
    static TiedHashReachabilitySnapshot reachableThroughTiedHashes() {
        final int maxVisits = MAX_ROOT_SNAPSHOT_VISITS;
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
        return new TiedHashReachabilitySnapshot(reachableTied, todo.isEmpty());
    }

    static final class TiedHashReachabilitySnapshot {
        private final Set<RuntimeBase> reachable;
        private final boolean complete;

        TiedHashReachabilitySnapshot(Set<RuntimeBase> reachable,
                                     boolean complete) {
            this.reachable = reachable;
            this.complete = complete;
        }

        boolean contains(RuntimeBase target) {
            return target != null && (!complete || reachable.contains(target));
        }

        boolean complete() {
            return complete;
        }
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
        final int MAX_VISITS = MAX_ROOT_SNAPSHOT_VISITS;

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
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForDiagnostics()) {
            if (rescued == target) return true;
            if (seen.add(rescued)) todo.addLast(rescued);
        }
        for (RuntimeScalar sc : ScalarRefRegistry.snapshot()) {
            if (sc == null) continue;
            if (sc.hasClosureCaptureOwner()) continue;
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
        return !todo.isEmpty();
    }

    /**
     * Cached equivalent of {@link #isReachableFromExternalRoot(RuntimeBase)}.
     * <p>
     * The package/rescued root graph is snapshotted separately from live
     * lexical roots so callers can invalidate the live-lexical snapshot when
     * scope registrations change without forcing a package-root rebuild.
     */
    public static final class ExternalRootSnapshot {
        private final Set<RuntimeBase> nonLexicalReachable =
                Collections.newSetFromMap(new IdentityHashMap<>(512));
        private final ReachabilityQueryStats queryStats =
                MortalList.activeReachabilityQueryStats();
        private boolean complete = true;
        private final RootWalkBudget budget =
                new RootWalkBudget(MAX_ROOT_SNAPSHOT_EDGES);

        public ExternalRootSnapshot() {
            this(true);
        }

        public ExternalRootSnapshot(boolean includeRescued) {
            if (queryStats != null) queryStats.externalRootSnapshotsBuilt++;
            buildNonLexicalRoots(includeRescued);
        }

        public boolean isReachable(RuntimeBase target) {
            if (target == null) return false;
            if (nonLexicalReachable.contains(target)) return true;
            if (!complete) return true;
            return new LiveRootSnapshot().isReachable(target);
        }

        /** Whether the node and edge budgets covered the complete root graph. */
        boolean complete() {
            return complete;
        }

        /**
         * Positive proof only. A false result can mean the snapshot hit its
         * visit budget before reaching the target; callers must consult
         * {@link #complete()} before treating absence as unreachable.
         */
        public boolean isReachableFromNonLexicalRoot(RuntimeBase target) {
            return target != null && nonLexicalReachable.contains(target);
        }

        private void buildNonLexicalRoots(boolean includeRescued) {
            java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

            // Every installed CODE slot is itself a root, but only the
            // cached subset with capture/state edges needs reflective capture
            // traversal. Named subs with empty pads otherwise force this
            // diagnostic query to inspect every generated implementation object.
            rootScan: {
                for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefValuesView()) {
                    if (!seedNonLexicalScalar(codeRef, todo)) break rootScan;
                }
                for (RuntimeScalar codeRef : GlobalVariable.globalCodeRefEdgeRootsView()) {
                    if (!visitExternalEdge()) break rootScan;
                    if (codeRef != null && codeRef.value instanceof RuntimeCode code) {
                        seedGlobalCodeCaptures(code, todo);
                        if (!complete) break rootScan;
                    }
                }
                for (RuntimeScalar scalar : GlobalVariable.globalVariableValuesView()) {
                    if (!seedNonLexicalScalar(scalar, todo)) break rootScan;
                }
                for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrayEntriesView()) {
                    if (isNonOwningDebugArgsArray(e.getKey())) continue;
                    if (!visitExternalEdge()) break rootScan;
                    addNonLexical(e.getValue(), todo);
                }
                for (RuntimeHash hash : GlobalVariable.globalHashValuesView()) {
                    if (!visitExternalEdge()) break rootScan;
                    addNonLexical(hash, todo);
                }
                if (includeRescued) {
                    for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForDiagnostics()) {
                        if (!visitExternalEdge()) break rootScan;
                        addNonLexical(rescued, todo);
                    }
                }
            }

            int visits = 0;
            while (complete && !todo.isEmpty() && visits < MAX_ROOT_SNAPSHOT_VISITS) {
                RuntimeBase cur = todo.removeFirst();
                visits++;
                if (queryStats != null) queryStats.externalRootSnapshotNodesVisited++;
                walkSnapshotNode(cur, todo);
            }
            complete = complete && budget.complete && todo.isEmpty();
        }

        private void walkSnapshotNode(RuntimeBase cur,
                                      java.util.ArrayDeque<RuntimeBase> nonLexicalTodo) {
            if (cur instanceof RuntimeStash) return;
            if (cur instanceof RuntimeHash h) {
                if (h.elements instanceof HashSpecialVariable) return;
                if (h.elements instanceof TieHash tieHash) {
                    seedNonLexicalScalar(tieHash.getSelf(), nonLexicalTodo);
                    if (!complete) return;
                }
                for (RuntimeScalar v : h.elements.values()) {
                    if (!seedNonLexicalScalar(v, nonLexicalTodo)) return;
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
                    if (!seedNonLexicalScalar(v, nonLexicalTodo)) return;
                }
            } else if (cur instanceof RuntimeScalar sc) {
                seedNonLexicalScalar(sc, nonLexicalTodo);
            }
        }

        private boolean seedNonLexicalScalar(RuntimeScalar s,
                                             java.util.ArrayDeque<RuntimeBase> todo) {
            if (!visitExternalEdge()) return false;
            if (s == null) return true;
            if (WeakRefRegistry.isweak(s)) return true;
            if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && s.value instanceof RuntimeBase b) {
                addNonLexical(b, todo);
            }
            return true;
        }

        private boolean visitExternalEdge() {
            if (!budget.visitEdge()) {
                complete = false;
                return false;
            }
            if (queryStats != null) queryStats.externalRootSnapshotEdgesInspected++;
            return true;
        }

        private void seedGlobalCodeCaptures(RuntimeCode code,
                                            java.util.ArrayDeque<RuntimeBase> todo) {
            if (code.capturedScalars != null) {
                for (RuntimeScalar cap : code.capturedScalars) {
                    seedNonLexicalScalar(cap, todo);
                    if (!complete) return;
                }
            }
            if (code.capturedAggregates != null) {
                for (RuntimeBase cap : code.capturedAggregates) {
                    if (!visitExternalEdge()) return;
                    addNonLexical(cap, todo);
                }
            }
            visitReflectiveCodeScalars(code, cap -> {
                if (complete) seedNonLexicalScalar(cap, todo);
            });
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
        private static final Object MIXED_DIRECT_LEXICAL_ORIGIN = new Object();

        private final ReachabilityQueryStats queryStats =
                MortalList.activeReachabilityQueryStats();

        private final Set<RuntimeBase> directLexicalRoots =
                Collections.newSetFromMap(new IdentityHashMap<>(512));
        private final IdentityHashMap<RuntimeBase, Object> directLexicalOrigins =
                new IdentityHashMap<>(512);
        private boolean complete = true;

        public LiveRootSnapshot() {
            if (queryStats != null) queryStats.liveRootSnapshotsBuilt++;
            build();
        }

        public boolean isReachable(RuntimeBase target) {
            if (target == null) return false;
            if (!complete) return true;
            Object origin = directLexicalOrigins.get(target);
            if (origin == null) return false;
            if (!directLexicalRoots.contains(target)) return true;
            return origin != target;
        }

        /** Whether this node is in the lexical graph and may need rewalking after mutation. */
        boolean mayContain(RuntimeBase target) {
            return !complete || directLexicalOrigins.containsKey(target);
        }

        /** Whether the visit budget drained the complete queued lexical graph. */
        boolean complete() {
            return complete;
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
            while (!todo.isEmpty() && visits < MAX_ROOT_SNAPSHOT_VISITS) {
                DirectRootStep step = todo.removeFirst();
                visits++;
                if (queryStats != null) queryStats.liveRootSnapshotNodesVisited++;
                walkLiveNode(step.base, todo, step.origin);
            }
            complete = todo.isEmpty();
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
        return followGlobalCodeCaptures(code, target, seen, todo, null);
    }

    private static boolean followGlobalCodeCaptures(RuntimeCode code, RuntimeBase target,
                                                    Set<RuntimeBase> seen,
                                                    java.util.ArrayDeque<RuntimeBase> todo,
                                                    RootWalkBudget budget) {
        for (RuntimeScalar state : code.stateVariable.values()) {
            if (budget != null && !budget.visitEdge()) return false;
            if (followScalar(state, target, seen, todo)) return true;
        }
        for (RuntimeArray state : code.stateArray.values()) {
            if (budget != null && !budget.visitEdge()) return false;
            if (state == target) return true;
            if (seen.add(state)) todo.addLast(state);
        }
        for (RuntimeHash state : code.stateHash.values()) {
            if (budget != null && !budget.visitEdge()) return false;
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
                if (budget != null && !budget.visitEdge()) return false;
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
                if (budget != null && !budget.visitEdge()) return false;
                if (followScalar(cap, target, seen, todo)) return true;
            }
        }
        if (code.capturedAggregates != null) {
            for (RuntimeBase cap : code.capturedAggregates) {
                if (budget != null && !budget.visitEdge()) return false;
                if (cap == target) return true;
                if (seen.add(cap)) todo.addLast(cap);
            }
        }
        final boolean[] foundReflectiveCapture = {false};
        visitReflectiveCodeScalars(code, cap -> {
            if (budget != null && !budget.visitEdge()) return;
            if (!foundReflectiveCapture[0] && followScalar(cap, target, seen, todo)) {
                foundReflectiveCapture[0] = true;
            }
        });
        if (budget != null && !budget.complete) return false;
        if (foundReflectiveCapture[0]) return true;
        final boolean[] foundReflectiveBase = {false};
        visitReflectiveCodeBases(code, base -> {
            if (budget != null && !budget.visitEdge()) return;
            if (foundReflectiveBase[0]) return;
            if (base == target) {
                foundReflectiveBase[0] = true;
            } else if (seen.add(base)) {
                todo.addLast(base);
            }
        });
        if (budget != null && !budget.complete) return false;
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


}
