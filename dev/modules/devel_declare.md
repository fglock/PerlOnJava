# Devel::Declare + B::Hooks::OP::Check Support for PerlOnJava

## Status

**Not started.** This document is a plan only; no code has been written.

`./jcpan -t Devel::Declare` currently dies during `Makefile.PL`:

```
*** Can't load dependency information for B::Hooks::OP::Check:
   Can't locate B/Hooks/OP/Check/Install/Files.pm in @INC
   (you may need to install the B::Hooks::OP::Check::Install::Files module)
```

Tracing this back:

1. `Devel::Declare`'s `Makefile.PL` calls
   `ExtUtils::Depends->new('Devel::Declare', 'B::Hooks::OP::Check')`,
   which `require`s `B/Hooks/OP/Check/Install/Files.pm` — a bookkeeping
   `.pm` that is normally generated and installed by
   `B::Hooks::OP::Check`'s own `Makefile.PL` via
   `$pkg->save_config('build/IFiles.pm')` + `pm_to_blib`.
2. On PerlOnJava, only `lib/B/Hooks/OP/Check.pm` ended up under
   `~/.perlonjava/lib/`; the `Install/Files.pm` companion was never
   produced because…
3. `./jcpan -t B::Hooks::OP::Check` itself fails:
   ```
   Can't load loadable object for module B::Hooks::OP::Check:
   no Java XS implementation available
   ```
   `B::Hooks::OP::Check` is a pure-XS module (`Check.xs` +
   `hook_op_check.h`) that wraps Perl's `PL_check` table at the C
   level. It calls `bootstrap B::Hooks::OP::Check`; there is no Java
   backend implementing `hook_op_check` / `hook_op_check_remove`, so
   every downstream consumer is dead on arrival:
   `Devel::Declare`, `B::Hooks::OP::Check::EntersubForCV`,
   `B::Hooks::OP::Check::LeaveEval`, `Future::AsyncAwait::Hooks`,
   `MooseX::Declare`, `MooseX::DeclareX`, `Exporter::Declare`,
   `POE::Declare::*`, …

Currently neither module is even logged in `dev/cpan-reports/` —
they fail too early to be tested.

## Goal

Bundle Java-backed reimplementations of `B::Hooks::OP::Check` and
`Devel::Declare` with PerlOnJava so that:

1. `use Devel::Declare` works without any CPAN install.
2. The minimal `Devel::Declare` API surface used by real-world
   consumers behaves correctly: `setup_for`, `teardown_for`,
   `get_linestr`, `set_linestr`, `get_lex_stuff`, `clear_lex_stuff`,
   `get_curstash_name`, `shadow_sub`.
3. At least the upstream `Devel::Declare` `t/*.t` suite passes (or, if
   some tests are inherently impossible on the JVM, those are
   `SKIP`ped with a clear reason).
4. `MooseX::Declare`, `Exporter::Declare`, and a couple of
   representative downstream consumers from the cpan cache install and
   pass their own minimal smoke tests.

No new Maven dependency. The work is entirely in PerlOnJava's parser
plus a Java-XS shim, plus pure-Perl façade modules in
`src/main/perl/lib/`.

## Why this is hard (architectural reality check)

`Devel::Declare` is not a normal XS extension. It is an active
**source-code rewriter** that hooks into perl's tokenizer:

- `Devel/Declare.pm` does `bootstrap Devel::Declare` and exposes
  primitives that read and rewrite the *current source line* during
  parsing.
- Inside a registered handler, user code can call
  `Devel::Declare::get_linestr()` to read the buffer that `PL_parser`
  is currently chewing on, and `set_linestr($newstr)` to splice in
  generated code that perl will then continue parsing as if the user
  had written it.
- The hook itself is wired through `B::Hooks::OP::Check`, which
  registers a callback on `OP_ENTERSUB` — when perl is about to
  compile a call to a registered declarator (e.g. `method`, `class`),
  `Devel::Declare`'s callback fires *before* the sub call is finalised
  and rewrites the source so the next thing the lexer sees is real
  Perl code.

PerlOnJava's frontend looks nothing like this:

| perl                                            | PerlOnJava                                                |
|-------------------------------------------------|-----------------------------------------------------------|
| `PL_parser->linestr` — mutable `SV *` line buf | `Lexer.input` — single `String`, eagerly tokenised then nulled (`Lexer.java:139`) |
| `PL_check[OP_*]` — per-op callback table       | No equivalent. AST is built directly in `Parser.java`     |
| `PL_keyword_plugin`                            | No equivalent. Keywords are dispatched in `StatementParser` |
| Tokenizer is reentrant; can re-read a line      | `Lexer.tokenize()` runs once, returns a `List<LexerToken>` |
| `linestr_callback` runs mid-parse              | No mid-parse callback hook                                 |

So a faithful port of `set_linestr` is impossible without a non-trivial
rewrite of the lexer to support **mutate-and-resume** parsing. The
plan below proposes a **scoped, less-than-faithful** port that is
sufficient for the way real downstream users actually call
`Devel::Declare`.

## Scope of "real-world" usage

Looking at the cpan build cache (`~/.perlonjava/cpan/build/`) and the
typical Devel::Declare consumers, the declarators in the wild fall
into three buckets:

### Bucket A — `method`/`fun`/`class`-style block declarators

```perl
method foo ($self, $x) { $self->{x} = $x }
fun add ($x, $y)       { $x + $y }
```

Used by `MooseX::Declare`, `Method::Signatures`, `Function::Parameters`
(early versions), `Exporter::Declare`. The handler reads from `(` to
the matching `)`, then locates the following `{...}` block, and
rewrites the source into something equivalent to:

```perl
sub foo { my $self = shift; my $x = shift; $self->{x} = $x }
```

Almost every real-world Devel::Declare user fits this pattern.

### Bucket B — keyword-with-prototype declarators

```perl
declare 'foo', sub { ... };
```

Used internally by `Devel::Declare::Parser` and `Exporter::Declare`'s
parsers. Same mechanism, different shape.

### Bucket C — true source-buffer wizardry

Modules that call `get_linestr`/`set_linestr` to do arbitrary text
substitution far from the declarator (`POE::Declare::HTTP::Server`'s
DSL, `MooseX::DeclareX::Plugin::singleton`, etc.). Buckets A and B
also reach into the buffer, but in a structured way; bucket C does
not.

**Strategy:** Implement Bucket A and Bucket B properly. Bucket C will
remain unsupported in v1; we'll log a clear "not implemented on
PerlOnJava" message and let the consumer fail loudly. That covers
≥90% of practical use cases (everything that just wants
`method`/`fun`/`class` keywords) without forcing us to rebuild the
lexer.

## Architecture

### Module shape

| Path | Purpose |
|------|---------|
| `src/main/perl/lib/B/Hooks/OP/Check.pm` | Pure-Perl façade. `XSLoader::load`. Already in `~/.perlonjava/lib/` from a stale CPAN install — replace with our own. |
| `src/main/perl/lib/B/Hooks/OP/Check/Install/Files.pm` | **Stub**, hand-written, satisfies `ExtUtils::Depends->load`. Returns empty `inc`/`libs`/`typemaps` so other Makefile.PLs that depend on us configure cleanly. |
| `src/main/perl/lib/Devel/Declare.pm` | Pure-Perl façade. `XSLoader::load`. Re-exports `DECLARE_NAME`/`PROTO`/`NONE`/`PACKAGE` constants, defines `import`/`unimport`. Most of its body is just dispatch into the Java side. |
| `src/main/java/org/perlonjava/runtime/perlmodule/BHooksOPCheck.java` | Java-XS shim. Registry of declarator names per package. Effectively empty `register`/`unregister` stubs that only exist so `B::Hooks::OP::Check` `use`s succeed. The real work happens in `DevelDeclare.java`. |
| `src/main/java/org/perlonjava/runtime/perlmodule/DevelDeclare.java` | Java-XS implementation: `setup_for`, `teardown_for`, `get_linestr`, `set_linestr`, `get_lex_stuff`, `clear_lex_stuff`, `get_curstash_name`, `shadow_sub`, `init`. Maintains a thread-local "current declarator context" stack. |
| `src/main/java/org/perlonjava/frontend/parser/DeclaratorRewriter.java` | New. Hook called from `StatementParser` when a token matches a registered declarator name. Invokes the user's Perl-side handler with `($name, $offset)`, then re-tokenises the rewritten line. |
| `src/test/resources/module/Devel-Declare/` | Bundled subset of upstream `t/*.t` plus a PerlOnJava-specific `t/00-method-signatures.t`. |

### The `Install/Files.pm` stub

Pure mechanical. Looking at what `ExtUtils::Depends->load` actually
calls (`->deps`, `->Inline('C')`, `inc`, `libs`, `typemaps`,
`Inline`), the stub is ~25 lines:

```perl
package B::Hooks::OP::Check::Install::Files;
use strict; use warnings;
sub deps { () }
sub Inline { () }
$INC{'B/Hooks/OP/Check/Install/Files.pm'} = __FILE__;
package B::Hooks::OP::Check::Install::Files;
our $self = {
    inc      => '',
    libs     => '',
    typemaps => [],
    deps     => [],
};
sub Inc      { '' }
sub Libs     { '' }
sub Typemaps { () }
1;
```

This single file unblocks every `Makefile.PL` that does
`use ExtUtils::Depends; ... Depends->new('Foo', 'B::Hooks::OP::Check')`
— which is every consumer downstream of B::Hooks::OP::Check.

### The `Devel::Declare` Java side — minimum viable

`DevelDeclare.java` exposes (all wired through `XSLoader`):

| Java method | Perl-visible name | What it does on PerlOnJava |
|-------------|-------------------|----------------------------|
| `setup_for(target, args)` | `Devel::Declare::setup_for` | Register declarator names + handler coderefs in a per-package registry. Registry shape: `Map<String pkg, Map<String declarator, RuntimeCode handler>>`. |
| `teardown_for(target)`    | `Devel::Declare::teardown_for` | Drop the package's registry entry. |
| `init(filename)`          | `Devel::Declare::init` | No-op (perl uses it to install the OP_CHECK hook; we install ours unconditionally at parser construction). |
| `get_curstash_name()`     | `Devel::Declare::get_curstash_name` | Read PerlOnJava's current package from the parser's symbol-table context. |
| `get_linestr()`           | `Devel::Declare::get_linestr` | Return the current-line slice from `Lexer.input`. **See "Linestr emulation" below.** |
| `set_linestr(str)`        | `Devel::Declare::set_linestr` | Splice into the line buffer; queue a re-tokenise. Only allowed inside an active declarator handler invocation. |
| `get_lex_stuff()` / `clear_lex_stuff()` | same | Get/clear the "stuff between `(` and `)`" the parser has captured for this declarator. |
| `shadow_sub(name, code)`  | `Devel::Declare::shadow_sub` | At end-of-statement, install `$name` as a CV pointing at `$code`. We do this by emitting a deferred `*$name = \&$code` after the rewritten statement is parsed. |

### Linestr emulation

This is the only genuinely hard piece. Plan:

1. **Keep the original source**. Today `Lexer.tokenize()` nulls out
   `this.input` after the first pass (`Lexer.java:139`). Stop nulling
   it (or null only when no declarators are registered for the
   current compilation unit). Memory cost is negligible — we already
   keep the full token list.
2. **Track per-token source offsets.** `LexerToken` already implies
   start/end positions via list order; explicitly store `int
   sourceStart, int sourceEnd` per token. Required for
   `get_linestr_offset` and surgical splices.
3. **Declarator interception point.** In `StatementParser`, when a
   bareword token is about to be parsed as a sub call, check the
   active-declarators registry. If the bareword matches:
   a. Compute "current line" = `input.substring(lineStart,
      lineEnd)`.
   b. Compute `offset` = position of the declarator's first character
      relative to `lineStart`.
   c. Push a `DeclaratorContext { line, offset, lineStart, lineEnd,
      stuffBetweenParens }` onto a thread-local stack.
   d. Call the user handler `$handler->($declarator_name, $offset)`.
      The handler may call `get_linestr` / `set_linestr` /
      `get_lex_stuff` / `clear_lex_stuff` against this context.
   e. If `set_linestr` was invoked, replace `input.substring(lineStart,
      lineEnd)` with the new content **and re-tokenise from
      `lineStart`**. This is the part that requires
      `Lexer.tokenize()` to be re-entrant for a single line range.
   f. Pop the context.
4. **Re-tokenisation.** Refactor `Lexer.tokenize()` to expose
   `tokenizeRange(int start, int end)` returning a fresh `List<LexerToken>`
   that we splice into the existing token stream replacing the range
   that came from the rewritten line. The public `tokenize()` becomes
   `tokenizeRange(0, input.length())`.

This is a chunkier change than typical XS-shim ports, but it is
self-contained — no AST changes, no codegen changes, just lexer/parser
glue.

### shadow_sub

`Devel::Declare::shadow_sub($name, $code)` is documented as installing
`$name` as a sub at the very moment the current compile unit finishes.
Equivalent on PerlOnJava: register a callback in the existing
end-of-compilation hook used by `B::Hooks::EndOfScope`
(`BHooksEndOfScope.endFileLoad`). When that fires, do the equivalent
of `*{$pkg.::}{$name} = $code` via the existing namespace API.

### Constants

`Devel::Declare` exports four flag constants:

```perl
use constant DECLARE_NAME    => 1;
use constant DECLARE_PROTO   => 2;
use constant DECLARE_NONE    => 4;
use constant DECLARE_PACKAGE => 9;  # 8 | DECLARE_NAME
```

Pure Perl, no XS needed — define them in the `.pm` façade.

## Phases

### Phase 0 — investigation artefacts and stubs (small, fast)

Goal: stop `jcpan -t` from blowing up at `Makefile.PL`, and get the
two modules into the compatibility report so we can track them.

Tasks:

1. Add `src/main/perl/lib/B/Hooks/OP/Check/Install/Files.pm` (the stub
   shown above).
2. Add `src/main/perl/lib/B/Hooks/OP/Check.pm` shipping our own minimal
   façade (replaces the file copied from CPAN). Body:
   ```perl
   package B::Hooks::OP::Check;
   use strict; use warnings;
   our $VERSION = '0.22';
   use XSLoader;
   XSLoader::load('B::Hooks::OP::Check', $VERSION);
   1;
   ```
3. Add `BHooksOPCheck.java` providing a minimum bootstrap so
   `XSLoader::load('B::Hooks::OP::Check', ...)` succeeds. No callbacks
   are wired yet — `register`/`unregister` are no-ops that silently
   accept their arguments. Document loudly that this is a stub.
4. Add Devel::Declare and B::Hooks::OP::Check to
   `dev/cpan-reports/cpan-compatibility.md` and the matching `.dat`
   with their actual outcome (currently `Configure failed`).
5. Verify: `./jcpan -t B::Hooks::OP::Check` now reaches `make test`,
   `t/use.t` passes (because XSLoader succeeds), and any module that
   only `use`s `B::Hooks::OP::Check` for its side-effect (rare but
   exists) configures cleanly.

This phase alone unblocks several modules whose `Makefile.PL` only
*configures-time* depends on `B::Hooks::OP::Check` without actually
calling its API.

**Estimated size:** ~150 LOC (mostly `Install/Files.pm` + a tiny Java
class). One PR. No risk.

### Phase 1 — Devel::Declare bootstrap + Bucket A declarators

Goal: `method foo ($self, $x) { ... }` (the
`Method::Signatures::Simple` / `MooseX::Declare` style) actually
works.

Tasks:

1. `Devel::Declare.pm` façade, including `import`/`unimport` and the
   four constants.
2. `DevelDeclare.java` with `setup_for`/`teardown_for`/`init` (registry
   only — no parser hook yet).
3. Lexer/Parser changes:
   - `Lexer`: stop nulling `input`; add `sourceStart`/`sourceEnd` to
     `LexerToken`.
   - Add `Lexer.tokenizeRange(int, int)`.
   - `StatementParser`: declarator interception (steps 3a–3f above).
4. `DevelDeclare.get_linestr/set_linestr/get_lex_stuff/clear_lex_stuff/
   get_curstash_name`. All operate on the top of the thread-local
   `DeclaratorContext` stack.
5. `shadow_sub` via `BHooksEndOfScope.endFileLoad` piggybacking.
6. Bundle and adapt the upstream Devel::Declare test suite under
   `src/test/resources/module/Devel-Declare/`. Skip with a clear
   `SKIP` reason any test that genuinely requires Bucket-C
   (free-form line surgery far from the declarator).
7. Add a smoke test that uses `Method::Signatures::Simple` (the
   simplest real-world consumer) to declare and call a few `method`s.
8. Run `./jcpan -t MooseX::Declare` on a feature branch and document
   what still breaks. (Expected: `MooseX::Declare` itself depends on
   `MooseX::Method::Signatures` which depends on `Devel::Declare` and
   probably also `Parse::Method::Signatures` — track which fail.)

**Estimated size:** ~800 LOC Java + ~150 LOC Perl + tests. One feature
branch, one PR.

### Phase 2 — Bucket B and downstream coverage

Goal: `Devel::Declare::Parser` and `Exporter::Declare` work; we are
green on a meaningful slice of upstream Devel::Declare consumers.

Tasks:

1. Whatever holes Phase 1's `MooseX::Declare` smoke test exposes. In
   particular: declarators that re-enter `set_linestr` multiple times
   in one handler invocation, and declarators registered from inside
   another declarator's handler.
2. `./jcpan -t Devel::Declare::Parser`,
   `./jcpan -t Exporter::Declare`, `./jcpan -t MooseX::Declare`. Land
   their results in the compatibility report.
3. Decide per-module whether failures are bugs in our shim, missing
   feature (Bucket C), or pre-existing (e.g. a Moose internals issue
   already tracked elsewhere).

**Estimated size:** Unknown until Phase 1 lands; budget ~300 LOC Java
+ targeted bug fixes.

### Phase 3 (optional) — Bucket C

Only if a specific compelling consumer needs it. Would require
proper "rewrite-and-resume" lexer surgery rather than the
single-line splice of Phase 1. Defer until there's a concrete
motivating user.

## Open questions

1. Is `B::Hooks::OP::Check`'s C ABI also used by anything *other* than
   pure-Perl callers via `Devel::Declare`? In CPAN, yes — modules like
   `Sub::Exporter::Util` build directly against the C header
   (`hook_op_check.h`) and link their own XS to it. None of those can
   work on PerlOnJava in any case (they're XS), so the
   `Install/Files.pm` stub returning empty `inc`/`libs` is safe — they
   will still fail at `dlopen` time, just with the existing
   "no Java XS implementation" message instead of the current
   `ExtUtils::Depends` configure error.
2. `set_linestr` re-entrancy. Some declarators rewrite the line, then
   call back into the parser, then rewrite again. The Phase 1 plan
   handles this naturally because re-tokenising the line resets the
   parser to step (3a) for whatever appears next. Need to confirm with
   `MooseX::Declare`'s nested declarators (`class { method ... }`).
3. Should we emit any warning when consumers call APIs we don't
   implement (e.g. `Devel::Declare::interface_offset` if we don't
   wire it)? Probably yes, gated on `JPERL_UNIMPLEMENTED=warn` like
   the rest of the codebase.
4. Is it worth a `Devel::Declare`-shaped *Plan B*: detect specific
   well-known declarator names (`method`, `fun`, `class`) at parse
   time and synthesise a built-in keyword plugin in pure Java,
   bypassing the source-rewriter entirely? Faster, simpler, but
   leaves arbitrary user declarators broken. Not recommended as a
   replacement; possibly worth keeping as a fast-path for Phase 1
   while the rewriter matures.

## Progress Tracking

### Current Status: Plan only — no implementation yet.

### Completed Phases
None.

### Next Steps
1. Land Phase 0 on its own short PR — it's mostly mechanical and
   immediately improves the cpan-compatibility report.
2. Open `feature/devel-declare` and start Phase 1 with the lexer
   `sourceStart`/`sourceEnd` annotation, since that's the prerequisite
   for everything else.

### Blockers / risks
- Lexer changes touch a hot path; need before/after `make` runs to
  confirm no measurable regression.
- Re-tokenising a range inside an existing token stream is tricky
  with the way `StatementParser` currently consumes the list — may
  need to switch the parser from `List<LexerToken>` index to a small
  cursor abstraction. Plan for that complication during Phase 1.

## References

- Upstream sources (cached):
  - `~/.perlonjava/cpan/build/B-Hooks-OP-Check-0.22-4/` — `Check.xs`,
    `hook_op_check.h`, `lib/B/Hooks/OP/Check.pm`
  - `~/.perlonjava/cpan/build/Devel-Declare-0.006022-3/` —
    `Declare.xs`, `stolen_chunk_of_toke.c`, `lib/Devel/Declare.pm`
- PerlOnJava reference ports of similar XS modules:
  - `src/main/java/org/perlonjava/runtime/perlmodule/BHooksEndOfScope.java`
    (compile-time scope-end hooks; same general shape we want for
    `shadow_sub`)
  - `dev/architecture/weaken-destroy.md` for thread-local
    bookkeeping patterns
- PerlOnJava lexer/parser entry points:
  - `src/main/java/org/perlonjava/frontend/lexer/Lexer.java`
  - `src/main/java/org/perlonjava/frontend/parser/Parser.java`
  - `src/main/java/org/perlonjava/frontend/parser/StatementParser.java`
- Skill: `.agents/skills/port-cpan-module/SKILL.md`
- Authoritative porting guide: `docs/guides/module-porting.md`
- Background investigation (ad-hoc, not yet a doc): output of
  `./jcpan -t Devel::Declare` and `./jcpan -t B::Hooks::OP::Check`
  on master @ 2c57f0469.
