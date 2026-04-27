# Text::WordCounter port plan

`Text::WordCounter` (ZBY/Text-WordCounter-0.001.tar.gz) is a tiny Moose
class on CPAN that counts and ranks words in a text. Its source is one
~80-line `.pm` file and is otherwise pure Perl. It is therefore a good
"smoke test" target for our CPAN-install pipeline. Today
`jcpan -t Text::WordCounter` fails — not because of `Text::WordCounter`
itself, but because of two transitive dependencies and one
already-fixed PerlOnJava error-reporting bug. This plan tracks the work
to get `jcpan -t Text::WordCounter` green end-to-end.

## Status snapshot (2026-04-27, updated)

| Layer                         | State                                        |
|-------------------------------|----------------------------------------------|
| `Text::WordCounter` itself    | Blocked by Unicode::UCD::charinfo (see below)|
| Misleading `require` error    | **Fixed** (PR #569)                          |
| `Lingua::ZH::MMSEG` dep       | **Fixed** via `encoding.pm` stub (PR #569)   |
| `URI::Find` dep               | **Fixed** via `$SIG{__DIE__}` parity (PR#569)|
| `Lingua::Stem` dep            | Passes                                       |
| `Module::Build::Base.pm:301`  | Could not reproduce post-fix; deferred       |
| `Unicode::UCD` from JAR       | **Open**: only 3/44 subs compile — see W5    |

After PR #569 phases 1-3:

- `jcpan -t Text::WordCounter` now successfully builds and tests
  Lingua::Stem, Lingua::Stem::Snowball::No, Lingua::Stem::Snowball::Se,
  Lingua::ZH::MMSEG, and URI::Find (URI::Find: 578/578).
- The remaining failure is at the Text::WordCounter layer itself,
  which calls `Unicode::UCD::charinfo` and dies with
  "Undefined subroutine &Unicode::UCD::charinfo called". Root cause
  identified — see Work item 5 below.

## Dependency graph

```
Text::WordCounter
├── Lingua::Stem               OK
│   ├── Lingua::Stem::Snowball::No   OK
│   └── Lingua::Stem::Snowball::Se   OK
├── Lingua::ZH::MMSEG          FAIL (encoding pragma)
├── Unicode::UCD               OK (core)
└── URI::Find                  FAIL (2 subtests)
```

## Work item 1 — `encoding.pm` pragma stub

### Symptom

```
Can't locate encoding.pm in @INC (you may need to install the encoding
module) ... at jar:PERL5LIB/Test/More.pm line 1056.
```

The CPAN module `Lingua::ZH::MMSEG` opens with:

```perl
use strict;
use warnings;
use utf8;
use Encode qw (is_utf8);
use encoding 'utf8';   # <-- this line
```

PerlOnJava ships no `encoding.pm`. The `encoding` pragma was deprecated
in Perl 5.18 and removed from core in Perl 5.26+, but a great deal of
older CPAN code (especially CJK-related modules) still loads it.

### Background

In real Perl, `use encoding 'utf8';` did three things:

1. Sets the source-code encoding for *the rest of the file* — so
   bytes in string literals are interpreted as UTF-8.
2. Sets default I/O layers on STDIN/STDOUT/STDERR.
3. (Historically) made `chr`/`ord`/`length` operate on characters
   under the supplied encoding.

(1) doesn't apply to PerlOnJava: our parser already reads source files
as UTF-8, and `use utf8;` (which `Lingua::ZH::MMSEG` already has) is
the modern way to opt in. (2) is configurable globally via
`PERL_UNICODE` / `binmode`. (3) was always considered a bad idea and
nothing modern relies on it. So functionally a **no-op stub** is
sufficient for almost every consumer that still says `use encoding`.

### Plan

1. Add `src/main/perl/lib/encoding.pm`:
   ```perl
   package encoding;
   use strict;
   use warnings;
   our $VERSION = '3.00';

   # PerlOnJava implementation of the deprecated `encoding` pragma.
   #
   # The historical pragma changed the source-code encoding for the
   # remainder of the file, optionally set I/O layers, and (in the
   # bad old days) altered chr/ord/length. PerlOnJava parses sources
   # as UTF-8 unconditionally and lets users pick I/O layers via
   # binmode / PERL_UNICODE, so this module is intentionally a no-op
   # for source-encoding effects.
   #
   # We accept the same import forms that `encoding` accepted:
   #     use encoding 'utf8';
   #     use encoding 'utf8', STDIN => 'utf8', STDOUT => 'utf8';
   #     use encoding ':locale';
   # and apply binmode to the requested filehandles when given.

   sub import {
       my ($class, $name, %args) = @_;
       return unless defined $name;          # `use encoding;` -> no-op
       # Best-effort: apply layers to listed filehandles. Anything we
       # don't understand is silently ignored, matching older code's
       # tolerance.
       for my $fh_name (keys %args) {
           my $layer = $args{$fh_name};
           next unless defined $layer;
           my $glob = do { no strict 'refs'; \*{"main::$fh_name"} };
           eval { binmode $glob, ":encoding($layer)" };
       }
       return;
   }

   sub unimport { return }   # `no encoding;` -> no-op

   1;
   ```
2. Add a tiny unit test under `src/test/resources/unit/` that exercises:
   - `use encoding;` (no args)
   - `use encoding 'utf8';`
   - `use encoding 'utf8', STDOUT => 'utf8';` (must not die)
3. Re-run `jcpan -t Lingua::ZH::MMSEG`. Its three test files should now
   compile (`t/000-load.t` first); whether `t/002-mmseg.t` /
   `t/003-fmm.t` pass is independent of the pragma — they exercise
   real segmentation logic and we can address residual failures
   separately.

### Risks / open questions

- Some callers do `use encoding 'utf8'; print "héllo\n";` and rely on
  the *implicit* `:encoding(UTF-8)` push onto STDOUT. Our stub does
  not do that for the bare-string form, so output of high-bit
  characters could come out as Wide-character warnings rather than
  proper UTF-8. If we see this in real CPAN modules we can either
  default-binmode STDOUT or recommend `use open ':std', ':utf8';`
  as the modern alternative.
- We are not faithful to the source-encoding semantics. A file
  encoded as Latin-1 with `use encoding 'latin1'` will be treated as
  UTF-8. None of our current targets need that; if one does, the
  parser would be the right place to address it, not this stub.

## Work item 2 — `Lingua::ZH::MMSEG` itself

Once `encoding.pm` is in place, the next time `t/000-load.t` is run
we will discover the next round of issues (probably none, since
the rest of the module is straight-Perl tables and a regex
segmenter). Track here.

## Work item 3 — `URI::Find` regex parity

### Symptom

```
t/Find.t  Failed tests: 355, 364     (out of 578)
```

The two failing tests are the cases where the input contains
`git://github.com/GwenDragon/uri-find.git` and
`svn+ssh://example.net`. Both use schemes that URI::Find handles via
the `extraSchemesRe` whitelist:

```perl
my($schemeRe)       = qr/[a-zA-Z][a-zA-Z0-9\+]*/;
my  $extraSchemesRe = qr{^(?:git|svn|ssh|svn\+ssh)$};
```

The pieces in isolation work correctly under `jperl`:

```perl
$ jperl -E 'my $r = qr/[a-zA-Z][a-zA-Z0-9\+]*/;
            say "ok" if "svn+ssh" =~ /^$r$/;'
ok
$ jperl -E 'my $r = qr{^(?:git|svn|ssh|svn\+ssh)$};
            say "ok" if "svn+ssh" =~ $r;'
ok
```

So the basic scheme regex *does* work. The failure is in the bigger
match in `URI::Find::find` (around line 134 of Find.pm):

```perl
$$r_text =~ s{ (.*?) (?:(<(?:URL:)?)(.+?)(>)|($uriRe)) | (.+?)$ }{
    ...
}xseg;
```

where `$uriRe` is built from `$schemeRe` and a class containing
`\s\x00-\x1f\x7F-\xFF`. Calling `$finder->find(\$text)` directly under
`jperl`:

```perl
TEXT: GwenDragon\tgit://github.com/GwenDragon/uri-find.git (fetch)
  found:   (n=0)
TEXT: URLs like svn+ssh://example.net aren't found
  found:   (n=0)
TEXT: see http://example.com here
  found: http://example.com  (n=1)
```

`http://...` is found, `git://...` and `svn+ssh://...` are not. Both
contain a tab character, the `<` / `>` alternation, or the `+` literal
in the scheme. The parity gap is one of:

1. `\+` inside a character class. Real Perl treats `[\+]` as a literal
   `+`. Java regex *also* treats `\+` in a class as literal `+` —
   but our regex translation layer might be turning it into a quantifier
   metacharacter.
2. The interaction between a non-greedy `(.*?)` early in the pattern
   and the alternation `<URL:...>|($uriRe)`. Our backtracking on
   alternation under `s///g` may give up before considering the
   `($uriRe)` branch when the leading `(.*?)` has already swallowed
   the scheme.
3. The complement character class `[^][<>"\s\x00-\x1f\x7F-\xFF]`
   contains `]`, `[`, `<`, `>`, `"` and a Latin-1 range. Mis-handling
   the high-byte range in the JVM regex engine is a known PerlOnJava
   sore spot.

### Investigation plan

1. Reduce `URI::Find::find` to a tiny standalone reproducer
   (just the `s{...}{...}xseg` invocation against the failing inputs).
   Save under `dev/regex-parity/uri-find/`.
2. Compare match traces between system `perl` and `jperl` with
   `JPERL_REGEX_DEBUG=1` (if available) on each candidate input.
3. Bisect the alternation: replace `<(?:URL:)?` branch with `(?!)` to
   force the `$uriRe` branch, and see whether the URL is found. If
   yes → the issue is alternation backtracking. If no → the issue is
   inside `$uriRe`.
4. If it's inside `$uriRe`, narrow further by replacing the
   complement class with `\S+` and seeing whether `git://...` is found.
   If yes → character-class parity. If no → scheme matching.
5. File a regex-parity issue with the minimal reproducer and link it
   from this plan.

This work item is independent of `encoding.pm` and can be picked up in
parallel.

### Resolution (PR #569)

The bisection above turned up something different: **it wasn't a
regex-parity bug at all.**

Tracing `_uri_filter` and `_is_uri` showed the failing inputs ran
through both functions correctly, but `_is_uri` returned undef with
`$@ = "Undefined subroutine &main::DEFAULT called at .../URI.pm
line 188"`. Line 188 of `URI.pm` is `eval "require $ic"` (the
implementor lookup that loads `URI::git`, `URI::svn_Pssh`, etc.).
Those scheme-implementor modules do not exist, so the eval-string
fails by design — but the failure was being dispatched through
`local $SIG{__DIE__} = 'DEFAULT';` from `URI::Find::find`, and
PerlOnJava was treating the literal string `'DEFAULT'` as the name
of a sub to invoke.

Real Perl 5 treats two literal strings in `%SIG` as reserved:

- `'DEFAULT'` — use the OS / default disposition (for `__DIE__` /
  `__WARN__`, equivalent to "no handler installed");
- `'IGNORE'`  — ignore the signal entirely (effective for
  `__WARN__`; ineffective + warns for `__DIE__`).

`WarnDie.catchEval`, `WarnDie.die` and `WarnDie.warn` were all
unconditionally invoking `RuntimeCode.apply()` on whatever was in
`$SIG{__DIE__}` / `$SIG{__WARN__}`. With the literal string
`'DEFAULT'` that became `&main::DEFAULT()`, which croaked with
"Undefined subroutine" and clobbered `$@` inside the very `eval`
that was supposed to absorb the implementor-lookup failure.

Fix in PR #569: introduce `WarnDie.isReservedSigString` and gate
the three handler invocations on `!isReservedSigString(sig)`. For
`__WARN__`, additionally honour `'IGNORE'` by suppressing the
STDERR write.

Reproducer (one-liner):

```bash
$ jperl -e 'local $SIG{__DIE__} = "DEFAULT";
            eval q{ require NoSuchModule };
            print "[\$\@=$@]"'
# before fix : [$@=Undefined subroutine &main::DEFAULT called ...]
# after  fix : [$@=Can't locate NoSuchModule.pm in @INC ...]
```

Result: `URI::Find` t/Find.t passes 578/578 subtests.

## Work item 4 — `Module::Build::Base` line 301 warning

```
Use of uninitialized value in join or string at
/Users/fglock/.perlonjava/lib/Module/Build/Base.pm line 301.
```

Line 301 is `$p->{original_prefix}{site} ||= $p->{original_prefix}{core};`
— the `||=` evaluates the RHS before the `||` short-circuit decision
can be made, and `original_prefix` is not seeded before this code
runs.

This is purely cosmetic but appears on every Build.PL run, including
all the `Snowball-Norwegian` / `Snowball-Swedish` / `URI-Find`
invocations. Fix is one line: ensure `$p->{original_prefix}` is
populated (with at least `core`) before the `||=` chain runs. Track
under the broader `module_build` plan if one exists; not blocking
`Text::WordCounter`.

After the PR #569 fixes the warning could no longer be reproduced
on this codepath; deferred.

## Work item 5 — `Unicode::UCD` lazy-loading bug (NEW BLOCKER)

After Work items 1–3 ship, `jcpan -t Text::WordCounter` advances all
the way to running `t/word_count.t`, which dies with:

```
Undefined subroutine &Unicode::UCD::charinfo called at
/Users/fglock/.cpan/build/Text-WordCounter-0.001-0/blib/lib/Text/WordCounter.pm
line 56, <DATA> line 161435.
```

The bundled `Unicode/UCD.pm` is identical on disk and inside the
shaded jar (md5 match, 187,640 bytes, 4824 lines, 44 `sub` decls).
But behaviour depends on **how it is loaded**:

| Load method                                                    | Subs defined |
|----------------------------------------------------------------|--------------|
| `do "/abs/path/to/Unicode/UCD.pm"`                             | 44 (all)     |
| `require "/abs/path/to/Unicode/UCD.pm"` (absolute string form) | 44 (all)     |
| `require Unicode::UCD;` (module form, resolves to JAR)         | **3**        |
| `use Unicode::UCD;` (module form, resolves to JAR)             | **3**        |
| `use Unicode::UCD qw(charinfo);` (one explicit import)         | 4            |

The 3 subs that survive are `all_casefolds`, `prop_invlist`,
`prop_invmap` — all of which carry sub prototypes (`()`, `($;$)`).
But subs with prototypes earlier in the file (e.g.
`charprop ($$;$)`) are dropped, so prototype-vs-no-prototype is not
the distinguishing factor.

`use Unicode::UCD qw(charinfo)` adds *just* `charinfo` (and
similarly for any single explicit import name). That suggests the
loader is selectively materialising only the subs that Exporter is
asked for, plus the three "always there" ones. This is
suspicious-looking and warrants a separate investigation.

### Investigation plan

1. Confirm whether the JAR vs disk path is the actual axis. Build
   a tiny module of our own with a known sub list, ship it inside
   the jar, and load it via the same paths.
2. Diff the bytes after `code = content.toString();` in
   `ModuleOperators.java:589-600` (jar path) vs
   `FileUtils.readFileWithEncodingDetection(...)` (disk path).
   Both should produce the same source string.
3. Check whether `parsedArgs.fileName` of `jar:PERL5LIB/...` form
   is interacting with `BHooksEndOfScope`, `B::Hooks::EndOfScope`,
   or any source-filter machinery that aborts compilation early.
4. Check whether `use feature 'unicode_strings';` *inside* a sub
   body (line 985 of UCD.pm) is interpreted differently when the
   surrounding source is loaded from the JAR; that pragma is
   active *during* the execution of a few of the failing subs.
5. Bisect by truncating UCD.pm in-place (in a copy) until the
   "load via require X::Y" path matches the "load via abs path"
   path.

### Workaround for Text::WordCounter

`Text::WordCounter::split_scripts` only uses `charinfo($ord)->{script}`.
If Work item 5 takes time, a one-line Perl-side workaround is
acceptable: ship a small `Unicode/UCD.pm` shim that delegates
`charinfo` to a Java-backed implementation built on
`java.lang.Character.UnicodeScript.of(codepoint)`. That gets
Text::WordCounter green without solving the underlying loader bug.
This workaround is intentionally NOT in PR #569 — the loader bug
deserves its own diagnosis first.

## Verification

Once the above are addressed, the success criterion is:

```bash
./jcpan -t Text::WordCounter
# Result: PASS for all of:
#   Lingua::Stem
#   Lingua::Stem::Snowball::No
#   Lingua::Stem::Snowball::Se
#   Lingua::ZH::MMSEG
#   URI::Find
#   Text::WordCounter
```

with no `Can't locate ... .pm in @INC` warnings and no
"Use of uninitialized value" noise from `Module::Build::Base`.

## Progress tracking

### Current status: Work items 1, 2, 3 complete in PR #569; Work item 5 newly identified

### Completed
- [x] PR #569 commit 1 — preserve `$@`/`$!` across `on_scope_end`
      callbacks (commit `4850e9a83`). Fixes the misleading
      "Can't locate <outer-module>.pm" reports.
- [x] PR #569 commit 2 — `encoding.pm` no-op stub +
      `encoding_pragma.t`. Unblocks `Lingua::ZH::MMSEG` (3/3 tests
      pass).
- [x] PR #569 commit 3 — `WarnDie.isReservedSigString` for
      `$SIG{__DIE__}` / `$SIG{__WARN__} = 'DEFAULT'|'IGNORE'`.
      Unblocks `URI::Find` (578/578 tests pass).

### Next steps
1. **Work item 5** (Unicode::UCD lazy-loading from JAR) — diagnose
   why module-form require loads only 3/44 subs. Likely a
   compile-time short-circuit on the JAR-resource code path.
2. Optional fall-back if W5 is large: ship a `Unicode/UCD.pm` shim
   that delegates `charinfo->{script}` to Java's
   `Character.UnicodeScript`.
3. Revisit Module::Build::Base line 301 cosmetic warning if it
   resurfaces under any future repro.

### Open questions
- Is Work item 5 specific to Unicode::UCD's particular shape, or
  is any sufficiently-large module loaded from the JAR similarly
  affected? A regression-test-friendly minimal repro is needed.
- Should `encoding.pm` default-binmode STDOUT to UTF-8 to match
  the implicit behaviour of the original pragma, or stay strictly
  no-op? Current PR keeps it no-op for the source-encoding path,
  but does honour explicit filehandle layers.

## Related

- PR #569 — error-reporting fix that exposed the real failures
- `dev/modules/cpan_patch_plan.md` — general CPAN patching strategy
- `.agents/skills/port-cpan-module/SKILL.md` — porting workflow
