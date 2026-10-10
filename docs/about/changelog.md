# Changelog

Release history of PerlOnJava. See the
[Project Status and Roadmap](roadmap.md#current-project-status) for current
priorities and future plans.

## Work in progress

- Load the `Errno` Perl bootstrap when `use Errno` follows `%!` access, and
  preserve captured aggregate caches during targeted weak-reference sweeps so
  Pod::HtmlEasy can import errno constants and load its regex patterns (#1662).
- Preserve the caller's `DATA` handle when a compile-time required module ends with `__END__` (#1244).
- Bound captured subprocess output while continuing to drain child streams, and report output-reader errors (#1723).
- Avoid false experimental `@_` warnings for ordinary named calls inside signatured subroutines (#1422).
- Respect lexical `syntax::prototype` warning controls for prototype mismatch
  diagnostics while preserving default-enabled prototype warnings under
  `local $^W = 0`, including Math::Complex's intentional overrides (#1655).
- Support legacy Object::Pad `has` declarations and scalar `:accessor` fields
  with generated read/write methods (#1177).
- Keep `IO::Select` process pipe handles registered while polling, so gzip
  filters and similar child processes receive input and drain output without
  hanging (#1597).
- Match Perl's signed remainder and truncation toward zero for negative arithmetic under `use integer`, fixing `op/int.t` and DateTime subtraction across midnight by one nanosecond (#1720).
- Keep `use strict` inside a module loaded during `BEGIN` or `use_ok` from leaking into the caller's compilation scope (#1684).
- Preserve a normal child exit status of 129 when reaping `IPC::Open3` processes, so `System::Command` reports it instead of treating it as SIGHUP (#1715).
- Parse indexed scalar expressions as sort-list items rather than scalar comparators, fixing Class::MethodMaker `hash.t` under `strict vars` (#1680).
- Suppress the `exiting` warning for a non-local `last SKIP` from `Test::More::skip()` and other callees that disable it lexically, and stop a labeled block that ends a sub from leaking an unmatched loop-control marker on the interpreter (#1482).
- Parse `return` with imported subroutines followed by `map` expressions (#1584).
- Report file-scope and enclosing `our` variables from `PadWalker::peek_our`
  on both backends, including `our` assignments in subs (#1669).
- Report `ref()` of a reference to a read-only scalar as `SCALAR`, or `REF`
  when the scalar holds a reference.
- Report `ref($Pkg::{name})` as `""` for a constant sub or a forward declaration
  outside `main::`, matching blead; constant.pm proxies stay `SCALAR`.
- Report the call-site package in `caller()` for code compiled by eval STRING
  on the interpreter backend, so a callee in another package no longer changes
  the package reported for its caller.
- Parse `@{ NAME->... }` and similar braced dereferences of a constant or sub
  call as expressions instead of variable names.
- Keep a read-only package scalar shared when it is aliased by glob assignment,
  so `Scalar::Readonly` unlocks it through the alias.
- Ignore whitespace inside prototypes when checking them, and report
  prototype diagnostics under the lexical warning state of eval STRING code.
- Fix `Data::Util` loading by avoiding recursive shim evaluation during XS initialization (#1681).
- Stop reporting `mro::get_mro` as redefined when `mro` loads under `-w` (#1703).
- Preserve blessed IO classes during `can()` checks so socket-specific methods remain discoverable.
- Report native socketpair readiness from the descriptor instead of treating every event as ready.
- Apply IO::Handle blocking mode changes to native descriptors so nonblocking socket writes can return EAGAIN.
- Retain queued IO::Async call futures until dispatch completes, preserving queued worker results.
- Fix vstring numification: dotted-numeric literals (`49.46.48`, `v49.46.48`) now
  correctly numify via their character content ("1.0" → 1.0), fixing the
  `Scalar-List-Utils isvstring.t` `dotted num` assertion (#1683).
  Non-printable vstrings (e.g. `v5.6`) retain version-number numification
  (5.006) to preserve `require`-override compatibility.

- Autovivify undefined hash references when an element is read, including in
  `defined` checks.
- Support `sprintf` field widths up to 1,000,000 characters, including widths
  above the former 8192-character limit.
- Preserve bare carriage returns in quote-like strings and regex patterns read
  from Perl source files.
- Keep version-control conflict-marker diagnostics out of quoted strings and
  heredoc contents, including heredocs with punctuation in quoted labels.
- Parse bare phaser-name forward declarations such as `END;` after heredocs.
- Preserve diagnostics for malformed CSV rows, decode EUC-JP row-13 extension
  characters, accept open filehandles in `Tie::File`, and honor ZIP member
  `desiredCompressionMethod` and `desiredCompressionLevel` when writing
  archives.
- Add Java-backed `String::CRC32` support for byte strings, filehandles, and
  seeded incremental checksums.
- Deliver `SIGCHLD` for exited ProcessBuilder children, wake blocked event loops,
  and forward package-local filehandle input to `IPC::Open3` children.
- Match blead Perl's `re 'debug'` output for caseless negated ASCII singletons:
  no-fold-peer chars use `NEXACTb`, fold pairs use `NANYOFM`.
- Add Java-backed `Scalar::Readonly` support, fix caller-frame visibility for
  nested signature calls, and keep Test2 negation accessors visible to the
  interpreter.
- Preserve call-site package information in generated subroutine frames so Moo destructor croaks report the correct source location.
- Preserve generated-method call-site packages while Carp selects croak locations through eval wrappers.
- Pass the Image::ExifTool 13.55 test suite by preserving escaped
  transliteration ranges and numeric string flags, and allowing valid nested
  conditional `goto` targets.
- Compare owner-slot identities around DESTROY to distinguish new resurrection
  from preexisting global owners during global destruction.
- Retain positive closure-pad owner cycles per Perl runtime, record destruction
  separately from count sentinels, track deferred releases by owner-slot
  identity, guard bridge count overflow, and balance DESTROY callback ownership
  on exceptional exits.
- Register assignment-initialized lexical arrays and hashes as interpreter
  roots, and release aggregate-only closure captures when their frame exits.
- Register BEGIN-backed scalar and aggregate lexical pads as interpreter roots
  when they enter the main pad, preserving weak references at runtime sweeps.
- Count captured array and hash pads as closure owners and align named aggregate
  `B::SV::REFCNT` results with Perl.
- Keep captured aggregate ownership in native owner slots, compose slot counts
  with legacy owners for `B::SV::REFCNT`, and dispatch final scope cleanup when
  the last captured pad releases.
- Transfer aggregate capture owner slots when forward-declared subs adopt
  definitions, retain lazy capture ownership across forward-CV adoption, and
  release captures on `undef &sub`; keep Catalyst::Runtime's system and
  backtick tests active while skipping only its `fork()` assertions.
- Release closure captures when retired interpreter pad metadata is no longer a Perl owner.
- Keep END block captures alive through queued and active execution until END
  releases their lexical owners, including `die`-initiated shutdown, nested
  `exit`, and draining remaining END blocks after a callback fails. Match Perl
  5.45.4's exit status 22 for an uncaught END callback failure.
- Set the Windows CI unit gate budget to 75 minutes while preserving its
  per-test watchdogs.
- Document the 2026-10-03 imported core-suite milestone: 575 selected files
  completed without unexpected failures, with skips, TODOs, exclusions, and
  verification scope recorded in the testing guide.
- Stage MakeMaker inputs before `PL_FILES`, preserve generated nested modules,
  honor custom test targets, create the `pm_to_blib` marker, and expose install
  directory macros used by CPAN build tooling.
- Preserve Thrift's list-returning `MY::test` customization, including its
  generated-module include paths.
- Implement `Bit::Vector::Chunk_Store` used by Thrift's 64-bit protocol.
- Preserve binary HTTP response bytes so CPAN downloads remain extractable.
- Release captures from retired direct callback arguments, report the host's
  `EAGAIN` value for nonblocking `flock` conflicts, and release DBI statement
  handles without retaining their parent database handle.
- Keep array-size proxies usable while strong references to returned arrays
  remain in flight, preserving DBIx::Class nested-query bind records.
- Release weakly observed objects after their exited, unreachable closure pads
  stop owning them.
- Make strict test runs fail when a child process exits nonzero after complete TAP.
- Preserve Unix executable permissions when extracting ZIP archive entries.
- Accept Perl's valid `\@;@` prototype without an `illegalproto` warning.
- Match current blead's fatal checks for differing in-scope `use VERSION`
  declarations, its `caller()` statement-line reporting, and its compiled
  representation for case-folded negated singleton regex classes.
- Remove deleted package stashes from their parent namespace in
  `Symbol::delete_package`.
- Forward only parent-declared named parameters to generated parent class
  constructors, preserving child fields and validation across inheritance.
- Preserve direct-call semantics for lexical and package `->&` methods across
  both backends, support `CORE::bless` and `CORE::break` code references, and
  allow same-finally local `goto` targets.
- Preserve raw-source DATA handles for `CORE::__DATA__` and lvalue behavior for
  `CORE::substr` code references.
- Match Perl diagnostics, lvalue behavior, and list context for callable
  `CORE::recv`, `CORE::select`, `CORE::splice`, and `CORE::reset`.
- Support callable `CORE::undef`, `CORE::sysread`, and `CORE::umask`, and validate
  callable `CORE::tie` and `CORE::tied` reference arguments.
- Support callable `CORE::close` and directory I/O wrappers with Perl-compatible
  false results and list-context behavior.
- Support `threads::shared::bless` and report Perl-compatible argument errors
  for shared storage, conditions, and locks.
- Preserve Perl diagnostics and lvalue behavior for callable `CORE::join`,
  `CORE::keys`, and `CORE::lock`, and support reference aliases to class
  aggregate fields. Allow callable `CORE::open` to use read-only literals as
  one-argument handle names without mutating the literal; preserve the default
  caller arrays for `CORE::pop` and `CORE::push`, and match callable `CORE::pos`,
  `CORE::prototype`, `CORE::read`, and `CORE::readline` type and context behavior.
- Preserve caller argument presence through `CORE::caller` references and
  support `CORE::continue` code references inside `given` blocks.
- Route callable `CORE::dbmopen` and `CORE::dbmclose` through the PerlOnJava DBM backend.
- Dispatch callable `CORE::die` with the original Perl call-site location.
- Support `CORE::evalbytes` code references with caller lexical hints and `CORE::each` references.
- Match Perl's strict-refs behavior for callable `CORE::accept`, preserve
  `CORE::truncate`'s bareword-only reference error, and propagate caller context
  through `CORE::wantarray` references.
- Preserve caller `unicode_strings` behavior in `CORE::fc` and support `CORE::glob` references.
- Keep lexical class methods visible across method declarations without
  installing them in the package stash, and support callable `CORE::bless`.

- Keep format captures bound to their active lexical cell and prevent reuse of unrelated active lexicals.
- Match Perl's reference count for the compile-time `%^H` hash.
- Run deeply nested conditional core tests with an adequate JVM stack.
- Preserve captured lexical aliases in nested eval `BEGIN` blocks.
- Preserve lexical sub references passed through the debugger's `DB::goto` hook.
- Resolve POSIX group records and retain supplementary IDs in `$(` and `$)`.
- Identify the runtime operating system in `Config::Config{myuname}`.
- Default bare `-t` file tests to `STDIN`.
- Inherit terminal `STDIN` in subprocesses launched by `system` and `qx`.
- Preserve setuid and setgid permission bits after in-place file editing.
- Provide a controlling pseudo-terminal for Perl core tests that require one.
- Supply the PerlOnJava launcher for core tests that spawn `./perl`.
- Keep `close` and `fileno` probes from creating nonexistent symbolic filehandles.
- Preserve typeglob values from scalar assignments and selected-handle lookups.
- Return EOF as undef from scalar-backed `getc`, and let argumentless `system()` wait.
- Resolve scalar pipe names as symbolic handles and reuse descriptor 0 after
  closing STDIN.
- Reject splice on read-only arrays and negative tied-array `FETCHSIZE` values.
- Reset array `each` iterators when replacing their contents.
- Fetch tied scalars during `study` and return `crypt` results without the
  UTF-8 flag.
- Report disabled `evalbytes` as a syntax error.
- Warn when inserting new keys into a hash during an active `each` traversal.
- Return the final expression from parenthesized lists in scalar context.
- Warn about bareword exponent suffixes following numeric literals.
- Preserve the empty string yielded by a range with two undefined endpoints.
- Avoid materializing values from empty list assignments in void context.
- Preserve omitted optional underscore prototype arguments after required arguments.
- Parse comma-delimited `q` and `qq` strings when disambiguating hashrefs from blocks.
- Clear `pos()` after a failed second match of a global match-once pattern.
- Validate typed hash dereferences against explicitly referenced `%FIELDS` tables.
- Warn about anonymous subroutines in void context and undef dynamic code references in place.
- Report explicitly referenced stash-backed subroutines as anonymous after deletion while preserving ordinary deleted CV names in `caller()`.
- Detect oversized repetition counts before they wrap during conversion.
- Run eval-block destructors before clearing `$@`, and report `ENOENT` from failed `rmdir` calls.
- Preserve `sprintf` numeric overload counts and format-string UTF-8 flags.
- Format infinities and NaNs with Perl's spelling across numeric `sprintf` conversions.
- Preserve runtime list context through short-circuit and ternary branches in empty-list assignments.
- Restore `EAGAIN` after alarm interrupts `sleep`, even when a signal handler changes `$!`.
- Preserve Unicode `-s` arguments when a core test launches a nested interpreter through the shell.
- Preserve non-ASCII byte arguments in unquoted shell commands and nested
  PerlOnJava launches under C locales and through Windows command shells.
- Apply Unicode character classes to interpolated Unicode regex patterns.
- Preserve combining-mark order in Unicode uppercase mappings and honor byte-string method names in `can`.
- Reject scalar constants passed to hash-reference prototypes with the expected diagnostic.
- Preserve malformed octets read through Perl's `:utf8` layer while decoding valid UTF-8.
- Keep `$^X` unflagged for shell command construction and preserve encoded input octets.
- Evaluate `readline` in list context when an empty-target assignment discards its results.
- Preserve valid Unicode goto targets after conditional labels, non-vivifying symbolic glob checks, compact pseudo-constant aliases after redefinition, and weak-reference cleanup when closure captures leave scope.

- Prevent stale CPAN archive-name entries and namespace-resolution errors from
  being recorded as compatibility regressions.

- Keep SQLite column metadata fetchable and quote reserved table names, support
  Perl-compatible SQLite `REGEXP`, clear successful DBI error strings, and
  delegate non-JDBC transaction methods to their drivers.

- Preserve objects with counted collection owners during weak-reference sweeps,
  and resolve deferred user-defined regex properties in the match caller's
  package.

- Fix Unicode `IsPrint` aliases in character classes, `/x` literal-brace
  warnings and matching, and Joni backtracking across repeated whitespace.

- Preserve debugger eval caller lines, expose overloaded callbacks through
  `$DB::sub`, initialize `$^V` as a `version` object with canonical string
  output, and diagnose loop control in unused subroutines.

- Keep imported `try`/`catch` and declared `dump` calls free of false CORE
  ambiguity warnings, support `chdir` and `chmod` through open filehandles,
  report directory `chdir` as unavailable without `dirfd`, restore
  invalid-descriptor errors, preserve dynamic package variables and monotonic
  clocks needed by AnyEvent::Tools, and reuse root reachability snapshots
  during weak-reference cleanup.

- Support ascending Perl version declarations, accept valid optional-array
  prototypes and fully qualified indirect constructors, and preserve text and
  entity boundaries across incremental HTML parsing.

- Preserve ordinary `goto` jumps between conditional branches while rejecting
  jumps into conditional blocks from outside.

- Support anonymous temporary files for read/write opens with undefined paths,
  report non-numeric process IDs passed to `kill()`, and release each result of
  a void-context `map` iteration before invoking the next block; diagnose the
  missing comma in malformed `grep` predicate syntax.

- Resolve Perl `IsWord` aliases without changing custom-property precedence;
  preserve default `/d` classes inside `(?^:...)`, recognize ordinary hex-like
  byte text during matching, and retain Unicode provenance when formatting
  compiled regex values.

- Keep imported `try`/`catch` and declared `dump` calls free of false CORE
  ambiguity warnings, restore filehandle `chdir` and invalid-descriptor errors,
  preserve dynamic package variables and monotonic clocks needed by
  AnyEvent::Tools, and reuse root reachability snapshots during weak-reference
  cleanup.

- Preserve selected filehandles across glob localization, named Unicode handle
  names, void tied-assignment tails, dynamic regex input encoding, syntax-error
  eval callers, and stdio layers; keep wide CLI diagnostics free of host print
  warnings and give the complete Windows unit gate adequate bounded time.

- Restore sparse tied-array reversal, typed declarations in `eval`, regex
  capture restoration across loop control, and `PERL5OPT` include ordering;
  match Perl's seeded random sequence and Unicode `quotemeta` rules.

- Preserve missing slots when assigning reversed tied arrays and restore the
  loop-entry regex captures when `next`, `redo`, or `last` exits nested scopes.

- Restore core-test compatibility for regex-set diagnostics (including
  POSIX-looking text in extended-class comments), classes and MRO, op subs,
  filesystem metadata, `die` state, and scalar flip-flop warnings on both
  execution backends.

- Resolve dynamic `require` package names as portable module paths before
  filesystem lookup, including on Windows.

- Preserve file-operator precedence for `eof`, `tell`, and `readline`; flush
  incomplete HTML tags at EOF; accept prototype block arguments; resolve
  imported `break` subs; skip adjacent POD sections after executable code; and
  bind concatenated filename expressions to `do` while preserving relative
  file paths used by loaded scripts.

- Parse `PERL5LIB` with the host platform's path separator.

- Restore `re/recompile.t`, `class/accessor.t`, `op/smartkve.t`, `comp/utf.t`,
  `op/gmagic.t`, and `uni/overload.t` compatibility.

- Preserve tied scalar magic when deleting tied hash elements and stash entries,
  without triggering tied `FETCH` in void context.

- Restore JVM/interpreter compatibility for `goto.t`, `qr.t`, `runlevel.t`, `select.t`,
  `sselect.t`, `perlio_fail.t`, `try.t`, and the three substitution tests,
  including source-site diagnostics, eval caller frames, and regex identity.

- Restore UTF-8 I/O warnings, defer control flow, directory reads, and
  version-declaration semantics on both execution backends.

- Unwind interpreter control-block entries after caught eval exceptions so
  subsequent loop control cannot re-enter an abandoned block.

- Route eval STRING loop control to the active caller loop, including while
  loops, and preserve eval BLOCK catchers during interpreter fallback.

- Fix source-site control-flow diagnostics, nested eval caller frames, try
  caller frames, regex identity, and other reported JVM/interpreter compatibility
  gaps.

- Restore `op/stash.t` namespace spellings, stash clearing, and forward-CV
  provenance on both execution backends.

- Restore `op/reset.t`, `io/perlio.t`, `uni/lex_utf8.t`, and `op/bop.t`
  compatibility for scoped reset, PerlIO encoding recursion, byte unpacking,
  and integer-mode bitwise behavior on both execution backends.

- Restore `op/bless.t` compatibility for empty-package warnings, deleted
  package stashes, and readonly scalar references on both execution backends.

- Fix `bless` of references returned by tied hash elements.

- Restore command-line Unicode and Perl shebang switch compatibility, including
  scoped `-Ci`/`-Co` open layers and `Module::Pluggable::Fast` loading.

- Restore `op/refstack.t` compatibility for prefix `!~` assignment parsing,
  special typeglob spellings, and typeglob numeric coercion on both execution
  backends.

- Restore `comp/retainedlines.t` debugger eval-source retention, `#line`
  mappings, and UNITCHECK diagnostics on both execution backends.

- Restore CGI::Simple multipart request parsing and `CORE::GLOBAL::time`
  overrides on both execution backends.

- Restore `op/caller.t` compatibility for eval source text, tied and freed
  `@DB::args`, anonymous stashes, hint hashes, nested `use` frames, and
  caller source-line tracking on both execution backends.

- Restore `uni/parser.t` Unicode parser diagnostics, typed declaration errors,
  and eval compilation messages on both execution backends.

- Run CPU-heavy and ordinary Perl test fixtures concurrently when using the
  runner's compatibility resource caps, while admitting long-running work
  first.

- Restore `op/ref.t` compatibility for regexp, lvalue, blessed-reference,
  standard-IO, and range refgen semantics on both execution backends.

- Retain source lines for `do FILE` under `$^P`, restoring `comp/line_debug.t`
  compatibility on both execution backends.

- Restore `op/kvhslice.t` compatibility for empty key/value slices, scalar
  warnings, invalid lvalue diagnostics, `foreach` aliases, and hash prototypes
  on both execution backends.

- Preserve integer-mode auto-increment and decrement through aggregate lvalue
  proxies, restoring Pod::Simple numbered-list validation.

- Restore `op/sort.t` compatibility for dynamic and glob comparators,
  scalar method-list expressions, comparator control flow, and temporary
  argument lifetimes on both execution backends.

- Preserve shared-scalar identity after an ithread locks it through a lexical
  reference, restoring recursive locks and condition operations on both
  execution backends.

- Restore BEGIN-captured container aliases, localized hash closure destruction,
  and lexical `no warnings 'signal'` handling on both execution backends.

- Restore `lex.t` compatibility for quote-like `#line` mapping, byte-source
  eval diagnostics, evalled substitutions, constant stash entries, and both
  execution backends.

- Restore special-block phaser behavior, including compile-time execution,
  lexical phaser-name calls, and Perl-compatible diagnostic boundaries.

- Implement Unix `socketpair` through real POSIX descriptors, preserving
  bidirectional I/O, socket options, shutdown, and scope-exit EOF semantics.

- Restore lexical lvalue alias isolation, `${^LAST_FH}` lexical filehandle
  identity, and user-defined Unicode property diagnostics on both backends.

- Preserve lexical-sub storage and CODE aliases across interpreter fallback,
  and reject writes to padded multi-variable foreach iterators on both backends.

- Restore scalar flip-flop semantics for `..`/`...` in `when` predicates and
  subroutines whose caller context is determined at runtime.

- Restore logical `when` predicate selection, preserving smartmatch semantics
  for non-topical logical expressions and boolean context for topic/captures.

- Preserve `DATA` handle semantics for commented marker text and empty
  top-level data sections, restoring `eof(DATA)` behavior in `given`/`when`.

- Restore `use integer` smartmatch semantics in `given`/`when`, including
  numeric scalar, array, and regex dispatch on both execution backends.

- Preserve Perl-compatible `EISDIR` readline behavior for filehandles opened
  on directories on Windows, and ensure `Config` does not advertise unsupported
  fork capabilities.
- Restore `op/split.t` nested array-assignment compatibility and split regex
  debug extflags on both execution backends.

- Preserve valid package and module version literals, restoring CPAN modules
  that use zero-padded dotted versions, large bare v-versions, or repeated
  identical `use VERSION` declarations across package changes.

- Restore the public `YAML::Syck` API and legacy template parsing, including
  exports, file helpers, percent placeholders, duplicate keys, and tabs.

- Restore package v-string declarations as `version` objects, including
  v-string comparisons and leading-zero diagnostics.

- Synchronize shared aggregate traversal during thread destruction, preventing
  intermittent `ConcurrentModificationException` failures in nested joins.

- Restore `op/ref.t` destructor reblessing semantics, invoking the new class's
  `DESTROY` method after an object is reblessed during destruction.

- Restore core compatibility for protected `DESTROY` invocants, tied hint-hash
  cloning, compile-time `__DIE__` diagnostics, and barehandle `eof` precedence.

- Restore core `gv`, `local`, and Unicode glob compatibility, including
  localized magic-stash slices and glob patterns whose first word is not a
  filehandle.

- Restore qx handling of leading POSIX environment assignments, unblocking
  Getopt::Complete shell completion on both execution backends.

- Preserve deferred string content when `Clone::clone` copies scalar values,
  restoring Dist::Zilla::Plugin::TrialVersionComment on both backends.

- Restore Mojolicious 9.49 loading by accepting valid `return sort map` pipelines.

- Preserve generated eval closure caller packages, restoring `Import::Into`
  exporters and Form::Tiny/Moo integration.

- Preserve localized standard-handle file descriptors when redirected output
  is active, restoring IO::Prompt::Tiny prompt reads on both execution backends.

- Restore Unicode 18 identifier acceptance and case-insensitive regex debug
  rendering for multi-character folds.

- Fix regex interpolation of `$|.` anchors, restoring `String::Errf` formatting
  with interpolated `/x` patterns on both execution backends.

- Restore `require` compatibility for dynamic `@INC` hooks, failed-open
  diagnostics, and hook-provided source locations on both execution backends.

- Restore `op/override.t` compatibility for `CORE::GLOBAL` overrides in string
  evals, v-string `require` versions, backticks, readline syntax, and
  zero-prototype lexical closures.

- Restore strict-reference diagnostics for dynamic subroutine calls, including
  numeric scalar invocants and both execution backends.

- Restore core method-lookup diagnostics for existing stashes and qualified
  method names.

- Preserve Perl-compatible NUL method diagnostics and ignore declared but
  undefined destructors.

- Preserve glob identity and literal immutability for method invocants on both
  execution backends.

- Restore the global-destruction warning when a `DESTROY` method revives its
  object.

- Avoid spurious global-destruction resurrection diagnostics from ordinary
  `DESTROY` resurrection and same-class reblessing during destruction.

- Restore `Hash::Util::FieldHash` `:all` imports and scalar `id` semantics,
  unblocking Cache::Ref's LRU implementation.

- Add a portable SQLite-backed DBM implementation for `dbmopen` and
  `dbmclose`, including persistence, binary-safe values, tied-hash iteration,
  and reopen support.

- Allow valid Moo-style constant closures that capture an initialized lexical
  within a `BEGIN` block.

- Restore `op/sub_lval.t` compatibility for lvalue subroutine returns, including
  nested calls, readonly values, list aliases, tied scalars, and both execution
  backends.

- Normalize bytecode-interpreter method-call operands in scalar context, restoring
  Moose role composition with constant methods.

- Allow the full core UAT sufficient wall-clock time to complete `re/anyof.t`
  under parallel load.

- Restore remaining core compatibility around format lexical scope warnings,
  glob IO handles, `-x` extraction, length diagnostics, and deterministic
  closure destruction.

- Restore braced typeglob IO-slot handles in `print`, unblocking core glob
  compatibility tests for both ASCII and Unicode symbol names.

- Restore core compatibility for UTF-8 `pack "U"` byte comparisons and
  whitespace-equivalent subroutine prototype declarations.

- Restore `op/const-optree.t` compatibility for lexical constant CVs,
  including mutation errors, refaliasing, closure ordering, redefinition
  diagnostics, and both execution backends.

- Restore core `op/magic.t` compatibility for wide `$0`, `%ENV` byte handling,
  `${^LAST_FH}`, `%!` void-context autoloading, signal wait statuses, shebang
  `$^X` identity, and subprocess output decoding.

- Restore `sysread`/`syswrite` validation and `sysseek` failure semantics,
  including tied-buffer magic access counts.

- Restore core compatibility for grouped subroutine prototypes, nested
  heredocs in interpolations, tied-handle EOF/write dispatch, and byte-string
  UTF-8 evals.

- Fix core compatibility gaps for UTF-8 eval sources, `lock &code` prototypes,
  nested `@ARGV` traversal, and in-place editing of existing backup files.

- Restore Perl-compatible warning order for prototype attributes and report
  failed in-place backup renames instead of moving files into a backup
  directory.

- Avoid spurious `imprecision` warnings when incrementing or decrementing
  exact integer literals beyond the IEEE-754 precision boundary, while
  preserving warnings for computed integer boundary values.

- Restore `op/tie.t` compatibility for tied scalar operators, output
  separators, deferred aliases, glob copies, and tied hash iteration on both
  execution backends.

- Improve `pack`/`unpack` compatibility for UUencoding, scoped Unicode modes,
  pointer strings, and native unsigned integers; correct interpreter string
  repetition for function calls in list context.

- Extend CPAN release acceptance coverage to Excel::Writer::XLSX with a timeout suitable for its large test suite.

- Complete `tr///` compatibility for extended Unicode and surrogate scalars,
  identity lvalues, and `chop`/`chomp` diagnostics on both execution backends.

- Reject Unicode named sequences in transliteration operands and preserve the
  Perl foreach-entry diagnostic for shadowing `goto` labels inside `eval`.

- Avoid materializing a key list when bytecode evaluates `keys %hash` in scalar
  context, restoring empty-hash performance for repeated hash-count queries.

- Reset embedded-script readline state between top-level programs and include
  the nested-class fixture required by the standalone unit-test corpus.

- Preserve literal exclamation marks in multiline `-e` arguments when Windows
  dispatches a child `jperl` process without invoking its batch launcher.

- Improve Perl-compatible compiler diagnostics for unterminated quoted strings
  and here-document delimiters.

- Restore `local` compatibility for tied hash and array elements, sparse
  arrays, magic stashes, implicit `$_` foreach aliases (including early
  return), and localized regex captures on both execution backends.

- Classify scalars above Unicode's ceiling as `Cn`/`Unassigned` in regex
  properties, including Perl-compatible warnings and global matching.

- Reuse interpreter deparse source text when cloning closures, substantially
  improving closure-heavy JSON workloads.

- Run Gradle builds without a persistent daemon so worker JVMs inherit the
  launcher's process priority.

- Keep debugger EOF from terminating embedded Gradle test workers on Windows.


- Restore source-scoped eval diagnostic numbering; reject Unicode punctuation
  in lexical declarations; diagnose invalid `delete` and `exists` targets; and
  report clean control-flow errors from `defer` and `finally` blocks. Require
  block arguments for feature-gated `all` and `any` keywords, with
  Perl-compatible syntax diagnostics, and diagnose invalid indirect arguments
  to `return` without rejecting valid return statement modifiers. Reject
  reference-valued `bless` class names, including values from tied scalars.
  Diagnose `when` and `default` used outside a `given` topicalizer.
  Reject assignments to unknown `%SIG` hooks and diagnose defined assignments
  to the removed `${^ENCODING}` special variable.
  Reject assignments of Perl class objects to typeglobs.
  Diagnose aggregate operands to numeric and string bitwise assignments.
  Diagnose aggregate lvalues passed to `substr` and `vec`.
  Report Perl-compatible hash, private-hash, and typeglob diagnostics for
  invalid `push`, `pop`, `shift`, and `unshift` operands, including every
  direct invalid operation in a compilation.
  Report undefined hash-reference diagnostics when aggregate values are used
  as hash references.
  Preserve mismatched array and hash literal delimiters in syntax-error
  context, matching Perl's diagnostics.
  Report evaluated missing labels for the legacy `CORE::dump` operator.
  Report Perl-compatible undefined subroutine-reference errors for ordinary
  and tied scalar codereferences.
  Reject attempts to reopen active filehandles as directory handles (and vice
  versa), with Perl-compatible lexical and Unicode handle diagnostics.
  Report Perl-compatible UTF-8-layer errors from `sysread` and `syswrite`.
  Reject non-reference and wrong-reference-type values in declared-reference
  `foreach` iterators with Perl-compatible diagnostics.

- Avoid transient helper allocation while counting ordinary Perl UTF strings.

- Reuse static literal regular-expression match wrappers per runtime and call
  site, reducing recurring pattern-resolution and compilation overhead.

- Restore lexical-sub debugger dispatch and `glob` fallback behavior after an
  undefined `CORE::GLOBAL::glob` slot on both execution backends.

- Restore `run/switches.t` command-line compatibility: Perl shebang switch
  processing, `-E` builtins, in-place-edit failure handling, record-separator
  chomping, and warning diagnostics on both execution backends.

- Restore `op/sub.t` compatibility for aliased subroutines, tied-local eval
  cleanup, argument-array undef, and scalar-return lexical destruction.

- Restore MRO method caching through whole-glob aliases, CODE-slot replacement,
  localized aliases, and undefined-stub vivification on both execution backends.

- Restore scope-aware label resolution, loop-entry validation, and runtime
  package restoration for `goto` on both execution backends.

- Fix direct execution of scripts generated with a PerlOnJava `$^X` shebang and update the Java-backed `Compress::Raw::{Bzip2,Zlib}` providers to the audited 2.224 compatibility level.

- Add guarded compiler fast paths for BMP substring offsets, small negative
  integer literals, and scalar captured-integer closure additions.

- Restore Perl-compatible integer increment/decrement semantics, imprecision
  warnings, numeric overload fallback, and postfix-reference lifetime handling.

- Restore Perl continuation-picture ellipsis, lexical and multiline
  argument-block, text-record, and eval-error semantics for `write` and
  `formline`.

- Preserve Perl control-verb boundaries through nested common-prefix regex
  alternatives, restoring `re/regexp.t` compatibility on both backends.

- Restore indented here-doc delimiters with whitespace, eval-string substitution
  bodies, EOF termination, Perl-compatible diagnostics, and source positions on
  both backends.

- Report Perl-compatible `Usage:` diagnostics for invalid prototype-bypassing
  calls to `Internals::SvREADONLY`, `SvREFCNT`, and `hv_clear_placeholders`.

- Restore Perl smartmatch dispatch for arrays, hashes, regexes, predicates,
  tied hashes, overloaded objects, and both execution backends.

- Preserve Perl's divisor-sign modulus semantics in dynamically compiled
  methods under `no overloading`.

- Restore file-test error, stat-cache, glob-reference, and `tell` bareword
  behavior while preserving `${^LAST_FH}` for ordinary scalar arguments.

- Decode Perl extended UTF-8 `C0U*` sequences, including surrogate scalars,
  and report malformed byte streams through Perl warning hooks.

- Restore Perl full case-fold matching across adjacent character classes,
  including literal-delimited and evaluated regex patterns.

- Complete Perl-compatible signature argument binding, defaults, diagnostics,
  closure capture, and experimental `@_` warnings.

- Preserve state-variable initialization across `goto` loops after nested
  closure compilation and parenthesized logical defaults on both execution
  backends.

- Preserve async Future ownership across interpreter suspension and resume.

- Make key/value hash slices supply writable values when used as a `foreach`
  source, matching Perl on both execution backends.

- Bind `for \\%hash (@hashrefs)` loop variables to each referenced hash on
  both execution backends, restoring constant-sub core-test coverage.

- Restore parser diagnostics for malformed quoted-string escapes,
  overlong identifiers, invalid typed loop declarations, and version-control
  conflict markers; accept Unicode identifiers in normal Unicode-string `eval`
  calls; evaluate `keys %hash` as a scalar temporary in lvalue consumers; and
  retain subroutine prototypes for deprecated quote-qualified declarations and
  diagnose unknown one-letter filetest operators and constant `read` or
  `undef` operands and bareword list-assignment targets; preserve qualified
  subroutine names that begin with `CORE::`; and parse empty braces as an
  indirect-method hash-reference invocant; reject empty braced interpolation
  in substitution replacements; and preserve Unicode capture provenance during
  interpolated evaluated substitutions; reject semicolons within
  parenthesized unprototyped calls; reject aggregate substitutions and
  transliterations that lack a mutable scalar target; and retain the original
  syntax diagnostic and context for malformed braced interpolation.

- Identify PerlOnJava, its copyright, and its dual-license terms in
  `jperl -v` output while retaining the standard Perl text.

- Preserve source files when extensionless in-place editing aborts, and treat
  a lone `'*'` in-place extension like Perl's extensionless form.
- Prevent eval-created named subs from treating lexical variables as
  same-named constant calls, restoring `Types::Numbers` loading through
  `Data::Float`.

- Restore Perl-compatible `<>` and `<<>>` ARGV traversal, `eof()` behavior,
  diagnostics, and warning handling on both execution backends.

- Implement undef-aware experimental equality operators (`===`, `!==`, `equ`,
  and `neu`) with lexical warnings and single-evaluation chained comparisons.

- Pass state returned beside an `@INC` hook generator to each generator call,
  restoring stateful module source loading on both execution backends.

- Make an absent `maybe::next::method` return an empty list in list context,
  restoring MooX::Options metadata and command-line parsing.

- Support `local *$globref` dynamic typeglob localization, including its IO
  slot, so Test::Trap and Test::Spec can load their temporary-handle helpers.

- Fixed large dynamic named-subexpression grammars hanging during regex compilation.

- Restore PPR's complete suite by correcting recursive duplicate-name captures,
  nullable recursion checks, and callback regex reuse.

- Route argumentless `readline` through localized `@ARGV`, matching Perl's
  diamond-reader behavior and keeping PPR's self-document test warning-free.

- Preserve Data::Dumper's pure-Perl numeric-string behavior for
  Test::Differences, including copied `qw` values and numeric zero fixtures.

- Correct named-unary operand precedence, so `! scalar @array % 2` evaluates
  the modulo operation before its logical negation.

- Preserve tied-scalar magic through `utf8::encode` and `utf8::decode`.

- Preserve IO::Async thread callback results and accepted listener sockets on
  both execution backends, retain binary channel payload octets, and align its
  notifier-loop refcount expectation with native Perl.

- Amortize repeated scalar `.=` growth, avoiding quadratic JSON decoding and
  allowing Selenium::Remote::Driver's recorded mock responses to load.

- Preserve buffered IPC::Open3 stdout and stderr until consumed before
  reporting EOF, preventing IPC::Open3::Utils handler loss and pipe hangs.

- Fix parsing of dense Mo::Inline expressions that use `::` as a bareword.

- Preserve UTF-8 HTML octets through HTML::Parser and no-op entity decoding,
  restoring complete Thai text in HTML::Formatter output.

- Return `undef` from false `if` expressions without an `else`, preserving
  omitted optional arguments for `Params::Validate` and DateTime formatters.
- Preserve caller-owned array and hash lifetimes across generated coercion
  callbacks, so `Types::Const` freezes cloned values without modifying the
  original reference.

- Keep deferred interpreter-fallback return values alive while a replacement
  scalar reference releases a guard, restoring Object::Event callback-guard
  assignment semantics.

- Make PPIx::Regexp 0.092's upstream suite pass by clearing its private weak
  parent-map test hook at the post-parse quiescence point.

- Restore the #1238 UAT baseline across I/O, eval/control flow, interpreter
  parity, and core-test TAP accounting; all rows previously reported negative
  now meet or exceed their reference counts.

- Restore selected-handle format state, recursive format argument blocks, and
  cross-subroutine loop control; preserve bytecode socket and tied-handle I/O
  results.

- Restore bytecode interpreter loop scope, regex-state, and scalar-context
  parity for `redo`, `continue`, and `while`.

- Fix numeric compound assignments on `vec` lvalues, preventing high-precision date arithmetic from producing `NaN` and hanging.
- Preserve the last element for interpreter list-to-scalar localization assignments, restoring DateTime::Precise arithmetic parity.
- Keep non-array localized scalar assignment RHS expressions in scalar context, restoring CRLF readline/seek behavior.
- Fix `Encode::encodings` failing after JCodings' relocated charset provider
  was discovered through Java's service loader.

- Enter the compatibility and performance phase: the broad language
  implementation is in place, and active development now focuses on remaining
  Perl compatibility gaps, wider CPAN coverage, and CPU and memory performance.
  The 2026-09-03 imported upstream compatibility run reports 669,688 of 673,847
  checks passing (99.4%); the 2026-09-02 sampled CPAN report records 8,315 of
  16,443 tested modules passing all tests (50.6%). See the
  [current project status](roadmap.md#current-project-status) for scope and
  methodology.

- Exclude upstream Perl release-engineering `t/porting` tests from the
  selectively imported compatibility fixture.

- Fix format declarations being discarded during compilation, unblocking `write` execution.

- Make `write FILEHANDLE` use that handle's active `$~` format, including when the format undefines its own glob during execution.

- Clear weakened references after a nested method releases its final
  array-slot owner, restoring `Algorithm::SlidingWindow` eviction and clear
  behavior on both execution backends.
- Release captured closure owners when their callback is discarded, preventing
  stale refcounts after a temporary global owner (such as an IO::Async loop
  notifier) is removed, and retire unreachable eval-capture ownership before
  global destruction.
- Release interpreter hash-slice RHS staging owners after their durable hash
  slots are created, restoring Net::Async::HTTP connection refcounts.
- Correct bundled `HTTP::Cookies` Cookie2 quoting and restore gzip and bzip2
  content-coding wrappers used by `Net::Async::HTTP`.
- Add Mojolicious 9.49 support through `jcpan`; 109 files and 4,194 tests pass
  in 955 seconds with only upstream developer/optional-feature skips.
- Make Catalyst::Runtime pass 199 supported files, and DBIx::Class pass all tests.
- Improve listener polling and socket ownership, streaming gzip/zlib detection,
  JSON/YAML byte handling, and use the upstream `File::Temp` implementation.
- Attribute CPAN tester results to the package represented by each distribution,
  avoiding alias-package misattribution in compatibility reports.
- Fix parser diagnostics, Unicode split and global-regex progression, and
  persistent-app closure cleanup while preserving DBIx::Class leak behavior.
- Complete `goto &sub` tail-call parity, including eval diagnostics, sparse
  `@_` reification, late `AUTOLOAD`, completed-handoff temporary cleanup,
  dynamic-coderef calls from eval, preserved saved-coderef identity across
  named redefinition, and top-level anonymous-coderef invocation.
- Route uncaught Perl diagnostics through the active `STDERR` handle, so a
  closed `STDERR` suppresses a bare `die` like standard Perl.
- Preserve process-pipe descriptors through returned and argument-aliased
  aggregates, and align compound-assignment lvalue order across both backends.
- Keep Windows `sysopen` raw unless lexical `use open` applies, preserve exact
  emulated mode bits in `stat`, make tempfile handle stats reflect creation
  modes.
- Restore the post-acceptance core UAT baseline on `9b2377b6f`: value-producing
  `defer` bodies remain verifier-safe, `PerlIO->import` rejects code injection
  without inheriting `UNIVERSAL` export errors, and repeated `$#array` lvalues
  stay writable on JVM and interpreter backends.

* Add JDBC-backed `DBD::mysql` and `DBD::Pg` compatibility shims and preserve
  SQLite URI-file schema state across DBI connections.

- Fix typeglob slot semantics exposed by `Symbol::Util`: `undef *Pkg::name` and
  `undef $Pkg::{name}` now detach every slot (so `*Pkg::name{ARRAY}` and friends
  read back as undef) while leaving referenced containers intact for
  re-installation, `&name` inside `defined eval { ... }` is called instead of
  being turned into a code reference, typeglob assignment correctly replaces
  `@ISA`, and `require` no longer adds a phantom `DATA` entry to the requiring
  package's stash.
- Report the enclosing statement's source line for calls inside multi-line
  expressions, so `caller`, `warn`, and Test::Builder diagnostics identify the
  statement instead of the closing `)->method` line of a chained call.
- Parse fully-qualified indirect constructors followed by method calls, and
  stage generated nested pure-Perl MakeMaker modules correctly (including
  NetAddr::IP's `-noxs` installation path).
- Preserve `CORE::__SUB__` from enclosing named subroutines inside sort blocks.
- Taint `Cwd` results under `-T` (`getcwd`, `cwd`, `fastcwd`, `fastgetcwd`,
  `abs_path`, `realpath`, `fast_abs_path`, `fast_realpath`), matching standard
  Perl and restoring Data::Compare's taint-mode plugin guard.
- Distinguish the logical from the physical current directory in `Cwd`:
  `cwd` and `fastgetcwd` now report a validated `$ENV{PWD}` while `getcwd` and
  `fastcwd` stay physical, matching standard Perl on Unix-like platforms.
- Propagate argument taint through `File::Spec` `canonpath`, `catdir`, and
  `catfile`, so `rel2abs`/`abs2rel` taint their `Cwd`-derived results under
  `-T` like standard Perl.
- Preserve the lifetime of borrowed `+>&=` filehandle aliases, restoring
  `Tie::File::Indexed` file-backed array storage.
- Add a pure-Perl `JSON::Parse` compatibility layer backed by bundled `JSON::PP`.
- Recognize retired `experimental::isa` and `experimental::alpha_assertions`
  warning categories for Perl source compatibility.
- Fix Object::HashBase deferred Role::Tiny composition.
- Fix localization of numbered regex captures.
- Fix IO-handle type checks and uninitialized-value warning locations.
- Fix numeric-zero results from failed `s///` substitutions.
- Preserve references in `utf8::downgrade`.
- Bound `Compress::Raw::Bunzip2` output when callers enable `LimitOutput`.
- Resolve bare named filehandle methods in the filehandle’s current package.
- Treat undef-like overloaded regex subjects as the empty string.
- Preserve strict-subs imports for `Compress::Raw::Zlib` constants.
- Evaluate `ref` operands in scalar context on the interpreter backend.
- Fix RFC 2047 header decoding for Email::MIME.
- Fix XML::Parser streaming from native filehandles before `IO::Handle` has
  been explicitly loaded.
- Bundle the complete CPAN `File::Path` 2.18 implementation, including modern
  `rmtree`/`remove_tree` options such as `keep_root`, `error`, `result`,
  `safe`, and `verbose`.
- Add `Time::Moment` 0.46 as a Java-backed bundled provider using `java.time`.
- Add `Crypt::Rijndael` 1.16 as a Java-backed AES provider with ECB, CBC,
  CFB128, OFB, and CTR compatibility.
- Preserve string-compatible scalar channels for arithmetic results derived
  from string operands on both execution backends.
- Honor imported overrides of the core `chmod` and `lock` built-ins.
- Preserve plain-string semantics for `%vd` formatting instead of treating
  dotted numeric strings as v-strings.
- Package Unicode::Collate DUCET tables in the runtime JAR.
- Bundle `Class::MethodMaker` 2.25 with its pure-Perl accessor engine and
  portable generated-subroutine naming support.
- Accept Perl-compatible whitespace in `open` mode strings, such as the `'< '`
  used by `Perl6::Slurp`.
- Duplicate the underlying handle when `open` is given a dup mode for a tied
  filehandle, as `CPAN.pm` does with a tied `STDOUT`.
- Preserve objects captured by live closures across weak-reference sweeps and
  release them when the final closure is discarded.

## v5.44.1: Regex, Threads, Async/Await, and CPAN Compatibility

- Reach 686,288 of 696,597 passing assertions in the Perl standard test suite
  (98.5%), and 7,522 of 16,311 randomly selected CPAN modules passing all tests
  (46.1%).
- Bundle `Moose` and `Future::AsyncAwait`. Applications and libraries
  including `WWW::Mechanize`, `Moo`, Template Toolkit, `DBIx::Class`, and
  `Catalyst::Runtime` can be installed from CPAN through `jcpan`.
- Complete the regex implementation's Joni compatibility slice on both
  execution backends, including dynamic regex programs, bounded recursion,
  variable-length lookbehind, grapheme clusters, advanced Unicode properties,
  and Perl control verbs. The 80-file core regex gate gains 729 passing
  assertions over the PR 958 baseline with no per-file regressions.
- Add full ithread support and interpreter multiplicity across both execution
  backends. The unchanged `threads`, `threads::shared`, `Thread::Queue`, and
  `Thread::Semaphore` distributions pass with virtual and platform threads;
  the five non-regex Perl core thread files pass all 849 assertions in all four
  backend/carrier combinations.
- Add native `Future::AsyncAwait` syntax and runtime support, including
  suspension, resumption, cancellation, async signatures, `defer`, `CANCEL`,
  and the Awaitable role. All 52 upstream files and 225 assertions pass on
  both execution backends.
- Add Perl taint mode with `-T` on both execution backends.
- Add single-process Catalyst applications through `Plack::Handler::Netty`,
  and support the PAGI HTTP, WebSocket, and Server-Sent Events reference stack.
- Add or expand Java-backed compatibility for `XML::LibXSLT`,
  `Text::Markdown::Hoedown`, `PadWalker`, `Devel::Caller`,
  `Devel::LexAlias`, `Data::Util`, `YAML::Syck`, `Scalar::Type`,
  `Tie::Hash::Indexed`, `Tie::Array::Packed`, `Digest::JHash`,
  `Crypt::Blowfish`, `Crypt::Twofish2`, `Proc::ProcessTable`, and other
  modules.
- Improve `jcpan`, MakeMaker, distribution preferences, prerequisite
  resolution, generated-module handling, and test-plan compatibility,
  unblocking a broad set of CPAN distributions without source preferences.
- Make quote-heavy generated Perl sources parse linearly, load CPAN metadata
  lazily, and reduce redundant reachability work in large object graphs.
- Fix weak-reference, destruction, closure, lvalue, warning-scope, encoding,
  networking, symbol-table, and interpreter-context behavior.
- Improve IPv6 and UDP behavior, dynamic Encode aliases, PerlIO encoding
  layers, CJK display width, and `env perl5` shebang routing.
- Preserve the single-JAR distribution model with strengthened packaging,
  SBOM, license, and cross-platform validation.

## v5.44.0: Named Parameters in Signatures

- Add named parameters in method and subroutine signatures.
- Security: added `SECURITY.md` and CycloneDX SBOM generation (`make sbom`)
- Tools: added `jcpan`, `jperldoc`, and `jprove`
- Perl debugger with `-d` command line option
- Add `defer` feature
- Lexical warnings with `use warnings` and FATAL support
- Non-local control flow: `last`/`next`/`redo`/`goto LABEL`/`goto $EXPR`
- Tail call with trampoline for `goto &NAME` and `goto __SUB__`
- Add modules: `CPAN`, `Time::Piece`, `TOML`, `DirHandle`, `Dumpvalue`, `Sys::Hostname`, `IO::Socket`, `IO::Socket::INET`, `IO::Socket::UNIX`, `IO::Zlib`, `Archive::Tar`, `Archive::Zip`, `Net::FTP`, `Net::Cmd`, `IPC::Open2`, `IPC::Open3`, `ExtUtils::MakeMaker`, `XML::Parser`, `Net::SSLeay`, `IO::Socket::SSL`, `Pod::Html` (+ `Pod::Html::Util`), `Cpanel::JSON::XS` (+ `Cpanel::JSON::XS::Type`, `Cpanel::JSON::XS::Boolean`; JSON::PP-backed shim).
- **Plack::Handler::Netty**: PSGI web server using Netty async I/O. Supports HTTP/HTTPS, streaming responses, 32k+ req/sec. See [examples/http_server_plack](../../examples/http_server_plack/README.md).
- Add operators: `flock`, `syscall`, `fcntl`, `ioctl`. 
- Add `\&CORE::X` subroutine references: built-in functions can be used as first-class code refs (e.g., `\&CORE::push`, `\&CORE::length`) with correct prototypes and glob aliasing.
- Support for forking patterns with `exec`:
        my $pid = open FH, "-|"; if ($pid) {...} else { exec @cmd }
        my $pid = open FH, "-|"; unless ($pid) { exec @cmd } ...
        open FH, "-|" or exec @cmd;
- Bugfix: parser now handles `@{${...}}` nested dereference in push/unshift.
- Bugfix: regex octal escapes `\10`-`\377` now work correctly.
- Bugfix: `\K` (keep left) assertion now works in `m//` and `s///`.
- Bugfix: `^` / `$` in `/m` mode under `/g` no longer produce spurious empty matches in list context (e.g. `"ab\ncd\n" =~ /^(.*)/mg` now returns 2 matches as in Perl, not 4). Restores correct behaviour for the common line-walking idiom and unblocks `Pod::Html::Util::trim_leading_whitespace`.
- Bugfix: `$Config{perladmin}`, `$Config{cf_email}`, `$Config{cf_by}`, and `$Config{myhostname}` are now populated from the running JVM's user/host info instead of being undef.
- Bugfix: operator override in Time::Hires now works.
- Bugfix: internal temp variables are now pre-initialized.
- Optimization: faster list assignment.
- Optimization: faster type resolution in Perl scalars.
- Optimization: `make` now runs tests in parallel.
- Optimization: A workaround is implemented to Java 64k bytes segment limit.
- New `interpreter` backend.
  - New command line option: `--interpreter` to run PerlOnJava as an interpreter instead of JVM compiler.
    - `./jperl --interpreter --disassemble -e 'print "Hello, World!\n"'`
  - The interpreter mode excels at dynamic eval STRING operations (46x faster than compilation for unique strings, matching Perl 5 performance). For general code, it runs only 15% slower than Perl 5. It is also useful for implementing debugging, handling "Method too large" errors, and enabling Android and GraalVM compatibility.
- Add `attributes` pragma with `MODIFY_*_ATTRIBUTES`/`FETCH_*_ATTRIBUTES` callbacks for subroutines and variables.
- Add modules: `Filter::Simple` with `FILTER` and `FILTER_ONLY` support.
- Add `DESTROY` method support with selective reference counting on blessed objects, cascading destruction, closure capture tracking, and global destruction phase.
- Add `Scalar::Util` functions: `weaken`, `isweak`, `unweaken`.
- Add `Internals::SvREFCNT` for compatibility with reference-counting introspection (e.g. Sub::Quote, Moo, DBIx::Class internals).
- **Bundled Moose 2.4000 and Class::MOP 2.4000**: the upstream Moose source tree is shipped in `src/main/perl/lib/{Moose,Class/MOP}/`. Tested by installing `DBIx::Class` 0.082843 via `jcpan` (DBIx::Class itself uses `Moo`, fetched from CPAN) and running its test suite — it passes 100% (314 files / 13858 asserts). Upstream Moose's own test suite passes ~99% (≥396/478 files, ≥13413/13550 asserts). See [bundled modules](../reference/bundled-modules.md#moose--classmop) and [dev/modules/moose_support.md](../../dev/modules/moose_support.md) for the full status and the small set of remaining failure clusters (numeric-arg warnings, anon-class GC timing, threads/fork tests).

- Work in Progress
  - Fix scoped `%^H` guard destruction.
  - [Multiplicity — per-runtime isolation for concurrent Perl interpreters](https://github.com/fglock/PerlOnJava/pull/480): `PerlRuntime` with `ThreadLocal`-based isolation; all mutable state (globals, I/O, regex, caller stack, method caches) moved to per-runtime instances; 122/126 concurrent interpreter tests pass; pending closure/method dispatch optimization
  - Moose - most tests pass
  - XML::LibXML - some tests pass
  - PerlIO
    - `get_layers`
  - Term::ReadLine
  - Term::ReadKey
  - FileHandle
  - File::Temp
  - File::Path
  - File::Copy
  - IO::File
  - IO::Handle
    - `ungetc`
    - Auto-bless filehandle into IO::Handle subclass
  - IO::Seekable
  - Math::BigInt
  - Text::ParseWords
  - Text::Tabs
  - Locale::Maketext::Simple
  - Params::Check
  - SelectSaver
  - locale pragma
  - utf8 pragma
  - bytes pragma
  - vmsish pragma
  - Constant folding - in ConstantFoldingVisitor.java
  - Overload operators: `++`, `--`.
  - String interpolation fixes.
  - Command line option `-C`


## v5.42.2: 250k Tests, Class Features, System V IPC, Sockets, and More

  - Add Perl 5.38+ Class Features
    - Class keyword with block syntax fully working
    - Method declarations with automatic $self injection
    - Field declarations supporting all sigils ($, @, %)
    - Constructor parameter fields with :param attribute
    - Reader method generation with :reader attribute  
    - Automatic constructor generation with named parameters
    - Default values for fields fully functional
    - ADJUST blocks with field transformation working
    - Field transformation to $self->{field} in methods
    - Lexical method calls using $self->&priv syntax
    - Class inheritance with :isa attribute working
    - Version checking in :isa(Parent version) implemented
    - Parent class field inheritance fully functional
    - Object stringification shows OBJECT not HASH
    - ClassRegistry tracks Perl 5.38+ class instances
    - Context-aware reader methods for arrays/hashes
    - Field transformation in string interpolation works
    - __CLASS__ keyword with compile-time evaluation
  - Add System V IPC operators: `msgctl`, `msgget`, `msgrcv`, `msgsnd`, `semctl`, `semget`, `semop`, `shmctl`, `shmget`, `shmread`, `shmwrite`.
  - Add network enumeration operators: `endhostent`, `endnetent`, `endprotoent`, `endservent`, `gethostent`, `getnetbyaddr`, `getnetbyname`, `getnetent`, `getprotoent`, `getservent`, `sethostent`, `setnetent`, `setprotoent`, `setservent`.
  - Add socket operators: `socket`, `bind`, `listen`, `accept`, `connect`, `send`, `recv`, `shutdown`, `setsockopt`, `getsockopt`, `getsockname`, `getpeername`, `socketpair`.
  - Add Socket.pm module with socket constants and functions.
  - Add `alarm` operator with `$SIG{ALRM}` signal handling.
  - Fix `truncate` operator.
  - Add `pipe` operator.
  - Add `do \&subroutine`.
  - Add `formline` operator and `$^A` accumulator variable
  - Add file descriptor duplication support in `open` (`<&`, `>&`, `<&=`, `>&=`).
  - Add statement: `format`, and `write` operator
  - Add special variables: `@{^CAPTURE}`, `${^LAST_SUCCESSFUL_PATTERN}`.
  - Add pack format `x`.
  - Add `do filehandle`.
  - Add module `Storable`, `experimental`, `Unicode::UCD`.
  - Add single-quote as package separator.
  - Dereferencing using `$$var{...}` and `$$var[...]` works.
  - Add declared references: `my \$x`, `my(\@arr)`, `my(\%hash)`.
  - Add subroutines declared `my`, `state`, or `our`.
  - Bugfix in regex `/r`.
  - Bugfix in transliterate with octal values.
  - Bugfix in nested heredocs.
 

## v5.42.1: 150k Tests, Extended Operators, and More Perl 5 Features

  - Add operators: `getlogin`, `getpwnam`, `getpwuid`, `getgrnam`, `getgrgid`, `getpwent`, `getgrent`, `setpwent`, `setgrent`, `endpwent`, `endgrent`, `gethostbyname`, `gethostbyaddr`, `getservbyname`, `getservbyport`, `getprotobyname`, `getprotobynumber`, `reset`.
  - Add overload operators: `<=>`, `cmp`, `<`, `<=`, `>`, `>=`, `==`, `!=`, `lt`, `le`, `gt`, `ge`, `eq`, `ne`, `qr`.
  - Add command line switches: `-s`, `-f`.
  - Add `__CLASS__` keyword.
  - Add modules: `mro`, `version`, `List::Util`.
  - Add more `sprintf` formatters.
  - Add readline modes depending on `$/` special variable.
  - Add `PERL5OPT` environment variable.
  - Add regex extended character classes `(?[...])`
  - Bugfix: fixed vstring with codepoints above 65535.


## v5.42.0: 100k Tests Passed, Tie Support, and Total Compatibility
  - Add `tie`, `tied`, `untie` operators.
  - Add all `tie` types: scalar, array, hash, and handle.
  - Add operators: `sysread`, `syswrite`, `kill`, `utime`, `chown`, `waitpid`, `umask`, `readlink`, `link`, `symlink`, `rename`.
  - Add modules: `XSLoader`, `Encode`,`Config`, `Errno`, `Tie::Scalar`, `Tie::Array`, `Tie::Hash`, `Tie::Handle`, `Perl::OSType`, `Env`, `MIME::Base64`, `MIME::QuotedPrint`, `Digest::SHA`, `Digest::MD5`, `Digest`.
  - Add key-value slices: `%c{"1", "3"}`.
  - Add special variable: `$^X`.
  - Add `W`, `H`, `F`, `h`, `c`, `u`, `C0`, `U0` formats to `pack`, `unpack`.
  - Add dualvar.
  - Add `DATA` file handle.
  - Add Indirect method call.
  - Add regex variables: `${^PREMATCH}`, `${^MATCH}`, `${^POSTMATCH}`.
  - Add regex operators: `\N` not-newline, `\b{gcb}`, `\B{gcb}` boundary assertions.
  - Add regex properties supported by Perl but missing in Java regex.
  - Add command line switches: `-w`, `-W`, `-X`.
  - Process `\L`, `\U`, `\l`, `\u` in regex.
  - `Test::More` `skip` works.
  - UTF-16 is accepted in source code.
  - Add support for `pmc` files.
  - Bugfix: methods can be called in all blessed reference types.
  - Bugfix: more robust `sprintf` formatting.
  - Bugfix: string constants can be larger than 64k.
  - Bugfix: fixed foreach loops with global variables.


## v3.1.0: Tracks Perl 5.42.0
  - Update Perl version to `5.42.0`.
  - Added features: `keyword_all`, `keyword_any`

  - Accept input program in several ways:
    1. **Piped input**: `echo 'print "Hello\n"' | ./jperl` - reads from pipe and executes immediately
    2. **Interactive input**: `./jperl` - shows a prompt and waits for you to type code, then press Ctrl+D (on Unix/Linux/Mac) or Ctrl+Z (on Windows) to signal end of input
    3. **File redirection**: `./jperl < script.pl` - reads from the file
    4. **With arguments**: `./jperl -e 'print "Hello\n"'` or `./jperl script.pl`

  - Added overload operators: `!`, `+`, `-`, `*`, `/`, `%`, `int`, `neg`, `log`, `sqrt`, `cos`, `sin`, `exp`, `abs`, `atan2`, `**`, `@{}`, `%{}`. `${}`, `&{}`, `*{}`.
  - Subroutine prototypes are fully implemented. Added or fixed: `+`, `;`, `*`, `\@`, `\%`, `\$`, `\[@%]`.
  - Added double quoted string escapes: `\U`, `\L`, `\u`, `\l`.
  - Added star count (`C*`) in `pack`, `unpack`.
  - Added operators: `read`, `tell`, `seek`, `system`, `exec`, `sysopen`, `chmod`.
  - Added operator: `select(undef,undef,undef,$time)`.
  - Added operator: `^^=`.
  - Added operator: `delete`, `exists` for array indexes.
  - Added `open` option: in-memory files.
  - Syntax: identifiers starting with `::` are in `main` package.
  - Added I/O layers support to `open`, `binmode`: `:raw`, `:bytes`, `:crlf`, `:utf8`, `:unix`, `:encoding()`.
  - Add `open` support for pipe `-|`, `|-`, `ls|`, `|sort`.
  - Added `# line` preprocessor directive.
  - `Test::More` module: added `subtest`, `use_ok`, `require_ok`.
  - `CORE::` operators have the same prototypes as in Perl.
  - Added modules: `Fcntl`, `Test`, `Text::CSV`.
  - Operator `$#` returns an lvalue.
  - Improved autovivification handling: distinguish between contexts where undefined references should automatically create data structures versus where they should throw errors.
  - Bugfix: fix a problem with Windows newlines and qw(). Also fixed `mkdir` in Windows.
  - Bugfix: `-E` switch was setting strict mode.
  - BugFix: fix calling context in operators that return list.
  - BugFix: fix rules for overriding operators.
  - Added Makefile.
  - Debian package can be created with `make deb`.


## v3.0.0: Performance Boost, New Modules, and Streamlined Configuration
  - Added `--upgrade` option to `Configure.pl` to upgrade dependencies.
  - Added `Dockerfile` configuration.
  - Added `Time::HiRes`, `Benchmark` modules.
  - Added `/ee` regex modifier.
  - Added no strict `vars`, `subs`.
  - Execute the code generation on demand, for faster module loading.
  - Use `int` instead of `enum` to reduce the memory overhead of scalar variables.


## v2.3.0: Modern Perl Features, Expanded Modules, and Developer Tools
  - Project description updated in `README.md` to "A Perl Distribution for the JVM"
  - Added module porting guide at `docs/PORTING_MODULES.md`
  - Added wrapper scripts (`jperl`/`jperl.bat`) for easier command-line usage
  - Added `YAML` and `YAML::PP` modules.
  - Added `Text::Balanced` module.
  - Added `Unicode::Normalize` module.
  - Added subroutine signatures and `signature` feature.
  - Added chained operators.
  - Added stacked file test operators.
  - Added `module_true` feature.
  - Added `<<` and `<<~` Here documents.
  - Added `/p`, `/c`, `/n` regex modifiers.
  - Added regex `(?^` clear embedded pattern-match modifier.
  - Added regex `(?'name'...)` named capture groups.
  - Added regex `\k<name>` and `\g{name}` backreferences to named groups.
  - Added regex `\p{...}` and `\P{...}` for Unicode properties.
  - Added regex `\g{-n}` for relative backreferences.
  - Added regex `*+`, `++`, `?+`, `{n,m}+` possessive quantifiers.
  - Added regex `(?>...)` for atomic groups.
  - Added overload: `""`, `0+`, `bool`, `fallback`, `nomethod`.
  - Added `class` feature and `class` keyword.
  - Library upgrades.
    Maven:  `mvn versions:use-latest-versions`.
    Gradle: `./gradlew useLatestVersions`.

## v2.2.0: Core modules
  - Perl version is now v5.40.0
  - `for` loop can iterate over multiple values at the same time.
  - `for` loop variables are aliased.
  - Added `DBI` module with JDBC support.
  - Added `URI::Escape` module.
  - Added `builtin` methods: `inf` `nan` `weaken` `unweaken` `is_weak` `blessed` `refaddr` `reftype` `created_as_string` `created_as_number` `stringify` `ceil` `floor` `indexed` `trim` `is_tainted`.
  - Added command line switches: `-S`.
  - Added low-precedence xor `^^` operator.
  - Added [Configure.pl](../../Configure.pl) to set compiler options and add JDBC drivers.
  - Added Links to Perl on JVM resources in README - https://github.com/fglock/PerlOnJava/tree/master#additional-information-and-resources
  - Added [SUPPORT.md](support.md)
 
## v2.1.0: Core modules and optimization
  - Added `Getopt::Long`, `JSON` modules.
  - Optimized `print` to `STDOUT`/`STDERR` performance by running in a separate thread.
  - Added `subs` pragma.
  - Added regex `$+` variable.
  - Added command line switches: `-v`, `-V` .
  - Added file test operators: `-R`, `-W`, `-X`, `-O`, `-t`.
  - Added feature flags: `evalbytes`.
  - Added `CORE::GLOBAL` and core function overrides.
  - Added hexadecimal floating point numbers.

## v2.0.0: Towards a Complete Perl Port on the JVM
  - Added unmodified core Perl modules `File::Basename`, `File::Find`, `Data::Dumper`, `Term::ANSIColor`, `Time::Local`, `HTTP::Date`, `HTTP::CookieJar`.
  - Added `Cwd`, `File::Spec`, `File::Spec::Functions`, `HTTP::Tiny` modules.
  - "use feature" implemented: `fc`, `say`, `current_sub`, `isa`, `state`, `try`, `bitwise`, `postderef`.
  - Stash can be accessed as a hash like `$namespace::{entry}`.
  - Added stash constants:  `$constant::{_CAN_PCS} = \$const`;
  - Added `exists &sub`, `defined &sub`.
  - Added `builtin` pragma: `true`, `false`, `is_bool`.
  - Added `re` pragma: `is_regexp`.
  - Added `vars` pragma.
  - Added `SUPER::method` method resolution.
  - Added `AUTOLOAD` default subroutine.
  - Added `stat`, `lstat` operators. Some fields are not available in JVM and return `undef`.
  - Added directory operators.
  - Added regex patterns: `[[:ascii:]]`, `[[:print:]]`, `(?#comment)`, and the `/xx` modifier.

## v1.11.0: Compile-time Features
  - Added `BEGIN`, `CHECK`, `UNITCHECK`, `INIT`, `END` blocks.
  - Added subroutine hoisting: Invoking subroutines before their actual declaration in the code.
  - Improved Exporter.pm, glob assignment.
  - Added modules: `constant`, `if`, `lib`, `Internals` (`SvREADONLY`), `Carp`.
  - Added `goto &name`; not a tail-call.
  - Added `state` variables.
  - Added `$SIG{ALRM}`, `${^GLOBAL_PHASE}`.
  - Added operators: `fileno`, `getc`, `prototype`.
  - Added `\N{U+hex}` operator in double quoted strings and regex.

## v1.10.0: Operators and Special Variables
  - Error messages mimic those in Perl for consistency.
  - Added `$.`, `$]`, `$^V`, `${^LAST_FH}`, `$SIG{__DIE__}`, `$SIG{__WARN__}` special variables.
  - Added command line switches `-E`, `-p`, `-n`, `-i`, `-0`, `-a`, `-F`, `-m`, `-M`, `-g`, `-l`, `-x`, `-?`.
  - Added `select(filehandle)` operator, `ARGVOUT` filehandle.
  - Added `~.`, `&.`, `|.`, `^.` operators.
  - Added `try catch` statement.
  - Added Scalar::Util: `blessed`, `reftype`.
  - Added UNIVERSAL: `VERSION`.
  - Added v-strings.
  - Added Infinity, -Infinity, NaN.
  - Added `\N{name}` operator for named characters in double quoted strings and in regex.
  - Added lvalue subroutines.
  - CI/CD runs in Ubuntu and Windows
 
## v1.9.0: Operators and Special Variables
  - Added bitwise string operators.
  - Added lvalue `substr`, lvalue `vec`
  - Fix `%b` specifier in `sprintf`
  - Emulate Perl behaviour with unsigned integers in bitwise operators.
  - Regex `m?pat?` match-once and the `reset()` operator are implemented.
  - Regex `\G` and the `pos` operator are implemented.
  - Regex `@-`, `@+`, `%+`, `%-` special variables are implemented.
  - Regex `` $` ``, `$&`, `$'` special variables are implemented.
  - Regex performance comparable to Perl; optimized regex variables.
  - Regex matching plain strings: `$var =~ "Test"`.
  - Added `__SUB__` keyword; `readpipe`.
  - Added `&$sub` call syntax.
  - Added `local` dynamic variables.
  - Tests in `src/test/resources` are executed automatically.

## v1.8.0: Operators
  - Added `continue` blocks and loop operators `next`, `last`, `redo`; a bare-block is a loop
  - Added bitwise operators `vec`, `pack`, `unpack`
  - Added `srand`, `crypt`, `exit`, ellipsis statement (`...`)
  - Added `readdir`, `opendir`, `closedir`, `telldir`, `seekdir`, `rewinddir`, `mkdir`, `rmdir`
  - Added file test operators like `-d`, `-f`
  - Added the variants of diamond operator `<>` and special cases of `while`
  - Completed `chomp` operator; fixed `qw//` operator, `defined-or` and `x=`
  - Added modules: `parent`, `Test::More`

## v1.7.0: Performance Improvements
  - Focus on optimizing the execution engine for better performance.
  - Improve error handling and debugging tools to make development easier. More detailed debugging symbols added to the bytecode. Added `Carp` module.
  - Moved Perl standard library modules into the jar file.
  - More tests and various bug fixes

## v1.6.0: Module System and Standard Library Enhancements
  - Module system for improved code organization and reuse
  - Core Perl module operators: `do FILE`, `require`, `caller`, `use`, `no`
  - Module special subroutines: `import`, `unimport`
  - Environment and special variables: `PERL5LIB`, `@INC`, `%INC`, `@ARGV`, `%ENV`, `$0`, `$`
  - Additional operators: `die`, `warn`, `time`, `times`, `localtime`, `gmtime`, `index`, `rindex`
  - Standard library ported modules: `Data::Dumper`, `Symbol`, `strict`
  - Expanded documentation and usage examples

## v1.5.0: Regex operators
  - Added Regular expressions and pattern matching: m//, pos, qr//, quotemeta, s///, split
  - More complete set of operations on strings, numbers, arrays, hashes, lists
  - More special variables
  - More tests and various bug fixes

## v1.4.0: I/O operators
  - File i/o operators, STDOUT, STDERR, STDIN
  - TAP (Perl standard) tests

## v1.3.0: Added Objects.
  - Objects and object operators, UNIVERSAL class
  - Array and List related operators
  - More tests and various bug fixes

## v1.2.0: Added Namespaces and named subroutines.
  - Added typeglobs
  - Added more operators

## v1.1.0: Established architecture and added key features. The system now supports benchmarks and tests.
  - JSR 223 integration
  - Support for closures
  - Eval-string functionality
  - Enhanced statements, data types, and call context

## v1.0.0: Initial proof of concept for the parser and execution engine.
