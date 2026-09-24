# JVM lexical-registration optimization handoff

## Scope

This is a current-source re-evaluation of the lexical-binding idea from
historical commit `0ffe20f30`. That commit was reverted as part of the broad
performance rollback in PR #1489 and must not be cherry-picked.

## Current finding

The old change bypassed live-pad registration for selected leaf JVM CVs. That
is not transplantable: the current runtime has different closure and active
lexical-frame lifecycle machinery, and live lexical registration remains
observable through PadWalker, Devel::LexAlias, eval STRING, runtime regex
source, and callback/CV identity paths.

The narrow candidate worth evaluating is instead in
`RuntimeCode.registerActiveLexical`: when the top active lexical frame has the
same logical CODE identity as the code being registered, write directly to
that frame. Keep the existing full-frame search and top-frame fallback for all
other cases. This changes lookup cost only; it must not change which frame
receives a lexical cell.

## Coverage retained in this handoff

- `integer_bitwise_tree_flow.t` preserves tied FETCH cardinality and overload
  ordering in nested integer-bitwise expressions.
- `regex/lazy_whole_match_snapshot.t` preserves whole-match and capture
  snapshots across a later failed match and during `/e` substitution.
- `ActiveLexicalRegistrationTest` establishes both the logical-CODE active-top
  case and the non-top nested-call lookup contract needed by the candidate.

The Perl tests passed with system Perl and both PerlOnJava backends. The
complete `make` gate passed before this handoff was recorded.

## Required proof before implementation is retained

1. Add the top-frame short circuit without weakening the existing fallback.
2. Run the focused Java and Perl coverage, plus a fresh immutable `make` gate.
3. Build exact parent and candidate sources separately and record their source
   and JAR identities.
4. Measure a representative lexical-heavy workload in alternating fresh JVM
   pairs under the normal production load; use JFR only for attribution.
5. Retain the code only if the source-matched semantic and full-gate evidence
   pass and the paired production result is materially positive. Otherwise
   retain this handoff and the regression tests, but reject the optimization.
