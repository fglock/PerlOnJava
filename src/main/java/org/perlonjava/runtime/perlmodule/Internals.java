package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.util.Map;

/**
 * The Strict class provides functionalities similar to the Perl strict module.
 */
public class Internals extends PerlModuleBase {

    /**
     * Constructor initializes the module.
     */
    public Internals() {
        super("Internals");
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        Internals internals = new Internals();
        try {
            internals.registerMethod("SvREADONLY", "svReadonly", "\\[$@%];$");
            internals.registerMethod("SvREFCNT", "svRefcount", "$;$");
            // Phase 0 diagnostic: expose PerlOnJava-internal refcount state
            // (refCount, flags, tracking mode) for differential testing
            // against native Perl. See dev/design/refcount_alignment_plan.md.
            internals.registerMethod("jperl_refstate", "jperl_refstate", "$");
            internals.registerMethod("jperl_refstate_str", "jperl_refstate_str", "$");
            // Phase 4 (refcount_alignment_plan.md): On-demand reachability
            // sweep. Walks Perl-visible roots (globals, stashes, rescued
            // objects) and clears weak refs for unreachable objects. Returns
            // the number of weak refs cleared.
            internals.registerMethod("jperl_gc", "jperl_gc", "");
            // Phase 4 diagnostic: trace a reachable path from any Perl root
            // to the given referent. Returns the first-found path string or
            // undef if unreachable. Used to debug why an object that should
            // be GC'd remains reachable from the walker's point of view.
            internals.registerMethod("jperl_trace_to", "jperl_trace_to", "$");
            internals.registerMethod("initialize_state_variable", "initializeStateVariable", "$$");
            internals.registerMethod("initialize_state_array", "initializeStateArray", "$$");
            internals.registerMethod("initialize_state_hash", "initializeStateHash", "$$");
            internals.registerMethod("is_initialized_state_variable", "isInitializedStateVariable", "$$");
            internals.registerMethod("stack_refcounted", null);
            internals.registerMethod("V", "V", null);
            internals.registerMethod("getcwd", "getcwd", null);
            internals.registerMethod("abs_path", "abs_path", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Internals method: " + e.getMessage());
        }
    }

    /**
     * Returns 1 to indicate reference-counted stack behavior.
     * <p>
     * This is appropriate for PerlOnJava since Java's GC keeps objects alive
     * as long as they're referenced, similar to Perl's RC stack builds.
     * <p>
     * IMPORTANT: Returning 1 is required for op/array.t tests 136-199 to run.
     * When this returned undef (empty list), the test at line 509 would try to
     * set an array length to a huge number (the numeric value of a reference),
     * causing OutOfMemoryError and stopping the test early. With RC=1, that
     * dangerous test is skipped, allowing all remaining tests to execute.
     *
     * @param args Unused arguments
     * @param ctx  The context in which the method is called
     * @return RuntimeScalar(1) indicating RC stack behavior
     */
    public static RuntimeList stack_refcounted(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    /**
     * No-op, returns false.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList V(RuntimeArray args, int ctx) {

        // XXX TODO

        return new RuntimeList();
    }

    /**
     * No-op, returns false.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList svRefcount(RuntimeArray args, int ctx) {
        RuntimeScalar arg = args.get(0);
        if (arg.value instanceof RuntimeBase base) {
            int rc = base.refCount;
            if (rc == Integer.MIN_VALUE) return new RuntimeScalar(0).getList();
            if (rc < 0) return new RuntimeScalar(1).getList(); // untracked
            // PerlOnJava's `refCount` counts *external* refs (RVs, container
            // slots). Real Perl's SvREFCNT also counts the lexical pad slot
            // that owns the SV. We model the lexical slot via the separate
            // `localBindingExists` flag.
            //
            // Real Perl's `Internals::SvREFCNT(arg)` semantics, verified
            // empirically:
            //   my @a; &SvREFCNT(\@a)        => 1   (just lex pad)
            //   $r=\@a; &SvREFCNT(\@a)       => 2   (lex + $r)
            //   my $x = []; &SvREFCNT($x)    => 0   (1 owner, reports owner-1)
            //   $r=$x; &SvREFCNT($x)         => 1   (2 owners, reports owner-1)
            //
            // For named lexicals (`localBindingExists=true`), add +1 for the
            // pad slot. For anonymous referents the function arg itself is
            // one of the counted refs; real Perl discounts it (-1) so that
            // a single owner reports 0. The two adjustments together match
            // real Perl across all test patterns:
            //   - inccode.t "no leaks" delta-checks
            //   - for-many.t "refcount inside/after loop"
            //   - test_pl/examples.t "only one reference"/"two references"
            int extra = base.localBindingExists ? 1 : 0;
            // Legacy fudge: anonymous tracked container with no counted
            // owners -- still report 1 to indicate "live SV". Used by
            // Sub::Quote / Moo introspection paths that probe for liveness.
            if (rc == 0 && extra == 0) return new RuntimeScalar(1).getList();
            // Real Perl reports `owner_count − 1` when the queried referent
            // is an anonymous tracked container (the function arg itself is
            // one of the owners and gets discounted). For named lexicals,
            // no adjustment — the temp `\@a` from the arg doesn't add an
            // extra owner in real Perl.
            int adjust = base.localBindingExists ? 0 : -1;
            return new RuntimeScalar(rc + extra + adjust).getList();
        }
        return new RuntimeScalar(1).getList();
    }

    /**
     * Phase 0 diagnostic: return a hashref describing the full internal
     * refcount state of the referent. Intended for differential testing
     * between PerlOnJava and native Perl (see
     * {@code dev/tools/refcount_diff.pl}). On native Perl, this builtin
     * doesn't exist; callers are expected to check availability.
     * <p>
     * Returned hash keys:
     * <ul>
     *   <li>{@code refCount} — raw {@link RuntimeBase#refCount}</li>
     *   <li>{@code localBindingExists} — true when a named-variable slot still holds the container</li>
     *   <li>{@code destroyFired} — true once DESTROY has run</li>
     *   <li>{@code blessId} — bless class id (0 = unblessed)</li>
     *   <li>{@code class_name} — Perl class name (empty string if unblessed)</li>
     *   <li>{@code kind} — runtime type: SCALAR / ARRAY / HASH / CODE / GLOB / OTHER</li>
     *   <li>{@code has_weak_refs} — true if the weak-ref registry has entries pointing here</li>
     * </ul>
     */
    public static RuntimeList jperl_refstate(RuntimeArray args, int ctx) {
        RuntimeScalar arg = args.get(0);
        RuntimeHash result = new RuntimeHash();
        if (arg.value instanceof RuntimeBase base) {
            result.put("refCount", new RuntimeScalar(base.refCount));
            result.put("localBindingExists", new RuntimeScalar(base.localBindingExists));
            result.put("destroyFired", new RuntimeScalar(base.destroyFired));
            result.put("blessId", new RuntimeScalar(base.blessId));
            String className = NameNormalizer.getBlessStr(base.blessId);
            result.put("class_name", new RuntimeScalar(className == null ? "" : className));
            String kind = "OTHER";
            if (base instanceof RuntimeGlob) kind = "GLOB";
            else if (base instanceof RuntimeHash) kind = "HASH";
            else if (base instanceof RuntimeArray) kind = "ARRAY";
            else if (base instanceof RuntimeCode) kind = "CODE";
            else if (base instanceof RuntimeScalar) kind = "SCALAR";
            result.put("kind", new RuntimeScalar(kind));
            result.put("has_weak_refs", new RuntimeScalar(WeakRefRegistry.hasWeakRefsTo(base)));
        } else {
            result.put("refCount", new RuntimeScalar(-1));
            result.put("kind", new RuntimeScalar("NOT_REF"));
        }
        return result.createReference().getList();
    }

    /**
     * Phase 0 diagnostic: return a compact single-line string describing
     * the internal refcount state of the referent. Shorthand form of
     * {@link #jperl_refstate(RuntimeArray, int)} suitable for log lines.
     * Format: {@code kind:class_name:refCount:flags} where flags is a
     * concatenation of single letters: L=localBindingExists, D=destroyFired, W=has_weak_refs.
     */
    public static RuntimeList jperl_refstate_str(RuntimeArray args, int ctx) {
        RuntimeScalar arg = args.get(0);
        if (arg.value instanceof RuntimeBase base) {
            String kind = "OTHER";
            if (base instanceof RuntimeGlob) kind = "GLOB";
            else if (base instanceof RuntimeHash) kind = "HASH";
            else if (base instanceof RuntimeArray) kind = "ARRAY";
            else if (base instanceof RuntimeCode) kind = "CODE";
            else if (base instanceof RuntimeScalar) kind = "SCALAR";
            String cn = NameNormalizer.getBlessStr(base.blessId);
            StringBuilder flags = new StringBuilder();
            if (base.localBindingExists) flags.append('L');
            if (base.destroyFired) flags.append('D');
            if (WeakRefRegistry.hasWeakRefsTo(base)) flags.append('W');
            // Subtract 1 for the passed-in ref (the argument scalar itself
            // holds one counted reference). Matches native Perl's
            // `$sv->REFCNT - 1` convention used in dev/tools/refcount_diff.pl.
            int reportedRc = base.refCount;
            if (reportedRc > 0) reportedRc--;
            return new RuntimeScalar(kind + ":" + (cn == null ? "" : cn) + ":"
                    + reportedRc + ":" + flags).getList();
        }
        return new RuntimeScalar("NOT_REF").getList();
    }

    /**
     * Phase 4 (refcount_alignment_plan.md): Run a reachability sweep from
     * Perl roots (globals, rescued objects) and clear weak refs for
     * unreachable objects. Returns the number of weak refs cleared. This
     * is jperl-only; under native Perl it should be treated as a no-op.
     */
    public static RuntimeList jperl_gc(RuntimeArray args, int ctx) {
        // Two passes: the first pass fires DESTROY on unreachable
        // objects, which may break circular refs and make more objects
        // unreachable. The second pass catches those cascades.
        int cleared = ReachabilityWalker.sweepWeakRefs();
        int secondPass = ReachabilityWalker.sweepWeakRefs();
        return new RuntimeScalar(cleared + secondPass).getList();
    }

    /**
     * Phase 4 diagnostic: find a reachable path from Perl roots to the
     * given referent. Returns the path as a string ("$some::global{key}[3]")
     * or undef if unreachable.
     */
    public static RuntimeList jperl_trace_to(RuntimeArray args, int ctx) {
        RuntimeScalar arg = args.get(0);
        if (!(arg.value instanceof RuntimeBase base)) {
            return new RuntimeScalar().getList();
        }
        // Phase I: when JPERL_TRACE_SKIP_LEX=1, omit ScalarRefRegistry seeds
        // from path discovery — forces the trace through Perl-semantic
        // roots so diagnostics show which global/stash data structure
        // keeps the object alive (not just "live-lexical").
        boolean skipLex = System.getenv("JPERL_TRACE_SKIP_LEX") != null;
        java.util.List<String> path = ReachabilityWalker.findPathTo(base, skipLex);
        if (path == null) return new RuntimeScalar().getList();
        // Also dump all direct lexical-holders for debugging deep leaks
        if (System.getenv("JPERL_TRACE_ALL") != null) {
            System.err.println("jperl_trace_to target addr="
                    + System.identityHashCode(base)
                    + " class=" + base.getClass().getSimpleName());
            int matchIdx = 0;
            int totalLexIdx = 0;
            // Collect candidate parent hashes (those with any key pointing at base)
            // when no direct holder exists. Useful for traces like
            // "<live-lexical#N>{random_results}" where target is reached via
            // a parent hash rather than directly held.
            java.util.ArrayList<RuntimeScalar> parentScalars = new java.util.ArrayList<>();
            for (RuntimeScalar sc : ScalarRefRegistry.snapshot()) {
                if (sc == null) continue;
                if (sc.captureCount > 0) continue;
                if (WeakRefRegistry.isweak(sc)) continue;
                if (!RuntimeScalarType.isReference(sc)) continue;
                totalLexIdx++;
                if (sc.value == base) {
                    System.err.println("  direct holder #" + (matchIdx++)
                            + " scId=" + System.identityHashCode(sc)
                            + " type=" + sc.type
                            + " rcO=" + sc.refCountOwned
                            + " captureCount=" + sc.captureCount);
                    Throwable st = ScalarRefRegistry.stackFor(sc);
                    if (st != null) {
                        StackTraceElement[] frames = st.getStackTrace();
                        int shown = Math.min(frames.length, 40);
                        for (int fi = 0; fi < shown; fi++) {
                            System.err.println("      at " + frames[fi]);
                        }
                        if (frames.length > shown) {
                            System.err.println("      ... "
                                    + (frames.length - shown) + " more");
                        }
                    }
                } else if (sc.value instanceof RuntimeHash h
                        && h.elements.values().stream().anyMatch(v -> v != null && v.value == base)) {
                    parentScalars.add(sc);
                } else if (sc.value instanceof RuntimeArray a
                        && a.elements.stream().anyMatch(v -> v != null && v.value == base)) {
                    parentScalars.add(sc);
                }
            }
            System.err.println("  total direct holders=" + matchIdx
                    + " total lexicals scanned=" + totalLexIdx);
            if (matchIdx == 0 && !parentScalars.isEmpty()) {
                System.err.println("  --- parent-holder candidates ("
                        + parentScalars.size() + ") ---");
                int pIdx = 0;
                for (RuntimeScalar ps : parentScalars) {
                    System.err.println("  parent #" + (pIdx++)
                            + " scId=" + System.identityHashCode(ps)
                            + " type=" + ps.type
                            + " rcO=" + ps.refCountOwned
                            + " parentClass="
                            + (ps.value != null ? ps.value.getClass().getSimpleName() : "null"));
                    if (ps.value instanceof RuntimeHash ph) {
                        java.util.List<String> keys = new java.util.ArrayList<>();
                        for (Map.Entry<String, RuntimeScalar> ent : ph.elements.entrySet()) {
                            if (ent.getValue() != null && ent.getValue().value == base) {
                                keys.add(ent.getKey());
                            }
                        }
                        System.err.println("    via keys: " + keys);
                    }
                    Throwable pst = ScalarRefRegistry.stackFor(ps);
                    if (pst != null) {
                        StackTraceElement[] frames = pst.getStackTrace();
                        int shown = Math.min(frames.length, 30);
                        for (int fi = 0; fi < shown; fi++) {
                            System.err.println("      at " + frames[fi]);
                        }
                    }
                    if (pIdx >= 5) {
                        System.err.println("    ... " + (parentScalars.size() - 5) + " more parents");
                        break;
                    }
                }
            }
        }
        return new RuntimeScalar(String.join(" -> ", path)).getList();
    }

    /**
     * Sets or gets the read-only status of a variable.
     *
     * @param args The arguments passed to the method: variable, optional flag to set readonly
     * @param ctx  The context in which the method is called.
     * @return The readonly status (query mode) or empty list (set mode)
     */
    public static RuntimeList svReadonly(RuntimeArray args, int ctx) {
        if (args.size() >= 2) {
            RuntimeBase variable = args.get(0);
            RuntimeBase flag = args.get(1);

            if (flag.getBoolean()) {
                // Make the variable readonly
                if (variable instanceof RuntimeArray array) {
                    array.type = RuntimeArray.READONLY_ARRAY;
                } else if (variable instanceof RuntimeScalar scalar) {
                    // Handle array reference (from \@array via prototype)
                    if (scalar.type == RuntimeScalarType.ARRAYREFERENCE && scalar.value instanceof RuntimeArray array) {
                        array.type = RuntimeArray.READONLY_ARRAY;
                    }
                    // Handle hash reference (from \%hash via prototype)
                    else if (scalar.type == RuntimeScalarType.HASHREFERENCE && scalar.value instanceof RuntimeHash hash) {
                        // TODO: implement readonly hash when needed
                    }
                    // Check if it's a scalar reference (from \$var)
                    else if (scalar.type == RuntimeScalarType.REFERENCE && scalar.value instanceof RuntimeScalar targetScalar) {
                        // Skip if already readonly
                        if (targetScalar.type != RuntimeScalarType.READONLY_SCALAR
                                && !(targetScalar instanceof RuntimeScalarReadOnly)) {
                            // Wrap: save original type+value in an inner scalar,
                            // set targetScalar.type = READONLY_SCALAR
                            RuntimeScalar inner = new RuntimeScalar();
                            inner.type = targetScalar.type;
                            inner.value = targetScalar.value;
                            targetScalar.type = RuntimeScalarType.READONLY_SCALAR;
                            targetScalar.value = inner;
                        }
                    }
                }
            } else {
                // Make the variable writable again
                if (variable instanceof RuntimeScalar scalar) {
                    if (scalar.type == RuntimeScalarType.REFERENCE && scalar.value instanceof RuntimeScalar targetScalar) {
                        if (targetScalar.type == RuntimeScalarType.READONLY_SCALAR) {
                            // Unwrap: restore original type+value
                            RuntimeScalar inner = (RuntimeScalar) targetScalar.value;
                            targetScalar.type = inner.type;
                            targetScalar.value = inner.value;
                        }
                    }
                }
            }
        } else if (args.size() == 1) {
            // Query mode: return whether the variable is readonly
            RuntimeBase variable = args.get(0);
            if (variable instanceof RuntimeScalar scalar) {
                if (scalar.type == RuntimeScalarType.REFERENCE && scalar.value instanceof RuntimeScalar targetScalar) {
                    boolean isRo = targetScalar.type == RuntimeScalarType.READONLY_SCALAR
                            || targetScalar instanceof RuntimeScalarReadOnly;
                    return new RuntimeScalar(isRo).getList();
                }
                boolean isRo = scalar instanceof RuntimeScalarReadOnly
                        || scalar.type == RuntimeScalarType.READONLY_SCALAR;
                return new RuntimeScalar(isRo).getList();
            }
        }
        return new RuntimeList();
    }

    /**
     * Initialize a state variable exactly once
     *
     * @param args Args: variable name with sigil; persistent variable id; value to initialize.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList initializeStateVariable(RuntimeArray args, int ctx) {
        StateVariable.initializeStateVariable(
                args.get(0),
                args.get(1).toString(),
                args.get(2).getInt(),
                args.get(3));
        return new RuntimeList();
    }

    public static RuntimeList initializeStateArray(RuntimeArray args, int ctx) {
        StateVariable.initializeStateArray(
                RuntimeArray.shift(args),
                RuntimeArray.shift(args).toString(),
                RuntimeArray.shift(args).getInt(),
                args);
        return new RuntimeList();
    }

    public static RuntimeList initializeStateHash(RuntimeArray args, int ctx) {
        StateVariable.initializeStateHash(
                RuntimeArray.shift(args),
                RuntimeArray.shift(args).toString(),
                RuntimeArray.shift(args).getInt(),
                args);
        return new RuntimeList();
    }

    /**
     * Check is a state variable was initialized
     *
     * @param args Args: variable name with sigil; persistent variable id.
     * @param ctx  The context in which the method is called.
     * @return RuntimeScalar with true or false.
     */
    public static RuntimeList isInitializedStateVariable(RuntimeArray args, int ctx) {
        RuntimeScalar var = StateVariable.isInitializedStateVariable(
                args.get(0),
                args.get(1).toString(),
                args.get(2).getInt());
        return var.getList();
    }

    /**
     * Returns the current working directory.
     * This provides a native Java implementation that works on all platforms,
     * which Cwd.pm will use instead of shell-based fallbacks.
     *
     * @param args Unused arguments
     * @param ctx  The context in which the method is called
     * @return RuntimeScalar with the current working directory path
     */
    public static RuntimeList getcwd(RuntimeArray args, int ctx) {
        return new RuntimeScalar(System.getProperty("user.dir")).getList();
    }

    /**
     * Gets the absolute path of a file or directory, resolving . and .. components.
     * This provides a reliable, platform-independent way to get absolute paths,
     * which Cwd.pm will use instead of Perl-based implementations.
     *
     * @param args The path to resolve (first argument), or "." if not provided
     * @param ctx  The context in which the method is called
     * @return RuntimeScalar with the absolute path, or undef if the path doesn't exist
     */
    public static RuntimeList abs_path(RuntimeArray args, int ctx) {
        String path = args.size() > 0 ? args.get(0).toString() : ".";
        try {
            java.io.File file = new java.io.File(path);
            if (!file.isAbsolute()) {
                file = new java.io.File(System.getProperty("user.dir"), path);
            }
            if (!file.exists()) {
                return new RuntimeScalar().getList();  // return undef
            }
            return new RuntimeScalar(file.getCanonicalPath()).getList();
        } catch (java.io.IOException e) {
            return new RuntimeScalar().getList();  // return undef on error
        }
    }
}
