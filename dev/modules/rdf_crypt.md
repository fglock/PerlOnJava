# RDF::Crypt CPAN tooling and compiler work

## Goal

Make `./jcpan -t RDF::Crypt` and its system-Perl-compatible dependency chain
complete by fixing reusable PerlOnJava compiler and CPAN tooling behavior. Reuse
the existing Bouncy Castle-backed `Crypt::OpenSSL` implementations and avoid new
distribution preferences.

## Progress Tracking

### Current Status: Phase 3 completed with upstream/unsupported exclusions

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
  - Accepted leading dash-named arguments in qualified indirect-object calls,
    such as `throw RDF::Trine::Error -text => $message`, without changing
    ordinary unary-minus parsing.
  - Made pseudo-constant CODE installation advance the package MRO generation
    and report the `constant::__ANON__` CV identity expected by Class::MOP.
    Package::Stash's pure-Perl backend now reads that real CODE glob slot
    directly, allowing Moose roles to provide constant methods.
  - Merged optional relationships from static META back into generated MYMETA
    prerequisites according to CPAN's existing recommends/suggests policies.
    This discovers split runtime packages such as `Mail::Transport` without a
    distribution preference.
  - Propagated `AUTOMATED_TESTING=1` alongside CPAN's other noninteractive
    environment settings. This prevents dependency build scripts such as
    DBD::CSV's from waiting forever for input despite `use_prompt_default`.
  - Corrected the existing Bouncy Castle-backed `Crypt::OpenSSL::RSA`
    `use_pkcs1_padding` selector. PKCS#1 v1.5 is now selectable for the legacy
    signature/encryption API instead of dying at mode-selection time.
  - Restored the normal Exporter interface in the bundled
    `Crypt::OpenSSL::Random` wrapper. Consumers can import the Java
    `SecureRandom`-backed `random_bytes` and related functions.
  - Prevented recursive boolification when an overload legally returns its own
    object. Perl treats that result as true; PerlOnJava previously exhausted
    the JVM stack in `RDF::Trine::Iterator`.

- [x] Phase 3: End-to-end verification (2026-08-11)
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
  - The bounded exact `./jcpan -t RDF::Crypt` traversal completes dependency
    resolution rather than looping and exposed the reusable DBI,
    indirect-object, Moose constant-method, static-recommendation,
    noninteractive-build, RSA selector, random export, and bool-overload issues
    above.
    A targeted `./jcpan -t RDF::Trine` verification passed the repaired
    `DBIx::Connector` stage and ran 9,052 Type::Tiny assertions before exposing
    the embedded-code-block compiler gap.
  - With the final shared fixes packaged, RDF::Crypt's plain encryption test
    passes 5/5 and its RDF model encryption test passes 1/1. Its basic and RDF
    signing files also pass. No RDF::Crypt preference was added.
  - Two target-distribution results remain intentionally outside this work:
    `t/03signing.t` fails only its Unicode case because RDF::Crypt encodes text
    before signing but not before verification, and `t/06manifests.t` uses
    `Test::HTTP::Server`'s unsupported `fork`, producing duplicate TAP plans.
    System Perl cannot load the locally absent `Crypt::OpenSSL::RSA`, so the
    distribution is not a system-Perl passing baseline under the requested
    rule.
  - New focused tests for indirect named arguments, MRO generation,
    Package::Stash constant CODE lookup, Moose role constants, static META
    recommendations, PKCS#1 selection, OpenSSL random exports, and self-returning
    bool overloads pass on both PerlOnJava backends. The final focused totals
    are 14 assertions on the JVM backend and 12 on the interpreter (Moose skips
    there). System Perl passes 10 assertions and skips the three unavailable
    optional modules.
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

1. Protect earlier object-valued call arguments while later arguments are
   evaluated, then fix the legacy indirect-constructor dereference parse in
   Set::Scalar's `intersection.t`.
2. Revisit `t/06manifests.t` only if PerlOnJava gains process `fork` support or
   Test::HTTP::Server gains a Java-thread transport.

### Open Questions

- None for the RDF::Crypt compiler and CPAN tooling path.

## Related

- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/port-cpan-module/SKILL.md`
