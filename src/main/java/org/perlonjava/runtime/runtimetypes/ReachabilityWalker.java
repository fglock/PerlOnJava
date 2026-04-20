package org.perlonjava.runtime.runtimetypes;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

/**
 * Phase 4 (refcount_alignment_plan.md): On-demand reachability walker.
 * <p>
 * Walks the live object graph from Perl-visible roots and identifies which
 * objects in the weak-ref registry are unreachable. Clears weak refs for
 * those objects, simulating Perl 5's refcount-based collection when
 * PerlOnJava's cooperative refCount has drifted due to JVM temporaries.
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
                // Phase I: a scalar is only a valid "live lexical" seed if
                // its declaration scope is still registered in
                // MyVarCleanupStack. Scalars whose scopes have exited may
                // still be Java-alive (via MortalList.deferredCaptures,
                // MortalList.pending, or transient container elements)
                // but they are NOT live Perl lexicals — using them as
                // walker roots falsely pins their referents and breaks
                // DBIC's leak tracer. Falls back to scopeExited /
                // refCountOwned heuristics for scalars not tracked by
                // MyVarCleanupStack (e.g. interpreter-path scalars).
                if (!MyVarCleanupStack.isLive(sc)) {
                    if (sc.scopeExited) continue;
                    if (!sc.refCountOwned) continue;
                }
                visitScalar(sc, todo);
            }
        }

        bfs(todo, walkCodeCaptures);

        return reachable;
    }

    private void bfs(java.util.ArrayDeque<RuntimeBase> todo, boolean walkCaptures) {
        while (!todo.isEmpty()) {
            RuntimeBase cur = todo.removeFirst();
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
     */
    public static java.util.List<String> findPathTo(RuntimeBase target) {
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
        // Phase B1: seed from ScalarRefRegistry (same as walk()) so the
        // trace matches what sweepWeakRefs sees.
        int scIdx = 0;
        for (RuntimeScalar sc : ScalarRefRegistry.snapshot()) {
            if (sc == null) continue;
            if (sc.captureCount > 0) continue;
            if (WeakRefRegistry.isweak(sc)) continue;
            if ((sc.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && sc.value instanceof RuntimeBase b) {
                // Include scalar identity so users can correlate with
                // heap dumps / profilers. captureCount=0 here by the
                // filter above, but including refCountOwned + type
                // helps narrow down which lexical it is.
                String label = "<live-lexical#" + (scIdx++)
                        + " scId=" + System.identityHashCode(sc)
                        + " type=" + sc.type
                        + " rcO=" + sc.refCountOwned + ">";
                if (howReached.putIfAbsent(b, label) == null) {
                    todo.add(b);
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
            // Fire DESTROY if the object is blessed and hasn't destroyed yet.
            // This matches Perl's behavior of collecting orphan circular
            // structures: when they become unreachable, DESTROY fires and
            // weak refs clear.
            //
            // In quiet mode (auto-sweep from hot paths), we skip DESTROY
            // to avoid running Perl code that could affect module loading
            // or other in-flight state — we only clear the weak ref.
            if (!quiet && referent.blessId != 0 && !referent.destroyFired
                    && referent.refCount != Integer.MIN_VALUE) {
                referent.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(referent);
            } else {
                WeakRefRegistry.clearWeakRefsTo(referent);
                if (!quiet && referent.refCount != Integer.MIN_VALUE) {
                    referent.refCount = Integer.MIN_VALUE;
                }
            }
            cleared++;
        }
        return cleared;
    }
}
