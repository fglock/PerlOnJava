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
                if (!MyVarCleanupStack.isLive(sc)) {
                    if (sc.scopeExited) continue;
                    if (!sc.refCountOwned) continue;
                }
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
                    visitScalar(sc, todo);
                } else if (liveVar instanceof RuntimeBase rb) {
                    addReachable(rb, todo);
                }
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
                for (RuntimeScalar v : h.elements.values()) {
                    visitScalar(v, todo);
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
                    visitScalar(v, todo);
                }
            } else if (cur instanceof RuntimeCode code) {
                if (walkCaptures && code.capturedScalars != null) {
                    for (RuntimeScalar cap : code.capturedScalars) {
                        visitScalar(cap, todo);
                    }
                }
            } else if (cur instanceof RuntimeScalar s) {
                visitScalar(s, todo);
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
            } else if (cur instanceof RuntimeCode code && code.capturedScalars != null) {
                for (RuntimeScalar cap : code.capturedScalars) {
                    if (cap == null) continue;
                    if (WeakRefRegistry.isweak(cap)) continue;
                    if (cap == target) return true;
                    if (seen.add(cap)) todo.addLast(cap);
                }
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
        // Phase H1: drain rescued objects in BOTH quiet and non-quiet modes.
        // Rescued objects are blessed-with-DESTROY objects that self-saved
        // during their DESTROY body. Clearing their weak refs from auto-
        // sweep matches Perl's behavior: once the last user-visible strong
        // ref goes, weak refs to the self-rescued object clear.
        DestroyDispatch.clearRescuedWeakRefs();
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
