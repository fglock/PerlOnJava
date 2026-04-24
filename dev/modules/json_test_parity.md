# JSON Test Parity — `./jcpan -t JSON`

## Goal

Make the complete CPAN `JSON` 4.11 test suite pass when run through
PerlOnJava's bundled `JSON` backend (`src/main/perl/lib/JSON.pm` +
`src/main/java/org/perlonjava/runtime/perlmodule/Json.java`).

This means that `./jcpan -t JSON` should report **0 failures**. Today
(after PR #550 lands) the baseline is **22 / 68 passing**.

## How tests see our backend

CPAN `JSON` 4.11 is a pure-Perl dispatcher that normally loads
`JSON::XS` or `JSON::PP`. Our `MakeMaker` intentionally skips
`lib/JSON.pm` during install and doesn't stage it to `blib/lib/`, so
`make test` (which uses `PERL5LIB=./blib/lib:./blib/arch`) falls
through to our JAR-bundled `JSON.pm`. That shim loads `Json.java`
via `XSLoader::load('Json')` and exposes itself as `is_xs == 1`,
so from the tests' point of view we are "the XS backend".

## Current state (PR #550)

`JSON.pm` has been taught the CPAN dispatcher's API surface:
`import` tags, `$JSON::Backend`, `backend()`, `jsonToObj`/`objToJson`,
option stubs, minimal incremental parser. That alone lifts passing
count from 16 → 22.

The other 46 failures all need `Json.java` work.

## Remaining failures — by feature

Counts are approximate (one test file may fail for multiple reasons).

### 1. Error handling / "croak" compatibility (~15 files)

Every encoder/decoder error today surfaces as a Java stack trace
(`... at Json.java line 151`). `JSON::XS` croaks with very specific
messages that many tests regex-match on:

- `malformed UTF-8 character in JSON string`
- `unexpected end of string`
- `unexpected character, expected ...`
- `, or ] expected while parsing array`
- `, or } expected while parsing object/hash`
- `garbage after JSON object`
- `cannot encode reference to scalar` / `cannot encode reference`
- `hash- or arrayref expected` (when `allow_nonref` off)
- `attempt to encode object` (when `allow_blessed` off)
- `number out of range` (when `max_size` exceeded)
- `JSON text must be an object or array` (strict mode)
- offset/line/column info in decode errors

**Tests**: `02_error`, `08_pc_base`, `108_decode`,
`120_incr_parse_truncated`, `17_relaxed`, `18_json_checker`,
`20_unknown`, `22_comment_at_eof`, `rt_116998_wrong_character_offset`,
`x02_error`, `xe19_xs_and_suportbypp`, `xe20_croak_message`,
`gh_28_json_test_suite`.

**Approach**: Replace the fastjson2 exception with a
`PerlCompilerException` / `croak()` call that carries the
JSON::XS-style message with offset/line/column. Requires a small
shim between the fastjson2 error and our croak path (OR writing our
own parser — see §10 below).

### 2. `utf8` / `ascii` / `latin1` flags (~10 files)

`JSON::XS` has three orthogonal encoding options:

| Flag    | On                                   | Off                                |
|---------|--------------------------------------|------------------------------------|
| `utf8`  | encode returns byte string; decode expects byte string | encode returns character string; decode expects character string |
| `ascii` | escape all codepoints > 0x7F as `\uXXXX` (with surrogate pairs for > 0xFFFF) | emit raw characters |
| `latin1`| escape codepoints > 0xFF as `\uXXXX` | emit raw characters |

Today we ignore all three.  When `ascii` is requested, codepoints
like `U+8000` must come out as `\u8000` and `U+10402` as
`\ud801\udc02`. When `utf8` is set, the output must be a byte string
(`length` returns byte count).

**Tests**: `01_utf8`, `109_encode`, `14_latin1`, `e02_bool`,
`112_upgrade`.

**Approach**: This only makes sense if we write our own emitter
(see §10). Fastjson2's output shape is fixed.

### 3. Pretty printing (`indent`, `indent_length`, `space_before`, `space_after`) (~3 files)

`JSON::XS` uses exactly this format:

```json
{
   "a" : 1,
   "b" : [
      2,
      3
   ]
}
```

- 3-space indent by default (`indent_length` overrides)
- one element per line
- `" : "` between key and value (spaces from `space_before`/`space_after`)
- `,\n` between elements (no trailing comma)

Today we delegate to fastjson2's `PrettyFormat` then do regex
replacements, which doesn't produce byte-identical output.

**Tests**: `06_pc_pretty`, `xe05_indent_length`.

### 4. `canonical` / `sort_by` (~2 files)

- `canonical` → encode object keys sorted
- `sort_by` → callback-based ordering

**Tests**: parts of `03_types`, `104_sortby` (already passing after
shim), `52_object`.

### 5. Booleans / `JSON::PP::Boolean` (~3 files)

`JSON::XS` and `JSON::PP` both bless their true/false singletons into
`JSON::PP::Boolean`. Tests use `isa_ok($x, 'JSON::PP::Boolean')`.

**Tests**: `e03_bool2`, `xe12_boolean`, `e02_bool`, `118_boolean_values`.

**Approach**:

1. Create a trivial `JSON/PP/Boolean.pm` in the JAR (if not already —
   there is one).
2. Bless the singletons in `Json.java` (or have `true()`/`false()`
   return a blessed `RuntimeScalar`).
3. Make `is_bool()` recognise both raw `BOOLEAN` and blessed-as-
   `JSON::PP::Boolean` references.

### 6. `allow_blessed` / `convert_blessed` / `TO_JSON` (~5 files)

- By default: die when encoding a blessed reference.
- `allow_blessed`: emit as `null`.
- `convert_blessed`: call `$obj->TO_JSON`, encode its return value.
- `-convert_blessed_universally` import: install a default
  `UNIVERSAL::TO_JSON` (already accepted as a no-op after PR #550;
  actual behaviour needs Java-side hook).

**Tests**: `12_blessed`, `x12_blessed`, `52_object`,
`e11_conv_blessed_univ`, `113_overloaded_eq`.

### 7. Relaxed decoding (~3 files)

`relaxed` option: accept `#`-to-EOL comments, trailing commas in
arrays and objects.

**Tests**: `17_relaxed`, `22_comment_at_eof`.

### 8. `allow_barekey`, `allow_singlequote`, `escape_slash`, `loose` (~4 files)

Decoder/encoder relaxations, all PP-only in CPAN JSON.  Because we
already accept these option setters in the shim, we just need the
Java side to honour them.

**Tests**: `105_esc_slash`, `106_allow_barekey`, `107_allow_singlequote`,
`xe04_escape_slash`.

### 9. Number formatting (~1-2 files)

Perl-number-compatible output:

- `-1.234e5` → `-123400`  (no trailing `.0`)
- `1.23E-4` → `0.000123`  (or at least a form Perl round-trips)
- Large `1.01e+30` must preserve magnitude and typeness
- Integers stay integers (no `.0`)
- `NaN`/`Inf` → error (JSON can't represent them)

Today we emit fastjson2's native formatting (`-123400.0`, `1.23E-4`).

**Tests**: `11_pc_expo`, and number-adjacent bits of `03_types`.

### 10. Incremental parser (~5 files)

Full JSON::XS `incr_parse` / `incr_text` / `incr_skip` / `incr_reset`
semantics: buffer bytes, try to extract one top-level JSON value at
a time, report remaining input, support truncation.

**Tests**: `19_incr`, `22_comment_at_eof`, `116_incr_parse_fixed`,
`119_incr_parse_utf8`, `120_incr_parse_truncated`, `rt_90071_incr_parse`.

### 11. `decode_prefix` (~2 files)

Decode the first JSON value in a buffer and return it along with the
number of characters consumed.

**Tests**: `15_prefix`, `114_decode_prefix`.

### 12. `max_depth` / `max_size` (~2 files)

Enforce recursion/size limits, croak with a specific message
(`max_depth exceeded`, `json text or perl structure exceeds maximum
nesting level`, `max_size exceeded`).

**Tests**: `13_limit`, `e01_property`.

### 13. `boolean_values` (~1 file)

Customise the two values used to represent JSON `true`/`false` when
decoding.

**Tests**: `118_boolean_values`.

### 14. Tied hashes / arrays (~2 files)

Encode tied data structures by iterating via `FETCH` (our current
`Json.java` uses `RuntimeHash.elements.keySet()` which bypasses ties).

**Tests**: `16_tied`, `x16_tied`.

### 15. `allow_nonref` enforcement (~several)

When off (the default), encode/decode of non-objects/arrays must die.

### 16. Overloaded objects (~1 file)

`113_overloaded_eq`: a blessed hashref with `"" eq`/`""` overloads
should serialize using its stringification. Interaction with
`allow_blessed` + `convert_blessed`.

### 17. `allow_unknown` (~1 file)

Currently-unencodable values (coderefs, globs, filehandles, …)
become `null` instead of a fatal error.

**Tests**: `20_unknown`.

### 18. `allow_tags` (~1 file)

JSON::XS-specific tagged-value extension. May be acceptable to stub.

### 19. Standards compliance — `gh_28_json_test_suite` (~1 file)

Runs the json.org conformance suite; most of it passes once error
handling, number formatting, and surrogate handling are fixed.

### 20. `00_load_backport_pp`

Tests that `JSON::backportPP` can load and set `$JSON::BackendModulePP`.
Our shim currently sets no PP backend. Probably fine to load
`JSON::backportPP` on demand from within the shim.

## Implementation strategy

Fastjson2 is fundamentally the wrong shape for this API: it has its
own error vocabulary, its own number formatting, doesn't know about
Perl `undef`/booleans/blessed refs, and offers no hooks for relaxed
parsing, tagged values, or incremental input.

Rather than fight it, **write our own JSON encoder and decoder in
Java** in `Json.java`, roughly along the lines of JSON::PP's
algorithm but working on `RuntimeScalar`/`RuntimeHash`/`RuntimeArray`
directly. This is ~1000-1500 lines of straightforward code and
gives us full control over every option.

We can keep fastjson2 as a fallback for JSON::MaybeXS-style consumers
who don't need the full option set, but `JSON.pm`'s path should go
through our hand-rolled emitter/parser.

## Phased implementation

Each phase is independently testable against the 68-test CPAN suite
plus `make` (regressions).

| Phase | Scope | Expected new passes | Risk |
|-------|-------|---------------------|------|
| **1** | Hand-rolled encoder + `utf8`/`ascii`/`latin1`/`indent`/`space_*`/`canonical` options, Perl-aware number formatting, proper `null`/boolean handling, `allow_nonref` enforcement, `cannot encode reference` errors, blessed-as-`JSON::PP::Boolean` true/false | ~15 | low |
| **2** | Hand-rolled decoder + offset/line/column-aware croak messages matching `JSON::XS` patterns, tied iteration, `allow_blessed` / `convert_blessed` / `TO_JSON` | ~10 | low |
| **3** | `relaxed`, `allow_barekey`, `allow_singlequote`, `escape_slash`, `loose`, `allow_unknown`, `max_depth`, `max_size`, `decode_prefix` | ~10 | low |
| **4** | Real `incr_parse` state machine | ~5 | medium |
| **5** | `boolean_values`, `allow_tags`, `sort_by`, overload integration, remaining edges until `./jcpan -t JSON` reports 0 failures | ~6 | medium |

Each phase ends with: `make` green, `jcpan -t JSON` count
measured and recorded in "Progress Tracking" below.

## Measuring progress

Reproducible local check (no `jcpan`/`make test` wrapper needed):

```bash
cd ~/.cpan/build/JSON-4.11-9
export PERL5LIB=./blib/lib:./blib/arch
# or use a fresh extract:
#   jcpan -g JSON && cd ~/.cpan/build/JSON-*/ && jperl Makefile.PL && make
pass=0; fail=0
for t in t/*.t; do
    out=$(timeout 90 /path/to/jperl "$t" 2>&1)
    rc=$?
    last_plan=$(echo "$out" | grep -E "^1\.\." | tail -1)
    fail_count=$(echo "$out" | grep -cE "^not ok ")
    if [ "$last_plan" = "1..0" ] || echo "$last_plan" | grep -q "SKIP"; then
        :
    elif [ $rc -eq 0 ] && [ $fail_count -eq 0 ]; then
        pass=$((pass+1))
    else
        fail=$((fail+1)); echo "  $t"
    fi
done
echo "PASS: $pass  FAIL: $fail"
```

The tooling-friendly success criterion is `FAIL: 0` from the snippet
above, and `./jcpan -t JSON` reporting `PASS` at the end.

## Progress Tracking

### Current status: `./jcpan -t JSON` — `Result: PASS` (68 files, 26126 subtests, 0 failures)

### Completed

- [x] **Shim compatibility** (`src/main/perl/lib/JSON.pm`): import
      tags (`-support_by_pp`, `-no_export`,
      `-convert_blessed_universally`), `$JSON::Backend`,
      `backend()`, `is_xs`/`is_pp`, `jsonToObj`/`objToJson`,
      `pureperl_only_methods`, `null`, option stubs, `property()`.
      **16 → 22** passing.

- [x] **Strategic pivot: delegate to JSON::PP** — instead of
      hand-rolling an encoder/decoder in Java, the JSON shim now
      `@ISA = 'JSON::PP'`.  The bundled `JSON::PP` is a complete
      pure-Perl backend that already implements every option the
      test suite exercises.  Deleted `Json.java` and the
      `fastjson2` dependency.  **22 → 47** passing.

- [x] **Parser fix**: package-literal barewords
      (`Foo::Bar::`) in `bless` and before word operators
      (`eq`, `ne`, `lt`, `gt`, `le`, `ge`, `cmp`, `x`, `isa`, `and`,
      `or`, `xor`).  Previously `bless $x, Foo::Bar::;` kept the
      trailing `::` and `Foo::Bar:: eq $x` was mis-parsed as a sub
      call.  See commit 80842ea.

- [x] **Parser fix**: `{"a","b"}` / `{1,2}` / `{foo,1,bar,2}` now
      parse as hashrefs (not blocks evaluating a comma expression)
      when the first content token is a hash-key-shaped thing.
      See commit 3cf3405.

- [x] **Runtime fix**: `unpack "U*"` in character mode now reads
      code points directly from the string instead of re-decoding
      its UTF-8 byte representation.  Matches real Perl on any
      Latin-1 byte string.  Fixed `JSON::PP`'s `_encode_ascii`.
      See commit 3c1545e.

- [x] **Runtime fix**: `is_xs`/`is_pp` added to `JSON::PP` as
      class methods (was `Can't locate object method` in ~8 tests).

- [x] **Parser fix**: `[\c?]` inside a character class now matches
      U+007F (DEL), not U+001C.  The regex preprocessor no longer
      escapes `?` inside `[ ]`.  Fixes `JSON::PP`'s control-char
      escape regex and the whole `t/99_binary.t` suite.  See commit
      f8b3864.

- [x] **JSON.pm fix**: `-convert_blessed_universally` uses
      `reftype` instead of `ref` to classify the underlying
      storage of a blessed reference.  See commit da2a693.

- [x] **Runtime fix**: `bless` and `UNIVERSAL::isa` now canonicalise
      through stash aliases (`*Dst:: = *Src::;`).  After aliasing,
      `ref($x)` reports the canonical name and `isa` accepts either
      name.  See commit 3c3ef4f.

- [x] **`PERL_JSON_BACKEND=JSON::backportPP`** — new
      `JSON/backportPP.pm` that `require`s `JSON::PP` and then
      `delete $INC{'JSON/PP.pm'}` so the backend-identification
      contract (check `%INC` for `JSON/backportPP.pm` vs `JSON/PP.pm`)
      is satisfied.  `JSON.pm` gates its backend load on the env var
      and paper-patches the CPAN-shipped `JSON/backportPP.pm`'s
      missing introspection (`@JSON::PP::ISA`, `@JSON::backportPP::ISA`,
      `is_xs`/`is_pp`) so the dispatcher surface works regardless of
      which backportPP was picked up.  See commits e800c29, 88aea98.

- [x] **JSON::PP IncrParser `substr` byte handling** — replaced the
      `use bytes; substr($text, $offset)` line (which depends on
      `use bytes` redirecting `substr` — a feature PerlOnJava does
      not yet implement) with an explicit encode/substr/decode when
      `get_utf8` is off, and plain `substr` when it is on.  Fixes
      multi-byte-char incremental parsing.  See commit b6d67bf.

### jcpan -t JSON progression

| Milestone | Result |
|-----------|--------|
| Before    | 16 / 68 passing |
| Shim-only | 22 / 68 |
| + JSON::PP delegation, drop fastjson2 | 47 / 68 |
| + 4 parser/runtime/regex fixes | 64 / 68 |
| + JSON::PP class methods + property | 65 / 68 |
| + reftype fix | 65 / 68 (+ t/e11 internally) |
| + stash alias canonicalisation | 66 / 68 |
| + JSON::backportPP support | 67 / 68 (1 test 6/24 subtests failing) |
| **+ IncrParser UTF-8 byte substr** | **`Result: PASS` — 68 / 68, 26126 / 26126 subtests** |

### Still outstanding

None — `./jcpan -t JSON` reports `Result: PASS` end-to-end.

Follow-up: `use bytes` should eventually redirect `substr` / `unpack`
/ `index` globally in PerlOnJava (following the pattern already
established for `length`/`chr`/`ord`).  Tracked under the runtime
roadmap; not needed for this task.

### Files changed

- `src/main/perl/lib/JSON.pm` — full rewrite, now `@ISA = ('JSON::PP')`
- `src/main/perl/lib/JSON/PP.pm` — added `is_xs` / `is_pp` class methods
- `src/main/java/org/perlonjava/runtime/perlmodule/Json.java` — DELETED
- `src/main/java/org/perlonjava/frontend/parser/OperatorParser.java`
  (bless `::` strip)
- `src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java`
  (word-op infix detection)
- `src/main/java/org/perlonjava/frontend/parser/StatementResolver.java`
  (hashref disambiguation)
- `src/main/java/org/perlonjava/runtime/operators/unpack/UFormatHandler.java`
  (unpack U*)
- `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessorHelper.java`
  (`?` in char class)
- `src/main/java/org/perlonjava/runtime/operators/ReferenceOperators.java`
  (bless via stash alias)
- `src/main/java/org/perlonjava/runtime/perlmodule/Universal.java`
  (isa via stash alias)
- `src/test/resources/unit/{method_call_trailing_colons,hash_ref_disambiguation,unpack,regex/regex_charclass,stash_aliasing}.t`
  — regression tests for every runtime/parser fix
- Many docs cleaned up to drop fastjson2 references
  (`docs/reference/bundled-modules.md`,
  `docs/reference/xs-compatibility.md`,
  `docs/guides/using-cpan-modules.md`,
  `docs/getting-started/installation.md`,
  `dev/modules/README.md`,
  `dev/modules/dynamic_loading.md`,
  `dev/design/sbom.md`,
  `dev/custom_bytecode/TESTING.md`,
  `dev/presentations/blogs_perl_org_jcpan_2026/blog-post{,-long}.md`,
  `build.gradle`, `gradle/libs.versions.toml`, `pom.xml`,
  `jperl`, `jperl.bat`)

## Related documents

- `dev/modules/xs_fallback.md` — general XS → Java fallback framework
- `src/main/perl/lib/JSON.pm` — the shim
- `src/main/java/org/perlonjava/runtime/perlmodule/Json.java` — the Java backend
