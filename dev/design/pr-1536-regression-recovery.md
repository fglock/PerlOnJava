# PR 1536 regression recovery

## Acceptance

Restore per-file results against `test_20260925_220000_pkg.log`, preserve
coreamp/lvref/switch improvements, and introduce no missing files or new invalid
TAP results. Aggregate pass gains do not offset regressions. Full unfiltered
make, frozen-source UAT, and successful Linux/Windows CI on the published SHA
remain required before completion. Do not add exclusions to obtain acceptance.

## Completed: lexical-sub storage correction (2026-09-26)

- Fetched origin and checked the integration branch against master; already
  up to date at `38ce261b9`.
- Preserved the isolated parser experiment as `83f65d7a2` on
  `wip/state-experiment-recovery-20260926`, with `/tmp/state-recovery-*` backups.
  That speculative parser change is not part of this correction.
- Confirmed that upstream lexsub uses interpreter fallback. A small JVM-only
  reproducer therefore did not establish the failing execution path.
- Corrected interpreter lexical-sub reads to respect the parser's choice of
  qualified compile-time storage versus an unqualified runtime pad.
- Corrected scalar/list CODE refalias writes and foreach CODE bindings to
  update the stored scalar cell, not merely replace a temporary read register.
- Added `state_sub_nested_forward_binding.t` and `lexical_code_alias_storage.t`.
  Both pass blead Perl; retained failing interpreter evidence before the fixes.
  Both have passing interpreter evidence; both also passed JVM checks.

Files changed: `BytecodeCompiler.java`, `CompileAssignment.java`, and the two
new unit tests. No existing tests were changed or deleted.

### Evidence

- `/tmp/state-nested-oracle-20260926.log`
- `/tmp/state-nested-parent-interpreter-20260926.log`: six failed assertions
- `/tmp/code-alias-oracle.log`
- `/tmp/code-alias-unfixed.log`: undefined CODE binding
- `/tmp/state-binding-alias-build.log`: filtered subroutine gate passed
- `/tmp/code-alias-storage-build.log`: final focused CODE-alias gate passed
- `/tmp/code-alias-final-{jvm,interpreter}.log`: 7/7
- `/tmp/state-nested-final-interpreter.log`: 6/6
- `/tmp/lexical-binding-final.log`: lvref 203/203, state 171/171,
  coreamp 32/32, switch 197/197, lexsub 159/160 (historical baseline restored).

## Completed: foreach padding (2026-09-26)

- Both backends now protect padded multi-variable foreach iterators with the
  same read-only alias semantics used for explicit undef list elements.
- Added `foreach_multivar_padding_readonly.t`: blead passes 3/3, unfixed JVM
  and interpreter fail two assertions, and both fixed backends pass 3/3.
- `JPERL_TEST_FILTER=foreach` make passes. Evidence:
  `/tmp/foreach-padding-build.log`, `/tmp/foreach-padding-parent-{jvm,interpreter}.log`,
  `/tmp/foreach-padding-fixed-{jvm,interpreter}.log`.
- `/tmp/foreach-padding-core.log`: for-many restored to baseline 73/81;
  lvref remains 203/203.
- Files: `EmitForeach.java`, `BytecodeCompiler.java`, and the new unit test.

## Completed: state declared-reference lists (2026-09-26)

- Interpreter lowering for a state declaration list used transient BEGIN storage
  rather than the declaration's state cell. Returned references therefore did
  not identify the variables after lookup.
- Added `state_declared_reference_list_identity.t`: blead passes 3/3, both
  unfixed backends fail 3/3, and both fixed backends pass 3/3.
- `JPERL_TEST_FILTER=state_declared_reference_list_identity` make passes;
  `/tmp/decl-refs-state-fixed.log` restores `op/decl-refs.t` to 408/408.
- Files: `BytecodeCompiler.java` and the new unit test.

## Completed: numeric scalar flip-flop endpoints (2026-09-26)

- Numeric literal endpoints in scalar `..`/`...` now compare with `$.`, as
  Perl requires, instead of behaving as unconditionally true values.
- The endpoint metadata is carried by both JVM emission and interpreter
  bytecode; list-context ranges retain literal bounds.
- Added `scalar_flipflop_numeric_endpoints.t`: blead passes 3/3, both unfixed
  backends fail the original check, and both fixed backends pass 3/3.
- `JPERL_TEST_FILTER=scalar_flipflop_numeric_endpoints` make passes;
  `/tmp/smartmatch-flipflop-fixed.log` restores `op/smartmatch.t` to 353/353.
- Files: shared flip-flop runtime, JVM/interpreter lowering, opcode
  disassembly, and the new unit test.

## Completed: final regression recovery (2026-09-27)

- Restored the Unicode-property regressions and the shared-reference thread
  teardown regression. The permanent project-owned coverage includes
  `threads_shared_recursive_lock.t`; the relevant focused tests pass on blead
  Perl, JVM, and interpreter backends.
- Ran the full unfiltered `nice -n 19 make` from the rebased commit; it passed.
- Ran the frozen-source full UAT at
  `/tmp/test_20260927_uat_final_rebased.log`: 575 files, 680,740 assertions,
  zero timeouts, and zero incomplete files.
- Compared that UAT against `test_20260925_220000_pkg.log`. It reports zero
  regression files and 783 additional passing assertions. The historical
  September 26 lvref log retains its historical regressions and is not the
  final-run evidence.
- Rebasing produced `d73d5eb74`; PR #1536 was updated and is currently ready
  for review. The subsequent empty CI-retrigger commit changes no source tree.

## Next steps

1. Obtain Linux and Windows CI success for the final PR head. GitHub Actions
   has not attached a run to the current head despite synchronize and reopen
   events; do not claim PR acceptance until that external check completes.

## Open issues

Local acceptance is complete. The only remaining acceptance condition is the
missing GitHub Actions run for the published PR head.

## References

- [Testing cadence](../../.agents/skills/debug-perlonjava/references/testing-cadence.md)
- [Agent guidelines](../../AGENTS.md)
