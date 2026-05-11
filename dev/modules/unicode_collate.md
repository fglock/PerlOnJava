# Unicode::Collate on PerlOnJava — plan

## Goals (PerlOnJava use case)

1. **Bundle the real module** from the Perl 5 tree (`perl5/cpan/Unicode-Collate/`) via `dev/import-perl5/sync.pl` — same layout as other core-ish CPAN imports (`Unicode/Collate.pm`, `Unicode/Collate/**`, `allkeys.txt`).
2. **Run upstream tests** under `make test-bundled-modules` (`src/test/resources/module/Unicode-Collate/t/`) without editing test files.
3. **Stay compatible with `use Unicode::Collate` in the wild** — constructor options, `getSortKey`, `cmp`/`sort`, `change()`, tailoring from `allkeys.txt` / small `keys.txt`, and (later) `Unicode::Collate::Locale` / CJK helpers as needed by dependents.
4. **Minimize long-term risk**: predictable behavior vs system Perl for the same table version, and a clear story when ICU differs.

Non-goals for v1: shipping `ucatbl.h`-style packed trie in Java (same binary layout as XS). That duplicates the upstream build and buys little over **file-backed DUCET** for our JVM port.

---

## Constraints

| Constraint | Implication |
|------------|-------------|
| No real XS / no `ucatbl.h` in the JVM | Drop the `__useXS` fast path that reads the trie from XS; always load **table lines from files** already shipped under `@INC` (`Unicode/Collate/allkeys.txt`, test `keys.txt`). |
| Tests are the source of truth | Any ICU-first approach must either match Perl’s sort keys / golden output or we accept **explicit excludes + documented gaps** — not silent drift. |
| ICU4J already on the classpath | Available for **optional** paths; not mandatory for core parity. |
| `Unicode::Collate.pm` is huge and option-rich | A thin “delegate everything to ICU” wrapper still needs a lot of **adapter + option mapping** work; savings are not “delete Collate.pm”. |

---

## `@INC` and shadowing (must match system Perl)

Perl resolves `require` by walking **`@INC` in order** and using the **first** place that provides `Foo/Bar.pm`. A newer or extra install in a **site** / **local** tree overrides the copy shipped with the interpreter — that is normal Perl behaviour, not a bug.

PerlOnJava follows the same policy (see `GlobalContext.initializeGlobals`):

1. **`-I` / `CompilerOptions.inc`** (highest priority — explicit user override)
2. **`PERL5LIB`**
3. **`~/.perlonjava/lib`** (user-installed modules, analogous to site/local)
4. **`jar:PERL5LIB`** (bundled tree inside the JAR — analogous to core / vendor lib)

**There must be no special-case** in `jperl`, `GlobalContext`, or elsewhere that forces bundled `Unicode::Collate` (or any module) ahead of `~/.perlonjava/lib` for normal runs. That would diverge from system Perl’s “site can shadow core” rule.

**Stale or conflicting installs** under `~/.perlonjava/lib/` behave like a stale **`site_perl`** tree on system Perl: they shadow the bundled/JAR copy until the user **removes** them or **reinstalls** with `jcpan` (same idea as `cpanm` / `cpan` fixing site).

**Bundled module tests** (`ModuleTestExecutionTest`) prepend the checkout’s `src/main/perl/lib` via `CompilerOptions.inc` for that JVM only. That is the same idea as running `perl -I/path/to/lib t/foo.t` or setting `PERL5LIB` for one command — an **explicit** test harness override, not a change to global `@INC` policy for interactive `jperl`.

---

## Options compared

### Option A — **Recommended default: “XS surface” in Java + file-backed DUCET**

**Idea:** Patch `Unicode/Collate.pm` so `$self->{__useXS}` is always false (PerlOnJava-only patch in `dev/import-perl5/patches/`). Keep upstream **pure Perl** for reading/parsing `allkeys.txt`, `%mapping`, contractions, etc. Implement **only the XSUBs** from `Collate.xs` in `UnicodeCollate.java`: `_getHexArray`, `_isIllegal`, `_decompHangul`, `getHST`, `_derivCE_*`, `_uideoCE_8`, `_isUIdeo`, `mk_SortKey`, `varCE`, `visualizeSortKey` — port logic from C, not from ICU.

| Pros | Cons |
|------|------|
| Best shot at **bit-for-bit parity** with the same `allkeys.txt` + `Collate.pm` as system Perl for bundled tests. | Non-trivial **Java** to maintain (hundreds of lines of collation-element math). |
| No dependency on ICU’s UCA version skew for the default path. | Must track upstream **XS changes** when bumping the synced dist. |
| Clear separation: Perl = data + algorithm orchestration, Java = hot spots. | |

### Option B — **ICU4J as the collation engine**

**Idea:** Use `com.ibm.icu.text.RuleBasedCollator` (or `java.text.RuleBasedCollator`) + ICU sort keys / comparison, and adapt `Unicode::Collate`’s API in Java/Perl.

| Pros | Cons |
|------|------|
| Deletes most **low-level weight / Hangul / ideograph** code we would otherwise port from `Collate.xs`. | **Sort key bytes and `visualizeSortKey` output** generally **will not match** Perl’s implementation; bundled `.t` files assume Perl/Unicode::Collate + bundled table. |
| Battle-tested performance and data updates with ICU releases. | Constructor options (`backwards`, `rearrange`, `overrideCJK`, `preprocess`, `identical`, `hangul_terminator`, …) do not map 1:1 to ICU; **adapter layer** stays large and subtle. |
| | **Test policy** becomes either large `exclude:` lists in `config.yaml` or a parallel ICU-specific corpus — both are ongoing cost. |

**Verdict for our use case:** Treat as **secondary / optional** (e.g. a clearly named experimental backend or a different module namespace), not as the default bundled implementation, unless we explicitly abandon strict upstream test parity.

### Option C — **Hybrid (pragmatic split)**

**Idea:** Ship **Option A** for the default `Unicode::Collate` and CI. Optionally add:

- **`Unicode::Collate::ICU`** (or env-gated behavior) that documents “JVM collation via ICU; not identical to `getSortKey` from core Perl”, **or**
- ICU only for **narrow** add-ons later (e.g. heavy locale corpora) where we already expect different reference data.

| Pros | Cons |
|------|------|
| Keeps **bundled tests honest** on the parity path. | Two codepaths to maintain if the optional ICU route grows. |
| Lets product code opt into ICU where **exact Perl key bytes** do not matter. | Must guard against confusion about which backend is active. |

**Verdict:** Best fit for “we care about CPAN/core parity **and** might want ICU later”.

---

## Recommendation

| Tier | Choice |
|------|--------|
| **Default (bundled + CI)** | **Option A** — Java port of `Collate.xs` entry points + `__useXS` forced off + tables from `sync.pl`. |
| **Later / optional** | **Option C** — small, documented ICU-backed helper or flag **only** if a concrete consumer needs JVM-native collation and accepts semantic differences. |
| **Avoid as default** | **Option B** as a drop-in replacement for `Unicode::Collate` while claiming full upstream test coverage — cost of parity and false confidence is too high. |

---

## Implementation phases

### Phase 1 — Land the bundle (minimal viable)

1. Extend `dev/import-perl5/config.yaml`:
   - `Collate.pm` → `src/main/perl/lib/Unicode/Collate.pm` **with patch** (force `__useXS = undef`).
   - `Collate/` directory → `src/main/perl/lib/Unicode/Collate/` (includes `allkeys.txt`, `keys.txt`, `Locale/`, `CJK/`, etc.).
   - Upstream `t/` → `src/test/resources/module/Unicode-Collate/t/` with a **short exclude list** only where impossible (document each entry like `Math-BigInt` / `Storable` imports).
2. Implement / complete `org.perlonjava.runtime.perlmodule.UnicodeCollate` (`XSLoader` discovers `Unicode::Collate` → `UnicodeCollate`).
3. Run `timeout … make test-bundled-modules` with `JPERL_TEST_FILTER=Unicode-Collate`; fix **runtime/Java**, not tests.
4. Update `docs/reference/bundled-modules.md`, `docs/reference/feature-matrix.md`, and `docs/about/changelog.md` when the module is actually green enough to advertise.

### Phase 2 — Expand coverage

1. Remove excludes one-by-one as Java/Perl parity improves.
2. Add `Unicode::Collate::Locale` if tests or dependents require it (same sync + optional small Java surface if it pulls XS).
3. Add regression notes to this file (date, tests fixed, root cause).

### Phase 3 — Optional ICU path (only if justified)

1. Spike `Unicode::Collate::ICU` (or documented env switch) **without** reusing the name `Unicode::Collate` for incompatible semantics.
2. Separate tiny test file under `src/test/resources/module/…` that asserts **ICU invariants**, not Perl `getSortKey` bytes.

---

## Work tracking

| Status | Item |
|--------|------|
| Done (2026-05-11) | `config.yaml` + bundled `Unicode/Collate*` + upstream tests (excl. `loc_*.t`) |
| Done (2026-05-11) | `Collate.pm` patch: `__useXS` off — file-backed DUCET |
| In progress | `UnicodeCollate.java`: XS parity — **34/37** bundled `Unicode-Collate` `.t` files pass under `JPERL_TEST_FILTER=Unicode-Collate`; remaining: `illegal.t`, `illegalp.t`, `tangut.t` |
| Done (2026-05-11) | `UnicodeNormalize.java`: `getCombinClass` (ICU) + `normalize()` long forms (`NFC`, …) for `Unicode::Collate` |
| Done (2026-05-11) | Test harness: `JPERL_TEST_FILTER` → forked workers (`build.gradle` + getenv-first in `ModuleTestExecutionTest` / `PerlScriptExecutionTest`) |
| Optional | ICU-backed parallel API (Option C) |

### Open questions

1. Do we need **`Unicode::Collate::Locale`** in the same PR as core `Unicode::Collate`, or a follow-up once `use Unicode::Collate` is stable?
2. For **performance** (huge `allkeys.txt`), is pure-Perl `read_table` acceptable on JVM cold start, or do we need a lazy-load / binary cache later (still not ICU — e.g. compiled trie in a resource file built from `mkheader` offline)?

---

## References

- Import workflow: `dev/import-perl5/sync.pl`, `dev/import-perl5/config.yaml`
- Porting patterns: `docs/guides/module-porting.md`, `.agents/skills/port-cpan-module/SKILL.md`
- Bundled tests: `src/test/java/org/perlonjava/ModuleTestExecutionTest.java`
- Related prior art in-repo: `UnicodeNormalize.java` (different problem: normalization, not collation)
