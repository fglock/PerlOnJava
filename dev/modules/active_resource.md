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

### 2. SAX empty-element text reported as `''` instead of `undef`  (MEDIUM EFFORT)

`XML::SemanticDiff/t/16zero_to_empty_str_cmp.t` has 2 failing subtests:

```
#   Failed test 'check new value undef'
#          got: ''
#     expected: undef
```

The test compares `<el>0</el>` against `<el></el>` and `<el />`. Real Perl
yields `undef` for the new empty/self-closing element's text content;
PerlOnJava yields `''`.

**Suspected root cause**: PerlOnJava's XML::SAX (likely the bundled
`XML::SAX::PurePerl`, or a Java-backed parser) emits a zero-length
`characters` event for empty elements, or stores `''` where real Perl leaves
the field unset, so `XML::SemanticDiff`'s `keepdata` walk sees `''` instead
of `undef`.

**Plan**:
1. Build a 5-line repro: parse `<el></el>` and `<el>0</el>` with
   `XML::SAX::ParserFactory`, dump the events.
2. Diff the event stream against system `perl`.
3. Fix the divergence in whichever of XML::SAX::PurePerl or the Java SAX
   bridge is responsible. Prefer fixing the parser, not XML::SemanticDiff.
4. Re-run `t/16zero_to_empty_str_cmp.t` (and the rest of XML-SemanticDiff to
   make sure no regressions).

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

### Current Status: starting

### Completed Steps
- [ ] Plan written
- [ ] Encode `%EXPORT_TAGS` fix
- [ ] SAX empty-element repro & fix
- [ ] Regression test(s)
- [ ] PR opened

### Open Questions
- For #2: is the empty-element discrepancy in `XML::SAX::PurePerl` (pure
  Perl, easy to patch) or in the Java-backed SAX driver?
- For #3 (future PR): is option A (Perl shim) sufficient for the modules we
  care about, or do we need a real Want port?

### Next Steps
1. Create feature branch `feature/active-resource-deps`.
2. Land fix #1 with regression test.
3. Build SAX repro to scope fix #2.
