package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

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

    // Re-use the weak-ref registry's internal map (we add a getter)
    private final Set<RuntimeBase> reachable =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    // Whether to follow RuntimeCode.capturedScalars edges. Off by default
    // because Sub::Quote/Moo-generated accessors over-capture instances,
    // which would mark DBIC Schema/ResultSource instances as reachable
    // even after they should be GC'd. Native Perl doesn't hit this pitfall
    // because its refcount already tracks the captures accurately.
    private boolean walkCodeCaptures = false;

    // Phase B1: whether to seed the walk from ScalarRefRegistry — the
    // set of ref-holding RuntimeScalars that survived the last JVM GC
    // cycle. ON by default for sweepWeakRefs (safe because the
    // WeakHashMap has already been GC-pruned to live lexicals only).
    private boolean useLexicalSeeds = true;

    /** Enable walking closures' captured scalars. */
    public ReachabilityWalker withCodeCaptures(boolean v) {
        this.walkCodeCaptures = v;
        return this;
    }

    /** Disable the ScalarRefRegistry root seed (globals-only walk). */
    public ReachabilityWalker withLexicalSeeds(boolean v) {
        this.useLexicalSeeds = v;
        return this;
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
        for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalCodeRefs.entrySet()) {
            visitScalar(e.getValue(), todo);
        }
        bfs(todo, /*walkCaptures=*/ true);

        // Phase 2: seed remaining roots.
        for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalVariables.entrySet()) {
            visitScalar(e.getValue(), todo);
        }
        for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrays.entrySet()) {
            addReachable(e.getValue(), todo);
        }
        for (Map.Entry<String, RuntimeHash> e : GlobalVariable.globalHashes.entrySet()) {
            addReachable(e.getValue(), todo);
        }
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForWalk()) {
            addReachable(rescued, todo);
        }
        if (useLexicalSeeds) {
            for (RuntimeScalar sc : ScalarRefRegistry.snapshot()) {
                if (sc.captureCount > 0) continue;
                // Phase I: skip weak scalars — they don't count as
                // strong reachability edges.
                if (WeakRefRegistry.isweak(sc)) continue;
                // Phase I: a scalar is only a valid "live lexical" seed if
                // its declaration scope is still registered in
                // MyVarCleanupStack. Scalars whose scopes have exited may
                // still be Java-alive (via MortalList.deferredCaptures,
                // MortalList.pending, or transient container elements)
                // but they are NOT live Perl lexicals — using them as
                // walker roots falsely pins their referents and breaks
                // DBIC's leak tracer.
                if (MortalList.isDeferredCapture(sc)) continue;
                if (!MyVarCleanupStack.isLive(sc)) continue;
                addReachable(sc, todo);
                visitScalar(sc, todo);
            }

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
            // and arrays, missing the scalar's referent.
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
            for (RuntimeBase tempRoot : MortalList.snapshotTemporaryRoots()) {
                addReachable(tempRoot, todo);
            }
        }

        bfs(todo, walkCodeCaptures);

        return reachable;
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
            if (cur instanceof RuntimeStash) continue;
            if (cur instanceof RuntimeHash h) {
                if (h.elements instanceof HashSpecialVariable) continue;
                for (RuntimeScalar v : h.elements.values()) {
                    visitScalar(v, todo);
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
                    visitScalar(v, todo);
                }
            } else if (cur instanceof RuntimeCode code) {
                visitCodePadConstants(code, todo);
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

    private void visitCodeCaptures(RuntimeCode code,
                                   java.util.ArrayDeque<RuntimeBase> todo) {
        if (code.capturedScalars != null) {
            for (RuntimeScalar cap : code.capturedScalars) {
                visitScalar(cap, todo);
            }
        }
        visitReflectiveCodeScalars(code, cap -> visitScalar(cap, todo));
        if (code instanceof org.perlonjava.backend.bytecode.InterpretedCode interpreted
                && interpreted.capturedVars != null) {
            for (RuntimeBase cap : interpreted.capturedVars) {
                if (cap instanceof RuntimeScalar scalar) {
                    visitScalar(scalar, todo);
                } else if (cap != null) {
                    addReachable(cap, todo);
                }
            }
        }
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
                String name = code.packageName == null ? "?" : code.packageName;
                String sub = code.subName == null ? "(anon)" : code.subName;
                final int[] reflectiveIdx = {0};
                visitReflectiveCodeScalars(code, cap ->
                        visitScalarPath(cap, curPath + "<closure " + name + "::" + sub
                                + " field-cap#" + (reflectiveIdx[0]++) + ">", howReached, todo));
                if (cur instanceof org.perlonjava.backend.bytecode.InterpretedCode interpreted
                        && interpreted.capturedVars != null) {
                    int i = 0;
                    for (RuntimeBase cap : interpreted.capturedVars) {
                        String path = curPath + "<interpreted closure " + name + "::" + sub
                                + " cap#" + (i++) + ">";
                        if (cap instanceof RuntimeScalar scalar) {
                            visitScalarPath(scalar, path, howReached, todo);
                        } else if (cap != null && howReached.putIfAbsent(cap, path) == null) {
                            todo.add(cap);
                        }
                    }
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
     * behavior for unblessed self-cycles: they remain alive even when no
     * package/global root points at them.
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

    private static boolean enqueueStrongEdges(RuntimeBase cur, RuntimeBase target,
                                              Set<RuntimeBase> seen,
                                              java.util.ArrayDeque<RuntimeBase> todo) {
        if (cur instanceof RuntimeHash h) {
            for (RuntimeScalar v : h.elements.values()) {
                if (enqueueStrongScalar(v, target, seen, todo)) return true;
            }
        } else if (cur instanceof RuntimeArray a) {
            for (RuntimeScalar v : a.elements) {
                if (enqueueStrongScalar(v, target, seen, todo)) return true;
            }
        } else if (cur instanceof RuntimeCode code) {
            if (code.capturedScalars != null) {
                for (RuntimeScalar cap : code.capturedScalars) {
                    if (enqueueStrongScalar(cap, target, seen, todo)) return true;
                }
            }
            if (cur instanceof org.perlonjava.backend.bytecode.InterpretedCode interpreted
                    && interpreted.capturedVars != null) {
                for (RuntimeBase cap : interpreted.capturedVars) {
                    if (cap instanceof RuntimeScalar scalar) {
                        if (enqueueStrongScalar(scalar, target, seen, todo)) return true;
                    } else if (cap != null) {
                        if (cap == target) return true;
                        if (seen.add(cap)) todo.addLast(cap);
                    }
                }
            }
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
                    && followGlobalCodeCaptures(code, target, seen, todo)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasLiveStrongScalarReferent(RuntimeBase target) {
        if (target == null) return false;
        for (Object liveVar : MyVarCleanupStack.snapshotLiveVars()) {
            if (liveVar instanceof RuntimeScalar sc
                    && !WeakRefRegistry.isweak(sc)
                    && !sc.scopeExited
                    && sc.value == target) {
                return true;
            }
        }
        for (RuntimeScalar sc : ScalarRefRegistry.snapshot()) {
            if (sc == null) continue;
            if (WeakRefRegistry.isweak(sc)) continue;
            if (sc.scopeExited) continue;
            if (!MyVarCleanupStack.isLive(sc)) continue;
            if (sc.value == target) return true;
        }
        return false;
    }

    public static boolean isReachableFromGlobalCodeCaptures(RuntimeBase target) {
        if (target == null) return false;
        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        for (RuntimeScalar sc : GlobalVariable.globalCodeRefs.values()) {
            if (sc != null && sc.value instanceof RuntimeCode code
                    && followGlobalCodeCaptures(code, target, seen, todo)) {
                return true;
            }
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
        for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalCodeRefs.entrySet()) {
            if (e.getValue() == target) return true;
            if (e.getValue() != null && seen.add(e.getValue())) todo.addLast(e.getValue());
        }
        for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalVariables.entrySet()) {
            if (e.getValue() == target) return true;
            if (e.getValue() != null && seen.add(e.getValue())) todo.addLast(e.getValue());
        }
        for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrays.entrySet()) {
            if (seen.add(e.getValue())) todo.addLast(e.getValue());
        }
        for (Map.Entry<String, RuntimeHash> e : GlobalVariable.globalHashes.entrySet()) {
            if (seen.add(e.getValue())) todo.addLast(e.getValue());
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
            if (cur instanceof RuntimeHash hash) {
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
                if (code.capturedScalars != null) {
                    for (RuntimeScalar cap : code.capturedScalars) {
                        if (cap == null) continue;
                        if (WeakRefRegistry.isweak(cap)) continue;
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
        // Hard cap to prevent pathological worst-case walks. Class::MOP
        // bootstrap touches ~thousands of nodes; pick a generous limit
        // that still bounds cost.
        final int MAX_VISITS = 50_000;

        Set<RuntimeBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        // Seed: package globals (scalars, arrays, hashes, code refs).
        for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalCodeRefs.entrySet()) {
            seedTarget(e.getValue(), target, seen, todo);
            if (seen.contains(target)) return true;
            if (!globalOnly
                    && e.getValue() != null
                    && e.getValue().value instanceof RuntimeCode code) {
                if (followGlobalCodeCaptures(code, target, seen, todo)) return true;
            }
        }
        for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalVariables.entrySet()) {
            seedTarget(e.getValue(), target, seen, todo);
            if (seen.contains(target)) return true;
        }
        for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrays.entrySet()) {
            if (e.getValue() == target) return true;
            if (seen.add(e.getValue())) todo.addLast(e.getValue());
        }
        for (Map.Entry<String, RuntimeHash> e : GlobalVariable.globalHashes.entrySet()) {
            if (e.getValue() == target) return true;
            if (seen.add(e.getValue())) todo.addLast(e.getValue());
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
                seedTarget(sc, target, seen, todo);
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
                    if (sc == target) return true;
                    seedTarget(sc, target, seen, todo);
                    if (seen.contains(target)) return true;
                } else if (liveVar instanceof RuntimeBase rb) {
                    if (rb == target) return true;
                    if (seen.add(rb)) todo.addLast(rb);
                }
            }
        }
        // Seed: rescued objects.
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForWalk()) {
            if (rescued == target) return true;
            if (seen.add(rescued)) todo.addLast(rescued);
        }

        // BFS, short-circuiting on target.
        int visits = 0;
        while (!todo.isEmpty() && visits < MAX_VISITS) {
            RuntimeBase cur = todo.removeFirst();
            visits++;
            if (cur == target) return true;
            // Phase D-W2 (perf): skip RuntimeStash — see bfs().
            if (cur instanceof RuntimeStash) continue;
            if (cur instanceof RuntimeHash h) {
                for (RuntimeScalar v : h.elements.values()) {
                    if (followScalar(v, target, seen, todo)) return true;
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
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

        for (RuntimeScalar sc : GlobalVariable.globalCodeRefs.values()) {
            seedTarget(sc, target, seen, todo);
            if (seen.contains(target)) return true;
            if (sc != null && sc.value instanceof RuntimeCode code
                    && followGlobalCodeCaptures(code, target, seen, todo)) {
                return true;
            }
        }
        for (RuntimeScalar sc : GlobalVariable.globalVariables.values()) {
            seedTarget(sc, target, seen, todo);
            if (seen.contains(target)) return true;
        }
        for (RuntimeArray array : GlobalVariable.globalArrays.values()) {
            if (array == target) return true;
            if (seen.add(array)) todo.addLast(array);
        }
        for (RuntimeHash hash : GlobalVariable.globalHashes.values()) {
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
                Collections.newSetFromMap(new IdentityHashMap<>());

        public ExternalRootSnapshot() {
            buildNonLexicalRoots();
        }

        public boolean isReachable(RuntimeBase target) {
            if (target == null) return false;
            if (nonLexicalReachable.contains(target)) return true;
            return new LiveRootSnapshot().isReachable(target);
        }

        public boolean isReachableFromNonLexicalRoot(RuntimeBase target) {
            return target != null && nonLexicalReachable.contains(target);
        }

        private void buildNonLexicalRoots() {
            java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

            for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalCodeRefs.entrySet()) {
                seedGlobalCodeScalar(e.getValue(), todo);
            }
            for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalVariables.entrySet()) {
                seedNonLexicalScalar(e.getValue(), todo);
            }
            for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrays.entrySet()) {
                addNonLexical(e.getValue(), todo);
            }
            for (Map.Entry<String, RuntimeHash> e : GlobalVariable.globalHashes.entrySet()) {
                addNonLexical(e.getValue(), todo);
            }
            for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForWalk()) {
                addNonLexical(rescued, todo);
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

        private void seedGlobalCodeScalar(RuntimeScalar s,
                                          java.util.ArrayDeque<RuntimeBase> todo) {
            seedNonLexicalScalar(s, todo);
            if (s != null && s.value instanceof RuntimeCode code) {
                seedGlobalCodeCaptures(code, todo);
            }
        }

        private void seedGlobalCodeCaptures(RuntimeCode code,
                                            java.util.ArrayDeque<RuntimeBase> todo) {
            if (code.capturedScalars != null) {
                for (RuntimeScalar cap : code.capturedScalars) {
                    seedNonLexicalScalar(cap, todo);
                }
            }
            visitReflectiveCodeScalars(code, cap -> seedNonLexicalScalar(cap, todo));
            if (code instanceof org.perlonjava.backend.bytecode.InterpretedCode interpreted
                    && interpreted.capturedVars != null) {
                for (RuntimeBase cap : interpreted.capturedVars) {
                    if (cap instanceof RuntimeScalar scalar) {
                        seedNonLexicalScalar(scalar, todo);
                    } else {
                        addNonLexical(cap, todo);
                    }
                }
            }
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
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final IdentityHashMap<RuntimeBase, Object> directLexicalOrigins =
                new IdentityHashMap<>();

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
        if (s == null) return;
        if (WeakRefRegistry.isweak(s)) return;
        if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && s.value instanceof RuntimeBase b) {
            if (b == target) {
                seen.add(target);
                return;
            }
            if (seen.add(b)) todo.addLast(b);
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
        if (code.capturedScalars != null) {
            for (RuntimeScalar cap : code.capturedScalars) {
                if (followScalar(cap, target, seen, todo)) return true;
            }
        }
        final boolean[] foundReflectiveCapture = {false};
        visitReflectiveCodeScalars(code, cap -> {
            if (!foundReflectiveCapture[0] && followScalar(cap, target, seen, todo)) {
                foundReflectiveCapture[0] = true;
            }
        });
        if (foundReflectiveCapture[0]) return true;
        if (code instanceof org.perlonjava.backend.bytecode.InterpretedCode interpreted
                && interpreted.capturedVars != null) {
            for (RuntimeBase cap : interpreted.capturedVars) {
                if (cap instanceof RuntimeScalar scalar) {
                    if (followScalar(scalar, target, seen, todo)) return true;
                } else if (cap != null) {
                    if (cap == target) return true;
                    if (seen.add(cap)) todo.addLast(cap);
                }
            }
        }
        return false;
    }

    private static void visitReflectiveCodeScalars(RuntimeCode code,
                                                   java.util.function.Consumer<RuntimeScalar> visitor) {
        Object closureObject = code.codeObject != null ? code.codeObject : code.subroutine;
        if (closureObject == null) return;
        try {
            for (java.lang.reflect.Field field : closureObject.getClass().getDeclaredFields()) {
                if (field.getType() == RuntimeScalar.class && !"__SUB__".equals(field.getName())) {
                    RuntimeScalar cap = (RuntimeScalar) field.get(closureObject);
                    if (cap != null) {
                        visitor.accept(cap);
                    }
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
    public static int sweepWeakRefs(boolean quiet) {
        if (!WeakRefRegistry.weakRefsExist) return 0;
        ScalarRefRegistry.forceGcAndSnapshot();
        if (!quiet) {
            // Explicit sweeps drain rescued objects. Quiet auto-sweeps run
            // during user workflows and must not clear DBIC Schema rescue
            // links while later chained calls still rely on them.
            DestroyDispatch.clearRescuedWeakRefs();
        }
        ReachabilityWalker w = new ReachabilityWalker();
        Set<RuntimeBase> live = w.walk();
        ArrayList<RuntimeBase> toClear = new ArrayList<>();
        for (RuntimeBase referent : WeakRefRegistry.snapshotWeakRefReferents()) {
            if (!live.contains(referent)) {
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
                if ((referent instanceof RuntimeHash || referent instanceof RuntimeArray)
                        && referent.localBindingExists) {
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
                toClear.add(referent);
            }
        }
        int cleared = 0;
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
            } else {
                WeakRefRegistry.clearWeakRefsTo(referent);
                if (referent.refCount != Integer.MIN_VALUE) {
                    referent.refCount = Integer.MIN_VALUE;
                }
            }
            cleared++;
        }
        return cleared;
    }
}
