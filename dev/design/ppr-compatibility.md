# PPR 0.001010 Compatibility

Issue: #1318

## Objective

Make the complete non-optional test suite for CPAN distribution
`DCONWAY/PPR-0.001010` pass under PerlOnJava. The final integration gate is a
bounded `./jcpan -t PPR` run with no failed test files or stuck harness
processes.

This work follows #1299, which fixed the Joni analysis hang while loading
PPR's large dynamic named-subexpression grammar. Loading the module is no
longer the acceptance boundary: PPR grammar matching must now be compatible.

## Known Baseline

On the merged #1317 source, `timeout 1800 ./jcpan -t PPR` configured and built
PPR successfully, then exposed these failures:

| Test | Observed symptom | Initial ownership hypothesis |
| --- | --- | --- |
| `t/blocks.t` | `Range [4, 0) out of bounds for length 61` | Joni matcher offset/capture state |
| `t/control.t` | `Range [7, 0) out of bounds for length 49` | Joni matcher offset/capture state |
| `t/for_ref_iterator.t` | `Range [1, 0) out of bounds for length 43` | Joni matcher offset/capture state |
| `t/format.t` | `Range [1, 0) out of bounds for length 427` | Joni matcher offset/capture state |
| `t/heredoc.t` | second heredoc block does not match; harness then stops advancing | grammar matching / progression state |

The current baseline also proves that `t/00.load.t`, `t/decomment.t`,
`t/decomment_heredoc_large.t`, `t/document_self.t`, `t/erudil.t`, and
`t/eyedrops.t` passed before the stalled test. `t/disapproval.t` is an
explicit optional-dependency skip.

## Design Constraints

- Keep native regex semantics in the Joni fork. Do not special-case PPR source
  text, rewrite its patterns, or introduce a Java-regex fallback.
- Reduce each failure into a project-owned test under
  `src/test/resources/unit` before its fix is considered complete.
- Run each new Perl-level test on system Perl first, then on JVM and
  interpreter backends. Preserve positive and negative controls.
- Treat a timeout or a `0/0` test record as a failure, not a skip.
- Do not modify imported CPAN test files.

## Work Plan

### Phase 1: Establish the range-error owner

1. Reduce the first `t/blocks.t` grammar fragment to a small named-subpattern
   match.
2. Capture JVM debug stack traces and compare JVM/interpreter outcomes.
3. Locate the earliest invalid begin/end offset in Joni matcher, capture
   publication, or PerlOnJava's byte-to-character conversion.
4. Add the permanent reducer and correct the shared root cause.

### Phase 2: Complete adjacent grammar failures

1. Verify whether `control`, `for_ref_iterator`, and `format` share the Phase
   1 root cause.
2. Add distinct reducers for any non-shared behavior.
3. Test both matching and non-matching paths, including nested and empty
   recursive paths where relevant.

### Phase 3: Heredoc correctness and progress

1. Reduce PPR's second `t/heredoc.t` fixture unchanged in semantic form.
2. Establish the system Perl oracle and locate the first divergent regex stage.
3. Fix the mismatch and any subsequent non-progress condition separately if
   they have different causes.

### Phase 4: Distribution acceptance

1. Run focused PPR tests on both PerlOnJava backends.
2. Run the complete bounded `./jcpan -t PPR` suite and inspect its full log.
3. Run `make`, scan for warnings, and validate the exact clean candidate head.

## Progress Tracking

### Current Status: Phase 2 in progress

### Completed Phases

- [x] Phase 0: #1299 load-time analysis hang (2026-09-09)
  - Replaced exponential named-subexpression recursion analysis with graph
    analysis and retained dynamic-callout safety handling.
  - Added `src/test/resources/unit/regex_large_named_grammar.t`.
  - Merged in PR #1317.
- [x] Phase 1: Capture-range publication (2026-09-09)
  - Identified Joni's stale nonnegative begin / zero end sentinel as an
    unmatched capture in PerlOnJava's adapter.
  - Added direct adapter coverage and published it as `undef` rather than an
    invalid Java substring range.
  - This clears PPR's `blocks`, `control`, `for_ref_iterator`, and `format`
    range-error family.

### Next Steps

1. Complete Phase 2 by running the remaining range-family PPR tests and
   separating any non-shared failures.
2. Reduce the second `t/heredoc.t` fixture, which now reports its expected
   assertion failure and then times out in `ByteCodeMachine.opCall`.
3. Compare that reducer on system Perl, JVM, and interpreter backends.
4. Fix the remaining recursive-call execution path without weakening PPR's
   grammar or introducing source-specific handling.

### Open Questions

- Why does the second heredoc fixture fail before the subsequent recursive-call
  execution timeout?
- Does the heredoc execution path need an additional semantic guard distinct
  from call-frame lookup performance?

## Related Work

- Issue #1299: PPR grammar load-time Joni analysis hang.
- Issue #1318: PPR compatibility follow-up and acceptance tracking.
- `.agents/skills/debug-regex-engine/SKILL.md`.
