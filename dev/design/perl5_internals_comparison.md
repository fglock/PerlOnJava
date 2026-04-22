# System Perl's refcount + stack machine — what we could learn

**Scope:** compare `@perl5/` source with PerlOnJava's current architecture on the per-sub-call hot path. Identify the concrete wins Perl has, rank by how tractable each would be to adopt.

**Measurement anchor:** PR #526 runs `life_bitpacked` at 8.3 Mcells/s; master at 14.0; system perl at 21.2. This doc explains the **perl vs PerlOnJava** side of that gap (the 1.52× master-to-perl ratio). The **master-to-PR** 1.67× is a separate refcount-correctness cost documented in `life_bitpacked_regression_analysis.md`.

---

## 1. Reference counting: 4 inlined instructions vs several method calls

### Perl

From `perl5/sv_inline.h`:

```c
PERL_STATIC_INLINE SV *
Perl_SvREFCNT_inc(SV *sv) {
    if (LIKELY(sv != NULL))
        SvREFCNT(sv)++;
    return sv;
}

PERL_STATIC_INLINE void
Perl_SvREFCNT_dec(pTHX_ SV *sv) {
    if (LIKELY(sv != NULL)) {
        U32 rc = SvREFCNT(sv);
        if (LIKELY(rc > 1))
            SvREFCNT(sv) = rc - 1;
        else
            Perl_sv_free2(aTHX_ sv, rc);     /* cold path */
    }
}
```

**Hot path cost:** one null check (predicted taken), one load, one branch (predicted taken), one store. ~4 machine instructions total. Compiler inlines the whole thing at every call site.

Only the **cold path** (rc was 1, about to hit 0) calls out to `sv_free2` which does the actual free, DESTROY firing, hv_clear_magic etc.

### PerlOnJava

Our refcount is stored on `RuntimeBase.refCount` (field access — cheap), but the analogous operations go through:

- `setLarge()` / `setLargeRefCounted()` — do the increment plus register with `ScalarRefRegistry` and `MortalList`.
- `scopeExitCleanup()` — handles the decrement at scope exit, with extra checks for `refCountOwned`, `captureCount`, `ioOwner`.

The math on each hot path: potentially several TL.get() calls, a method invocation, and branches. Method-level JIT inlining helps, but we routinely see `scopeExitCleanup (109 bytes)` failing to inline at 3+ levels of call depth.

**What we could change:** expose direct `refCount++` / `refCount--` through tiny static helpers inlineable into emitted bytecode, and move the `ScalarRefRegistry` / `MortalList` bookkeeping into the cold path (last-ref only).

### Tractability

**Medium.** The fast-path is mostly there already (`RuntimeBase.refCount` is a plain field). What's missing: a small hot helper that the emitter can INVOKESTATIC for `refcnt_inc` / `refcnt_dec_or_free` with the body small enough to inline. Probably a 2-3 day refactor.

---

## 2. Value stack: pointer arithmetic vs ThreadLocal+Deque

### Perl

From `perl5/pp.h`:

```c
#define PUSHMARK(p) \
    STMT_START {                                            \
        Stack_off_t *mark_stack_entry;                      \
        if (UNLIKELY((mark_stack_entry = ++PL_markstack_ptr) \
                                       == PL_markstack_max)) \
            mark_stack_entry = markstack_grow();            \
        *mark_stack_entry = (Stack_off_t)((p) - PL_stack_base); \
    } STMT_END
```

`PUSHMARK` is: **inc pointer, store offset, done.** No method call. No ThreadLocal. No allocation in the hot path (only on rare grow). 3 machine instructions.

`PL_stack_base` / `PL_stack_sp` are cached in the thread's per-thread state struct; the compiler keeps them in registers across most of a pp function's body.

### PerlOnJava

```java
public static void pushArgs(RuntimeArray args) {
    argsStack.get().push(args);                           // TL lookup + ArrayDeque.push
    ...
    pristineArgsStack.get().push(snapshot);               // TL lookup + ArrayDeque.push
}
```

**Two ThreadLocal.get() per call, two ArrayDeque.push() per call.** Each TL.get() is ~5-10ns on a modern JIT. The Deque.push() is amortized O(1) but involves a virtual dispatch (ArrayDeque extends AbstractCollection).

### What we could change

**Option A — flatten to a single per-thread PerlRuntime struct** (master `d070812cd` tried this; reverted because of multiplicity tangles). One `ThreadLocal<PerlRuntime>` gets everything; accessing `argsStack` / `pristineArgsStack` / `hasArgsStack` / caller-state is one TL.get + a field load.

**Option B — pre-allocated array-backed stack.** An `Object[] stack` + `int sp` on the `PerlRuntime` struct, pushed by `stack[sp++] = value`. Matches Perl's PL_stack_sp semantics. The JIT recognises this as a simple heap array and register-allocates `sp`.

**Option C — keep the Deque but consolidate** (the minimum viable version). One ThreadLocal holds a single `CallState` object with all the stacks as fields. One TL.get() per sub call, not six.

### Tractability

- Option A: **high effort** (touches multiplicity story, many read sites). 1-2 weeks.
- Option B: **medium** (needs a consistent backing type; all stack consumers must be rewritten). 1-2 weeks.
- Option C: **low-medium** (mechanical refactor, preserves all APIs). 2-4 days.

Option C would recover most of the ThreadLocal overhead with the least risk. It's a strict improvement over the current design.

---

## 3. Context stack: one struct per sub call vs 6 separate ThreadLocal stacks

### Perl

`cxstack` is a single array of `PERL_CONTEXT` structs. Each sub call pushes ONE struct:

```c
struct block_sub {
    OP *retop;              /* return op */
    I32 old_cxsubix;
    PAD *prevcomppad;
    CV *cv;
    I32 olddepth;
    AV *savearray;          /* saved @_ */
};
```

Caller warning bits, hint hash, ctx.blk_oldsaveix, etc. are all fields in the same struct. `cx_pushsub(cx, cv, retop, hasargs)` is a single function call that fills the struct.

`cxstack_ix++` is the commit. Everything lives in one contiguous cache-friendly array.

### PerlOnJava

Per sub call, we push to (at least) these separate ThreadLocal stacks:

1. `argsStack` (RuntimeCode)
2. `pristineArgsStack` (RuntimeCode) — our branch only
3. `hasArgsStack` (RuntimeCode) — master+ours
4. `WarningBitsRegistry.currentBitsStack`
5. `WarningBitsRegistry.callerBitsStack`
6. `WarningBitsRegistry.callerHintsStack`
7. `HintHashRegistry.callerSnapshotIdStack`

Seven ThreadLocal lookups, seven `Deque.push()` calls. Each push involves a node allocation in some cases; at minimum, a field write.

**JFR confirms:** life_bitpacked allocates 4 `ArrayList` + 4 `RuntimeList` more per benchmark run than master, attributable to these stacks.

### What we could change

Introduce `class PerlContext { ... }` with all the fields, one per-thread stack of those. Replaces the seven separate stacks. Callers of `WarningBitsRegistry.getCallerBitsAtFrame(N)` etc. read the context struct instead.

This is a close sibling of Option C above (the consolidation) but richer — it also eliminates the per-call `HashMap<String, RuntimeScalar>` copy that `WarningBitsRegistry.pushCallerHintHash` does. Cacheable. Fast.

### Tractability

**Medium.** ~500-800 lines of diff but mechanical. Would need a release-candidate with `make test-bundled-modules` + DBIC + Moo verified. 3-5 days.

---

## 4. FREETMPS: one compare vs an always-called method

### Perl

```c
#define FREETMPS  if (PL_tmps_ix > PL_tmps_floor) free_tmps()
```

The compare is one instruction. When there are no mortals pending (the common case for leaf subs with no DESTROY-needing temps) it's **literally one compare and a branch-not-taken**.

### PerlOnJava

`MortalList.flush()` is called unconditionally at scope exit:

```java
if (flush) {
    ctx.mv.visitMethodInsn(INVOKESTATIC,
        "org/perlonjava/runtime/runtimetypes/MortalList",
        "flush", "()V", false);
}
```

`MortalList.flush()` itself checks for an empty list, but the INVOKESTATIC dispatch is ~5ns regardless. Across millions of sub calls in a tight loop, that's substantial.

### What we could change

Expose `MortalList.tmpsFloor` / `MortalList.tmpsIx` as direct fields accessible from emitted bytecode, and emit `GETSTATIC + IF_ICMPGE + INVOKESTATIC` instead of an unconditional INVOKESTATIC. That reproduces `FREETMPS`'s one-compare fast path.

### Tractability

**Low effort** (~100 lines). The `MortalList.flush()` method would stay for correctness; we'd just bypass it when the stack is empty.

---

## 5. Padlist: direct array indexing vs ThreadLocal symbol table

### Perl

Every lexical is a `PADOFFSET` — a simple array index. `PAD_SVl(idx)` expands to `((SV**)AvARRAY(PL_comppad))[idx]` — one load.

`PAD_SET_CUR_NOSAVE(padlist, depth)` sets the current pad via two stores; no lookups.

### PerlOnJava

We already have this! Our compiled Perl subs use Java locals for lexicals (`ALOAD idx` / `ASTORE idx`). The cost here is actually matched. ✅

---

## Summary: what would give us the biggest life_bitpacked win

Ranked by expected impact per engineering effort:

1. **§4 FREETMPS: compare-not-branch fast path** — low effort, eliminates per-scope-exit INVOKESTATIC when MortalList is empty. Expected: 3-5% on hot loops.

2. **§3 Consolidate 7 ThreadLocal stacks into one `PerlContext` struct** — medium effort, reduces per-sub-call TL traffic from 7 to 1. Expected: 5-10% on tight sub-call workloads; directly addresses the "more RuntimeList / ArrayList allocations" JFR finding.

3. **§1 Inline refcount helpers** — medium effort, makes the refcount fast path emit as INVOKESTATIC to a tiny helper instead of going through `setLarge`. Expected: 5-10% on refcount-heavy workloads (anon sub creation, bless).

4. **§2 Option C (single-TL consolidation)** — overlaps with §3; do as part of §3.

5. **§2 Option B (array-backed stack with direct sp pointer)** — high effort, would match Perl's value-stack model. Expected big win but risk is proportional. Save for a dedicated effort.

Total expected recovery, rough estimate: **combined §4 + §3 + §1 would close ~half the master-to-perl gap** (i.e. move us from 2.55× down to ~1.7-1.8× perl on life_bitpacked). That's significant but NOT parity. Full parity is a different order of project.

## What this does NOT explain

The **master-to-PR** 1.67× gap is NOT in this analysis — master has the same Perl-relative disadvantages. That gap is specifically the cost of our walker-hardening + refcount-alignment infrastructure (ReachabilityWalker, ScalarRefRegistry, Phase E emission). PR #533 already recovered ~2-3% of that; the rest needs static type tracking to safely skip Phase 1/1b.

## References

- `perl5/sv_inline.h` — refcount primitives
- `perl5/sv.h` — SvREFCNT_inc/dec macros
- `perl5/pp.h` — PUSHMARK/POPMARK
- `perl5/scope.h` — ENTER/LEAVE/FREETMPS/SAVETMPS
- `perl5/scope.c` — push_scope/pop_scope internals
- `perl5/pp_hot.c:6316` — pp_entersub
- `perl5/cop.h:836` — block_sub struct
