# PerlOnJava2/3/4 integration

## Scope

Integrate the committed sibling branch tips captured on 2026-09-30 into
`integrate/perlonjava3-4-20260930`, based on `6e53263b8`. Preserve each
branch's behavior and regression coverage, and require successful full
`make` validation and focused JVM/interpreter checks. New uncommitted work
in sibling directories is outside this snapshot.

| Source | Captured tip | Integrated commits |
| --- | --- | --- |
| PerlOnJava2 | `f513aa944` | `f50e2bf8f` plus restoration `c2c162768` |
| PerlOnJava3 | `95b3c1b58` | `3fa3c36ff`, `69af2da03`, `93b825aa0`, `17f228109`, `ae00ad79c` |
| PerlOnJava4 | `23514da1d` | `bae0b82df`, `8a33245ec`, `1faa6bda5` |

The original tips remain reachable through `refs/integration/perlonjava2`,
`refs/integration/perlonjava3`, and `refs/integration/perlonjava4`.

## Conflict audit

Whole-file selection during the initial PerlOnJava2 cherry-pick discarded
independent changes in conflicting files. The integration restores those
changes into the combined implementation rather than treating a successful
cherry-pick as proof of completeness.

| Conflicting file | Final integration decision |
| --- | --- |
| `Main.java` | Keep CLI delivery through `writeFatalDiagnostic`; combine both tied-STDERR recursion and reporting-failure handling there. |
| `BytecodeCompiler.java` | Restore defer label collection, compiler metadata propagation, strict-subs source positions and eval/defer boundary checks; retain field-initializer and source-token handling. |
| `BytecodeInterpreter.java` | Restore defer entry rejection and eval loop-control handling; retain sort-comparator checks and source-token locations. Unwind abandoned eval control stacks. |
| `InlineOpcodeHandler.java` | Keep token-aware marker creation and sort checks, which cover the incoming source-location behavior. |
| `EmitBlock.java` | Restore defer collection including lowered defer calls; retain construct and field-initializer checks. |
| `EmitControlFlow.java` | Restore defer entry/escape checks while retaining logical source coordinates. |
| `IOOperator.java` | Combine selected-glob return values with named unopened IO vivification and invalid-mask diagnostics. |
| `TieOperators.java` | Restore escaped-control-flow checks for both constructors and blessed method invocants; retain stash scalar ties. |
| `WarnDie.java` | Combine default-enabled categories with explicit lexical warning bits; restore UTF-8 handle context and fatal fallback; retain reference warnings and caller-frame handling. |
| `RuntimeRegex.java` | Keep mutable regex lvalues, scalarized identity and callback/cache behavior; the two sibling qr regression files verify the alternative representations' observable behavior. |
| `RuntimeBaseProxy.java` | Keep regex-scalar and compiled-identity propagation. |
| `RuntimeCode.java` | Restore defer loop-control and eval error helpers; retain nested-eval remapping and sort diagnostics. |
| `RuntimeIO.java` | Restore standard/lexical selected-handle identity, chunk counters, and default print warnings. |
| `RuntimeScalar.java` | Restore deferred malformed-UTF-8 state propagation; retain compiled-regex identity, weak refcount metadata and mutable regex lvalues. |
| `changelog.md` | Preserve all compatibility topics and remove duplicate entries. |

The earlier PerlOnJava3/4 conflicts also retain both regex identity fields
through scalar copies, mutation snapshots, localization, and graph cloning.

## Progress tracking

- [x] Capture and integrate all nine sibling commits (2026-09-30).
- [x] Audit discarded conflicting-file changes and commit restorations (2026-09-30).
- [x] Validate imported new Perl tests against system Perl: 31 files,
  144 assertions with version-dependent skips.
- [x] Validate `c2c162768`: focused defer make passes; JVM checks pass 33
  changed test files and 175 assertions.
- [x] Reproduce interpreter eval-stack regression with a permanent focused
  test; system Perl passes and unfixed interpreter fails (2026-09-30).
- [x] Validate `73af43548` with unfiltered make: 2,506 cases, three skips,
  zero failures/errors; all 35 changed Perl files (185 assertions) pass on
  JVM and interpreter (2026-09-30).
- [x] Publish draft PR 1580 and complete initial full UAT (2026-09-30).
- [ ] Validate the batched UAT corrections and require regression-free full
  UAT comparison plus green final-head CI.

### Next steps

Validate the UAT correction batch with a focused gate, then unfiltered make,
changed regression files on both backends, and the owning imported files.
Update PR 1580 and repeat full UAT against the supplied baseline; require green
final-head CI. Preserve the initial UAT evidence and keep full validation logs
under `/tmp/integration-*`.

### Full UAT correction batch (2026-09-30)

The initial 575-file UAT on `73af43548` gained 145 passing assertions overall,
but the raw comparison reports decreases in 14 files. Seven regex files
share a callback-program encoding mismatch after Unicode promotion. The other
causes are selected-glob capture before localization/restoration installation,
named Unicode handle return identity, runtime void assignment tails fetching
tied lvalues, duplicate symbolic-directory fetches, rejected `:stdio`, missing
JVM eval compilation caller frames, and a host print warning while reporting
a wide compilation error. Nine focused Perl regression files cover these
behaviors and pass system Perl; failure evidence is retained against the
unfixed `73af43548` development JAR.

Linux CI passed. Windows progressed through its serial unit suite until the
45-minute build cutoff, without a reported assertion failure. The batch raises
only that whole-build budget to 60 minutes, retaining per-test watchdogs and
all tests, with a project-owned CI budget contract test.

### Eval commit audit (2026-09-30)

The existing eval propagation commit `219525804` is already an ancestor of the
integration base. The imported nested-eval commit `1faa6bda5` repairs caller
metadata; it does not supply the missing immediate while-loop dispatch.
The direct dispatch in `26a58a882` exposed a JVM stack-shape error: value-producing
block exits require undef or an empty list on their incoming next/last edges.
Verification failure then exposed a separate fallback gap: the interpreter
factory ignored `useTryCatch` and compiled only the eval body without its catcher.
The candidate supplies the same exit values as ordinary next/last and preserves
the eval boundary through the existing interpreter eval-block compiler.
Coverage includes a direct fallback-factory Java test and Perl runtime-fault
tests, alongside the caught-exception control-stack regression.

### Open questions

The user supplied `../PerlOnJava/logs/test_20260930_090000_multi.log` as the
UAT comparison baseline; the file exists. GitHub access works outside the local sandbox.
Sibling directories have switched to other branches for new ongoing work;
the preserved imported refs define this integration's committed input snapshot.
