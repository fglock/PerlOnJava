# RDF::Crypt CPAN tooling and compiler work

## Goal

Make `./jcpan -t RDF::Crypt` and its system-Perl-compatible dependency chain
complete by fixing reusable PerlOnJava compiler and CPAN tooling behavior. Reuse
the existing Bouncy Castle-backed `Crypt::OpenSSL` implementations and avoid new
distribution preferences.

## Progress Tracking

### Current Status: Phase 3 verification in progress

### Completed Phases

- [x] Phase 1: Dependency/tooling reproduction (2026-08-10)
  - Confirmed `Any::Moose` completes; the apparent early loop was slow repeated
    dependency testing rather than a stuck individual test.
  - Isolated `MIME::Types` failures to missing `lib/MIME/types.db` in `blib`.
  - Confirmed a standard-Perl MakeMaker build stages `types.db` correctly.
  - Updated PerlOnJava MakeMaker to stage arbitrary PMLIBDIRS payloads while
    filtering editor and version-control artefacts.
  - Isolated the next dependency failure in `String::Print`: the bundled
    `Unicode::GCString::substr` shim ignored its replacement argument, so
    precision formatting never truncated strings. Implemented the documented
    mutating replacement behavior, including zero-length insertion.
  - Confirmed `Mail::Message` passes the relevant test under system Perl but
    PerlOnJava rejected its RFC charset regex. Added character-class handling
    for bare `\\xHH` escapes so ranges such as `[\\x00-\\ ]` compile correctly.
  - Matched the `MIME::QuotedPrint` XS return contract: encoded and decoded
    values are now byte scalars rather than inheriting a UTF-8 flag from Java
    `String` construction. This fixes `Mail::Message`'s raw-octet assertion.
  - Found the reported apparent loop in `Cache::LRU`: a shifted anonymous-array
    temporary retained its scalar reference past the statement boundary. The
    eviction queue was exhausted while its weak entry remained defined, causing
    an unbounded loop and repeated uninitialized-value warnings.

- [x] Phase 2: Reusable compiler/runtime and native-module fixes (2026-08-10)
  - Materialized `our` scalar/array/hash globs during compilation, matching Perl's
    compile-time symbol-table visibility and unblocking the circular
    `DateTime::Duration`/Specio load path.
  - Added a Java implementation of the eleven `Algorithm::Combinatorics` XS
    iterator primitives. The normal CPAN wrapper now loads it automatically;
    no distribution preference is required.
  - Marked anonymous arrays as owning the element reference counts they acquire.
    `shift` and `pop` now mortalize removed values and balance the literal's
    increments.
  - Promoted references to registered lexical scalars into tracked SV containers.
    An escaped `\$lexical` now retains its current value until the last scalar
    reference is released, including the weak-index/strong-FIFO structure used
    by `Cache::LRU`.
  - Added targeted weak-referent reconsideration when a discarded aggregate
    removes the final uncounted edge to a scalar referent.
  - Made targeted release sweeps model Perl's statement-boundary `FREETMPS`:
    discarded expression registers are excluded from this narrow sweep, while
    globals, live lexicals, arguments, and ordinary reachability sweeps retain
    their existing protection. Shifted containers retained in a lexical remain
    live; discarded shifted containers now clear weak nested referents in the
    same statement.
  - Made the bundled DBI expose native-style public `DBI::db` and `DBI::st`
    handles while retaining the Java/driver implementor in `ImplementorClass`.
    This lets modules such as `DBIx::Connector` locally replace handle methods
    through typeglob CODE slots, as they do with system DBI.
  - Added generic transaction forwarding for pure-Perl DBI drivers and kept the
    captured JDBC implementations for PerlOnJava's synthetic database handles.
    `DBIx::Connector`'s ExampleP driver now passes its complete test suite.
  - Fixed dynamic package CODE-slot replacement so a localized or newly
    assigned typeglob method is observed by subsequent method dispatch.
  - Extended literal regex code-block lowering to side-effect blocks followed
    by more pattern text (for example `qr/f(?{ ++$count })oo/`). This unblocks
    Type::Tiny's `StrMatch` serialization test without enabling unsafe runtime
    interpolated eval groups.
  - Reused the existing Bouncy Castle-backed `Crypt::OpenSSL::RSA` and
    `Crypt::OpenSSL::Bignum` implementations; no new crypto compatibility layer
    or native library was introduced.
  - Implemented conservative `Safe` operation-mask enforcement for the
    file/process/socket operations exercised by `Log::Log4perl`. The `:default`
    mask rejects operations such as `stat`, `unlink`, and `symlink`, while
    `:browse` permits the documented read-only file metadata operations. This
    is intentionally described as source-level enforcement, not a complete
    optree sandbox.
  - Implemented Perl's overload copy-constructor rule for direct compound
    mutators. A real `+=` overload receives a copied lvalue object, while a
    non-mutating `+` used to synthesize `+=` does not spuriously invoke `=`.
  - Localized and restored package `$a` and `$b` around `sort`, including
    exceptional exits. This prevents sort comparisons from overwriting caller
    globals and prematurely destroying objects stored in them.
  - Corrected unary and conversion overload argument ABI: overload methods now
    receive the object, `undef`, and an empty string. This fixes recursive
    Set::Scalar rendering and other overloads that distinguish top-level calls
    from recursion using the extra arguments.

- [ ] Phase 3: End-to-end verification
  - System Perl: `Algorithm::Combinatorics` passes 17 files / 361 tests;
    `Cache::LRU` passes 2 files / 38 tests.
  - PerlOnJava: `Algorithm::Combinatorics` passes 17 files / 361 tests;
    `Cache::LRU` passes 2 files / 38 tests.
  - System Perl and PerlOnJava: `DBIx::Connector` passes 13 files / 737 tests.
  - System Perl and PerlOnJava: Type::Tiny passes 375 files / 9,061 tests.
  - PerlOnJava: Log::Log4perl's Safe test passes 23/23.
  - Focused regression tests were validated first with system Perl and then with
    PerlOnJava, including overload mutator copying (7 assertions), unary ABI
    (6), and sort localization (4). The exact Type::Tiny
    `t/21-types/StrMatch-more.t` failure passes 9/9 after the regex fix.
  - The exact `./jcpan -t RDF::Crypt` traversal progressed beyond the former
    loop and then exposed the DBI handle-model issue above. A targeted
    `./jcpan -t RDF::Trine` verification passed the repaired
    `DBIx::Connector` stage and ran 9,052 Type::Tiny assertions before exposing
    the embedded-code-block compiler gap above. The exact requested
    `./jcpan -t RDF::Crypt` traversal is now being rerun after that fix.
  - Set::Scalar's `basic.t`, `custom_display.t`, and recursive `set_set.t` now
    pass. Its system-Perl suite passes 22 files / 2,652 tests. Two PerlOnJava
    gaps remain: indirect-constructor parsing in `intersection.t` and lifetime
    protection for the first of multiple object-valued temporary call
    arguments, exposed by `laws.t`.
  - Full `make` runs the focused RDF regressions successfully. Parallel runs
    still report the known `native_module_warning_caller.t` failure, the
    unrelated interpreter async-await ownership failure in each shard, and an
    order-dependent `weak_cache_failed_constructor.t`; the latter passes when
    run directly.

### Next Steps

1. Let the bounded exact `./jcpan -t RDF::Crypt` verification complete.
2. Reduce and fix any new PerlOnJava-only failure after the current dependency
   frontier, or record a
   dependency as ignorable if its distribution fails under system Perl too.
3. Protect earlier object-valued call arguments while later arguments are
   evaluated, then fix the legacy indirect-constructor dereference parse in
   Set::Scalar's `intersection.t`.
4. Run final focused regressions and process cleanup checks.

### Open Questions

- Does the remaining RDF dependency graph complete inside the clean bounded
  run, or expose another PerlOnJava-only failure after Set::Scalar?

## Related

- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/port-cpan-module/SKILL.md`
