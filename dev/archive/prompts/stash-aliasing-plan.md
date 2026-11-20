# Fix: Stash aliasing (`*Dst:: = *Src::`)

## Problem

Perl's typeglob-to-stash assignment makes the two package namespaces share a single underlying hash:

```perl
*Dst:: = *Src::;
\%Dst:: == \%Src::;       # true
sub Dst::x {}              # also visible as Src::x
*Dst::{HASH} == *Src::{HASH};  # true
```

In PerlOnJava this does not work: the two `%Pkg::` stash-view hashes remain separate objects, and subsequently-installed subs/variables only appear under the namespace they were explicitly qualified with.

Impact observed: `Sub-Name-0.28 t/exotic_names.t` — 522 failing assertions in the "natively compiled sub" block, which does `*palatable:: = *{"aliased::native::${pkg}::"}` and then compiles `sub palatable::sub`. The freshly-compiled sub ends up with `caller(0)` reporting `palatable::palatable::sub` instead of `aliased::native::${pkg}::${subname}`.

Other likely downstream breakage: `namespace::clean`, `Package::Stash`, ::ANON stashes, `Moose::Util::MetaRole` patterns, anything that vivifies a stash by glob-aliasing before installing methods.

## Current Architecture (summary)

(See detailed report in the agent investigation — also at the end of this doc.)

- Everything lives in flat, FQN-keyed maps in `GlobalVariable` (`globalVariables`, `globalArrays`, `globalHashes`, `globalCodeRefs`, `globalIORefs`, `globalFormatRefs`).
- `%Pkg::` is a `RuntimeStash` stored under the flat key `"Pkg::"` in `globalHashes`. It is a *view* — `get("bar")` concatenates `"Pkg::" + "bar"` and looks up each kind of symbol in the flat maps.
- `*Dst:: = *Src::` takes an early-return branch in `RuntimeGlob.set(RuntimeGlob)` (lines 372–377) that only calls `setStashAlias("Dst::", "Src::")` and returns. It does not unify storage and it does not rename any flat-map entries.
- `resolveStashAlias` is consulted in only three places: `InheritanceResolver` (method dispatch / AUTOLOAD), `OverloadContext`, and `SubroutineParser` when installing a sub with an *unqualified* name. Every other lookup ignores it.
- `resolveStashHashRedirect` exists for the HASHREFERENCE case (`*Pkg:: = \%Other::`) and is only consulted by `getGlobalIO`.

## Fix Strategy

Two complementary changes:

### 1. Unify the stash-view hash at the `globalHashes` layer

In `RuntimeGlob.set(RuntimeGlob)`, when both names end with `::`, in addition to `setStashAlias(...)`, make `globalHashes["Dst::"]` point at the same `RuntimeStash` that `globalHashes["Src::"]` already has:

```java
RuntimeHash srcStash = GlobalVariable.getGlobalHash(value.globName);
GlobalVariable.globalHashes.put(this.globName, srcStash);
```

Effect: `\%Dst:: == \%Src::` becomes true. `*Dst::{HASH}` and `*Src::{HASH}` return refs to the same stash.

Caveat: `getGlobalHash` currently creates a fresh `RuntimeStash(newName)` on first access; when we replace the entry, iterations over `%Dst::` need to surface `Src::` entries. Because `RuntimeStash.get` looks up flat keys using the stash's `namespace` field, the stored `RuntimeStash` will answer with `Src::…` keys — which is exactly what we want.

Subtle case: if `globalHashes["Dst::"]` already exists with content, we must either merge or blow it away. Perl's behavior is that `*Dst:: = *Src::` *replaces* `%Dst::` with `%Src::`. We should preserve Perl's semantics: after the assignment, the former `%Dst::` storage is gone and every lookup through `Dst::…` hits `Src::…` instead. See (2) below.

### 2. Make lookups through the aliased namespace hit the target

Two tactical approaches:

**2a — Resolve at lookup time (preferred; minimally invasive):**

Add an alias-resolution helper to `GlobalVariable`:

```java
public static String resolveAliasedFqn(String fqn) {
    if (stashAliases.isEmpty()) return fqn;              // fast path
    int idx = fqn.lastIndexOf("::");
    if (idx < 0) return fqn;
    String pkg = fqn.substring(0, idx + 2);              // "Foo::"
    String resolved = resolvePackageAliasCached(pkg);    // transitive, cached
    if (resolved == pkg) return fqn;                     // identity: no alias
    return resolved + fqn.substring(idx + 2);
}
```

**Caching strategy (important for hot-path performance).** Every global symbol read/write will go through this helper, so we must avoid repeated work:

- **Layer 1 — empty-map fast path.** `stashAliases.isEmpty()` short-circuits the entire resolution. This is the common case (no aliases declared in the program).
- **Layer 2 — transitive resolution cache.** A separate `Map<String,String> resolvedPackageAliases` stores the fully-resolved package name for each alias key — i.e. `"Dst::" -> "Src::"` after walking the chain `Dst -> Mid -> Src`. `resolvePackageAliasCached(pkg)` does `cache.computeIfAbsent(pkg, p -> walkChain(p))` where `walkChain` iterates `stashAliases` up to a hop cap (e.g. 16), detects cycles, and returns the terminal package (or the input if no alias applies). On a cache hit the helper is effectively one `HashMap.get` plus one `String.lastIndexOf("::")` plus one `substring`+concat; no per-lookup chain walking.
- **Cache invalidation.** Every mutation to `stashAliases` (add/remove/clear) must clear `resolvedPackageAliases` wholesale — chain resolutions aren't locally incremental. `setStashAlias`, `clearStashAlias`, and any stash-wipe path call `resolvedPackageAliases.clear()`. This already aligns with the existing `InheritanceResolver.invalidateCache()` / `clearPackageCache()` pattern.
- **Return-identity trick.** When no alias applies, `resolvePackageAliasCached` returns the **same `String` instance** that was passed in, so the caller can check `resolved == pkg` (reference equality) to skip the substring+concat entirely. The cache stores the input string itself for non-aliased packages — one `HashMap.get` for every negative hit.
- **Non-qualified names.** FQNs without `::` (e.g. plain variable names in the main package that arrive pre-normalised, or special variables) hit the `idx < 0` early-return without touching either cache.

Call the helper from the FQN-keyed accessors:
- `getGlobalVariable(name)` / `existsGlobalVariable` / `removeGlobalVariable`
- `getGlobalArray(name)` / `existsGlobalArray` / `removeGlobalArray`
- `getGlobalHash(name)` *when the key does not end in `::`* (i.e. `%Foo::x` not `%Foo::`)
- `getGlobalCodeRef(name)` / `existsGlobalCodeRef` / `defineGlobalCodeRef`
- `getGlobalIO(name)` — already uses `resolveStashHashRedirect`; replace with the unified helper or layer on top
- `getGlobalFormatRef(name)` if present

Also call it from `NameNormalizer.normalizeVariableName` for the already-qualified branch (line 159–161), so that e.g. `sub Dst::x {}` normalises to `"Src::x"` before installation.

Chain handling: already folded into `resolvePackageAliasCached` above; cycles caught by the hop cap.

**2b — Rewrite at assignment time (alternative):**

When aliasing `Dst:: → Src::`, walk the flat maps once and copy-or-reassign all entries whose keys start with `"Dst::"` into `"Src::"` keys (if absent; otherwise the Src entry wins, matching Perl). New writes still need to be intercepted because later `$Dst::x = 1;` compiles to a put under `"Dst::x"`. That means we still need (2a) for future writes. So (2b) is strictly more work than (2a) and gains us nothing; do not pursue.

### 3. Ensure new installations honor the alias

`NameNormalizer.normalizeVariableName` must apply `resolveAliasedFqn` to qualified names (the `variable.contains("::")` branch). This fixes `sub Foo::x{}` after `*Foo:: = *Src::;` so that the compiled sub is stored under `Src::x`.

`SubroutineParser.handleNamedSubWithFilter` already calls `resolveStashAlias` on `packageToUse` for the unqualified case (line 925). The `defineGlobalCodeRef(fullName)` path (line 985–986) will be covered automatically if `defineGlobalCodeRef` calls `resolveAliasedFqn` internally.

### 4. Invalidate caches

The existing alias path already calls `InheritanceResolver.invalidateCache()` and `GlobalVariable.clearPackageCache()`. Keep them. Audit for other caches keyed on FQN (OverloadContext, method caches) and invalidate.

## Proposed Implementation Plan

Break into commits:

**Commit 1 — infrastructure:**
- Add `GlobalVariable.resolveAliasedFqn(String)` with fixed-point iteration and cycle cap.
- Unit tests for the helper: no alias → identity; single hop; chain; cycle; non-qualified names pass through unchanged; `"Pkg::"` (stash-view key) passes through unchanged.

**Commit 2 — hash storage unification at assignment:**
- In `RuntimeGlob.set(RuntimeGlob)` stash branch: after `setStashAlias`, also `globalHashes.put(this.globName, getGlobalHash(value.globName))`.
- Unit test: `*Dst:: = *Src::;` then `\%Dst:: == \%Src::` is true.

**Commit 3 — route lookups through `resolveAliasedFqn`:**
- `getGlobalVariable` / `existsGlobalVariable` / `removeGlobalVariable`
- `getGlobalArray` / `existsGlobalArray` / `removeGlobalArray`
- `getGlobalCodeRef` / `existsGlobalCodeRef` / `defineGlobalCodeRef`
- `getGlobalIO` (replace `resolveStashHashRedirect` with the unified helper — or keep both, document the relationship)
- `NameNormalizer.normalizeVariableName` qualified branch
- Unit tests: `*Dst:: = *Src::;` then:
  - `sub Dst::x {}` is callable as `Src::x`
  - `$Dst::v = 1;` then `$Src::v == 1`
  - `@Dst::a = (1,2);` then `@Src::a == (1,2)`
  - caller() inside `sub Dst::x` reports `Src::x`

**Commit 4 — regression sweep:**
- `make test` — all unit tests
- `perl dev/tools/perl_test_runner.pl perl5_t/t/op/stash.t perl5_t/t/uni/stash.t` — expect improvement or parity (currently 75/105)
- `jcpan -t Sub::Name` — expect `t/exotic_names.t` to approach 1560/1560
- Sanity-check `namespace::clean` and `Package::Stash` tests if present in `src/test/resources/module`

**Commit 5 — documentation:**
- Update `docs/ARCHITECTURE.md` or `dev/design/stash-aliasing.md` describing the invariant that "every flat-map lookup resolves stash aliases first".

## Risks

1. **Iteration loops.** `resolveAliasedFqn` must terminate on malformed cycles. Use a hop cap.
2. **Hot-path overhead.** Stash-alias resolution runs on every global lookup. Keep `stashAliases` empty-check fast-path cheap (`if (stashAliases.isEmpty()) return fqn;`).
3. **Stash deletion/clearing.** `RuntimeStash.deleteNamespace` and `undefine` use prefix-based removal on the flat maps. After aliasing, `delete $Dst::{x}` should delete `Src::x` — verify this works via the view; if `RuntimeStash.get` already routes through the alias-aware accessors, deletes should Just Work.
4. **`Sub::Name::subname` interplay.** Once stash aliasing works, B.pm's `defined &{$fqn}` check may now succeed in some Sub::Name cases and make the `explicitlyRenamed` flag redundant — but the flag is still needed for the pure Sub::Name-with-nonexistent-package case, so keep it.
5. **Performance cache invalidation.** The existing `clearPackageCache` / `invalidateCache` calls at alias-set time must stay. Any new alias-dependent cache must also hook in.

## Follow-up / Non-goals

- `*Dst:: = \%H` (assigning a real hashref, not a stash-glob) already works via `HASHREFERENCE` branch + `resolveStashHashRedirect`; the unified resolution in this plan should subsume that case.
- Unicode package names and exotic-char stash names are orthogonal to aliasing — handled by existing name-normalisation.
- True hierarchical stashes (replacing flat-map storage) is out of scope. We are fixing the view layer only.

## Progress Tracking

### Current Status: Plan drafted (2026-04-22)

### Next Steps
1. Implement Commit 1 (infrastructure + tests).
2. Verify `make` stays green after each commit.
3. After Commit 3, re-run `jcpan -t Sub::Name` and update this doc with numbers.

### Open Questions
- Should `*Dst:: = *Src::` be a two-way alias (symmetric) or one-way (Dst → Src)? Perl 5 semantics: one-way — after the assignment, writing to `$Src::x` *does* show up in `$Dst::x` because they share the same hash, so it's effectively symmetric at the hash level. But `delete $Src::{}; delete $Dst::{}` leave the other un-deleted only if they no longer share. Need to verify exact Perl 5 semantics with a small test before committing to "symmetric alias" vs "one-way redirect".

## References

- Full architecture report: embedded below (from investigation 2026-04-22).
- Related PR: #541 (Sub::Name / B.pm GV->NAME) — independent fix that surfaced this issue.
- Upstream test exposing the bug: `Sub-Name-0.28 t/exotic_names.t` lines 107–124.

---

### Architecture report (verbatim)

See conversation log 2026-04-22. Key file pointers:
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalVariable.java` — flat maps, `setStashAlias`/`resolveStashAlias` (168-190), `resolveStashHashRedirect` (692-702), `getGlobalHash` (361-378), `getGlobalCodeRef` (414-456), `defineGlobalCodeRef` (467-474).
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeGlob.java` — `set(RuntimeScalar)` (206-327), `set(RuntimeGlob)` with stash branch (372-377), `getGlobSlot` HASH case (539-560).
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeStash.java` — view; `get` (92-117), `deleteNamespace` (213-241), `undefine` (385-410).
- `src/main/java/org/perlonjava/runtime/runtimetypes/NameNormalizer.java` — `normalizeVariableName` (124-177); does NOT consult `stashAliases` — fix here.
- `src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java` — `handleNamedSubWithFilter` (917-1101); stash alias rewrite at line 925.
- `src/main/java/org/perlonjava/runtime/mro/InheritanceResolver.java` — lines 316, 346 use `resolveStashAlias`.
- `src/main/java/org/perlonjava/runtime/runtimetypes/OverloadContext.java` — lines 133, 426.
