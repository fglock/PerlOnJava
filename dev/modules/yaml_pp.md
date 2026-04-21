# YAML::PP Support Plan for PerlOnJava

## Overview

**Module:** YAML::PP v0.39.0 (CPAN: TINITA)
**Bundled in PerlOnJava:** Yes — `lib/YAML/PP.pm` is a thin Perl wrapper
over the Java backend in
`src/main/java/org/perlonjava/runtime/perlmodule/YAMLPP.java`, which
delegates to [snakeyaml-engine] v3.
**Test command:** `./jcpan -t YAML::PP`
**Type:** Native (Java) implementation shadowing CPAN YAML::PP.

The shipped shim only supports `new`, `load_string`, `load_file`,
`dump_string`, `dump_file`. Most of YAML::PP's Perl-side API
(Loader/Parser/Emitter/Schema/Representer/Constructor classes) is not
present, so the CPAN test suite exercises many paths that bypass our
backend and therefore die.

## Current Status

**Branch:** `fix/yaml-pp-cpan-tests`

### Results History

| Date | Programs Failed | Subtests Failed | Total Subtests | Key Fix |
|------|-----------------|-----------------|----------------|---------|
| 2026-04-21 (baseline) | 32/44 (suite SIGTERM'd partway through) | 73/2275 (counted before timeout) | 2275 | — |
| 2026-04-21 (after PR) | 30/44 | 149/2581 | 2581 | Catch NFE, `!!set`/`!!binary`, cyclic-ref msg |

Higher subtest-failure count after the fix is expected: previously,
test files that died with uncaught Java exceptions reported only the
failures that ran before the crash. Now the whole suite runs to
completion, so more *latent* failures surface.

### Test Results Summary (post-fix)

| Test File | Status | Subtests | Root Cause |
|-----------|--------|----------|------------|
| t/00.compile.t | PASS | ok | — |
| t/10.parse-valid.t | PASS | ok | — |
| t/11.parse-invalid.t | PASS | ok | — |
| t/12.load-json.t | FAIL | 59/284 | **Bug P1:** snakeyaml-engine parser strictness (tabs, %TAG, flow-mapping colon edge cases, bare doc rules, zero-indented block scalars) |
| t/13.load-anchor.t | FAIL | 0/0 | **Bug A1:** Perl `YAML::PP::Loader` API not implemented |
| t/14.load-bool.t | FAIL | 3/14 | **Bug B1:** Boolean scalar type not returned (returns plain 1/0 or strings) |
| t/15.parse-eol.t | PASS | ok | — |
| t/16.loader.t | FAIL | 1/1 | **Bug A1:** `YAML::PP::Loader` API |
| t/17.load-complex-keys.t | FAIL | 0/0 | **Bug K1:** non-string mapping keys (array/hash refs) |
| t/18.control.t | PASS | ok | — |
| t/19.file.t | FAIL | 0/0 | **Bug F1:** `load_file($fh)` / `DumpFile($fh)` on open filehandles |
| t/20.dump.t | FAIL | 0/0 | **Bug D1:** emitter tag/style preservation mismatch |
| t/21.emit.t | PASS | ok | — |
| t/22.dump-bool.t | FAIL | 15/15 | **Bug B2:** dump of Perl/JSON::PP booleans → `!!bool` |
| t/23-dump-anchor.t | FAIL | 0/0 | **Bug D2:** anchor/alias preservation on dump |
| t/24.double-escapes.t | FAIL | 7/38 | **Bug E1:** `"\L..\E"`-style escape handling in double-quoted scalars |
| t/30.legacy.t | FAIL | 2/2 | **Bug L1:** `YAML::PP::Legacy` compat API |
| t/31.schema.t | FAIL | 46/61 | **Bug B3:** YAML1.1 schema (`yes`/`no`/`on`/`off` booleans, base8/base16 ints); **Bug S1:** `!!float` round-trip dump |
| t/32.cyclic-refs.t | FAIL | 1/7 | **Bug C1:** dump-side cyclic detection (load side fixed) |
| t/34.emit-scalar-styles.t | FAIL | 1/1 | **Bug E2:** `!!null` tag not constructible, scalar-style selection on dump |
| t/35.highlight.t | PASS | ok | — |
| t/36.debug.t | PASS | ok | — |
| t/37.schema-catchall.t | PASS | ok | — (fixed as side-effect) |
| t/37.schema-perl.t | FAIL | 0/0 | **Bug SP1:** `YAML::PP::Schema::Perl` (refs/globs/regex/code) |
| t/38.schema-ixhash.t | SKIP | — | Tie::IxHash not installed |
| t/39.emitter-alias.t | PASS | ok | — |
| t/40.representers.t | FAIL | 0/0 | **Bug R1:** `YAML::PP::Representer` API |
| t/41.custom.schema.t | FAIL | 0/0 | **Bug SP2:** custom schema registration API |
| t/42.tokens.t | FAIL | 1/326 | **Bug T1:** one tokenizer edge case |
| t/43.indent.t | FAIL | 0/0 | **Bug D3:** indent option on dump |
| t/44.writer.t | FAIL | 0/0 | **Bug W1:** `YAML::PP::Writer` API |
| t/45.binary.t | FAIL | 2/3 | **Bug B4:** dump `!!binary` (load-side fixed) |
| t/46.line-endings.t | PASS | ok | — |
| t/47.header-footer.t | FAIL | 1/5 | **Bug H1:** header/footer option on multi-doc dump |
| t/48.merge.t | FAIL | 1/1 | **Bug M1:** `<<:` merge-key support |
| t/49.include.t | FAIL | 1/1 | **Bug I1:** `YAML::PP::Schema::Include` (`!include`) |
| t/50.clone.t | FAIL | 1/2 | **Bug CL1:** `clone()` method |
| t/51.directives.t | FAIL | 2/2 | **Bug DI1:** `%TAG` / `%YAML` directive preservation |
| t/52.preserve.t | FAIL | 1/1 | **Bug PR1:** style/flow preservation on round-trip |
| t/53.customtag-alias.t | FAIL | 1/1 | **Bug CT1:** custom tag registration via constructor arg |
| t/54.glob.t | FAIL | 1/1 | **Bug G1:** typeglob dump |
| t/55.flow.t | PASS | ok | — |
| t/56.force-flow.t | PASS | ok | — |
| t/57.dup-keys.t | FAIL | 2/3 | **Bug DK1:** `duplicate_keys` option behavior |

## Recently Fixed (this branch)

Commit `e153feaf7` — *fix(YAML::PP): survive invalid !!int/!!float,
handle !!set/!!binary, align cyclic-ref message*

1. **NumberFormatException containment.** `snakeyaml-engine` wraps
   `NumberFormatException` thrown by its Int/Float resolvers inside a
   `YamlEngineException` whose message is literally
   `"java.lang.NumberFormatException: For input string: \"...\""`.
   `load_string()` now catches this and raises a Perl-level die
   `"YAML::PP: invalid numeric value: …"`. Previously these killed
   whole test files (31.schema.t, 32.cyclic-refs.t) with `Dubious`.
2. **`!!set` handling.** Java `Set<?>` is now converted to a Perl
   hashref with undef values (matches Perl YAML::PP convention).
   Previously fell through to `toString()` → `"[a, b, c]"`.
3. **`!!binary` handling.** `byte[]` is now base64-encoded; previously
   returned the JVM identity hash (`"[B@494c8f29"`).
4. **Cyclic-ref message.** Changed to
   `"Found cyclic reference in YAML structure"` so the regex
   `(?i:found cyclic ref)` used by t/32.cyclic-refs.t matches.
5. **`schema` option parsing.** Secondary list entries are now read;
   `key=value` options are validated (`empty=null|str`); invalid
   options (`empty=lala`) raise `"Invalid option: …"`. Non-key=value
   list entries are accepted for back-compat with existing tests
   (`schema => ['JSON','Perl']`).

## Outstanding Bugs / Plan

Bugs are grouped by the infrastructure change they need. Priority is
by number of subtests affected.

### P1 — snakeyaml-engine parser strictness (59 subtests)

`t/12.load-json.t` runs the official YAML test suite. `snakeyaml-engine`
is strictly YAML-1.2 and rejects many inputs that YAML::PP (which
implements both 1.1 and 1.2) accepts: tab-indented flow, `%TAG`
redefinitions, bare documents between `...` markers, zero-indented
block scalars, flow-mapping colons on the following line, etc.

**Options:**

- (a) Accept the gap and mark these as known limitations; enumerate
  skipped YAML tags.
- (b) Pre-process the YAML string before handing it to snakeyaml
  (tabs → spaces in safe positions, etc.) — fragile.
- (c) Port YAML::PP's Perl parser on top of our existing Perl runtime —
  large effort, preserves behavior exactly.

Recommendation: (a) for now, revisit if users hit it. Document
limitation in the module's POD.

### B1/B2/B3 — Boolean handling (~80 subtests across 14/22/31)

YAML 1.1 `!!bool` accepts `y/Y/yes/YES/Yes/on/ON/On/true/TRUE/True`
(+ negatives). `snakeyaml-engine` Core/JSON schemas only accept
`true`/`false`. YAML1_1 schema isn't currently switched on.

Plan:

1. Add a `"YAML1_1"` case to the schema `switch` that builds a
   `CoreSchema` extended with YAML 1.1 bool/int/float resolvers
   (snakeyaml-engine ships `YamlImplicitResolver` primitives we can
   reuse or hand-roll with regex-based scalar resolvers).
2. Convert `Boolean` results to `RuntimeScalar`s of type `BOOLEAN`
   (already done) but also make sure the dump side emits
   `true`/`false` instead of `1`/`` for booleans — update
   `convertRuntimeScalarToYaml` to emit a tagged bool when the scalar
   is a `JSON::PP::Boolean` / `boolean.pm` blessed reference too.
3. Add `"Perl"` schema equivalent that recognises Perl-style truthy
   values where appropriate.

### F1 — `load_file` / `DumpFile` on filehandles (t/19.file.t)

Our `load_file(filename)` opens the file itself; it cannot take an
already-open Perl GLOB.

Plan: in `YAMLPP.load_file`, check if arg is a GLOB/IO ref, and if so
slurp via existing Perl `<$fh>` machinery (or expose the GLOB's
underlying java.io.Reader). Same for `dump_file`.

### B4 / dump-side tags (t/22, t/23, t/45, t/34, t/20, t/43, t/44)

`convertRuntimeScalarToYaml` currently returns only plain Perl values
(`Map`, `List`, `String`, `Double`, `Long`, `Boolean`). It needs to
wrap bytes in `byte[]` (for `!!binary`), Perl refs for `!!set`, and
emit proper tags for booleans, nulls, anchored nodes, etc. This
likely requires building `org.snakeyaml.engine.v2.nodes.Node` trees
directly and passing them to `Dump.dumpNode`, bypassing the
`Dump.dumpToString` of plain Java objects.

Plan: introduce a `YAMLPPNodeBuilder` helper that walks
`RuntimeScalar` values and produces `Node` instances with explicit
`Tag`s and `ScalarStyle`s, then use `Dump.dumpAllToString` on those.

### A1 / R1 / W1 / SP1 / SP2 / CT1 — Pure-Perl API surface

Tests `t/13`, `t/16`, `t/17`, `t/20`, `t/23-dump-anchor`, `t/37.schema-perl`,
`t/40.representers`, `t/41.custom.schema`, `t/44.writer`,
`t/53.customtag-alias` use the full Perl class hierarchy:

- `YAML::PP::Loader` / `YAML::PP::Parser` / `YAML::PP::Lexer`
- `YAML::PP::Dumper` / `YAML::PP::Emitter` / `YAML::PP::Writer`
- `YAML::PP::Constructor` / `YAML::PP::Representer`
- `YAML::PP::Schema::*` plugin loading

We currently ship none of these. Options:

- Install the upstream Perl modules into our bundled libs as a
  drop-in; our Java `YAML::PP` would need to stop clobbering
  `$INC{'YAML/PP.pm'}` so the Perl implementation can load.
- Or keep our native backend and add *shim* Perl modules under those
  names that forward to it (a lot of surface to maintain).

Recommendation: port the upstream Perl Parser/Lexer/Emitter verbatim,
and switch the Java backend to an opt-in fast path (used for
`load_string`/`dump_string` with default settings). See
`dev/modules/port-cpan-module` skill for the usual approach.

### C1 — Dump-side cyclic detection (t/32.cyclic-refs.t)

`convertRuntimeScalarToYaml` does honour `seen`, but it does not
respect `_cyclic_refs` behavior (it silently loops to an already-seen
node instead of dying on `fatal`). Wire the same `CyclicRefsBehavior`
switch into the dump path.

### K1 — Complex mapping keys (t/17)

SnakeYAML returns `Map<Object, Object>` where keys can be lists/maps,
but we unconditionally `key.toString()` them. Implement non-scalar
keys by:

- If the target Perl value type is a tied hash that supports arbitrary
  keys, forward the Node.
- Otherwise follow Perl convention: stringify with `Data::Dumper`-style
  canonical form (this is what YAML::PP does via
  `YAML::PP::Constructor::construct_mapping`).

### DI1 / PR1 / CL1 — Directive/style preservation (51, 52, 50)

Needs a round-trip mode where the loader annotates parsed nodes with
their original style/tag/directive info and the dumper consumes it.
Beyond what snakeyaml-engine exposes via its public `Load` API;
requires using the `Parse` + `Compose` streaming API instead.

### I1 / M1 — Schema extensions (49, 48)

- `Merge` schema: handle `<<` merge key per YAML 1.1.
- `Include` schema: interpret `!include path.yaml` tag.

Both map cleanly to custom `ConstructNode` implementations registered
on a `Schema` subclass.

### DK1 — `duplicate_keys` option (57)

`LoadSettings.allowDuplicateKeys` is already wired from the hash but
the tests expect a specific error message when duplicates are
disallowed, and a specific behaviour (last-one-wins vs first-one-wins)
when allowed. Align both.

### G1 — Typeglobs (54)

Typeglobs should dump as `!perl/glob` tagged nodes and round-trip.
Part of the Perl schema (SP1).

### E1 / E2 / T1 / H1 — Smaller emitter/parser edge cases

- E1 (24): `"\L...\E"` and other Perl-style escape sequences inside
  double-quoted YAML scalars. Our `convertRuntimeScalarToYaml` emits
  raw strings; the Perl emitter picks quoting styles and escape
  forms YAML::PP expects.
- E2 (34): explicit `!!null` tag with literal text requires a
  `ConstructScalar` for the null tag.
- T1 (42): one tokenizer regression, to be tracked.
- H1 (47): `header`/`footer` options currently set
  `setExplicitStart`/`setExplicitEnd` on single-doc dump but not on
  multi-doc.

## Test Commands

```bash
# Quick smoke (our unit test uses the Java backend directly)
./jperl src/test/resources/unit/yaml_pp.t

# Full CPAN suite
./jcpan -t YAML::PP

# Or, after a prior install, just rerun the tests in place:
cd ~/.perlonjava/cpan/build/YAML-PP-v0.39.0-*
PERL5LIB="./blib/lib:./blib/arch:$PERL5LIB" \
  ~/projects/PerlOnJava/jperl \
  -MExtUtils::Command::MM -MTest::Harness \
  -e 'undef *Test::Harness::Switches; test_harness(0, "./blib/lib", "./blib/arch")' \
  t/*.t
```

## Related Files

- `src/main/java/org/perlonjava/runtime/perlmodule/YAMLPP.java` —
  the Java backend.
- `src/main/perl/lib/YAML/PP.pm` — Perl shim (loads the Java module).
- `src/test/resources/unit/yaml_pp.t` — in-tree smoke test.
- `docs/JDBC_GUIDE.md` — unrelated, but a sibling native-module doc.

[snakeyaml-engine]: https://bitbucket.org/snakeyaml/snakeyaml-engine/src/master/
