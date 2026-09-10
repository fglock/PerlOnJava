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

### Current status: Phase 17 in progress — remaining parser and `op/write.t` clusters

| Cluster | Representative assertion | Owner | Baseline | Fixed | New failures | Blocked delta | PR | Next step |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| `formline` multiline fields | `swrite("1^*2 3@*4", "N", "N")` | Codex | 489 explicit JVM Not OK records in `op/write.t` | 216 | 0 observed | 0 | Pending | Commit the validated batch, then classify remaining format clusters separately |
| Unicode-string `eval` identifiers | `comp/parser.t` tests 67–76 | Codex | 60 explicit JVM Not OK records in `comp/parser.t` | 10 | 0 observed | 0 | #1333 | Preserve Unicode source semantics for normal `eval`; keep byte-source validation exclusive to `evalbytes` |
| parser structural diagnostics | `comp/parser.t` tests 3, 5, 6, 121–129 | Codex | 50 explicit JVM Not OK records in `comp/parser.t` | 12 | 0 observed | 0 | #1333 | Diagnose unfinished quoted escapes and merge-conflict markers before generic parse reduction |
| bare identifier capacity | `comp/parser.t` test 113 | Codex | 38 explicit JVM Not OK records in `comp/parser.t` | 1 | 0 observed | 0 | #1333 | Apply Perl's identifier byte limit to bareword parser terms |
| typed loop declarations | `comp/parser.t` test 114 | Codex | 37 explicit JVM Not OK records in `comp/parser.t` | 1 | 0 observed | 0 | #1333 | Reject unloaded class types in `for my Type $var` declarations during parsing |
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

- [x] Phase 3: structural parser diagnostics (2026-09-10)
  - Diagnose unterminated braced `\\x` and `\\o` quoted-string escapes and
    brace-less quoted-string `\\N` escapes without indexing beyond end of input.
  - Tokenize seven-character merge-conflict markers at source-line start and
    report the specific Perl diagnostic before surrounding statement reduction.
  - Added `unit/string_escape_diagnostics.t` (3/3) and
    `unit/conflict_marker_diagnostics.t` (9/9), each validated with system Perl
    5.42.2 and both PerlOnJava backends.
  - `comp/parser.t` changed from 50 to 38 explicit JVM Not OK records,
    repairing assertions 3, 5, 6, and 121–129 without observed new failures.
  - Files: `StringSegmentParser.java`, `Lexer.java`, `LexerTokenType.java`,
    `Parser.java`, `ParsePrimary.java`, and the two focused unit tests.

- [x] Phase 4: bare identifier capacity validation (2026-09-10)
  - Applied the existing Perl identifier byte capacity to non-sigil identifier
    terms as well as complex variable identifiers.
  - Added `unit/bare_identifier_length.t`, validated with system Perl 5.42.2
    and both PerlOnJava backends (1/1).
  - `comp/parser.t` changed from 38 to 37 explicit JVM Not OK records,
    repairing assertion 113 without observed new failures.
  - Files: `ParsePrimary.java`,
    `src/test/resources/unit/bare_identifier_length.t`.

- [x] Phase 5: typed loop declaration validation (2026-09-10)
  - Validate the class type immediately for a lexical loop declaration rather
    than treating its type name as a bare loop expression.
  - Added `unit/typed_for_declaration.t`, validated with system Perl 5.42.2
    and both PerlOnJava backends (1/1).
  - `comp/parser.t` changed from 37 to 36 explicit JVM Not OK records,
    repairing assertion 114 without observed new failures.
  - Files: `OperatorParser.java`,
    `src/test/resources/unit/typed_for_declaration.t`.

- [x] Phase 6: `keys` lvalue scalar context (2026-09-10)
  - Compile `keys %hash` in lvalue consumers as its scalar count, and copy a
    read-only count to a mutable temporary for operations such as `substr`.
  - Added `unit/keys_lvalue_context.t`, validated with system Perl 5.42.2 and
    both PerlOnJava backends (2/2).
  - `comp/parser.t` changed from 36 to 34 explicit JVM Not OK records,
    repairing assertions 130 and 132 without observed new failures.
  - Files: `CompileOperator.java`,
    `src/test/resources/unit/keys_lvalue_context.t`.

- [x] Phase 7: quote-qualified subroutine declarations (2026-09-10)
  - Treat a leading deprecated quote in a subroutine declaration as the
    package separator before the first component, without inventing a `main::`
    component that stores the declaration under the wrong name.
  - Added `unit/quoted_subroutine_name.t`, validated with system Perl 5.42.2
    and both PerlOnJava backends (1/1).
  - `comp/parser.t` changed from 34 to 33 explicit JVM Not OK records,
    repairing assertion 104 without observed new failures.
  - Files: `IdentifierParser.java`,
    `src/test/resources/unit/quoted_subroutine_name.t`.

- [x] Phase 8: unknown filetest operator diagnostics (2026-09-10)
  - Recognize a one-letter unary-minus fallback as a subroutine only when its
    code reference is actually defined; a probe no longer autovivifies an
    undefined code slot and masks an invalid filetest operator.
  - Added `unit/unknown_filetest_diagnostic.t`, validated with system Perl
    5.42.2 and both PerlOnJava backends (2/2).
  - `comp/parser.t` changed from 33 to 32 explicit JVM Not OK records,
    repairing assertion 44 without observed new failures.
  - Files: `ParsePrimary.java`,
    `src/test/resources/unit/unknown_filetest_diagnostic.t`.

- [x] Phase 9: `read` buffer lvalue validation (2026-09-10)
  - Reject a bareword supplied to a scalar-reference prototype slot as a
    constant item, so `read` cannot use it as a mutable buffer.
  - Added `unit/read_constant_buffer_diagnostic.t`, validated with system Perl
    5.42.2 and both PerlOnJava backends (1/1).
  - `comp/parser.t` changed from 32 to 31 explicit JVM Not OK records,
    repairing assertion 12 without observed new failures.
  - Files: `PrototypeArgs.java`,
    `src/test/resources/unit/read_constant_buffer_diagnostic.t`.

- [x] Phase 10: `undef` bareword validation (2026-09-10)
  - Reject a bareword operand to `undef` as a constant item before the
    compiler can treat it as an rvalue expression.
  - Added `unit/undef_constant_item_diagnostic.t`, validated with system Perl
    5.42.2 and both PerlOnJava backends (1/1).
  - `comp/parser.t` changed from 31 to 30 explicit JVM Not OK records,
    repairing assertion 11 without observed new failures.
  - Files: `OperatorParser.java`,
    `src/test/resources/unit/undef_constant_item_diagnostic.t`.

- [x] Phase 11: list-assignment bareword validation (2026-09-10)
  - Reject bareword constant items used directly as list-assignment targets.
  - Added `unit/list_assignment_constant_item_diagnostic.t`, validated with
    system Perl 5.42.2 and both PerlOnJava backends (1/1).
  - `comp/parser.t` changed from 30 to 29 explicit JVM Not OK records,
    repairing assertion 9 without observed new failures.
  - Files: `ParseInfix.java`,
    `src/test/resources/unit/list_assignment_constant_item_diagnostic.t`.

- [x] Phase 12: `CORE::` qualified subroutine names (2026-09-10)
  - Defer explicit CORE-builtin dispatch when a further `::` or deprecated
    quote package separator continues the name.
  - Added `unit/core_qualified_subroutine_name.t`, validated with system Perl
    5.42.2 and both PerlOnJava backends (2/2).
  - `comp/parser.t` changed from 29 to 28 explicit JVM Not OK records,
    repairing assertions 85 and 86 without observed new failures.
  - Files: `ParsePrimary.java`,
    `src/test/resources/unit/core_qualified_subroutine_name.t`.

- [x] Phase 13: empty-brace indirect method invocants (2026-09-10)
  - Parse `{}` following an unknown indirect method name as a hash-reference
    invocant instead of an empty statement block.
  - Added `unit/indirect_method_empty_hash_invocant.t`, validated with system
    Perl 5.42.2 and both PerlOnJava backends (1/1).
  - `comp/parser.t` changed from 28 to 27 explicit JVM Not OK records,
    repairing assertion 115 without observed new failures.
  - Files: `SubroutineParser.java`,
    `src/test/resources/unit/indirect_method_empty_hash_invocant.t`.

- [x] Phase 14: empty braced substitution interpolation (2026-09-10)
  - Reject `${}` in an `s///` replacement rather than accepting it as an empty
    scalar interpolation and allowing the following malformed regex syntax.
  - Added `unit/substitution_empty_braced_interpolation.t`, validated with
    system Perl 5.42.2 and both PerlOnJava backends (1/1).
  - `comp/parser.t` changed from 27 to 26 explicit JVM Not OK records,
    repairing assertion 2 without observed new failures.
  - Files: `StringSegmentParser.java`,
    `src/test/resources/unit/substitution_empty_braced_interpolation.t`.

- [x] Phase 15: Unicode capture interpolation in evaluated substitutions (2026-09-10)
  - Materialize live regex-capture proxies before string-context concatenation
    decides byte versus UTF-8 provenance, and preserve that provenance through
    the case-conversion operators used by quoted replacement escapes.
  - Added `unit/regex_unicode_word_boundary.t`, validated with system Perl
    5.42.2 and both PerlOnJava backends (7/7).
  - `comp/parser.t` changed from 26 to 25 explicit JVM Not OK records,
    repairing assertion 139 without observed new failures.
  - Files: `StringOperators.java`,
    `src/test/resources/unit/regex_unicode_word_boundary.t`.

- [x] Phase 16: semicolon diagnostics in unprototyped calls (2026-09-10)
  - Reject a semicolon left inside a parenthesized unprototyped call as a
    syntax error rather than misreporting an extra argument.
  - Added `unit/unprototyped_call_semicolon_diagnostic.t`, validated with
    system Perl 5.42.2 and both PerlOnJava backends (1/1).
  - `comp/parser.t` changed from 25 to 24 explicit JVM Not OK records,
    repairing assertion 13 without observed new failures.
  - Files: `PrototypeArgs.java`,
    `src/test/resources/unit/unprototyped_call_semicolon_diagnostic.t`.

- [x] Phase 17: aggregate regex-mutation diagnostics (2026-09-10)
  - Reject substitutions and transliterations bound directly to arrays or
    hashes, while retaining scalarized non-mutating match behavior.
  - Added `unit/aggregate_regex_mutation_diagnostic.t`, validated with system
    Perl 5.42.2 and both PerlOnJava backends (2/2).
  - `comp/parser.t` changed from 24 to 14 explicit JVM Not OK records,
    repairing assertions 59 and 88–96 without observed new failures.
  - Files: `ParseInfix.java`,
    `src/test/resources/unit/aggregate_regex_mutation_diagnostic.t`.

### Next steps

1. Diagnose the remaining `comp/parser.t` groups, beginning with the
   compile-error cleanup and `#line` source-location assertions.
2. Recover the remaining complete `op/write.t` groups and choose the next
   independently proven root cause; do not count formatting-output changes as
   repaired assertions unless their TAP assertions become `ok`.
3. Run the same validated core runner on the pinned baseline and candidate
   commit before making suite-wide delta claims.

### Open questions

- `^*` continuation, mutation, and `~~` semantics remain separate from the
  narrow `formline` literal-field/one-FETCH repair.
- The historical suite aggregate needs runner-accounting reconciliation before
  it can be used as an authoritative percentage.
