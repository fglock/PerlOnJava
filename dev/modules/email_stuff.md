# Email::Stuff — `./jcpan -t Email::Stuff`

## Status: PARTIALLY WORKING (4 PerlOnJava bugs fixed; 1 macOS-specific blocker remains)

```bash
./jcpan -t Email::Stuff
```

System Perl baseline: 60/61 tests pass; the single remaining failure is an
upstream `Email::MIME` quoting style change (`name=README` vs.
`name="README"`) and is unrelated to PerlOnJava.

PerlOnJava current status:

| Distribution        | make | make test | Notes |
|---------------------|------|-----------|-------|
| `MailTools`         | OK   | **PASS** (109/109) | All tests pass |
| `Return::Value`     | OK   | PASS (98/98) | |
| `Email::Send`       | OK   | FAIL (89/90; 1 sub) | `t/sendmail.t` chained shebang on macOS |
| `Email::Send::Test` | OK   | (not run; same dist as Email::Send) | |
| `File::Type`        | OK   | PASS (58/58) | |
| `prefork`           | OK   | PASS | |
| `Email::Stuff`      | OK   | FAIL | cascade — `Email::Send` not in @INC |

The single remaining failure is the macOS-specific chained-shebang blocker;
on Linux the chain works and the entire test suite would pass.

## Dependency chain

```
Email::Stuff               (RJBS/Email-Stuff-2.105)
├── Email::Send            (RJBS/Email-Send-2.202)
│   ├── Mail::Internet     (MARKOV/MailTools-2.22) [build_requires]
│   └── Return::Value      (RJBS/Return-Value-1.666005)
├── Email::Send::Test      (bundled with Email::Send)
└── File::Type             (PMISON/File-Type-0.22)
```

`Mail::Internet`, `Email::Send`, and `Email::Stuff` are all old (~2008–2014)
and rely on idioms that are now-discouraged but still valid Perl.

## Issues fixed (this branch — `fix/email-stuff-build`)

### 1. `MakeMaker.pm`: missing `ppd::` target
**File:** `src/main/perl/lib/ExtUtils/MakeMaker.pm`

`MailTools/Makefile.PL`'s `MY::postamble` adds `all:: ppd` (real
ExtUtils::MakeMaker generates a Win32 PPM `.ppd` descriptor here).
PerlOnJava never emitted a `ppd` rule, so `make` died with
`*** No rule to make target 'ppd'`, blocking the entire chain.

Added a no-op `ppd::` target plus `.PHONY` entry.

### 2. `SubroutineParser`: `new Class or ...` syntax error
**File:** `src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java`

`my $x = new Foo or print "not "` produced
`syntax error ... near "or print "`. The indirect-object branch saw an
infix operator after the class name and backtracked past the class,
collapsing the call to a bare `new` identifier and confusing the outer
parser.

Now: when `new Class` is followed by an `INFIX_OP` (`or`, `and`, `||`,
`&&`, …) or a statement terminator (`;`, `)`, `}`, `]`, `,`, `?`, `:`, EOF),
parse it as a zero-argument `Class->new()` and let the outer parser
handle the operator. `->` and `=>` are excluded.

This idiom appears verbatim in `MailTools/t/mailer.t`, `t/send.t`, and
`Mail::Mailer::new`.

### 3. `send` not on `OVERRIDABLE_OP`
**File:** `src/main/java/org/perlonjava/frontend/parser/ParserTables.java`

`Email::Send` exports a sub named `send` and is used as
`use Email::Send 'Test'; send(Test => $msg);`. PerlOnJava was hard-coding
the prototype of the *socket* `send` builtin (`*$$;$`) and rejecting the
imported sub's call with `Not enough arguments for send` /
`Too many arguments for send`.

Real Perl honours typeglob assignment from Exporter as an override
(this is exactly how `CORE::GLOBAL::send` is supposed to work). Added
`send` to `ParserTables.OVERRIDABLE_OP`. This unblocks 5 Email::Send
test files (`t/abstract-msg.t`, `t/all-mailers.t`, `t/classic.t`,
`t/errors.t`, `t/without.t`).

### 4. `base.pm`: spurious "package already loaded" detection
**File:** `src/main/java/org/perlonjava/runtime/perlmodule/Base.java`

`Mail::Mailer::new` does `$class->SUPER::new` where the calling-package
`@ISA` chain leads to `IO::Handle`. PerlOnJava's `Base.importBase`
considered `IO::Handle` "already loaded" because the Java backend
pre-registers a handful of bridge stubs (`IO::Handle::_sync`, etc.) in
the global code-ref map — so `use base 'IO::Handle'` skipped the
`require IO::Handle` and `IO::Handle::new` was never defined.

Realigned with real Perl's `base.pm` logic: only `@ISA` or `$VERSION`
counts as "loaded"; otherwise attempt `require`. If the require
fails with a "Can't locate ... .pm in @INC" / "not found" error AND
the package nevertheless has code refs (Java bridge stubs OR
eval-created classes like DBIC's `t/inflate/hri.t`), accept the
in-memory package — preserving the DBIC fix from earlier.

### 5. `\L\u$1` in regex replacement / interpolated strings
**File:** `src/main/java/org/perlonjava/frontend/parser/StringDoubleQuoted.java`

`s/\b(\w+)/\L\u$1/g` on `spickett@tiac.net` produced `spickett@tiac.net`
(no change) instead of `Spickett@Tiac.Net`. The case-modifier stack
was wrapping the inner single-char modifier first and the outer region
modifier on top — yielding `lc(ucfirst($1))` (which lowercases the
freshly-uppercased first char). Real Perl applies modifiers
per-character left-to-right; for `\L\u$1` the first char gets `\u`
(wins over `\L`), the rest get `\L`, equivalent to
`ucfirst(lc($1))`.

Fixed `applyCaseModifier`: when applying a single-char modifier
(`\u`/`\l`) inside a region modifier (`\L`/`\U`/`\F`/`\Q`), pre-wrap
the segment with the outer's case function first, then wrap with the
single-char function, and remove those segments from the outer's
tracking so they aren't re-wrapped.

This fixes 6 subtests in `MailTools/t/extract.t` (used by `Mail::Address->name()`
which case-folds extracted names with `s/\b(\w+)/\L\u$1/igo`).

## Remaining blocker: chained-shebang on macOS

### 6. `Email::Send/t/sendmail.t`
**Symptom (1 subtest of 11 fails):**
```
t/temp.../executable: line 4: syntax error near unexpected token `;'
t/temp.../executable: line 4: `my $input = join '', <STDIN>;'
#   Failed test 'cannot check sendmail log contents' at t/sendmail.t line 120.
```

The test writes a fake `sendmail` shell script with `#!$^X\n` (where
`$^X` is the path to `jperl`, which is itself a `#!/bin/bash` wrapper
script), `chmod 0755`s it, and execs it.  macOS does **not** support
multi-level shebang (verified locally: a `#!/bin/bash` interpreter
script for a wrapper for our binary causes the kernel to return ENOEXEC,
and the calling shell falls back to interpreting the original file as
bash). Linux behaviour is the same — both fall back, but bash's fallback
is to run the script as bash code, which makes `my $input = …` a
syntax error.

This is a `jperl`-as-script-launcher issue. Real fixes would be:
(a) replace the bash wrapper with a tiny native binary launcher
    (`jpackage`, hand-written C, or rust);
(b) install a `binfmt_misc` rule on Linux only (still doesn't help macOS);
(c) intercept inside `Email::Send::Sendmail::send` (won't do — modifying
    tests / installed module code is forbidden per AGENTS.md).

For now, **out of scope** for this branch — Email::Stuff itself does
*not* exec the temp sendmail script; only the upstream `t/sendmail.t`
does. Once a native launcher exists, this test will pass and so will
the cascade.

The cascade is unfortunate: CPAN.pm marks `Email::Send` as
`make_test => NO` after the single subtest failure, so it does not add
its `blib/lib` to `@INC` for `Email::Stuff`'s tests, which then fail
with `Can't locate Email/Send.pm in @INC`.

### Future-proofing options for item 6

1. **Native binary launcher** — most robust. Add a small C or Rust
   `jperl-bin` that does `execve("java", ["-jar", JAR_PATH, …])`. The
   bash wrapper can stay as a convenience for users; `$^X` points at
   the binary instead.

2. **Detect bash-fallback inside the wrapper** — not possible; bash
   never re-invokes our wrapper when the kernel returns ENOEXEC.

3. **Patch `cpan_random_tester.pl`-style harness** to add a
   shebang-rewrite filter — invasive, doesn't help running tests
   manually.

## Notes on test ordering

`./jcpan -t` runs each prerequisite's `make test` and only adds its
`blib/lib` to the next module's `@INC` if the test passed. That's why
**any** `Email::Send` test failure cascades into `Can't locate
Email/Send.pm in @INC` when `Email::Stuff`'s tests run.

## Progress tracking

### Completed
- [x] System-perl baseline confirmed (60/61).
- [x] Item 1 — `ppd` Makefile target.
- [x] Item 2 — `new Class or ...` parse fix.
- [x] Item 3 — `send` override.
- [x] Item 4 — `use base 'IO::Handle'` actually `require`s now.
- [x] Item 5 — `\L\u$1` case modifier ordering.

### Open
- [ ] Item 6 — chained shebang in `t/sendmail.t` (needs native launcher;
      out of scope for this branch).

### Net effect
Without item 6 fixed:
- `MailTools` `make test`: was crashing → now PASS (109/109).
- `Email::Send` `make test`: was crashing on most files → now 89/90.
- `Email::Stuff` `make test`: blocked by item 6 cascade.

With item 6 fixed (Linux/once we have a native launcher):
- All distributions in the chain expected to PASS, mirroring the
  system-perl 60/61 baseline.

