# Core-suite failure reduction

Issue: [#1269](https://github.com/fglock/PerlOnJava/issues/1269)

## Goal

Reduce reported core-suite `not ok` assertions by at least 1,000 from a
pinned, comparable baseline without masking failures, increasing error files
or timeouts, or introducing unexplained regressions.

## Pinned baseline

The handoff baseline is `test_20260910_120000_1328.log`, captured on
2026-09-10: 587 files, 4,046 reported Not OK, 371 blocked tests (already
included in that count), 21 error files, and no timeouts.  Its TAP totals have
a known discrepancy and must not be used as a percentage calculation until
runner accounting has been audited.

Current work starts from commit `c4b9814f35eaccb39c7341b4084c02613565edee`.
The complete local JVM reproduction of `perl5_t/t/op/write.t` is retained in
`/tmp/issue-1269-write-baseline-jvm.log`: 605 executed of 636 planned, with
489 explicit `not ok` records.  It was run with `timeout 600 ../../jperl
op/write.t` from `perl5_t/t` on macOS Darwin 25.6.0 / arm64 using system Perl
5.42.2 for oracle tests.  Before a suite-wide comparison, record the runner
revision, source/JAR hashes, Perl-suite revision, and full per-file output for
both commits.

## Progress tracking

### Current status: Phase 3 in progress — remaining `op/write.t` format clusters

| Cluster | Representative assertion | Owner | Baseline | Fixed | New failures | Blocked delta | PR | Next step |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| `formline` multiline fields | `swrite("1^*2 3@*4", "N", "N")` | Codex | 489 explicit JVM Not OK records in `op/write.t` | 216 | 0 observed | 0 | Pending | Commit the validated batch, then classify remaining format clusters separately |
| Unicode-string `eval` identifiers | `comp/parser.t` tests 67–76 | Codex | 60 explicit JVM Not OK records in `comp/parser.t` | 10 | 0 observed | 0 | #1333 | Preserve Unicode source semantics for normal `eval`; keep byte-source validation exclusive to `evalbytes` |
| format declaration expressions | `HASH`/`HASH2`/`BLOCK` after TAP 581 | Unassigned | Pending full output grouping | — | — | — | #1234 remains open | Keep separate from multiline field mechanics |
| file-test overload/FETCH | `op/filetest.t`, `op/tie_fetch_count.t` | Unassigned | Not reproduced | — | — | — | — | Coordinate shared evaluation changes before implementation |

### Completed phases

- [x] Phase 0: baseline recovery and classification (2026-09-10)
  - Reproduced `op/write.t` with complete JVM output rather than a truncated
    historical failure tail.
  - Classified tests 128–397 as a shared `formline` `^*`/`@*` field parsing,
    argument consumption, and tied-FETCH cluster.
  - Confirmed #1234 is a separate write-time executable-format-expression
    pipeline and is not assumed to explain this cluster.
  - Files: this document; `/tmp/issue-1269-write-baseline-jvm.log` (untracked
    evidence).

- [x] Phase 1: `formline` multiline field materialization (2026-09-10)
  - Parsed supplied `formline` pictures rather than constructing an empty
    field list; fixed field-sigil positioning; expanded aggregate operands in
    the `$@` list context; and materialized tied operands once for both output
    and taint observation.
  - Added `unit/formline_multiline_fields.t`, validated with system Perl 5.42.2
    and on both PerlOnJava backends (4/4).
  - `op/write.t` changed from 489 to 273 explicit JVM Not OK records (216
    repaired assertions); interpreter output has the same 273-record count.
  - Files: `RuntimeFormat.java`, `IOOperator.java`,
    `src/test/resources/unit/formline_multiline_fields.t`.

- [x] Phase 2: Unicode-string `eval` identifier parsing (2026-09-10)
  - Normal `eval` of an upgraded Unicode string no longer inherits byte-source
    identifier rejection from an enclosing scope without `use utf8`; `evalbytes`
    remains byte-source validated.
  - Added `unit/unicode_identifier_length.t`, validated with system Perl 5.42.2
    and both PerlOnJava backends (2/2).
  - `comp/parser.t` changed from 60 to 50 explicit JVM Not OK records, repairing
    assertions 67–76 without introducing observed failures.
  - Files: `IdentifierParser.java`,
    `src/test/resources/unit/unicode_identifier_length.t`.

### Next steps

1. Recover the remaining complete `op/write.t` groups and choose the next
   independently proven root cause; do not count formatting-output changes as
   repaired assertions unless their TAP assertions become `ok`.
2. Run the same validated core runner on the pinned baseline and candidate
   commit before making suite-wide delta claims.
3. Proceed to the next independently proven cluster; do not fold #1234's
   executable expression work into this change.

### Open questions

- `^*` continuation, mutation, and `~~` semantics remain separate from the
  narrow `formline` literal-field/one-FETCH repair.
- The historical suite aggregate needs runner-accounting reconciliation before
  it can be used as an authoritative percentage.
