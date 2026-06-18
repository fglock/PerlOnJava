# Future 0.52 Support for PerlOnJava

## Status: Passing unpatched

- **Module version**: Future 0.52 (PEVANS/Future-0.52.tar.gz)
- **Last verified**: 2026-06-17
- **Test command**: `./jcpan -t Future`
- **Result**: 56/56 test programs passed, 786/786 subtests passed
- **Patch status**: The former `Future-0.52` CPAN patch is no longer needed.

## Background

Future is a foundational async programming module for Perl, used by IO::Async and
event-driven frameworks. It provides promise/future objects for deferred
computations. Future 0.52 is pure Perl, with optional Future::XS acceleration.

PerlOnJava now runs the full pure-Perl Future test suite without a distro patch.
Future::XS tests are skipped normally when Future::XS is not installed.

## Key Fixes

The historical failures were not Future-specific. They were symptoms of missing or
incorrect PerlOnJava runtime behavior:

| Area | Fix |
|------|-----|
| `B::SV::REFCNT` | Returns a stable nonzero value compatible with `Test2::Tools::Refcount` checks. |
| B optree walking | `B::OP::next`, `B::NULL`, `B::COP`, and `B::CV::START` provide enough structure for Future's debug helpers. |
| `do FILE` exception state | Successful `do FILE` clears `$@`, matching Perl behavior. |
| Weak-reference temporary cleanup | Nested weak sweep cleanup preserves temporaries long enough for Future::Mutex refcount-sensitive paths. |

## Former CPAN Patch

The removed CPAN patch changed `Future::Mutex` to keep an extra reference to an
active returned future, and changed `t/40mutex.t` to expect an extra refcount under
PerlOnJava. After the weak-reference temporary cleanup fix, upstream Future passes
as-is, so the distro preference and patch were removed from the bundled CPAN
configuration.

## Verification

```text
Files=56, Tests=786
Result: PASS
```

The final verification run used a cleared local CPAN Future patch/pref cache to
ensure `jcpan` did not apply the stale local patch.

## Related Documents

- `dev/modules/xs_fallback.md` - XS fallback mechanism
- `dev/design/destroy_and_weak_refs.md` - weak reference and DESTROY behavior
- `dev/architecture/weaken-destroy.md` - current weak reference and DESTROY implementation
