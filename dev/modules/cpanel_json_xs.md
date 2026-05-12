# Cpanel::JSON::XS — Full CPAN test parity on PerlOnJava

## Goal

Run the **complete upstream** `Cpanel-JSON-XS` distribution test suite (`t/*.t`)
under `./jperl` with **zero failures** (same bar as
[json_test_parity.md](json_test_parity.md) for `JSON`).

Success is measured as:

- The module is **bundled like other PerlOnJava modules**: upstream `.pm`
  sources live under `src/main/perl/lib/` (inside the JAR), and the upstream
  `t/*.t` suite lives under `src/test/resources/module/Cpanel-JSON-XS/t/` (or a
  single hyphenated name consistent with
  [module-porting.md](../docs/guides/module-porting.md)), run by
  `ModuleTestExecutionTest` / `make test-bundled-modules`, with **no edits** to
  upstream test files.
- `make test-bundled-modules` (filtered to this module) reports **all tests
  passed**, except documented `SKIP` blocks where PerlOnJava lacks a
  prerequisite (see [Non-goals](#non-goals)).
- The bundled tree stays **upstream-shaped**: e.g. official `Cpanel/JSON/XS.pm`
  (from upstream `XS.pm`), `Cpanel/JSON/XS/Type.pm`, `Cpanel/JSON/XS/Boolean.pm`.
- `XSLoader::load('Cpanel::JSON::XS', $VERSION)` resolves to a **Java**
  implementation: `org.perlonjava.runtime.perlmodule.CpanelJsonXs` (name per
  [module-porting.md](../docs/guides/module-porting.md)).

This document is the **single plan** whether you **extend today’s JSON::PP
shim** or **start from scratch** with a Java-first encoder: either way the
**exit criterion** is upstream tests green.

---

## Current state (baseline)

| Piece | Role |
|--------|------|
| `src/main/perl/lib/Cpanel/JSON/XS.pm` | JSON::PP inheritance **shim** when no Java class exists; `encode_json` / `decode_json` wrappers; croaks on type-aware extras. |
| `Cpanel::JSON::XS::Type` / `::Boolean` | `Type.pm`: pure-Perl **constants** matching `XS.xs`; `Boolean.pm`: tiny loader. |
| Bundled smoke / future full `t/` | Today: small smoke under `src/test/resources/module/Cpanel-JSON-XS/t/` (uses **runtime** `->can` for constants after `use_ok`, matching stock `strict subs`). Target: **copy entire upstream `t/`** into the same bundled-test tree (like Moose, XML::Parser, etc.). |

| Upstream-only checkout (optional) | During porting, a temporary unpack of the same upstream version can be used to diff behaviour; **git is the source of truth**, not a live CPAN fetch in CI. |

**Conclusion:** remaining gaps are **semantic / XS surface**; do not relax `strict subs` for FQ barewords (Perl 5 rejects those at compile time unless imported or written as a real sub call).

---

## Strategic options

### Option A — “Grow the shim” (JSON::PP + Perl)

- Implement missing **instance methods** and **type-aware** paths in Perl on
  top of `JSON::PP`, plus targeted Cpanel-only behaviour (inf/nan, dualvar,
  `binary`, etc.).
- **Pros:** Faster initial progress; reuses bundled `JSON::PP`.
- **Cons:** Upstream tests are written against **XS** semantics; reproducing
  every edge case in Perl is **high risk** and high maintenance. Many tests
  (`118_type.t`, `incr`, BOM, relaxed) will fight subtle differences.

**Verdict:** possible for a **subset**; **not recommended** as the path to
*all* tests green.

### Option B — **Java XS replacement** (recommended for “all tests pass”)

- Add `CpanelJsonXs.java` implementing the same contract as `XS.xs`:
  bootstrap constants, encoder/decoder objects, incremental API, type tags,
  error texts where tests regex-match.
- Keep **upstream `.pm` files** in `src/main/perl/lib/` (refresh from an
  upstream release when bumping the bundled version). `XSLoader::load` is the
  port boundary.
- **Pros:** One implementation language; test-driven; aligns with existing
  `Digest::MD5`, `DBI`, `Text::CSV` style ports.
- **Cons:** Large upfront effort; must track upstream releases.

### Option C — Dual-backend CPAN module (Option B in [module-porting.md](../docs/guides/module-porting.md))

- Ship `.java` **inside** the CPAN dist; `jcpan` compiles it.
- **Pros:** upstream authors own the port.
- **Cons:** PerlOnJava project still needs a **bundled** copy for offline use;
  splits maintenance.

**Recommendation for PerlOnJava repo:** **Option B** (bundled Java class).

---

## Source of truth and inventory

1. **Choose the bundled upstream version** (e.g. `$VERSION = 4.40` in the copied
   `Cpanel/JSON/XS.pm`) and document it in this file’s [Progress tracking](#progress-tracking)
   when the bundle is refreshed. The **committed files** in `src/main/perl/lib/`
   and `src/test/resources/module/…/t/` are the canonical copy; refreshing is a
   deliberate maintainer action (script or manual import), not an implicit
   “latest from CPAN” fetch at build time.
2. **Read `XS.xs` and the bundled `XS.pm`** (not only POD): list every exported
   XS function, `newCONSTSUB`, method, and flag bit.
3. **Classify bundled `t/*.t` files** by required feature set (spreadsheet or
   checklist in a follow-up PR):

   - Core encode/decode / utf8 / ascii / latin1  
   - Errors / offsets (`02_error`, `18_json_checker`, …)  
   - Relaxed / singlequote / barekey  
   - **Type system** (`118_type`, `119_type_decode`, `120_type_all_string`)  
   - **Incremental** (`19_incr`, `116_incr_parse_fixed`, …)  
   - **Binary extension** (`99_binary`)  
   - **BOM** (`31_bom`)  
   - **bignum** (`110_bignum`)  
   - **Tied / ixhash** (`115_tie_ixhash`)  
   - **Thread / unshare** (`97_unshare_hek` — likely skip)  
   - **Memory** (`121_memleak` — may be policy-skip under CI)

4. For each test file, note **first failing assertion** when run today: that
   becomes a **phase exit gate**.

---

## Architecture (Option B)

### Perl layer

- Replace the interim shim with **bundled upstream** `Cpanel/JSON/XS.pm`
  (upstream `XS.pm` layout), adjusted only if unavoidable (prefer **no** edits;
  if a one-line PerlOnJava pragma is required, document in `dev/import-perl5`
  or a tiny patch file next to the bundle notes).
- `XSLoader::load('Cpanel::JSON::XS', $XS_VERSION)` → `CpanelJsonXs.initialize()`.

### Java layer (`CpanelJsonXs.java`)

- `extends PerlModuleBase`, `super("Cpanel::JSON::XS", false)`.
- `public static final String XS_VERSION = "4.40";` (keep in sync with
  **bundled** `$VERSION` in `Cpanel/JSON/XS.pm`).
- `initialize()`: register all methods/constants matching XS bootstrap
  (mirror how other modules call `registerMethod` / constant installation).
- **Encoder state**: mirror Cpanel’s option struct (`pretty`, `indent`,
  `canonical`, `allow_*`, `escape_slash`, `stringify_infnan`, `binary`,
  `require_types`, `type_all_string`, `sort_by`, …).
- **Decoder state**: strict/relaxed, BOM handling, `max_depth`, `max_size`,
  incremental buffer, `allow_singlequote`, `allow_barekey`, etc.

### JSON engine choice inside Java

| Approach | Notes |
|----------|--------|
| **Hand-rolled lexer/parser** | Maximum control for **byte-exact** errors and offsets; highest effort. |
| **Gson (already on classpath)** | Good for generic JSON; **type tag** encoding and many Cpanel **error strings** will still need a **custom layer** or Gson hooks. |
| **Hybrid** | Gson (or `JsonReader`) for generic paths; **custom** passes for type-specified encode, binary mode, incremental. |

Recommendation: start a **small dedicated lexer** for decode (error
messages are test-sensitive, similar to [json_test_parity.md](json_test_parity.md)
section 10). Reuse **existing numeric / utf8 utilities** elsewhere in the runtime
where possible.

### Constants and `Cpanel::JSON::XS::Type`

- Real XS uses `newCONSTSUB` in the `Type` stash. Java `initialize()` should
  install the same **names and integer values** as `XS.xs` (already documented in
  bundled `Type.pm` constants today — keep one source of truth; generate from
  a small table shared with tests).

---

## Phased delivery (all gates use **bundled** upstream `t/`)

Phases are **ordered by dependency**: each phase ends with “run the relevant
subset under `make test-bundled-modules` / `JPERL_TEST_FILTER=…`; expect 0
failures in that subset”.

### Phase 0 — Bundle layout + tests in-repo

- [ ] Copy upstream **`Cpanel/JSON/XS.pm`** (from `XS.pm`), **`Type.pm`**,
  **`Boolean.pm`** into `src/main/perl/lib/` (replacing interim shims where
  appropriate).
- [ ] Copy upstream **`t/`** into `src/test/resources/module/Cpanel-JSON-XS/t/`
  (full suite — same pattern as other bundled CPAN modules; see
  [port-cpan-module skill](../../.agents/skills/port-cpan-module/SKILL.md)).
- [ ] Record bundled **upstream version** and refresh date in [Progress tracking](#progress-tracking).

### Phase 1 — Bootstrap + load

- [ ] `CpanelJsonXs.java`: `initialize()`, version check, no-op or minimal
  stubs so `00_load.t` passes.
- [ ] Eliminate **`Subroutine … redefined`** warnings: ensure jar shim `eval`
  does not redefine subs already installed by upstream `.pm` (guard in
  `XSLoader` or one-time install flag).

### Phase 2 — Core object API

- [ ] `new`, `encode`, `decode`, `utf8`, `ascii`, `pretty`, `indent`,
  `allow_nonref`, `canonical`, `escape_slash`, etc., matching **method chaining**
  semantics from XS.
- [ ] Gate: `08_pc_*`, `109_encode`, `108_decode` subsets.

### Phase 3 — Error messages and context

- [ ] Decode/encode errors: messages, offsets, line/column where tests assert.
- [ ] Gate: `02_error`, `18_json_checker`, parts of `17_relaxed`.

### Phase 4 — Relaxed / extensions

- [ ] `relaxed`, `allow_singlequote`, `allow_barekey`, `allow_dupkeys` (and any
  Cpanel-specific relaxed bits from XS).
- [ ] Gate: `106_*`, `107_*`, `17_relaxed`.

### Phase 5 — UTF-8 / Latin1 / BOM

- [ ] Byte vs character mode for `utf8`; `ascii` / `latin1` escapes.
- [ ] BOM sniffing / transcoding (`31_bom`).
- [ ] Gate: `01_utf8`, `14_latin1`, `31_bom`, `zero-mojibake.t`.

### Phase 6 — Numbers, inf/nan, bignum

- [ ] Match Cpanel rules for dualvars, `stringify_infnan`, `allow_bignum`, upgrade
  paths (`112_upgrade`, `117_numbers`, `110_bignum`).

### Phase 7 — Type system (large)

- [ ] Full **type tag** encode/decode (`118_type`, `119_type_decode`,
  `120_type_all_string`).
- [ ] `require_types`, `json_type_*` helpers stay in Perl `Type.pm`; Java must
  honour second argument to `encode` / functional `encode_json`.

### Phase 8 — Incremental / streaming

- [ ] `incr_parse`, `incr_text`, `incr_skip`, `incr_reset`, partial buffers,
  fixed bugs covered by `116_incr_parse_fixed`, `19_incr`.

### Phase 9 — Binary mode and DWIW

- [ ] `binary` extension and related tests (`99_binary`, `04_dwiw_*` if present
  in the **bundled** upstream tree).

### Phase 10 — Edge cases: tie, bless, overloading, sort_by

- [ ] `filter_json_object`, `convert_blessed`, `allow_blessed`, `TO_JSON`,
  `FREEZE`/`THAW` if XS exposes them in this version.
- [ ] Gate: `115_tie_ixhash`, `12_blessed`, `113_overloaded_eq`, `104_sortby`.

### Phase 11 — Memory / stress / binary size

- [ ] `121_memleak`, large cases: may require **CI-only** flags or timeouts
  (`PERL_SKIP_BIG_MEM_TESTS` pattern from `AGENTS.md`).
- [ ] Decide policy: skip vs implement.

### Phase 12 — Cleanup and docs

- [ ] Remove obsolete shim code paths if Java fully replaces them.
- [ ] Update [bundled-modules.md](../docs/reference/bundled-modules.md),
  [xs-compatibility.md](../docs/reference/xs-compatibility.md),
  [feature-matrix.md](../docs/reference/feature-matrix.md),
  [changelog.md](../docs/about/changelog.md).
- [ ] `./jcpan -t Cpanel::JSON::XS` green in CI (with `timeout` per `AGENTS.md`).

---

## Testing commands (reference)

Always wrap `jperl` in `timeout` (see `AGENTS.md`).

```bash
# Bundled module tests (preferred — same as CI)
JPERL_TEST_FILTER=Cpanel-JSON-XS timeout 600 make test-bundled-modules

# Spot-check one file while iterating
timeout 120 ./jperl -I…/src/main/perl/lib \
  src/test/resources/module/Cpanel-JSON-XS/t/118_type.t > /tmp/cj118.txt 2>&1
```

---

## Non-goals (explicit SKIP / out of scope)

Document each in test harness or skip file with rationale:

| Area | Reason |
|------|--------|
| **ithreads / CLONE / shared** | PerlOnJava does not model Perl ithreads; skip tests that require `threads`. |
| **Some memleak / huge-string tests** | JVM heap / CI time; align with existing `PERL_SKIP_BIG_MEM_TESTS`. |
| **Native `fork` in tests** | Not available on `jperl`; use `perl` harness if needed for side-by-side. |

---

## Open questions

1. **Bundle refresh procedure:** one-off `dev/tools/import-cpanel-json-xs.pl`
   (download → copy into `src/main/perl/lib` + `src/test/resources/…`) vs
   fully manual steps — either way, **commit** is the release boundary.
2. **Error message strategy:** shared “JSON error formatter” with
   `Json.java` / future `JSON::XS` port, or Cpanel-specific only?
3. **Bump policy:** how often to resync from upstream CPAN (security / bugfix
   cadence vs review cost).

---

## References

- [Porting modules (Option A vs B)](../docs/guides/module-porting.md)
- [JSON test parity (error strings, utf8, incremental lessons)](json_test_parity.md)
- [XS fallback overview](xs_fallback.md)
- Upstream repo / tracker: linked from upstream `README` (RURBAN fork of JSON::XS).

---

## Progress tracking

| Phase | Status | Date | Notes |
|-------|--------|------|-------|
| 0 Plumbing | Not started | | |
| 1 Bootstrap | Not started | | |
| … | | | |

_Update this table as work lands; keep “Next step” as the first unchecked row._

### Next step

Execute **Phase 0** (full upstream `.pm` + full `t/` **committed** under
`src/main/perl/lib/` and `src/test/resources/module/Cpanel-JSON-XS/`), then
scaffold **empty** `CpanelJsonXs.java` until bundled `00_load.t` passes without
redefine warnings.
