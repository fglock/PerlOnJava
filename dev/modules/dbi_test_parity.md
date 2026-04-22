# Plan: DBI Test Suite Parity

This document tracks the work needed to make `jcpan -t DBI` (the bundled
DBI test suite, 200 test files) pass on PerlOnJava.

## Current Baseline

After Phase 2 (driver-architecture pieces: `install_driver`,
`_new_drh` / `_new_dbh` / `_new_sth`, `DBD::_::common / dr / db / st`
base classes):

| | Files | Subtests | Passing | Failing |
|---|---|---|---|---|
| `jcpan -t DBI` | 200 | 1600 | 1240 | 360 |

Previous baseline (after Phase 1 — runtime interpreter fallback,
[PR #542](https://github.com/fglock/PerlOnJava/pull/542)):

| | Files | Subtests | Passing | Failing |
|---|---|---|---|---|
| `jcpan -t DBI` | 200 | 946 | 676 | 270 |

Previous baseline (after [PR #540](https://github.com/fglock/PerlOnJava/pull/540),
Exporter wiring only):

| | Files | Subtests | Passing | Failing |
|---|---|---|---|---|
| `jcpan -t DBI` | 200 | 638 | 368 | 270 |

The remaining failures fall into four categories, listed below in
priority order. Phase 1 is the hard blocker — several entire test files
abort mid-run on PerlOnJava backend errors, so we cannot even see what
DBI-level bugs lie behind them until the backend is fixed or we fall
back to the interpreter.

---

## Phase 1 (priority 1): fix or fall back from bytecode-gen verifier bug

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

### Current Status: Phase 2 complete. Phase 3 is next.

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

### Next Steps

1. Start **Phase 3**: skip `DBI::PurePerl` cleanly under PerlOnJava
   (or decide to port it), and triage `DBD::File` / `DBD::DBM`
   behaviour against `t/49dbd_file.t` and friends. `DBD::Gofer`
   can be deferred until the others stabilise.
2. After Phase 3, re-run `jcpan -t DBI` and refresh the baseline.

### Open Questions

- Is it worth porting `DBI::PurePerl` at all, or should we just
  skip it under PerlOnJava? See Phase 3a.
- Does anyone actually use Gofer on PerlOnJava? Phase 3c can
  probably be skipped entirely.

---

## Related Documents

- [`dev/modules/dbix_class.md`](dbix_class.md) — DBIx::Class sits on
  top of DBI; progress here directly helps DBIx::Class too.
- [`AGENTS.md`](../../AGENTS.md) — includes the `JPERL_SHOW_FALLBACK`
  debug-env var mentioned in Phase 1.
- [`src/main/java/org/perlonjava/app/scriptengine/PerlLanguageProvider.java`](../../src/main/java/org/perlonjava/app/scriptengine/PerlLanguageProvider.java)
  — existing interpreter-fallback path we'd extend in Phase 1.
