# Batch implementation before expensive validation

Prefer one coherent implementation batch over a change–full-test–change loop.
After establishing the failure and expected behavior, inspect related call
sites and complete the fix across affected paths, including both backends when
applicable, before rebuilding. Include the focused regression coverage and
nearby edge cases in that batch. Group failures only when evidence connects
them to the same cause; keep unrelated fixes and speculative optimizations
separate so failures remain attributable.

Use a small test earlier when it answers an unresolved question that could
change the implementation. Batching is a recommendation to reduce redundant
work, not a reason to postpone useful feedback or accumulate an arbitrary
number of edits. For performance work, retain a measured baseline and isolate
each optimization hypothesis.

Once the batch is ready, rebuild through an appropriate `make` target and run
focused regression and adjacent tests. `JPERL_TEST_FILTER` can narrow Perl
unit coverage during iteration; a filtered build is not a full validation
gate and may still run other build tasks. Direct `jperl` checks must use an
artifact built from the current implementation, never a stale JAR. `make dev`
is disabled; do not use older build-only examples in the skills.

Run unfiltered `make` and the task's required backend, engine, platform, and
integration gates when the coherent candidate is ready, before pushing or
updating a PR. Clear any test filter for full validation. Local WIP snapshots
do not each need a full-suite run. After a failure, inspect its evidence and
finish the related correction before restarting expensive validation. Repeat
required gates after subsequent runtime-affecting changes; do not reuse a
pass from an earlier candidate as proof of the final code.

Keep the existing safety and acceptance rules: validate new Perl-level tests
with system Perl first, retain unfixed-parent failure evidence and permanent
regression tests, verify both backends as required, capture complete output,
and wrap `jperl`/`jcpan`/`prove` in timeouts. Do not edit a checkout or rebuild
its shared JAR while a gate or reader is running there. This recommendation
does not introduce a queue, lock service, or additional parallel test workers.
