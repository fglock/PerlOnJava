# CPANPLUS on PerlOnJava

## Current Status

`CPANPLUS::Config` loads under PerlOnJava after fixing loop-control parsing for bare labels that share a name with an imported constant. This unblocks the upstream `Makefile.PL` path that declares CPANPLUS' dynamic prerequisites.

`./jcpan -t CPANPLUS` now passes with CPANPLUS 0.9916:

```text
Files=20, Tests=1751, Result: PASS
EXIT: 0
```

The run also verifies the dependency chain that previously blocked CPANPLUS: `Archive::Extract`, `Object::Accessor`, `File::Fetch`, `Log::Message`, `Module::Loaded`, `Package::Constants`, `Log::Message::Simple`, and `Term::UI`.

The only observed remaining issue is a non-fatal warning during CPANPLUS' own suite:

```text
Use of uninitialized value in addition (+) at jar:PERL5LIB/File/Copy.pm line 303.
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

## Root Causes Fixed

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

`Archive::Extract` then failed because PerlOnJava's bundled `Archive::Zip` member objects exposed `_name` but CPAN `Archive::Extract` expects the compatibility hash key `fileName`, and it passes member objects back into `extractMember`. The bundled module now exposes both names and accepts member objects.

`Object::Accessor` then exposed tied lexical cleanup differences. A tied lexical scalar whose tie object should be destroyed at scope exit was being cleaned through the generic scalar path, so `DESTROY` did not fire at the Perl-compatible time. Tied scalar cleanup is now explicit and idempotent, including the closure-capture case.

CPANPLUS' tests also use strict bareword coderef calls in forms such as `BUILD_PL->(...)`, `-e BUILD_PL->(...)`, and `stat MAKEFILE->(...)`. PerlOnJava now treats a bareword to the left of `->(...)` as a sub call returning a coderef, and reassociates filetest and `stat`/`lstat` unary operators so those expressions match Perl's parse.

Version checks then exposed decimal vs dotted-version numification differences. `version->parse("v1.5")->numify` now returns `1.005000`, while decimal versions such as `1.5` and `1.2345` retain decimal-padding semantics.

The final CPANPLUS blocker was in the generated Makefile for a dummy `Foo-Bar` distribution. When `Makefile.PL` was rerun after `blib/lib` already existed, PerlOnJava's MakeMaker treated staged `blib/lib/*.pm` files as source files. Its generated `pm_to_blib` target could delete `blib/lib/Foo/Bar.pm` and then try to copy that same path back to itself. MakeMaker now prefers real `lib/` sources over stale `blib/` entries and does not stage already-staged files back into `blib`.

## Completed Work

- Fixed loop-control parsing in [`OperatorParser.java`](../../src/main/java/org/perlonjava/frontend/parser/OperatorParser.java).
- Added regression coverage in [`loop_label_bareword_constant.t`](../../src/test/resources/unit/loop_label_bareword_constant.t).
- Verified CPANPLUS' upstream `Makefile.PL` now completes and emits `PREREQ_PM` / `MYMETA.yml` entries for `Log::Message`.
- Fixed the `Archive::Extract` dependency failure by making PerlOnJava's bundled `Archive::Zip` expose the CPAN-compatible member hash field `fileName` and accept member objects in `extractMember`.
- Added regression coverage in [`archive_zip_members_matching_qr.t`](../../src/test/resources/unit/archive_zip_members_matching_qr.t) for direct member hash access and object extraction.
- Verified `Archive::Extract` 0.88 upstream suite passes: `Files=1, Tests=1795, Result: PASS`.
- Fixed tied scalar scope cleanup so `Object::Accessor` local attribute restore passes.
- Added regression coverage in [`tie_scalar.t`](../../src/test/resources/unit/tie_scalar.t) for tied lexical `DESTROY` at scope exit and deferred destruction while a tie object is still referenced.
- Fixed strict bareword coderef arrow parsing and filetest/stat reassociation for CPANPLUS' `BUILD_PL->(...)` and `MAKEFILE->(...)` patterns.
- Added regression coverage in [`subroutine.t`](../../src/test/resources/unit/subroutine.t) for strict bareword coderef calls and unary-operator reassociation.
- Fixed `version` numification/normalization for dotted `v` versions and decimal versions.
- Added regression coverage in [`version_numify.t`](../../src/test/resources/unit/version_numify.t).
- Fixed PerlOnJava MakeMaker reruns after `blib/lib` exists so stale staged files are not copied onto themselves.
- Added regression coverage in [`makemaker_stale_blib_source.t`](../../src/test/resources/unit/makemaker_stale_blib_source.t).
- Verified `Object::Accessor` upstream suite passes: `Files=7, Tests=155, Result: PASS`.
- Verified `./jcpan -t CPANPLUS` passes: `Files=20, Tests=1751, Result: PASS`.
- Verified `make` passes.

## Acceptance

```bash
timeout 60 ./jperl src/test/resources/unit/loop_label_bareword_constant.t
timeout 60 ./jperl -I$CPANPLUS_DIR/inc/bundle -I$CPANPLUS_DIR/lib -e 'require CPANPLUS::Config; print "ok\n"'
timeout 1200 ./jcpan -t CPANPLUS
make
```

Before running the full `jcpan -t CPANPLUS` acceptance, make sure no local CPANPLUS distropref is masking the dependency path. A previous investigation generated `/Users/fglock/.perlonjava/cpan/prefs/CPANPLUS.yml`; move it aside or use an isolated CPAN home before judging dependency discovery.

## Next Steps

1. Reduce the non-fatal `File::Copy.pm line 303` warning from CPANPLUS' own suite. It appears to come from numeric conversion of `$!` or `$^E` after a failed move fallback, but it does not currently fail CPANPLUS.
2. Re-run `timeout 1200 ./jcpan -t CPANPLUS` from a fresh or isolated CPAN home before merging if cache independence is required.
3. Audit whether MakeMaker still needs to discover installable files from `blib/lib`; if it does, keep the new no-self-staging behavior as the regression guard.
4. Keep CPANPLUS as a regression target when touching `Archive::Extract`, `Object::Accessor`, `ExtUtils::MakeMaker`, parser precedence, or `version`.

## Open Questions

- Does the `File::Copy` warning reveal a generic `$!` / `$^E` numeric conversion difference when the error variables are unset?
- Are there CPAN distributions that intentionally rely on MakeMaker installing files that only exist under `blib/lib` after configure/build, and should that path be modeled more explicitly?
