# jcpan ActiveResource Fix Plan

## Overview

Tracks the issues uncovered while running `jcpan -t ActiveResource` and the
plan to address them. `ActiveResource` itself never even reaches its own test
files — it fails because of a chain of dependencies. Each link in the chain
fails for a different reason, and several of the failures are independently
useful to fix because they affect many other CPAN modules.

## Dependency Chain

```
ActiveResource
├── Class::Accessor::Lvalue   (XS dep "Want" — no Java port)
└── XML::Hash
    └── Test::XML
        └── XML::SemanticDiff (2 subtests fail in t/16zero_to_empty_str_cmp.t)
```

`ActiveResource::Base` `use`s `Class::Accessor::Lvalue::Fast`, so even with
`--force` the module is unreachable until the `Want` problem is solved.

## Issues

### 1. Encode `%EXPORT_TAGS` missing `:all` and `:default`  (LOW EFFORT, HIGH IMPACT)

Real Perl's `Encode.pm` exposes:

```
keys %Encode::EXPORT_TAGS = (all, default, fallbacks, fallback_all)
```

PerlOnJava's `src/main/perl/lib/Encode.pm` only sets `fallbacks` and
`fallback_all` (these come from the XS half). Any module that does
`use Encode qw(:all)` or `qw(:default)` dies during import:

```
"all" is not defined in %Encode::EXPORT_TAGS at (eval N) line 1.
```

Observed in `Test::XML`'s `t/sax.t`, `t/basic.t`, and elsewhere. This is a
self-contained 3-line fix.

**Plan**: extend `src/main/perl/lib/Encode.pm` to populate `%EXPORT_TAGS`:

```perl
our %EXPORT_TAGS = (
    all      => [ @EXPORT, @EXPORT_OK ],
    default  => [ @EXPORT ],
);
```

(The XS half already merges its own `fallbacks` / `fallback_all` keys in.)

**Verification**:
- `./jperl -e 'use Encode qw(:all); print "ok\n"'`
- `./jperl -e 'use Encode qw(:default); print "ok\n"'`
- Compare `keys %Encode::EXPORT_TAGS` with system `perl`.

**Priority**: HIGH (cheap, unblocks Encode-using modules).

---

### 2. `XML::Parser::Expat::current_element` push/pop timing  (MEDIUM EFFORT)

`XML::SemanticDiff/t/16zero_to_empty_str_cmp.t` has 2 failing subtests:

```
#   Failed test 'check new value undef'
#          got: ''
#     expected: undef
```

The test compares `<el2>0</el2>` against `<el2></el2>` and `<el2 />`. Real
Perl yields `undef` for the new (empty/self-closing) element's accumulated
text; PerlOnJava yields `''`.

**Root cause** (verified): PerlOnJava's SAX bridge updates
`@{ $expat->{Context} }` at the wrong time, so `current_element` returns
the element being started/ended instead of its parent. Trace from a small
repro using `Style => 'Stream'`:

```
=== system perl ===                     === jperl ===
[StartTag root]   current=  depth=0     [StartTag root]  current=root depth=1
[Text]            current=root          [Text]           current=el2
[StartTag el2]    current=root depth=1  [StartTag el2]   current=el2  depth=2
[EndTag el2]      current=root depth=1  [EndTag el2]     current=el2  depth=2
[Text]            current=root          [Text]           current=root
[EndTag root]     current=undef         [EndTag root]    current=root
```

Effect on `XML::SemanticDiff`:

- `XML::Parser::Style::Stream::Start` calls `doText`, which fires the
  user `Text` handler with `$_ = $expat->{Text}`. In real Perl this Text
  is attributed to the parent element (`current_element = root`); in
  PerlOnJava it's attributed to the just-started element (`el2`).
- `XML::SemanticDiff::Text` does
  `$char_accumulator->{$current_element} .= $char` (after stripping
  whitespace). For the inter-tag `\n`, $char becomes `''`, so on jperl
  `char_accumulator->{el2}` becomes `''`; on real Perl it stays `undef`.
- At `</el2>`, `EndElement` reads `$text = char_accumulator->{el2}` →
  `'' ` vs `undef`, and stores it in `CData`, which surfaces as
  `new_value`.

**Fix**: in `src/main/java/org/perlonjava/runtime/perlmodule/XMLParserExpat.java`,
match libexpat's actual behaviour (which differs from the current code's
comment claim):

- `startElement`: push to `Context` AFTER the user `startHandler` returns
  (currently happens BEFORE, around line 1237–1243).
- `endElement`: pop from `Context` BEFORE the user `endHandler` runs
  (currently happens AFTER, around line 1457–1465).

**Risk**: this changes a semantic that any handler reading
`current_element` from inside Start/End would notice. Existing PerlOnJava
test files using `current_element` are:

- `src/test/resources/module/XML-Parser/t/parament.t` — only reads
  `current_element` from the Char handler (unaffected by Start/End
  timing).
- `src/test/resources/module/XML-Parser/t/partial.t` — same, Char only.
- `src/test/resources/module/XML-Parser/t/astress.t` — uses `depth`/
  `element_index` from Char/End handlers; will need re-running.

**Plan**:
1. Move push/pop in `XMLParserExpat.java`.
2. Run all `src/test/resources/module/XML-Parser/t/*.t` tests under jperl.
3. Run `t/16zero_to_empty_str_cmp.t` from XML-SemanticDiff to confirm fix.
4. Run `make` for full unit coverage.
5. If regressions, narrow further (e.g. only adjust pop timing, etc.).

**Priority**: MEDIUM (unblocks XML::SemanticDiff → Test::XML → XML::Hash).

---

### 3. `Class::Accessor::Lvalue` blocked by missing `Want` XS module  (HIGH EFFORT)

```
Error:  Can't load loadable object for module Want: no Java XS implementation available
```

`Want` is pure XS — it walks Perl's op tree to determine the calling
context (lvalue / rvalue / wantarray / assign). PerlOnJava has no port.
Without `Want`, both `Class::Accessor::Lvalue` and `Class::Accessor::Lvalue::Fast`
die at `require`, which in turn blocks `ActiveResource::Base`.

Test failures in `Class-Accessor-Lvalue-0.11`:
- `t/lval.t`, `t/lval-fast.t`: subtests 1 (require fails) and 5–7 (the
  croak diagnostics that Want would normally produce never fire).

**Options** (in order of preference):

A. **Pure-Perl `Want` shim** — provide just the subset `Class::Accessor::Lvalue`
   actually uses: `want('LVALUE')`, `want('RVALUE')`, `want('ASSIGN')`,
   `rreturn`, `lnoreturn`. Implement using `caller`/`(caller(N))[5]` for
   wantarray-ish information; the LVALUE/ASSIGN paths are the hard part and
   may need PerlOnJava-specific hooks (see below).

B. **Java port of `Want`** — full implementation that introspects the
   PerlOnJava op tree / call frames. Largest effort, but unlocks every
   downstream module that uses Want (DBIx::Class::Schema::Loader, several
   accessor frameworks, etc.).

C. **Defer ActiveResource** — accept that ActiveResource is unreachable for
   now and only deliver fixes #1 and #2 in this PR; track Want as a
   follow-up issue.

**Plan for this PR**: Option C. Document the `Want` blocker, link to a
follow-up ticket, and ship the two cheap wins. Want is too large to
combine with these fixes.

**Priority**: deferred (own design doc / PR).

---

## Out-of-Scope (this PR)

- Implementing `Want`.
- The `Class::Accessor::Lvalue` test failures beyond require — they are
  symptoms of #3, not independent bugs.
- `XML::Hash` t/01-apitest.t — purely a cascade from #2.
- ActiveResource's own test files — purely a cascade from #3.

## Deliverables (this PR)

1. `dev/modules/active_resource.md` — this document.
2. Fix #1: populate `%Encode::EXPORT_TAGS` with `all` and `default`.
3. Fix #2: SAX empty-element `undef` parity (if root cause is small;
   otherwise split out to its own PR after the repro is written).
4. A regression test for fix #1 (and fix #2 if landed).

## Progress Tracking

### Current Status: fixes #1 and #2 landed; #3 (Want) deferred

### Completed Steps
- [x] Plan written
- [x] Encode `%EXPORT_TAGS` fix (commit 76eeee938)
- [x] SAX `current_element` push/pop timing fix (commit 5c28802d4)
- [x] Regression tests added:
  - `src/test/resources/unit/encode_export_tags.t` (8 subtests)
  - `src/test/resources/unit/xml_parser_current_element.t` (12 subtests)
- [x] PR opened (#568)
- [ ] Re-run `jcpan -t XML::SemanticDiff` end-to-end
- [ ] PR review and merge
- [ ] Follow-up: design doc + ticket for `Want` shim/port

### Verification Results
- `make` passes (all unit tests).
- Bundled `XML::Parser` test suite: 45 files / 434 tests, all pass
  (no regression from the Context timing change).
- `XML::SemanticDiff` standalone: 18/18 files, 47/47 tests now pass
  (2 previously-failing subtests in t/16zero_to_empty_str_cmp.t fixed).

### Open Questions
- For #3 (future PR): is option A (Pure-Perl Want shim) sufficient for
  the modules we care about, or do we need a real Want port?

### Next Steps
1. Re-run `jcpan -t XML::SemanticDiff`, then `Test::XML`, then `XML::Hash`
   end-to-end to confirm the dependency chain (sans Want) is now clear.
2. Land this PR.
3. Open a follow-up issue/design doc for `Want` (Class::Accessor::Lvalue
   blocker) so ActiveResource itself can eventually be reached.
