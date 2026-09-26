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

## Next steps

1. Fix remaining targeted regressions: universal 108/142 (unexpected tied
   STORE), decl-refs 402/408 (state declaration reference identity), for-many
   71/81 (baseline 73/81), magic 206/208, smartmatch 352/353, class/destruct 3/7
   (baseline 4/7). Raw evidence: `/tmp/recovery-targeted.log`.
2. Investigate the two Unicode-property files and compare fixture plans with
   local perl5/blead before interpreting count changes.
3. Restore baseline coverage for perf/opcount and perf/optree; resolve the
   branch-added exclusion test consistently with the prohibition on changing
   existing tests. No exclusion changes have been made in this recovery phase.
4. Reproduce the twelve published CI failures on the current branch; distinguish
   already-fixed failures from remaining runtime and platform problems.
5. Audit temporary committed artifacts and changelog, then run unfiltered make,
   backend gates, and full frozen-source UAT. Compare with both historical logs
   using per-file regressions, missing rows, and new-invalid checks.
6. Update existing PR 1536 only after local acceptance and require both CI jobs
   to pass on its final head. No push or PR update has occurred in this phase.

## Open issues

Full UAT and CI acceptance are not yet achieved. The one remaining lexsub
failure is the historical debugger/goto test 73, not a newly accepted regression.
Do not claim a full gate from the filtered builds above.

## References

- [Testing cadence](../../.agents/skills/debug-perlonjava/references/testing-cadence.md)
- [Agent guidelines](../../AGENTS.md)
