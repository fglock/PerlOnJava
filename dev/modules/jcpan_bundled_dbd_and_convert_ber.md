# jcpan: bundled-DBD test skip + Convert::BER triage

Context: see PR adding `src/main/perl/lib/DBI/DBD.pm` (the configure-time
shim that lets CPAN DBD::* `Makefile.PL` files load under jperl). After
that fix, `jcpan -t DBD::JDBC` reaches `make test`, but two follow-on
problems remain:

1. **(a) Upstream test suite vs. PerlOnJava-bundled driver mismatch.**
   `jcpan -t DBD::JDBC` runs the *upstream* CPAN tarball's tests
   (`t/01_env.t`, `t/02_connect.t`, `t/03_hsqldb.t`). Those drive the
   real DBD::JDBC architecture: a separate Java *proxy server* spawned
   from Perl, talking BER over a socket. PerlOnJava's bundled
   `DBD::JDBC` is a completely different design (in-JVM driver
   registered from Java, no socket, no BER). The upstream tests will
   never pass against our shim — they call e.g. `DBI->internal` and
   spawn `dbd_jdbc.jar` as a subprocess. Same situation for any future
   bundled DBD::* driver.

2. **(b) Convert::BER fails ~44% of its own subtests under jperl.**
   That is independent of DBD::JDBC, but DBD::JDBC declares
   `Convert::BER 1.31` as a prereq, so jcpan pulls Convert-BER-1.32 in
   first and its `make test` fails (and `t/07io.t` exits 137 after a
   300s timeout). This is a real correctness/perf bug somewhere in
   pack/unpack, IO, or regex under jperl.

This document plans both lines of work.

---

## Phase A — Skip `make test` for bundled DBD::* (and similar shimmed dists)

### Goal

When the primary `.pm` of a CPAN distribution is already bundled in the
PerlOnJava JAR (and was therefore SKIPped at install time), make
`jcpan -t <Module>` succeed without running the upstream test suite,
while still printing a clear notice that we did so.

### Current behaviour

In `src/main/perl/lib/ExtUtils/MakeMaker.pm`:

- Around line 353, the install loop already detects "this `.pm` is
  bundled in the JAR" by checking `-f "jar:PERL5LIB/$rel"` and prints:
  `SKIP: $rel (bundled in PerlOnJava JAR)`.
- Around line 522, the `test::` Makefile target is generated
  unconditionally as a `prove`-style command running `t/*.t`.

There is currently no signal carried from the SKIP loop into the
generated `test::` rule.

### Proposed change

1. While iterating the SKIP loop, set a flag
   `$primary_pm_bundled` when the SKIPped path matches the dist's
   primary module path (derived from `NAME`, e.g. `DBD::JDBC` →
   `DBD/JDBC.pm`). A secondary file like `Bundle/DBD/JDBC.pm` or
   `DBD/JDBC.pod` should NOT trigger the flag.

2. When `$primary_pm_bundled` is true, replace `$test_cmd` with a
   no-op that prints a clear notice, e.g.:

   ```
   $perl -e 'print "PerlOnJava: $name is bundled in the JAR; skipping upstream test suite (incompatible architecture).\n"'
   ```

   and additionally emit a `noop` `test_dynamic::` / `test_static::`
   rule so old harnesses that target those don't fall through to
   `make`'s implicit rules.

3. Honour an opt-out env var `JCPAN_RUN_BUNDLED_TESTS=1` so a
   developer can still force the upstream suite to run when
   investigating divergence between bundled and CPAN behaviour.

4. Print the SKIP-test notice at *configure* time too (next to the
   existing `SKIP: ... (bundled in PerlOnJava JAR)` line), so
   `jcpan -t` users see why testing was skipped before they wait for
   `make test` to start.

### Files touched (Phase A)

- `src/main/perl/lib/ExtUtils/MakeMaker.pm`
  - Track `$primary_pm_bundled` in the existing SKIP loop.
  - In `_create_install_makefile`, switch `$test_cmd` to the no-op
    when the flag is set (unless `JCPAN_RUN_BUNDLED_TESTS=1`).
  - Update the configure-time notice.
- `dev/modules/makemaker_perlonjava.md`: short note in the existing
  doc pointing at the bundled-skip behaviour.

### Verification (Phase A)

- `./jcpan -t DBD::JDBC` → exits 0, prints "bundled; skipping upstream
  tests" notice, no attempt to spawn `dbd_jdbc.jar`.
- `./jcpan -t DBD::SQLite` (also bundled) → same.
- `JCPAN_RUN_BUNDLED_TESTS=1 ./jcpan -t DBD::JDBC` → still runs the
  upstream suite (and still fails — the env var exists for diagnosis,
  not for CI).
- `./jcpan -t Try::Tiny` (NOT bundled) → unchanged behaviour, real
  tests still run.
- `make` (full unit suite) still green.

### Risk / edge cases

- Distributions that bundle the primary `.pm` only as a *facade* over
  XS (where the JAR shim would actually be incomplete) — none known
  for currently-bundled DBDs, but worth a comment in code.
- `$primary_pm_bundled` detection must be robust to `NAME =>
  'Foo-Bar'` (dist-style) vs `'Foo::Bar'` (module-style); existing
  code already normalises this for `(my $distname = $name) =~
  s/::/-/g;` — reuse the same logic.

---

## Phase B — Triage `Convert::BER` test failures

### Goal

Identify the root cause(s) of the Convert::BER failures and decide
between: (1) fix in jperl runtime, (2) ship a Convert::BER-specific
shim/patch, or (3) document as known-broken and add an exclusion.

### Failure summary (from `jcpan -t DBD::JDBC` run, 2026-04-29)

```
t/00prim.t        90 tests, 36 failed   — ints 4-6, 10, 14-15, 19-20, 24-25, ...
t/01basic.t       19 tests, 13 failed   — 4-9, 13-19
t/02seq.t          5 tests,  1 failed   — test 5
t/03seqof.t       19 tests, 19 failed   — ALL
t/04comp.t         1 test,   1 failed
t/05class.t       21 tests, 10 failed   — 4-5, 9-10, 16-21
t/06opt.t          6 tests,  3 failed   — 2-4
t/07io.t           5 tests, exit 137 after 300s timeout (hang)
t/09hightags.t   213 tests, 93 failed
```

`t/08tag.t` passes cleanly. The pattern (early "constant" tests pass,
later "encode integer", "sequence-of", "high-tag" fail) is consistent
with a `pack`/`unpack` template bug or a `chr`/`ord` byte-vs-codepoint
issue, since BER is purely a byte-string codec.

### Plan

1. **Reproduce in isolation.**
   Unpack Convert-BER-1.32 to `/tmp/Convert-BER-1.32` and run
   `t/00prim.t` directly under `./jperl` and under system `perl`.
   Capture output of the *first* failing subtest with full diagnostic
   (each test in 00prim is an `is_deeply` of an encode/decode pair —
   the diff will name exact bytes).

2. **Classify the first failure.**
   Likely categories:
   - `pack 'w'` (BER compressed integer) — Convert::BER does NOT use
     pack-w, but our equivalents may diverge.
   - `pack 'C*'` round-tripping non-ASCII bytes through a Perl
     scalar — known sore spot when string is upgraded to UTF-8 mid
     pipeline.
   - `unpack 'C'` on a substring vs. `ord(substr(...))`.
   - `vec` operations on a byte buffer.
   Read `Convert/BER.pm` `_encode_integer` / `_decode_integer` /
   `_encode_length` and compare bytewise.

3. **`t/07io.t` hang.** It's only 5 subtests but hits 300s. Almost
   certainly a `read`/`sysread` loop on a `pipe`/`socketpair` that
   never returns EOF under jperl. Run under `JPERL_OPTS=-Xss256m
   ./jperl t/07io.t` standalone and inspect with a short timeout +
   stack dump.

4. **Pick one fix per category and verify by re-running the affected
   `t/*.t`.**
   Where the bug is in jperl's runtime (likely for any byte-string
   issue), add a focused unit test under `src/test/resources/unit`
   reproducing it in 5–10 lines of Perl (no Convert::BER dependency)
   before patching the Java side, per `AGENTS.md` workflow.

5. **Decide on Convert::BER status.**
   - If we land jperl fixes: re-run `jcpan -t Convert::BER` and aim
     for full PASS, so DBD::JDBC's prereq resolution succeeds cleanly
     even though we'll have skipped DBD::JDBC's own tests in Phase A.
   - If a fix is too invasive for now: add Convert::BER to whatever
     "known-failing-prereq" allowlist `jcpan` uses (or document the
     workaround `cpan> force test Convert::BER`), and link to this
     doc.

### Files possibly touched (Phase B)

Depends on classification. Likely candidates:
- `src/main/java/org/perlonjava/operators/Pack.java` /
  `Unpack.java` — if a template variant misbehaves.
- `src/main/java/org/perlonjava/runtime/RuntimeScalar.java` — for
  byte-string upgrade edge cases.
- `src/main/java/org/perlonjava/io/*` — for the t/07io.t hang.
- `src/test/resources/unit/<minimal>.t` — regression test.
- `dev/modules/` — short note recording what was found, even if no
  fix lands this round.

### Verification (Phase B)

- `cd /tmp/Convert-BER-1.32 && /Users/fglock/projects/PerlOnJava3/jperl
  -Iblib/lib t/00prim.t` → all 90 ok.
- Same for the other failing files; `t/07io.t` either passes or
  reaches its plan within ~5s.
- `make` still green (no new unit-test regressions).
- `./jcpan -t DBD::JDBC` end-to-end:
  - With Phase A only: Convert::BER prereq fails its own test, but
    DBD::JDBC test stage is skipped → exit 0 (or whatever jcpan does
    when a prereq's tests failed; document it).
  - With Phase B fixes too: Convert::BER tests pass; whole pipeline
    is green.

---

## Sequencing and ownership

1. Land Phase A first. It's small, mechanical, and unblocks any
   current/future bundled DBD::* from tripping `jcpan -t`.
2. Phase B is open-ended (depends on what the first Convert::BER
   diff reveals). Track its progress in this doc under a
   "Progress Tracking" section per AGENTS.md.

## Progress Tracking

### Current Status: planning

### Completed
- (preliminary) `src/main/perl/lib/DBI/DBD.pm` shim added so
  `jcpan -t DBD::JDBC` clears configure step.

### Next Steps
1. Phase A: bundled-test SKIP in MakeMaker shim.
2. Phase B step 1: reproduce Convert::BER first failure in isolation.

### Open Questions
- Should the bundled-test SKIP also short-circuit `make` (build) for
  these dists, or only `make test`? Today `make` already succeeds
  cheaply (no XS), so probably leave it alone.
- For Phase A, is there value in writing a tiny `t/00-bundled.t`
  inside the JAR-bundled DBD modules so `jcpan -t` *does* run a
  smoke-test of the bundled driver (rather than nothing) when the
  upstream suite is skipped? Defer.
