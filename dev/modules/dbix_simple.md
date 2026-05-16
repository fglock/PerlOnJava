# jcpan DBIx::Simple stabilization

## Overview

`./jcpan -t DBIx::Simple` exercises the vendored OO DBI wrapper bundled under
[`src/main/perl/lib/DBIx/Simple.pm`](../../src/main/perl/lib/DBIx/Simple.pm).
The [`jcpan`](../../jcpan) launcher prepends that tree to `PERL5LIB`, so jcpan runs
prefer the JDBC-aware fork over stale copies under `~/.perlonjava/lib`.

## Symptoms

| Layer | When it bites | Symptoms |
| ----- | ------------- | ------- |
| A. Wrong `@INC` / old `Simple.pm` | `./jperl` without bundled `PERL5LIB`; first hit is `~/.perlonjava/lib/DBIx/Simple.pm` | `keep_statements` stays **16**, `old_statements` recycling clashes with JDBC `finish`/`execute_result` semantics → **`execute` on undef** after chained queries, bogus row counts |
| B. JVM method-chain lifetime | Bundled Simple (`keep_statements == 0` on JDBC) | **`scalar $db->query($sql)->arrays`** returns **too few rows** vs **`my $r = $db->query($sql); scalar $r->arrays`** for the same SQL |
| C. JDBC `fetchrow_hashref` | Upstream **`t/sqlite.t`** with `lc_columns` (default hash keys **`foo`** not **`FOO`**) | **`fetchrow_hashref`** ignored **`NAME_lc`** / **`'NAME_lc'`** argument — hashes missing expected keys |

Layer B points at PerlOnJava **`scalar` / `->` / mortal or refcount boundaries**, not DBIx `wantarray` in `arrays` alone.

## Reproduction

Always prefer the same library order jcpan uses:

```bash
export PERL5LIB="$PWD/src/main/perl/lib${PERL5LIB:+:$PERL5LIB}"
timeout 120 ./jperl dev/tools/dbix_simple_chain_repro.pl
```

Smoke compare with system ordering (may load `~/.perlonjava/lib`):

```bash
timeout 120 ./jperl dev/tools/dbix_simple_chain_repro.pl
```

Minimal inline check (`keep_statements` must be **0** on JDBC):

```bash
PERL5LIB="$PWD/src/main/perl/lib" ./jperl -e 'use DBIx::Simple; use DBI; \
  my $db=DBIx::Simple->connect(q{dbi:SQLite:dbname=:memory:},"","",{RaiseError=>1}); \
  print "keep=".$db->keep_statements."\n"'
```

Full module test harness (agents: **wrap jcpan** in `timeout`; capture TAP to a file).
Bundled **`DBIx/Simple.pm`** skips upstream **`t/`** unless you force it:

```bash
JCPAN_RUN_BUNDLED_TESTS=1 timeout 3600 ./jcpan -t DBIx::Simple > /tmp/jcpan_dbix_simple.txt 2>&1
echo EXIT:$? >> /tmp/jcpan_dbix_simple.txt
```

## JDBC /statement notes (bundled fork)

SQLite on PerlOnJava uses JDBC. Recycling finished statement wrappers while the
same `PreparedStatement` is reused is unsafe; bundled `Simple.pm` forces
`keep_statements = 0` when [`DBI::_is_jdbc_handle`](../../src/main/perl/lib/DBI.pm)
returns true (`connection`/`statement`/`ImplementorClass` heuristics).

## Resolution log

| Date | Change |
| ---- | ------ |
| 2026-05-16 | Added this doc + `dev/tools/dbix_simple_chain_repro.pl`; jcpan **`PERL5LIB`** prepend documented upstream in `jcpan` comments. **Layer B**: blessed method-call invocant **refcount hold** across `dispatchPerlMethodAfterSelfInjected` / `callCachedInner` (`RuntimeCode.java`). **Layer C**: JDBC **`DBI.fetchrow_hashref`** honors the optional **second argument** (`NAME_lc`) like `DBI.pm`, fixing **`t/sqlite.t`** hash key expectations under bundled tests. |

## `RuntimeCode.coerceScalarCallResult`

Collapsing multi-return lists at subroutine boundaries matches Perl semantics
and avoids late `RuntimeList.scalar()` on intermediates (see inline comment in
`RuntimeCode`). It alone did **not** fix Layer B; keep it unless regressions justify
narrowing/reverting after the JDBC chain fix lands.

## Next steps

### Implemented in this iteration (carry forward checklist)

| Item | Status |
| ---- | ------ |
| Document layers A/B/C + repro + bundled jcpan knobs | ✓ [`dev/modules/dbix_simple.md`](dbix_simple.md) |
| Repro harness | ✓ [`dev/tools/dbix_simple_chain_repro.pl`](../../dev/tools/dbix_simple_chain_repro.pl) |
| Layer B — blessed invocant refcount across method dispatch | ✓ [`RuntimeCode.java`](../../src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java) |
| Layer C — JDBC `fetchrow_hashref($sth, 'NAME_lc')` | ✓ [`DBI.java`](../../src/main/java/org/perlonjava/runtime/perlmodule/DBI.java) |
| Regression test for Layer C (`NAME_lc` keys) | ✓ [`unit/dbi_fetchrow_hashref_name_lc.t`](../../src/test/resources/unit/dbi_fetchrow_hashref_name_lc.t) |

### After merge / ongoing

1. **CI / review** — Ensure PR **`make`** and any Cloud CI workflows are green before merge.

2. **Bundled jcpan smoke** — Default `./jcpan -t DBIx::Simple` still skips upstream `t/` when `Simple.pm` is bundled; for full parity use:
   ```bash
   JCPAN_RUN_BUNDLED_TESTS=1 timeout 3600 ./jcpan -t DBIx::Simple > /tmp/jcpan_dbix_simple.txt 2>&1
   ```

3. **Interpreter parity** (only if regressions reported) — Run the chain repro under `./jperl --interpreter` and compare row counts vs JVM.

4. **Optional follow-ups** — If Layer B surfaces again elsewhere, revisit `scalar`/method-chain lowering (e.g. `Dereference` / late `scalar()` collapse) alongside `MortalList` boundaries; extend unit coverage for `FetchHashKeyName` / `NAME_uc` only if bugs appear.

## References

- [`AGENTS.md`](../../AGENTS.md) — jcpan/`timeout`, no orphan JVMs.
- [`dev/modules/jcpan_bundled_dbd_and_convert_ber.md`](jcpan_bundled_dbd_and_convert_ber.md) — bundled-module test knobs.
