# Plan: DBI Test Suite Parity

This document tracks the work needed to make `jcpan -t DBI` (the bundled
DBI test suite, 200 test files) pass on PerlOnJava.

## Current Baseline

After Phase 4 (tied-hash method-dispatch fix):

| | Files | Subtests | Passing | Failing |
|---|---|---|---|---|
| `jcpan -t DBI` | 200 | 5890 | 4160 | 1730 |

Previous baseline (after Phase 3 third batch):

| | Files | Subtests | Passing | Failing |
|---|---|---|---|---|
| `jcpan -t DBI` | 200 | 5878 | 4156 | 1722 |

Previous baseline (after Phase 3 second batch — tied handles):

| | Files | Subtests | Passing | Failing |
|---|---|---|---|---|
| `jcpan -t DBI` | 200 | 5862 | 4116 | 1746 |

Previous baseline (after Phase 3 first batch):

| | Files | Subtests | Passing | Failing |
|---|---|---|---|---|
| `jcpan -t DBI` | 200 | 5610 | 3978 | 1632 |

Previous baseline (after Phase 2 — driver-architecture pieces):

| | Files | Subtests | Passing | Failing |
|---|---|---|---|---|
| `jcpan -t DBI` | 200 | 1600 | 1240 | 360 |

Previous baseline (after Phase 1 — runtime interpreter fallback):

| | Files | Subtests | Passing | Failing |
|---|---|---|---|---|
| `jcpan -t DBI` | 200 | 946 | 676 | 270 |

Previous baseline (after Exporter wiring only):

| | Files | Subtests | Passing | Failing |
|---|---|---|---|---|
| `jcpan -t DBI` | 200 | 638 | 368 | 270 |

Original baseline on master: 562 subtests, 308 passing, 254 failing.

The remaining failures fall into five categories, listed below in
priority order. The highest priority is now Phase 4 — a PerlOnJava
interpreter bug discovered while working on Phase 3. It blocks an
entire family of DBI profile-related tests and, worse, is a latent
correctness problem that will keep surfacing in unrelated CPAN
modules as long as it's unfixed.

Phase 1 was a similar hard blocker on the JVM backend — test files
aborted mid-run and masked the real DBI gaps. Now that Phases 1–3
have opened up most of the suite, the interpreter bug has become
the single biggest lever.

---

## Phase 4 (priority 1, NEW): PerlOnJava bug — method dispatch on tied hash FETCH

**Status: done (2026-04-22).** Root-cause was narrower than the
repro suggested: `local` was a red herring. The real bug was that
`$tied_hash{key}->method(...)` dispatch on the value returned from
a tied hash FETCH saw only the `TIED_SCALAR` proxy shell, not the
underlying blessed reference, and fell through to the
"stringify-as-package-name" error path.

### The bug (refined diagnosis)

Minimal repro:

```perl
package Tie;
sub TIEHASH { bless \$_[1], $_[0] }
sub FETCH   { ${$_[0]}->{$_[1]} }

package Foo;
sub meth { print "in meth\n"; }

package main;
my $obj = bless {}, 'Foo';
my %h;
tie %h, 'Tie', { obj => $obj };
$h{obj}->meth;     # <-- died
```

Output before the fix:

```
Can't locate object method "meth" via package
    "Foo=HASH(0x7276c8cd)" (perhaps you forgot to load ...)
```

`ref($h{obj})` returned `"Foo"` (correct) but direct method
dispatch used the scalar's stringification as the package name
(`"Foo=HASH(0x...)"`).

### Why

`RuntimeHash.get()` for a tied hash returns a **`TIED_SCALAR`
proxy** — lazily backed by `tiedFetch()` — instead of the fetched
value itself. That's deliberate (so `$h{key} = "x"` can route
through `STORE` and so lvalue semantics work), but the method-
dispatch code in `RuntimeCode.callCached` and `RuntimeCode.call`
only unwrapped `READONLY_SCALAR`, not `TIED_SCALAR`. The latter
hit `isReference(invocant) -> false` and fell through to
`perlClassName = invocant.toString()`, which is what Perl's
default stringification of a blessed hashref looks like —
`Foo=HASH(0x...)`.

The scalar's stringified class name was then used as the package
to look up the method in, and naturally no such package exists.

### The fix

`src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`:
at the top of both `callCached` and `call`, unwrap `TIED_SCALAR`
to the fetched value (mirroring the existing handling for
`apply()` at lines 2378 / 2659 / 2846):

```java
if (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) {
    return callCached(callsiteId, runtimeScalar.tiedFetch(), ...);
}
```

`tiedFetch()` is an existing `RuntimeScalar` helper that either
returns the tied handle's `self` (for scalar tie handles) or calls
`TieHash.tiedFetch` for hash-element proxies.

### Effect

- The minimal repro passes with both `jperl` (JVM backend) and
  `jperl --interpreter`.
- `t/41prof_dump.t` runs 9 subtests before hitting an unrelated
  Profile-on-disk issue (was: died after 7).
- `t/42prof_data.t` runs 4 subtests (was: 3).
- Small `jcpan -t DBI` overall delta (+4 passing subtests,
  5886→5890 executed) because these tests have other
  Profile-related failures further down.
- No other regressions in `make` or the DBI suite.

### Still open

Not strictly related to the tie fix, but discovered during
investigation and worth nothing here:

- `local` + tied hashes may still have edge cases around restore
  ordering. The specific repro in the previous Phase 4 section
  now works, but it's worth auditing.
- `RuntimeHash.get()` on tied hashes always builds a fresh proxy
  `RuntimeScalar` each call, so repeated `$h{key}` does repeated
  `FETCH`es on access. The fix triggers one extra FETCH per
  method dispatch; still correct but not free.

---

## Phase 1 (priority 2): fix or fall back from bytecode-gen verifier bug

**Status: done (2026-04-22). Fell back to the interpreter on
runtime VerifyError rather than fixing the emitter.**

### The bug

Running `t/01basics.t` on the JVM backend produces:

```
ok 1 - use DBI;
Bad local variable type
Exception Details:
  Location:
    org/perlonjava/anon1762.apply(
        Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;I
    )Lorg/perlonjava/runtime/runtimetypes/RuntimeList; @25039: aload
  Reason:
    Type top (current frame, locals[203]) is not assignable to reference type
```

This is a JVM bytecode verifier error on a per-subroutine `apply()`
method. The cause is a PerlOnJava-generated flat method with ~200+
local variables and inconsistent stack-map frames — any flat Perl
script body with hundreds of top-level statements (here 130+ `cmp_ok`
calls plus 24 `ok`/`is` calls in a single `BEGIN`/top-level) triggers
it. It is not a DBI-specific bug; the same backend bug affects any
sufficiently large test script.

### Why the existing fallback does not kick in

`src/main/java/org/perlonjava/app/scriptengine/PerlLanguageProvider.java`
already has an interpreter-fallback path (`needsInterpreterFallback`)
that catches `VerifyError` and messages like `"Method too large"`,
`"dstFrame"`, `"ASM frame computation failed"`, etc. The 01basics.t
failure slips past that path because:

- the bad class is generated **successfully** (no ASM error during emit);
- the problem is only detected by the JVM verifier at **class-load /
  first-invocation time**, long after
  `PerlLanguageProvider.compileToRuntimeCode` returns;
- subroutines are compiled and loaded lazily (see
  `backend/jvm/EmitSubroutine.java`), and the lazy path does not have
  a fallback wrapper around the verify-time error.

### Plan

Target: when a JVM-compiled Perl subroutine fails class verification,
automatically recompile that subroutine with the bytecode interpreter
and re-invoke, rather than aborting the process.

1. **Reproduce in isolation.** Add a tiny repro under
   `src/test/resources/backend/`:
   ```perl
   # 200+ top-level cmp_ok-style calls, enough to trip the same
   # "locals[203] is not assignable" verifier error.
   ```
   Run it with `./jperl` and confirm the failure, then with
   `./jperl --interpreter` and confirm the interpreter executes it
   correctly. (We already know the interpreter handles this shape —
   `./jperl --interpreter t/01basics.t` runs past the verifier point
   and only stops on a different, unrelated issue.)

2. **Decide: fix the emitter, or fall back.** Two realistic options;
   do whichever is smaller:

   a. **Fix the emitter.** The underlying generator bug is that a
      local-variable slot ends up with `top` (= uninitialised /
      disjoint) on one incoming path and a reference type on another.
      Candidates to audit:
      - `backend/jvm/EmitBlock.java`, `EmitSubroutine.java`,
        `EmitCompilerFlag.java` — how locals are allocated across
        nested blocks and re-used;
      - `backend/jvm/EmitLiteral.java` — slot reuse for temporaries
        in large constant lists;
      - ASM `ClassWriter.COMPUTE_FRAMES` vs our manual frame logic
        in `EmitControlFlow.java`.
      Expect the fix to be: initialise all slots to a consistent
      reference type at method entry, or clear/reset slot type on
      every entry to a `full_frame` target so the verifier sees a
      consistent type.

   b. **Fall back on verifier errors at first call.** If (a) is too
      invasive, wire a `try { invoke } catch (VerifyError)` around the
      first invocation of a lazily-loaded compiled subroutine.
      On catch, rebuild the sub via `BytecodeCompiler` (as the main
      script path already does in `PerlLanguageProvider` lines
      519–557) and swap the `MethodHandle` in the `RuntimeCode`
      instance to point at the interpreted version.

      This probably belongs in `runtime/runtimetypes/RuntimeCode.java`
      or around the `MethodHandle.invokeExact` call site in
      `backend/jvm/EmitSubroutine.java`. Add a one-time guard so we
      don't retry compiled-then-verify on every call; remember the
      fallback and use it directly on subsequent invocations.

3. **Extend `JPERL_SHOW_FALLBACK` coverage** so both the main-script
   fallback and the new per-sub fallback print a "Note: using
   interpreter fallback (verify error in sub <name>)" line when the
   env var is set.

4. **Regression test.** Add the repro from step 1 to
   `src/test/resources/` and assert it runs to completion. Also
   re-run `jcpan -t DBI` and record the new baseline here.

### Acceptance criteria

- `./jperl t/01basics.t` (and sibling DBI tests) no longer aborts
  with a `VerifyError`; it either runs correctly on the JVM backend
  or falls back silently to the interpreter.
- `JPERL_SHOW_FALLBACK=1 ./jperl <repro>` prints a single `Note:`
  line identifying the fallback.
- `make` still passes.
- Expected DBI delta: ~25–30 additional test files move from
  "Tests: 1 Failed: 0, Parse errors: Bad plan" to reporting real
  test results.

---

## Phase 2 (priority 2): missing DBI core internals

**Status: done (2026-04-22). Pure-Perl DBDs now load and connect.**

Several tests die with:

```
Undefined subroutine &DBI::_new_drh called at t/02dbidrv.t line 28.
Can't locate object method "install_driver" via package "DBI".
```

These methods are part of the documented DBI API that driver modules
(including DBI's own `DBD::File`, `DBD::Gofer`, `DBD::Sponge`) build
on. They are currently unimplemented in
`src/main/java/org/perlonjava/runtime/perlmodule/DBI.java`.

### Plan

1. **Survey required methods.** Grep the test files and the bundled
   `DBD::*` modules for calls that fail:
   ```
   grep -rhoE '\bDBI::[A-Za-z_][A-Za-z0-9_]*|DBI->[A-Za-z_][A-Za-z0-9_]*' \
       ~/.cpan/build/DBI-1.647-5/t/ ~/.cpan/build/DBI-1.647-5/lib/ \
       | sort -u
   ```
   Expected minimum set (from spot-checking):
   - `DBI::_new_drh` — bless a driver handle (`DBI::dr`) with
     installed attributes.
   - `DBI::_new_dbh` / `DBI::_new_sth` — same for db/statement handles.
   - `DBI->install_driver($name)` — locate `DBD::$name`, call its
     `driver()` factory, cache result, return the drh.
   - `DBI->installed_drivers` (already a stub — verify it actually
     reflects loaded drivers).
   - `DBI->trace`, `DBI->trace_msg`, `DBI->parse_trace_flag(s)` —
     aliased from `DBD::_::common::` in real DBI; needs the
     `DBD::_::common` / `DBD::_::db` / `DBD::_::st` base classes
     with trace-flag state.
   - `$h->set_err`, `$h->err_handler`, `$h->func` — handle-level
     helpers used by tests in `t/08keeperr.t` and `t/17handle_error.t`.

2. **Pick implementation language per method.** Simple glue (e.g.
   `_new_drh` just blesses a hash with known attributes) should live
   in `src/main/perl/lib/DBI.pm`. Anything that has to interact with
   the JDBC driver registry (e.g. `install_driver`) belongs in
   `src/main/java/org/perlonjava/runtime/perlmodule/DBI.java`.

3. **Make `install_driver` work with bundled DBDs.** The test suite
   loads the bundled pure-Perl drivers:
   - `DBD::ExampleP` — trivial Perl-only driver used by many tests;
   - `DBD::NullP` — even simpler, used for negative tests;
   - `DBD::Sponge` — used by `fetchall_arrayref` tests.
   Verify each loads and `install_driver("ExampleP")` returns a
   working drh. These drivers already ship in
   `$HOME/.perlonjava/lib/DBD/` after `jcpan -i DBI`.

4. **Wire `DBD::_::common` / `db` / `st` base classes.** Real DBI
   exposes these as parent packages that drh/dbh/sth inherit from
   (in addition to the driver-specific `DBD::X::dr` etc.). Tests
   probe things like `ref($dbh)->isa('DBD::_::db')`. Add empty
   packages in `DBI.pm` with the required base methods (`trace`,
   `trace_msg`, `set_err`, `err`, `errstr`, `state`, `func`) wired
   to the existing Java implementation or to simple Perl stubs.

### Acceptance criteria

- `./jperl ~/.cpan/build/DBI-1.647-5/t/02dbidrv.t` runs past line 155
  (where it currently dies on `install_driver`).
- `./jperl -e 'use DBI; my $drh = DBI->install_driver("ExampleP"); print ref $drh'`
  prints `DBD::ExampleP::dr`.
- Expected DBI delta: `t/02dbidrv.t`, `t/07kids.t`,
  `t/17handle_error.t`, `t/10examp.t` start reporting meaningful
  results instead of blowing up early.

---

## Phase 3 (priority 3): pure-Perl subdrivers

Most of the 180 failing wrapper files belong to three pure-Perl
subdriver axes that real DBI ships and tests:

| Axis | Prefix | Implemented? |
|---|---|---|
| Base tests (no wrapper) | `01basics.t` etc. | mostly hits Phase 1/2 issues |
| `DBD::Gofer` | `zvg_*` | no — Gofer transport missing |
| `DBI::SQL::Nano` | `zvn_*` | partially — test framework only needs the module to load |
| `DBI::PurePerl` | `zvp_*` | no — module aborts on load today |
| combinations | `zvxg*_*` | combinations of the above |

The two big missing pieces:

### 3a. `DBI::PurePerl`

`lib/DBI/PurePerl.pm` is installed by `jcpan -i DBI` but fails to load
because it assumes `DBI::st::TIEHASH`, `DBI::db::TIEHASH`,
`%DBI::installed_drh`, and the whole tied-hash handle model — none of
which our Java-backed DBI uses.

Options:
- **Skip cleanly.** Make `DBI::PurePerl` `warn` and `exit` when
  loaded under PerlOnJava so the `zvp_*` wrappers are skipped
  rather than counted as failures. Low effort, immediate win on the
  overall file count.
- **Port properly.** Much bigger: we would need Perl-side handle
  objects tied to the same Java DBI state. Probably not worth it
  unless a user actually needs `DBI_PUREPERL=1`.

**Recommendation**: do the skip-cleanly approach first. Revisit if
there's demand.

### 3b. `DBD::File` / `DBD::DBM`

Used by `t/49dbd_file.t`, `t/50dbm_simple.t`, `t/51dbm_file.t`,
`t/52dbm_complex.t`, `t/53sqlengine_adv.t`, `t/54_dbd_mem.t`, and
every `zv*_49..54` variant. These drivers implement a SQL engine
(`DBI::DBD::SqlEngine`) over the filesystem / DBM / in-memory
storage.

The hard dependency is `SQL::Statement` and `Text::CSV_XS`.
`SQL::Statement` is pure Perl and should load. `Text::CSV_XS` is
XS — check whether `Text::CSV` (pure Perl) satisfies DBD::File's
requirements.

Plan:
1. Verify `SQL::Statement` loads under PerlOnJava.
2. Run `./jperl t/49dbd_file.t` and triage the first failure.
3. Decide whether to port the missing bits or mark the family
   as skipped with a clear reason.

### 3c. `DBD::Gofer`

Gofer is a remote-DBI transport using stream / pipe / HTTP. Tests
use the in-process `null` transport. The whole family (`zvg_*`) is
probably tractable if and only if `DBI::Gofer::Transport::null`
loads cleanly — which requires tie-hash compatibility similar to
Phase 3a. Defer until after Phase 1 & 2 are done so we can measure
the real baseline.

### Acceptance criteria

- `zvp_*` wrappers are either skipped with a clear "skipped under
  PerlOnJava: DBI::PurePerl requires tied-hash handles" or pass.
- `t/49dbd_file.t` and friends either pass or are skipped with a
  concrete reason.
- Expected DBI delta: of the remaining ~180 failing files, ~120
  should move to "skipped" or "passed".

---

## Phase 4 (priority 4): everything else

Anything left after Phase 3 is bug-by-bug DBI or subdriver work:
callbacks (`t/70callbacks.t`), handle-error ordering
(`t/17handle_error.t`), profiling (`t/40profile.t`,
`t/41prof_dump.t`, `t/42prof_data.t`, `t/43prof_env.t`), tainting
(skipped already because we don't run with `perl -T`), threads
(skipped already), proxy (`t/80proxy.t`, needs `RPC::PlServer`).

Triage these once Phase 1 & 2 are done and we have clean output.

---

## Progress Tracking

### Current Status: Phases 1–4 landed on `fix/dbi-test-parity` (PR #546). Phase 4 fixed a core PerlOnJava bug in method dispatch on tied hash FETCH.

### Completed

- [x] **2026-04-22 — Exporter fix.** PR #540.
  - Added `%EXPORT_TAGS` for `:sql_types`, `:sql_cursor_types`,
    `:utils`, `:profile` to `src/main/perl/lib/DBI.pm`.
  - Added missing constants (`SQL_INTERVAL_*`, `SQL_ARRAY_LOCATOR`,
    `SQL_CURSOR_*`, `DBIstcf_*`).
  - Ported `neat`, `neat_list`, `looks_like_number`,
    `data_string_diff`, `data_string_desc`, `data_diff`,
    `dump_results`, `sql_type_cast`, `dbi_time` into
    `src/main/perl/lib/DBI/_Utils.pm`.
  - Baseline went from 308/562 passing to 368/638 passing.

- [x] **2026-04-22 — Phase 1: runtime interpreter fallback.** PR #542.
  - Added a second try/catch at the `runtimeCode.apply(...)` call
    site in `PerlLanguageProvider.executeCode`. The existing
    compile-time fallback path only runs while
    `compileToExecutable` is executing, but HotSpot defers
    per-method bytecode verification to the first invocation,
    so `VerifyError` / `ClassFormatError` propagated past that
    point. Now we re-use `needsInterpreterFallback` at invocation
    time, recompile the AST through `BytecodeCompiler`, and re-run
    `apply()` on the interpreted form. BEGIN / CHECK / INIT have
    already run by this point and the main body has not, so retry
    is safe.
  - `JPERL_SHOW_FALLBACK=1` now also prints a
    "Note: Using interpreter fallback (verify error at first call)."
    line when this new path fires.
  - Baseline went from 368/638 passing to 676/946 passing
    (+308 additional subtests now execute successfully). Same 270
    still fail — those are Phase 2/3 DBI-level issues that were
    previously hidden behind the verifier crash.

- [x] **2026-04-22 — Phase 2: driver-architecture pieces.** PR TBD.
  - Added `DBI->install_driver`, `DBI->data_sources`,
    `DBI->available_drivers`, `DBI->installed_drivers`,
    `DBI->setup_driver`, `DBI::_new_drh`, `DBI::_new_dbh`,
    `DBI::_new_sth`, `DBI::_get_imp_data` in the new
    `src/main/perl/lib/DBI/_Handles.pm`.
  - Added `DBD::_::common` / `dr` / `db` / `st` base classes with
    FETCH, STORE, err, errstr, state, set_err, trace, trace_msg,
    parse_trace_flag(s), func, dump_handle, default connect,
    connect_cached, quote, data_sources, disconnect, finish,
    fetchrow_array/hashref, rows, etc. — enough for the bundled
    pure-Perl DBDs to work (`DBD::NullP`, `DBD::ExampleP`,
    `DBD::Sponge`, `DBD::Mem`, `DBD::File`, `DBD::DBM`).
  - Stubbed `DBI::dr` / `DBI::db` / `DBI::st` packages so
    `isa('DBI::dr')` etc. pass; `DBD::_::<suffix>` inherits from
    them.
  - Modified `DBI->connect` in `DBI.pm`: when the DSN's driver
    (`DBD::$name`) has a `driver()` method but no `_dsn_to_jdbc`
    (i.e. it's a pure-Perl DBD), route through
    `install_driver($name)->connect(...)` instead of the JDBC path.
  - Baseline went from 676/946 passing to 1240/1600 passing
    (+564 additional subtests now pass; +654 more execute). 10
    fewer test files fail overall.

- [x] **2026-04-22 — Phase 3 first batch: more DBI internals.**
  - (As before.) Baseline 1240/1600 → 3978/5610 passing.

- [x] **2026-04-22 — Phase 3 second batch: tied-handle semantics.**
  - (As before.) Baseline 3978/5610 → 4116/5862 passing.

- [x] **2026-04-22 — Phase 3 third batch: Profile / transactions / misc.**
  - Added `DBD::_::common::STORE` magic for the `Profile` attribute:
    a string like `"2/DBI::ProfileDumper/File:path"` is upgraded to
    a real `DBI::ProfileDumper` object on assignment (and on
    `_new_dbh` when passed via the connect attr hash).
  - `_new_sth` inherits `Profile` from the parent dbh.
  - Added `DBI->visit_handles` that walks `%installed_drh` and
    recurses via `visit_child_handles`.
  - Fixed `begin_work` / `commit` / `rollback` so transactions round-
    trip `AutoCommit` / `BegunWork` correctly.
  - Added `AutoCommit` sentinel translation in
    `DBD::_::common::FETCH`: the `-900` / `-901` values that pure-
    Perl drivers STORE (to signal "I've handled AutoCommit myself")
    are translated back to `0` / `1` on FETCH, matching real DBI's
    XS behaviour.
  - Made DBI.pm's `connect` wrapper re-apply the user's attr hash
    on the returned dbh (Profile / RaiseError / PrintError /
    HandleError) so driver `connect()` implementations that ignore
    most of the attr hash still get those attributes set.
  - Baseline 4116/5862 → 4156/5878 passing (+40 subtests). 2 more
    test files pass (164/200 failing, was 166/200).

- [x] **2026-04-22 — Phase 4: tied-hash method-dispatch fix.**
  - `RuntimeCode.callCached` and `RuntimeCode.call` now unwrap
    `TIED_SCALAR` to the underlying fetched value before
    checking `isReference` / `blessId`. Without this, method
    dispatch on `$tied_hash{key}->method(...)` treated the
    stringified form of the blessed ref as the package name.
  - Fixes both JVM backend and `--interpreter` path.
  - Baseline 4156/5878 → 4160/5890 passing. Small overall delta
    because the profile tests that were blocked on this have
    other downstream issues.
  - PerlOnJava bug fix; useful for any CPAN module that does
    direct method calls through tied hash elements (DBI itself,
    DBIx::Class, Catalyst-style dispatch tables).

### Next Steps

1. **Profile-on-disk internals.** `t/41prof_dump.t` /
   `t/42prof_data.t` / `t/43prof_env.t` still fail after Phase 4
   — not blocked by the tie bug anymore, but the
   ProfileDumper-writes-to-file path is not exercising correctly.
   Likely `flush_to_disk` path needs more DBI::Profile internals.
2. **HandleError flow** (`t/17handle_error.t`, `t/08keeperr.t`) —
   the ordering between RaiseError, PrintError, HandleError, and
   set_err is subtle and our current implementation cuts some
   corners.
3. **Trace file support** (`t/09trace.t`, `t/19fhtrace.t`) —
   `trace($level, $output)` currently only tracks a level, no
   output redirection.
4. **`t/16destroy.t` Active-in-DESTROY semantics.**
5. Periodically re-run `jcpan -t DBI` to track progress.

### Open Questions

- Is it worth porting `DBI::PurePerl` at all, or should we just
  skip it under PerlOnJava? See Phase 3a.
- Does anyone actually use Gofer on PerlOnJava? Phase 3c can
  probably be skipped entirely.
- Phase 4's bug: is it purely in the `local` restore path, or
  does method dispatch on a once-`local`-ized tied slot read
  the wrong SV? Minimal repro below will pin it down.

---

## Related Documents

- [`dev/modules/dbix_class.md`](dbix_class.md) — DBIx::Class sits on
  top of DBI; progress here directly helps DBIx::Class too.
- [`AGENTS.md`](../../AGENTS.md) — includes the `JPERL_SHOW_FALLBACK`
  debug-env var mentioned in Phase 1.
- [`src/main/java/org/perlonjava/app/scriptengine/PerlLanguageProvider.java`](../../src/main/java/org/perlonjava/app/scriptengine/PerlLanguageProvider.java)
  — existing interpreter-fallback path we'd extend in Phase 1.
- PerlOnJava source dirs relevant to Phase 4:
  - [`src/main/java/org/perlonjava/backend/bytecode/`](../../src/main/java/org/perlonjava/backend/bytecode/)
    — the interpreter backend (`--interpreter`), where tie magic
    and `local` restore hooks live.
  - [`src/main/java/org/perlonjava/backend/jvm/`](../../src/main/java/org/perlonjava/backend/jvm/)
    — the JVM backend emitter; both backends need the fix.
  - [`src/main/java/org/perlonjava/runtime/`](../../src/main/java/org/perlonjava/runtime/)
    — tied hash magic (TIEHASH / FETCH / STORE dispatch) lives
    here; the scalar representation that method dispatch reads is
    likely also here.
