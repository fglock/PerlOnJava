# CPAN Module Patching Plan for PerlOnJava

## Problem Statement

PerlOnJava cannot implement certain Perl features due to JVM limitations:

| Missing Feature | JVM Reason | Impact |
|----------------|-----------|--------|
| `DESTROY` | No deterministic destruction; JVM uses tracing GC | Scope guards, transaction rollback, resource cleanup |
| `weaken`/`isweak` | No reference counting | Circular reference breaking, leak tests |
| `fork` | No process forking | Test harnesses, parallel execution |
| `threads` | No Perl threads | Thread-based concurrency patterns |

CPAN modules that rely on these features need targeted patches to work on PerlOnJava.
The patches must be **sustainable** — they must survive module reinstallation and version
upgrades, and must be maintainable by the PerlOnJava project.

### Current Example: DBIx::Class TxnScopeGuard

DBIx::Class uses `TxnScopeGuard` which relies on `DESTROY` for automatic rollback:

```perl
my $guard = $self->txn_scope_guard;   # txn_begin
# ... work that might die ...
$guard->commit;                        # explicit commit
# if $guard goes out of scope without commit → DESTROY → rollback
```

Without `DESTROY`, the guard silently leaks, `transaction_depth` stays elevated,
and subsequent transactions break. This requires patching 4+ methods in 3 files
(`Storage/DBI.pm`, `ResultSet.pm`, `Row.pm`) to add explicit `eval { } or do { rollback }`
around guard-protected code.

---

## Prior Art: How Other JVM Languages Handle This

### GraalPython (Oracle) — Patch-at-Install

GraalPython maintains a `patches/` directory with version-specific `.patch` files
applied automatically when `pip install` runs. A `metadata.toml` config maps
package names + version ranges to patch files.

**Strengths:**
- Patches applied at install time, transparent to users
- Version-specific patches (e.g., `numpy-2.2.4.patch`)
- Priority system to prefer patched versions
- Scales well — GraalPython has 50+ patches

**Weaknesses:**
- Requires maintaining patches per upstream version
- Patches can break when upstream changes code

### JRuby — Alternative Gems + Bundled Replacements

JRuby uses three strategies:
1. **Alternative gems** — Drop-in replacements (e.g., `therubyrhino` instead of `therubyracer`)
2. **Bundled stdlib overrides** — Ship modified stdlib files that load before gems
3. **Java native extensions** — Rewrite C extensions in Java

JRuby does NOT patch third-party gems. Instead, they encourage gem authors to
add JRuby compatibility or create separate `-jruby` variants.

**Strengths:**
- Clean separation — no patching third-party code
- Gem authors maintain compatibility

**Weaknesses:**
- Requires gem ecosystem cooperation (not available for Perl/CPAN)
- Many gems simply don't work on JRuby

### Jython — Mostly Unsupported

Jython's approach is largely "if it works, it works." Few accommodations are made
for third-party packages that rely on CPython-specific behavior.

---

## Options for PerlOnJava

### Option A: Bundled Overlay Files (Recommended — Hybrid Approach)

Ship patched versions of specific CPAN module files inside the JAR
(`src/main/perl/lib/`), where they shadow the CPAN-installed versions.

**How it works:**

PerlOnJava's `@INC` order is:
1. `-I` arguments
2. `jar:PERL5LIB` (bundled in JAR from `src/main/perl/lib/`)
3. `PERL5LIB` environment variable
4. `~/.perlonjava/lib/` (CPAN-installed modules)

Since JAR files load first, a file at `src/main/perl/lib/DBIx/Class/Storage/DBI.pm`
would shadow the CPAN-installed `~/.perlonjava/lib/DBIx/Class/Storage/DBI.pm`.

**Advantages:**
- Already proven — DBI.pm, ExtUtils::MakeMaker.pm, and 150+ modules already use this
- Always active — no post-install step needed
- Survives `jcpan` reinstall — CPAN writes to `~/.perlonjava/lib/`, JAR is separate
- Version-controlled — patches tracked in git, reviewed in PRs
- Testable — `make` builds JAR, tests run against patched code

**Disadvantages:**
- Couples to specific upstream version — must be updated when upstream changes
- Full-file overlay (not a diff) — harder to see what was changed
- Bloats JAR size slightly (one file per patched module file)

**Mitigation for version coupling:**
- Keep a companion `.patch` file showing the diff from upstream
- Document the upstream version each overlay is based on
- Use `# PerlOnJava:` comments to mark modified lines

**Directory structure:**
```
src/main/perl/lib/
  DBIx/Class/Storage/DBI.pm          # Full patched file
  DBIx/Class/ResultSet.pm            # Full patched file
  DBIx/Class/Row.pm                  # Full patched file

dev/patches/cpan/
  DBIx-Class-0.082844/
    README.md                         # What was patched and why
    Storage-DBI.pm.patch              # Diff from upstream
    ResultSet.pm.patch                # Diff from upstream
    Row.pm.patch                      # Diff from upstream
```

### Option B: jcpan Post-Install Patches (GraalPython-style)

Add a patch-application step to `jcpan` that runs after module installation.

**How it works:**

1. Maintain patches in `src/main/perl/patches/` (bundled in JAR)
2. After `jcpan install Foo::Bar`, check if patches exist for `Foo::Bar`
3. Apply patches to the installed files in `~/.perlonjava/lib/`

**Config format** (inspired by GraalPython's `metadata.toml`):
```yaml
# src/main/perl/patches/config.yaml
- module: DBIx::Class
  version: ">= 0.082840"
  patches:
    - Storage-DBI.pm.patch
    - ResultSet.pm.patch
    - Row.pm.patch
  reason: "TxnScopeGuard DESTROY workaround for JVM"
```

**Advantages:**
- Patches are diffs, not full files — easy to review
- Version-range matching — can target multiple versions
- Follows GraalPython's proven model

**Disadvantages:**
- Requires modifying `jcpan`/CPAN.pm to add post-install hook
- Patches lost if user reinstalls via standard `cpan` instead of `jcpan`
- Patches applied to `~/.perlonjava/lib/` which the user might modify
- More complex than overlay — needs patch application logic

### Option C: Runtime Monkey-Patching Module

Ship a `PerlOnJava::Compat` module that monkey-patches problematic methods at
runtime, loaded automatically from the JAR.

**How it works:**

```perl
# src/main/perl/lib/PerlOnJava/Compat/DBIxClass.pm
package PerlOnJava::Compat::DBIxClass;

# Override _insert_bulk to add eval/rollback wrapper
no warnings 'redefine';
*DBIx::Class::Storage::DBI::_insert_bulk = sub {
    my ($self, @args) = @_;
    eval { $self->_original_insert_bulk(@args) } or do {
        $self->txn_rollback;
        die $@;
    };
};
```

Loaded via an `@INC` hook or `sitecustomize.pl` mechanism.

**Advantages:**
- Surgical — only overrides specific methods
- No full-file copies
- Can be version-adaptive (check `$Module::VERSION`)

**Disadvantages:**
- Fragile — method signatures may change between versions
- Complex — requires knowing exact internal APIs
- Hard to test — monkey-patching order matters
- Can conflict with the module's own monkey-patching

### Option D: Do Nothing (Manual Reapplication)

Document the patches and require users to apply them manually after each
`jcpan install`.

**Advantages:**
- No maintenance burden on PerlOnJava project
- Users control what gets patched

**Disadvantages:**
- Terrible UX — users must know about patches and apply them
- Errors when patches drift from upstream
- Effectively means CPAN modules with DESTROY-dependent code don't work

---

## Recommendation

**Use Option A (Bundled Overlay Files) as the primary strategy**, with
Option B as a future enhancement for modules where full-file overlays
are impractical.

### Rationale

1. **Proven pattern** — PerlOnJava already bundles 150+ modified files in
   `src/main/perl/lib/` including DBI.pm, ExtUtils::MakeMaker.pm, etc.
   Adding DBIx::Class files is the same mechanism.

2. **Zero user friction** — Works immediately after `jcpan install DBIx::Class`.
   No extra steps, no environment variables, no hooks.

3. **Version-safe** — The overlay is pinned to a specific upstream version.
   If the user installs a different version, the overlay still loads first.
   Worst case: the overlay is older than CPAN, but still functional.
   Best case: the user gets the tested, patched version automatically.

4. **Testable** — CI can run `jcpan install DBIx::Class && run-tests` and
   verify patches work. The test results are deterministic.

5. **Incremental** — Start with the 3-4 files that need DESTROY workarounds.
   Add more as needed. Each file is independent.

### When to Use Option B Instead

Option B (jcpan post-install patches) is better when:
- The patched file is very large and the change is small (a 2-line diff
  vs a 3000-line full-file copy)
- Multiple versions need different patches
- The module updates frequently and patches are simple

Consider implementing Option B as a future enhancement alongside the
existing `dev/import-perl5/sync.pl` patch infrastructure.

---

## Implementation Plan

### Phase 1: Infrastructure (Immediate)

1. **Create `dev/patches/cpan/` directory** for tracking upstream diffs
2. **Establish naming convention**: `ModuleName-version/filename.patch`
3. **Add documentation header template** for overlay files

### Phase 2: DBIx::Class TxnScopeGuard Patches (Current Work)

Files to overlay (based on analysis from `feature/txn-scope-guard-fix` branch):

| File | Upstream | Change | Reason |
|------|----------|--------|--------|
| `DBIx/Class/Storage/DBI.pm` | 0.082844 | Wrap `_insert_bulk` in eval+rollback | DESTROY workaround |
| `DBIx/Class/ResultSet.pm` | 0.082844 | Wrap list-context `populate`, void-context populate-with-rels | DESTROY workaround |
| `DBIx/Class/Row.pm` | 0.082844 | Wrap multi-create `insert` | DESTROY workaround |

Steps:
1. Copy upstream files to `src/main/perl/lib/DBIx/Class/`
2. Apply eval+rollback patches, mark with `# PerlOnJava:` comments
3. Generate `.patch` files and store in `dev/patches/cpan/DBIx-Class-0.082844/`
4. Add README documenting what was patched and why
5. Run full DBIx::Class test suite to verify
6. Run `make` to ensure no regressions

### Phase 3: Other Scope Guard Users (Future)

Based on analysis, these DBIx::Class files also use `txn_scope_guard`:

| File | Uses | Priority |
|------|------|----------|
| `Storage/DBI.pm` — `update_all`, `delete_all` | 2 | High — common operations |
| `Ordered.pm` | 3 | Medium — reordering ops |
| `CascadeActions.pm` | 2 | Medium — cascade delete/update |
| `Storage/DBI/Pg.pm`, `Oracle.pm`, `Sybase.pm`, `Informix.pm` | 1 each | Low — DB-specific |

### Phase 4: Generic Guard Pattern (Future)

Consider creating a `PerlOnJava::ScopeGuard` utility that can be used as
a drop-in replacement for any scope guard pattern:

```perl
# In the overlay file:
use PerlOnJava::ScopeGuard;  # eval+rollback wrapper

# Replaces: my $guard = $self->txn_scope_guard;
my $guard = PerlOnJava::ScopeGuard->new(
    commit  => sub { $self->txn_commit },
    rollback => sub { $self->txn_rollback },
);
```

This would reduce per-callsite patching to a one-line change.

---

## Maintenance Guidelines

### Adding a New Overlay

1. Install the module: `./jcpan install Module::Name`
2. Find the installed file: `ls ~/.perlonjava/lib/Module/Name.pm`
3. Copy to `src/main/perl/lib/Module/Name.pm`
4. Add the header comment block:
   ```perl
   # PerlOnJava overlay for Module::Name
   # Based on upstream version X.YZ
   # Changes: [brief description]
   # See: dev/patches/cpan/Module-Name-X.YZ/
   ```
5. Make changes, marking each with `# PerlOnJava: reason`
6. Generate patch: `diff -u original patched > dev/patches/cpan/.../file.patch`
7. Run tests: `perl dev/tools/perl_test_runner.pl t/relevant_test.t`
8. Run `make` to rebuild JAR and run unit tests

### Upgrading an Overlay When Upstream Changes

1. Download new upstream version
2. Apply existing `.patch` files to the new version
3. If patches apply cleanly, update the overlay file and patch version
4. If patches fail, manually re-apply changes and regenerate patches
5. Update the README with the new version number

### Deciding Whether to Overlay a File

Overlay a file when:
- The module has a specific JVM incompatibility (DESTROY, weaken, fork)
- The fix is well-understood and localized
- The module is widely used (justifies maintenance cost)
- No upstream fix is possible (the incompatibility is fundamental to JVM)

Do NOT overlay when:
- The issue can be fixed in the PerlOnJava engine instead
- The module is rarely used
- The fix requires understanding complex module internals
- An alternative module exists that works without patches

---

## Related Documents

- [dbix_class.md](dbix_class.md) — DBIx::Class fix plan (Phase 5)
- [cpan_client.md](cpan_client.md) — jcpan architecture
- [xs_fallback.md](xs_fallback.md) — XS fallback mechanism
- `dev/import-perl5/sync.pl` — Existing patch infrastructure for Perl5 core files
- `dev/import-perl5/patches/` — Existing patches (JSON::PP, File::Unix, etc.)
