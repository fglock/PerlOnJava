# CPAN Distroprefs for PerlOnJava

## Overview

PerlOnJava uses CPAN distroprefs to adapt CPAN distributions to the JVM runtime
when the upstream install flow assumes native Perl behavior that is not
available or not appropriate under `jperl`.

Distroprefs are YAML files read by CPAN.pm. They can match a distribution and
override phases such as `Makefile.PL`, `make`, `make test`, and `make install`,
or apply small patch files before the build. In PerlOnJava, bundled distroprefs
are part of the runtime so `jcpan` users get the same CPAN compatibility rules
without manually configuring their CPAN client.

Canonical files live here:

```text
src/main/perl/lib/PerlOnJava/CpanDistroprefs/*.yml
src/main/perl/lib/PerlOnJava/CpanPatches/<Distribution-Version>/*.patch
```

At CPAN startup, `src/main/perl/lib/CPAN/Config.pm` copies bundled distroprefs
to `~/.perlonjava/cpan/prefs/` and patch files to
`~/.perlonjava/cpan/patches/`. The bootstrap map in `_bootstrap_prefs` is the
source of truth for which bundled distroprefs are shipped.

For the low-level directory and bootstrap layout, see
[Patch and CPAN prefs layout](../../dev/design/patch-and-cpan-prefs-layout.md).

## When to Use Distroprefs

Use a distropref when the CPAN distribution needs install-time behavior that is
specific to PerlOnJava and should be shared by every `jcpan` user.

Good uses:

- Skip a dependency's upstream test phase when the dependency is only being
  staged for another module and its test suite is broader than the runtime
  surface PerlOnJava currently needs.
- Patch a CPAN tarball for a small compatibility issue that is clearly
  PerlOnJava-specific.
- Replace a phase that assumes native build tools, `fork`, process signals, or
  XS compilation with a `jperl` command.
- Prevent an upstream CPAN install from shadowing a patched module bundled in
  the PerlOnJava JAR.
- Run a module's test suite with custom environment, `@INC`, or helper code
  that CPAN.pm cannot infer.

Do not use a distropref as the first response to a failing target module. If
`jcpan -t Some::Module` fails in `Some::Module`'s own tests, normally fix
PerlOnJava or the bundled module implementation. Use a distropref only when the
failure is outside the supported behavior, or when the distropref exists to
exercise a known supported subset while preserving a clear log.

## Decision Rules

Prefer this order:

1. Fix PerlOnJava runtime behavior if the failure is caused by missing Perl
   semantics that the module legitimately needs.
2. Fix PerlOnJava's CPAN tooling if metadata, dependency discovery, build
   phases, or install paths are wrong for more than one distribution.
3. Add a bundled module or Java XS implementation if the module needs native
   behavior at runtime and is important enough to support.
4. Patch the CPAN distribution with a distropref if the upstream source needs a
   small PerlOnJava-specific adjustment.
5. Skip or replace phases with a distropref when the phase is not meaningful on
   PerlOnJava, or when it blocks downstream compatibility without giving useful
   signal.

Skipping `make test` is acceptable for a transitive dependency when all of the
following are true:

- The dependency's files are needed at runtime or build time by another module.
- The failing tests cover behavior outside the downstream module's required
  surface, or cover an already-known PerlOnJava compatibility gap.
- The downstream target's own tests still run and pass.
- The distropref comment documents what is being skipped and why.

Skipping the target distribution's test phase is a last resort. If you do it,
document the supported subset and keep a separate smoke test or downstream test
that proves the behavior PerlOnJava claims to support.

The `jcpan` launchers export `PERLONJAVA_JCPAN_ARGS` with the CPAN arguments
after wrapper-only options such as `--jobs`. Dependency-only skips can use an
`env` `not_PERLONJAVA_JCPAN_ARGS` match to stay out of direct target runs.
For example:

```yaml
match:
  distribution: "^AUTHOR/Example-Module-"
  env:
    not_PERLONJAVA_JCPAN_ARGS: "(^|[[:space:]])Example::Module($|[[:space:]])"
```

## Basic YAML Shape

A distropref should include a detailed `comment`, a narrow `match`, and only the
phase overrides that are needed:

```yaml
---
comment: |
  PerlOnJava distroprefs for Example::Module.

  Explain the compatibility problem, why this is a PerlOnJava-specific install
  adjustment, and what verification proves the module still works.
match:
  distribution: "^AUTHOR/Example-Module-"
test:
  commandline: "PERLONJAVA_SKIP"
```

Use the narrowest practical `match`. Matching the CPAN author path and
distribution prefix is usually enough for rolling upstream releases:

```yaml
match:
  distribution: "^OALDERS/HTML-Parser-"
```

This also applies to most source patches when the distribution's tests still
run. CPAN applies `patches:` before the build; if a new upstream release changes
too much, patch application fails and the install stops. If the patch applies,
the module's CPAN tests are the verification that the patched tree still works.

Pin to a specific version only when the workaround must not be tried against a
future release:

```yaml
match:
  distribution: "^.*/Example-Module-1\\.23"
```

Version-specific patches and matches can be documented when they are truly
needed, but they are not the preferred default. They become obsolete quickly
when the CPAN author publishes a new release, which can leave the new tarball
unpatched and make the old distropref look more authoritative than it is.
Prefer a distribution-prefix match for phase overrides and patches that are
validated by the module's own tests. Reserve version-pinned matches for cases
where tests are skipped or too weak to validate the patched behavior, or where
the patch is known to target one exact upstream layout.

## Phase Overrides

CPAN.pm supports phase-specific command overrides. PerlOnJava distroprefs most
commonly use these keys:

```yaml
pl:
  commandline: "..."
make:
  commandline: "..."
test:
  commandline: "..."
install:
  commandline: "..."
```

Use `PERLONJAVA_SKIP` to turn a phase into a successful no-op:

```yaml
test:
  commandline: "PERLONJAVA_SKIP"
```

`PERLONJAVA_SKIP` is handled specially by PerlOnJava's patched
`CPAN::Distribution`; it is not a shell command. It reports phase-specific
success messages such as:

```text
PERLONJAVA_SKIP -- test phase skipped
```

Use it when the phase is genuinely not useful or cannot run on PerlOnJava. Do
not use it to hide a regression that should be fixed.

For custom phase commands, prefer cross-platform `jperl -MHelper -e "..."` calls
over shell-specific syntax. Avoid `;`, `&&`, redirection, `/dev/null`, and other
POSIX shell assumptions in bundled distroprefs. CPAN.pm runs command lines
through Perl's `system()`, and Windows users may go through `cmd.exe`.

Example:

```yaml
test:
  commandline: 'jperl -MPerlOnJava::Distroprefs::Moose -e "PerlOnJava::Distroprefs::Moose::test_phase()"'
```

## Patch Files

Use `patches:` when a small source change is better than overriding a whole
phase:

```yaml
---
comment: |
  PerlOnJava distroprefs for Image::BMP.

  Explain the PerlOnJava-specific runtime or test behavior the patch fixes.
match:
  distribution: "^.*/Image-BMP-"
patches:
  - "Image-BMP-1.26/BMP.pm.patch"
```

Patch paths are relative to `src/main/perl/lib/PerlOnJava/CpanPatches/`. Keep
patches narrow and readable. The distropref comment should explain why the
patch belongs in PerlOnJava instead of upstream, or note if it is a candidate
for upstreaming.

Do not pin the `match` solely because the patch path contains a version. The
versioned directory records the tarball the patch was written against; the
`match` should express when PerlOnJava should try the patch. Prefer a
distribution-prefix match when patch failure and the module's CPAN tests provide
useful verification on future releases.

Use a version-specific match only when applying the patch to a future release
would be misleading or poorly checked, such as when the test phase is skipped,
test failures are ignored, or the patch changes behavior that the distribution's
tests do not cover. Version-specific patches can be documented, but they are
short-lived by nature: a new upstream release will obsolete the match until
someone refreshes or removes it.

Do not confuse these install-time CPAN patches with core import patches under
`dev/import-perl5/patches/`. Core import patches affect files copied from the
Perl 5 source checkout into this repository. CPAN patches affect tarballs that
end users install with `jcpan`.

## Comments Are Required

Every bundled distropref should have a `comment` that answers:

- What distribution is being handled?
- Which PerlOnJava compatibility issue requires a distropref?
- Which phase is patched, skipped, or replaced?
- What downstream module or test suite depends on this behavior?
- What should be revisited when PerlOnJava improves?

Good comments are operational documentation. They prevent future agents from
removing a workaround as dead config or copying a skip into unrelated
distributions.

## Adding a Bundled Distropref

1. Reproduce the failure and capture full output:
   ```bash
   timeout 1800 ./jcpan -t Target::Module > /tmp/target-before.log 2>&1
   ```
2. Decide whether the failure belongs in PerlOnJava runtime, CPAN tooling, a
   CPAN patch, or a phase override.
3. Add a YAML file under
   `src/main/perl/lib/PerlOnJava/CpanDistroprefs/`.
4. If using patches, add patch files under
   `src/main/perl/lib/PerlOnJava/CpanPatches/<Distribution-Version>/`.
5. Register the distropref in `_bootstrap_prefs` in
   `src/main/perl/lib/CPAN/Config.pm`.
6. Run `make` so the bundled runtime and tests are rebuilt:
   ```bash
   timeout 1200 make > /tmp/make-cpan-pref.log 2>&1
   ```
7. Run the target CPAN verification:
   ```bash
   timeout 1800 ./jcpan -t Target::Module > /tmp/target-after.log 2>&1
   ```
8. Read the log and confirm the target distribution's own tests still run unless
   the distropref intentionally documents otherwise.
9. Check for leftover high-CPU JVMs:
   ```bash
   ps aux | awk '$3 > 20 {print $2, $3, $11, $12}'
   ```

When adding a dependency-only skip, keep the target verification focused on the
original goal. For example, skipping `String::Print`'s upstream tests is only
defensible if `Log::Report` then runs its own suite and passes against the
staged `String::Print`.

## Local User Overrides

Bundled distroprefs are copied to `~/.perlonjava/cpan/prefs/`. A developer can
experiment there, but repository changes should be made in the canonical
`src/main/perl/lib/PerlOnJava/CpanDistroprefs/` files and registered in
`CPAN/Config.pm`.

If you need to compare behavior without a bundled pref, use a separate
temporary PerlOnJava home or move the copied file aside after backing up the
working tree. Do not delete user-local CPAN state as part of a repository
change.

## Review Checklist

- [ ] The `match` is narrow enough.
- [ ] The `comment` explains the compatibility issue and downstream need.
- [ ] The distropref does not skip the target module's tests unless explicitly
      justified.
- [ ] Any patch is small, documented, and either tested by a
      distribution-prefix match or justified as version-specific.
- [ ] The file is added to `_bootstrap_prefs`.
- [ ] `make` passes.
- [ ] The relevant `./jcpan -t ...` command passes or has a documented expected
      result.
- [ ] Full logs were captured to `/tmp` or another file before summarizing.

## See Also

- [Using CPAN Modules](using-cpan-modules.md)
- [Porting Perl Modules to PerlOnJava](module-porting.md)
- [Patch and CPAN prefs layout](../../dev/design/patch-and-cpan-prefs-layout.md)
- [Testing Reference](../reference/testing.md)
