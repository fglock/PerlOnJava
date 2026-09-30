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
- [ ] Validate corrected eval-stack candidate with full make and both backends.

### Next steps

Run the unfiltered build on the committed corrected candidate, then run all
changed Perl regression files on JVM and interpreter using its development
JAR. Record final results here. Keep full logs under `/tmp/integration-*`.

### Open questions

None. Sibling directories have switched to other branches for new ongoing work;
the preserved imported refs define this integration's committed input snapshot.
