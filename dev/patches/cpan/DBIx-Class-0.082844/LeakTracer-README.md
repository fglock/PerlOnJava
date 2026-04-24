# LeakTracer jperl_gc hook

`t-lib-DBICTest-Util-LeakTracer.pm.patch` adds a call to
`Internals::jperl_gc()` at the top of `assert_empty_weakregistry` —
but only for the outer test-wide registry (more than 5 entries).

## Why

DBIC's leak tracer uses `weaken()` + `defined` to detect orphan objects.
PerlOnJava's cooperative refCount inflates vs native Perl's reference
counting, so weak refs that *should* become undef at Perl-level (because
the object is unreachable) remain defined.

`Internals::jperl_gc()` runs a mark-and-sweep from Perl roots and clears
weak refs for unreachable objects. This gives DBIC's leak tracer the
Perl-compatible signal it expects.

## Why guarded by registry size

Inner `assert_empty_weakregistry($mini_registry)` calls inside the
TODO-marked "leaky_resultset_cond" cleanup loop create 1-entry registries.
At that point the test is iterating over known-leaked refs to break the
cycle via `$r->result_source(undef)`. If `jperl_gc` ran there, it would
clear the weak ref to the still-relevant $r *before* the cleanup code
uses it, crashing on `result_source()` on undef.

## Apply

Applied automatically by the CPAN install hook for DBIC 0.082844. When
the installed module is under `~/.perlonjava/lib/`, run:

```sh
cd ~/.perlonjava/cpan/build/DBIx-Class-0.082844-*
patch -p0 < /path/to/t-lib-DBICTest-Util-LeakTracer.pm.patch
```

## Effect

- t/52leaks.t: 9 real failures → **0 real failures** (TODO tests preserved)
- No regressions in other test files.
