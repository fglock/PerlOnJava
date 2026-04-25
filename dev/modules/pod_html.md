# Pod::Html Support for PerlOnJava

## Status

**Implemented (2026-04-25).** Both phases shipped together in PR #557.
`use Pod::Html` works out of the box; the upstream test suite is green
under `make test-bundled-modules`.

## Goals

After this work lands:

1. `use Pod::Html` works out of the box without any CPAN install.
2. The bundled upstream test suite passes:
   `make test-bundled-modules` for the `Pod-Html/` subtree is green.
3. `pod2html` produces byte-identical HTML to system perl on all
   inputs covered by the upstream tests (verbatim block dedenting in
   particular).
4. The underlying regex bug `(/^...$/mg`) is fixed across the board —
   reduced repro lands as a unit test.

No new Maven dependency. No new XS bridge. Pure Perl module + a small
fix to `RuntimeRegex.java`.

## Scope of the regex bug (Phase 0)

### Repro

```perl
my @m = "ab\ncd\n" =~ /^(.*)/mg;
# perl  : 2 matches  ("ab", "cd")
# jperl : 4 matches  ("ab", "", "cd", "")
```

Without the trailing `\n` the bug doesn't reproduce; with `.+` instead
of `.*` it doesn't reproduce. The trigger is **`^` in `/m` mode +
zero-width-capable body + `/g`**.

### Real-world impact

`Pod::Html::Util::trim_leading_whitespace` (called by Pod::Html via
`$parser->strip_verbatim_indent(\&trim_leading_whitespace)`):

```perl
my @indent_levels = sort(map { /^( *)./mg } @$para);
my $indent        = $indent_levels[0];   # min indent
$_ =~ s/^\Q$indent//mg for @$para;       # strip it
```

On `["    use Foo;\n    bar();\n"]`:

| Engine | `@indent_levels` | `$indent` |
|---|---|---|
| perl  | `("    ", "    ")` | 4 spaces |
| jperl | `("","","","","","","","","","",""," ","    ","    ")` | empty |

Empty `$indent` ⇒ no dedent ⇒ all rendered HTML `<pre>` blocks keep
their source indentation. Three Pod-Html tests fail for exactly this
reason (`htmlview.t`, `htmldir1.t`, `feature2.t`). Several other
upstream tests across the tree probably hit subtle variants — anything
that walks lines with `/^...$/mg` is at risk.

### Root cause

In `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java`
around lines 905–910 (LIST-context global match advancement):

```java
} else {
    startPos = matchEnd;
    if (posScalar != null) {
        posScalar.set(startPos);
    }
    // Update matcher region if we advanced past a zero-length match
    if (startPos > matchStart) {
        matcher.region(startPos, inputStr.length());
    }
}
```

Two problems with this `matcher.region(...)` call:

1. **The condition is wrong.** The comment says "if we advanced past a
   zero-length match", but `startPos = matchEnd` is set
   unconditionally above, so `startPos > matchStart` is true after
   *every* non-zero-length match, not just after the zero-length
   advancement path. So `region()` is invoked after every iteration in
   LIST context.
2. **`Matcher.region(start, end)` enables anchoring bounds by
   default.** With `useAnchoringBounds(true)` (the default), Java
   treats the new region's `start` as a line-start for `^` even when
   that offset is *not* preceded by `\n` in the actual input. So after
   matching `"ab"` at 0–2, `region(2, 6)` makes `^` succeed at offset 2
   — which is the `\n` itself — producing a spurious empty match. The
   same happens after every subsequent non-zero match, hence the
   garbage empty entries between real matches.

Verified by direct Java repro:

```java
Matcher m = Pattern.compile("^(.*)", Pattern.MULTILINE).matcher("ab\ncd\n");
m.find();                                  // "ab" at 0..2
m.region(2, s.length());                   // *** introduces phantom match
m.find();                                  // "" at 2..2  <-- bug
```

The fix in isolation:

```java
m.region(2, s.length());
m.useAnchoringBounds(false);   // <-- ^ and $ now only respect real \n
m.find();                                  // "cd" at 3..5  (correct)
```

### Fix plan (Phase 0)

In `RuntimeRegex.java`:

1. **Stop calling `matcher.region(...)` when we don't actually need
   to.** The natural flow of `Matcher.find()` advances past the
   previous match correctly on its own; the `region()` call is only
   needed when we advanced by 1 to escape a zero-length match
   (line 883: `matchEnd = matchStart + 1`). Tighten the condition:

   ```java
   // OLD: if (startPos > matchStart) { ... }
   // NEW: only when we forcibly advanced past a zero-length match
   if (matchEnd != matcher.end()) {     // we bumped past a 0-width match
       matcher.region(startPos, inputStr.length());
       matcher.useAnchoringBounds(false);   // *** key fix
   }
   ```

2. Apply the same `useAnchoringBounds(false)` to every other
   `matcher.region(...)` call site in this file (and
   `matchRegexDirectAlternate` at line ~1186). There are at least
   three such sites; all of them set a non-zero start offset and
   should disable anchoring bounds for parity with Perl's `^`/`$`/`\b`
   semantics. Audit grep:

   ```
   grep -n 'matcher.region' src/main/java/org/perlonjava/runtime/regex/*.java
   ```

3. **Add a unit test.** Suggested location:
   `src/test/perl/regex_multiline_global.t` (or fold into an existing
   `regex_*.t`). Cases to cover:

   ```perl
   is(scalar(() = "ab\ncd\n" =~ /^(.*)/mg),   2, 'm/^(.*)/mg with \n');
   is(scalar(() = "ab\ncd"   =~ /^(.*)/mg),   2, 'm/^(.*)/mg without trailing \n');
   is(scalar(() = ""         =~ /^(.*)/mg),   1, 'empty string');
   is(scalar(() = "\n\n"     =~ /^(.*)/mg),   2, 'just newlines');
   is_deeply([ "    a\n  b\n" =~ /^( *)./mg ], [ "    ", "  " ],
             'leading-whitespace capture');
   # And the Pod::Html idiom end-to-end:
   my @p = ("    use Foo;\n    bar();\n");
   my $indent = (sort(map { /^( *)./mg } @p))[0];
   is($indent, "    ", 'trim_leading_whitespace inner regex');
   ```

4. **Differential sanity.** Run the regex test under both backends:

   ```bash
   ./jperl       t/regex_multiline_global.t
   ./jperl --int t/regex_multiline_global.t
   ```

   The interpreter and JVM backends share `RuntimeRegex` so both
   should be fixed by the same change; the parity check just guards
   against any backend-specific path that might also need updating.

### Risk

`useAnchoringBounds(false)` is the right semantic for Perl's `^`/`$`
(they should *only* anchor at start of string and at internal
newlines, not at arbitrary region boundaries), but it changes
behaviour for any site that previously *relied* on the spurious
extra anchoring. Run the full unit suite (`make`) and the bundled
module suite (`make test-bundled-modules`) to surface regressions
before landing. None are anticipated.

## Bundling Pod::Html (Phase 1)

### Source location

The upstream lives in the perl source tree at
`perl5/ext/Pod-Html/`:

```
perl5/ext/Pod-Html/
├── bin/pod2html
├── corpus/                    # test-input PODs
├── lib/Pod/
│   ├── Html.pm                # 1.36, ~600 lines pure Perl
│   └── Html/
│       └── Util.pm            # helpers including trim_leading_whitespace
└── t/
    ├── anchorify.t  cache.t  crossref.t  crossref2.t  crossref3.t
    ├── eol.t        feature.t feature2.t
    ├── htmldir1.t … htmldir5.t
    ├── htmlescp.t   htmllink.t  htmlview.t
    ├── poderr.t     podnoerr.t
    ├── lib/Testing.pm         # in-tree test helper
    └── *.pod                  # test fixtures
```

All dependencies are already present in PerlOnJava and load cleanly:
`Pod::Simple`, `Pod::Simple::XHTML`, `Pod::Simple::SimpleTree`,
`Pod::Simple::Search`, `Text::Tabs`, `Getopt::Long`, `File::Spec`,
`Cwd`, `Config`, `File::Basename`. Verified manually with
`./jperl -e 'use $module'`.

### Sync configuration

Add to `dev/import-perl5/config.yaml`, alongside the existing Pod
entries:

```yaml
- source: perl5/ext/Pod-Html/lib/Pod
  target: src/main/perl/lib/Pod
  type: directory
  # Brings in Pod/Html.pm and Pod/Html/Util.pm
```

If `add_module.pl` / `add_similar_modules.sh` is the canonical entry
point, prefer that over hand-editing the YAML. Run:

```bash
perl dev/import-perl5/sync.pl
```

and verify only the two new files appear under `src/main/perl/lib/Pod/`.

### Bundled tests

Per `.agents/skills/port-cpan-module/SKILL.md` and
`docs/guides/module-porting.md`, copy the upstream tests into the
bundled-module test tree:

```
src/test/resources/module/Pod-Html/
└── t/
    ├── anchorify.t cache.t crossref{,2,3}.t eol.t feature{,2}.t
    ├── htmldir{1..5}.t htmlescp.t htmllink.t htmlview.t
    ├── poderr.t podnoerr.t
    ├── lib/Testing.pm
    ├── corpus/        # test-input PODs (or symlink alias)
    └── *.pod          # any sibling fixtures
```

`ModuleTestExecutionTest.java` will auto-discover these and run them
with `chdir` into `module/Pod-Html/`. Use
`JPERL_TEST_FILTER=Pod-Html` to run only this subtree during
iteration.

### Documentation updates (per skill checklist)

- `docs/reference/bundled-modules.md` — add `Pod::Html` and
  `Pod::Html::Util` to the Pod section.
- `docs/about/changelog.md` — mention `Pod::Html` in the next
  unreleased version's "Add modules" list.
- `docs/reference/feature-matrix.md` — add an entry under "Core
  modules → Pod" with status icon.
- `README.md` — only if Pod::Html is notable enough to surface in the
  feature blurb (probably skip).

### Cosmetic secondary issue (Phase 2, optional)

The `<link rev="made" href="mailto:..." />` in pod2html output is
empty under jperl because Pod::Html (or Pod::Simple::XHTML) reads the
maintainer email from `getpwuid($<)` / `$Config{cf_email}`, both of
which return empty in PerlOnJava. None of the upstream tests assert
on this string, so it doesn't break the bundle, but it's a visible
deviation. Track separately if/when somebody cares.

## Phasing

**Phase 0 — Regex `^/m/g` fix.**
- [ ] Add reduced unit test that fails before the fix.
- [ ] Tighten `if (startPos > matchStart)` predicate around the
      LIST-context `matcher.region(...)` call in
      `RuntimeRegex.matchRegexDirect`.
- [ ] Add `matcher.useAnchoringBounds(false)` to every
      `matcher.region(...)` site in `RuntimeRegex.java`.
- [ ] Verify both backends (`./jperl` and `./jperl --int`) pass.
- [ ] `make` green.

**Phase 1 — Bundle Pod::Html.**
- [ ] Add `perl5/ext/Pod-Html/lib/Pod` entry to
      `dev/import-perl5/config.yaml`.
- [ ] `perl dev/import-perl5/sync.pl` → adds `Pod/Html.pm`,
      `Pod/Html/Util.pm`.
- [ ] `./jperl -e 'use Pod::Html; print "ok\n"'` works.
- [ ] Copy upstream tests into
      `src/test/resources/module/Pod-Html/t/` plus fixtures.
- [ ] `make test-bundled-modules` green for `Pod-Html`.
- [ ] Documentation updates (see "Documentation updates" above).

**Phase 2 — `bin/pod2html` script (optional).**
- [ ] Bundle `perl5/ext/Pod-Html/bin/pod2html` to
      `src/main/perl/bin/pod2html` and provide a `pod2html` wrapper
      shell script next to `jperl`/`jcpan` if there's demand.

**Phase 3 — Cosmetic (optional).**
- [ ] Make `getpwuid($<)` / `$Config{cf_email}` return non-empty so
      the `<link rev="made">` URL has a real address.

## Open questions

1. Is `useAnchoringBounds(false)` the right setting for **all**
   `matcher.region(...)` call sites, or are there places (e.g. the
   notempty-pattern path at line 734) where Perl actually wants the
   region boundary to behave like an anchor? Audit all four sites
   before flipping the switch globally; if any one of them legitimately
   wants the default behaviour, flip per-call.
2. After Phase 0 lands, are there any *other* CPAN ports in
   `make test-bundled-modules` that start passing tests they were
   previously failing? Useful free wins to capture in the changelog.
3. Should the regex unit test live as a `.t` next to the Perl
   regex tests in `src/test/resources/`, or as a JUnit test in
   `src/test/java/.../RegexTest.java`? Pattern in the codebase
   appears to favour `.t` for behavioural parity tests; confirm
   before writing.

## Progress Tracking

### Current Status: ✅ Done (2026-04-25)

### Completed Phases
- [x] Phase 0 — Regex `^/m/g` fix.
  - Tightened the `matcher.region(...)` call site in
    `RuntimeRegex.matchRegexDirect` (LIST-context branch) so it only
    runs after the engine forcibly advances past a zero-length match.
  - Added `matcher.useAnchoringBounds(false)` at every
    `matcher.region(...)` site, restoring Perl's `^`/`$` semantics
    under `/m`.
  - Reduced unit test:
    `src/test/resources/unit/regex/regex_caret_multiline_global.t`
    (15 subtests covering the canonical line-walking idioms).
- [x] Phase 1 — Bundle Pod::Html.
  - Added `perl5/ext/Pod-Html/lib/Pod` entry to
    `dev/import-perl5/config.yaml` and ran `sync.pl` to import
    `Pod/Html.pm` (1.36) and `Pod/Html/Util.pm`.
  - Copied upstream `t/` and `corpus/` to
    `src/test/resources/module/Pod-Html/`.
  - All 18 upstream tests pass under `make test-bundled-modules`.
- [x] Cosmetic fix (folded into Phase 1):
  populate `$Config{perladmin}`, `$Config{cf_email}`, `$Config{cf_by}`,
  `$Config{myhostname}` from the running JVM. Eliminates "Use of
  uninitialized value" warnings inside Pod-Html's test harness
  (`Testing.pm:543` interpolating `$Config::Config{perladmin}`) and
  fills in `<link rev="made" href="mailto:user@host">` in
  `pod2html` output. Was tracked as Phase 3 in the original plan;
  ended up being needed for `feature2.t` to pass.

### Skipped (deferred)
- Phase 2 — `bin/pod2html` wrapper. Not needed by the upstream tests
  and no consumer currently asks for it. Easy follow-up if/when a
  user wants to invoke the script directly from the shell.

### Next Steps
None. Module is fully bundled and passing tests.

## References

- Upstream source (in tree): `perl5/ext/Pod-Html/`
- Affected file: `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java`
  (LIST-context global match block, ~lines 870–911 and the
  `matchRegexDirectAlternate` mirror around line 1186)
- Java docs:
  [`Matcher#region(int,int)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#region-int-int-),
  [`Matcher#useAnchoringBounds(boolean)`](https://docs.oracle.com/javase/8/docs/api/java/util/regex/Matcher.html#useAnchoringBounds-boolean-)
- Existing similar bundling: see how `Pod-Simple`, `Pod-Usage`,
  `podlators`, `Pod-Checker` are wired in
  `dev/import-perl5/config.yaml`.
- Skill: `.agents/skills/port-cpan-module/SKILL.md`
- Authoritative porting guide: `docs/guides/module-porting.md`
- Investigation log (informal): the conversation that produced this
  document, plus `~/.cpan/build/perl-5.42.2-0/` (full perl tarball
  jcpan attempted to build).
