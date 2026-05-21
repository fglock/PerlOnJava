# CPANPLUS on PerlOnJava

## Current Status

`CPANPLUS::Config` now loads under PerlOnJava after fixing loop-control parsing for bare labels that share a name with an imported constant. This unblocks the upstream `Makefile.PL` path that declares CPANPLUS' dynamic prerequisites, including `Log::Message`.

## Symptom

`./jcpan -t CPANPLUS` ran CPANPLUS tests without installing `Log::Message`, then failed with:

```text
Can't locate Log/Message.pm
```

The dependency was not followed because CPANPLUS 0.9916 declares prerequisites dynamically from `Makefile.PL` / `CPANPLUS::Selfupdate`. Its static `META.yml` and `META.json` do not contain the required dependency list.

Under PerlOnJava, the original `Makefile.PL` failed while loading `CPANPLUS::Config`, so the CPAN client fell back to a generated Makefile.PL with only:

```perl
WriteMakefile(
    NAME => 'CPANPLUS',
    VERSION => '0.9916',
);
```

That fallback had no `PREREQ_PM`, so `Log::Message` was never scheduled.

## Root Cause Fixed

`CPANPLUS::Config` imports constants from `CPANPLUS::Internals::Constants`, including:

```perl
use constant BIN => 'bin';
```

The same file also has a labeled loop:

```perl
BIN: for my $bin (@bins) {
    $path = $maybe and last BIN if -f $maybe;
}
```

Perl treats bare `last BIN` as a literal loop label, even when a constant sub named `BIN` exists. PerlOnJava was parsing the bareword through normal expression parsing, rewriting it as a call to the constant sub. The interpreter then saw a dynamic label expression equivalent to `last(BIN())`, tried label `bin`, and propagated a non-local `last` marker out of module loading. The result was `CPANPLUS/Config.pm did not return a true value`.

The fix keeps a standalone bare identifier immediately after `last`, `next`, or `redo` as a literal loop label. Parenthesized and expression labels still go through the dynamic-label path.

## Completed Work

- Fixed loop-control parsing in [`OperatorParser.java`](../../src/main/java/org/perlonjava/frontend/parser/OperatorParser.java).
- Added regression coverage in [`loop_label_bareword_constant.t`](../../src/test/resources/unit/loop_label_bareword_constant.t).
- Verified CPANPLUS' upstream `Makefile.PL` now completes and emits `PREREQ_PM` / `MYMETA.yml` entries for `Log::Message`.
- Verified `make` passes.

## Acceptance

```bash
timeout 60 ./jperl src/test/resources/unit/loop_label_bareword_constant.t
timeout 60 ./jperl -I$CPANPLUS_DIR/inc/bundle -I$CPANPLUS_DIR/lib -e 'require CPANPLUS::Config; print "ok\n"'
timeout 600 ./jcpan -t CPANPLUS
make
```

Before running the full `jcpan -t CPANPLUS` acceptance, make sure no local CPANPLUS distropref is masking the dependency path. A previous investigation generated `/Users/fglock/.perlonjava/cpan/prefs/CPANPLUS.yml`; move it aside or use an isolated CPAN home before judging dependency discovery.

## Next Steps

1. Re-run `timeout 600 ./jcpan -t CPANPLUS` without the temporary local CPANPLUS distropref and confirm CPAN installs or schedules `Log::Message` from the upstream Makefile.PL metadata.
2. Inspect the generated `Makefile`, `MYMETA.yml`, and CPAN log to verify `PREREQ_PM` includes the CPANPLUS runtime dependency set from `CPANPLUS::Selfupdate`.
3. Continue from the next observed failures after dependency discovery is correct. The earlier workaround run reached `Archive::Extract` and `Module::Loaded`; treat those as separate module/runtime issues, not dependency-discovery fixes.
4. Add any new minimal runtime/parser regression tests before patching CPAN distroprefs. Distroprefs should only be used for unavoidable CPAN packaging/test harness differences, not to hide missing interpreter semantics.
5. When CPANPLUS tests are passing or have documented non-runtime blockers, update this document with the final test count and remaining skips/failures.

## Open Questions

- Does CPAN consume CPANPLUS' dynamic prereqs from `MYMETA.yml` reliably after the upstream Makefile.PL succeeds, or does PerlOnJava's MakeMaker shim need a targeted metadata handoff fix?
- Are the later `Archive::Extract` / `Module::Loaded` failures pure module gaps, network/cache issues, or consequences of CPANPLUS test setup?
