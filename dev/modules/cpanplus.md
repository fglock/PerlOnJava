# CPANPLUS on PerlOnJava

## Current Status

`CPANPLUS::Config` now loads under PerlOnJava after fixing loop-control parsing for bare labels that share a name with an imported constant. This unblocks the upstream `Makefile.PL` path that declares CPANPLUS' dynamic prerequisites.

The current `./jcpan -t CPANPLUS` run now gets through dependency discovery and the `Archive::Extract` dependency. It is blocked by `Object::Accessor`:

```text
t/03_Object-Accessor-local.t line 49
got:      't/03_Object-Accessor-local.t'
expected: '<process pid>'
```

Because `Object::Accessor` fails its dependency test, it is not installed, and CPANPLUS' own test files then fail at compile time with:

```text
Base class package "Object::Accessor" is empty.
```

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
- Fixed the `Archive::Extract` dependency failure by making PerlOnJava's bundled `Archive::Zip` expose the CPAN-compatible member hash field `fileName` and accept member objects in `extractMember`.
- Added regression coverage in [`archive_zip_members_matching_qr.t`](../../src/test/resources/unit/archive_zip_members_matching_qr.t) for direct member hash access and object extraction.
- Verified `Archive::Extract` 0.88 upstream suite passes: `Files=1, Tests=1795, Result: PASS`.
- Verified `./jcpan -t CPANPLUS` now gets past `Archive::Extract`; `File::Fetch`, `Log::Message`, `Module::Loaded`, `Package::Constants`, `Log::Message::Simple`, and `Term::UI` pass in the observed run.
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

1. Reduce `Object::Accessor`'s `t/03_Object-Accessor-local.t` failure. The failing construct is scoped attribute restore via `$Object->$Acc( $0 => \my $temp )`; PerlOnJava restores `$0` instead of the previous `$$` value when `$temp` leaves scope.
2. Compare the reduced `Object::Accessor` case with system Perl and both PerlOnJava backends. Likely areas are `local`/scope cleanup, weak/DESTROY-like cleanup behavior around temporary references, or assignment to lexical references passed as arguments.
3. Add a minimal unit regression for the runtime behavior before changing CPAN module prefs or overlays.
4. Re-run `timeout 600 ./jcpan -t Object::Accessor` after the runtime fix, then re-run `timeout 1200 ./jcpan -t CPANPLUS`.
5. Inspect the generated `Makefile`, `MYMETA.yml`, and CPAN log to verify `PREREQ_PM` includes the CPANPLUS runtime dependency set from `CPANPLUS::Selfupdate`.
6. When CPANPLUS tests are passing or have documented non-runtime blockers, update this document with the final test count and remaining skips/failures.

## Open Questions

- Is the `Object::Accessor` failure a generic PerlOnJava scope-cleanup bug or specific to the module's accessor implementation?
- After `Object::Accessor` installs, do CPANPLUS' own tests expose runtime failures beyond dependency loading?
