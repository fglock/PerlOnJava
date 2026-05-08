# Role::Basic (CPAN) on PerlOnJava

Implemented deliverable: `Role::Basic` passes under PerlOnJava after fixing nested eval runtime context handling in `RuntimeCode`.

## Acceptance

```bash
timeout 600 ./jcpan -t Role::Basic
make
```

Target: all Role-Basic tests pass, especially `t/exceptions.t` (“Trying to load non-roles should fail”).

## Symptom

`Role::Basic->_load_role('My::Example')` should end in `Carp::confess` with a message matching `Only roles defined with Role::Basic may be loaded`. On jperl, `$@` was empty and the call wrongly returned success.

Upstream logic (simplified): after `eval "use $role $version"`, code does `return 1 if $IS_ROLE{$role}` then checks `requires` / `_sub_package`. If the **outer** lexical `$role` reads as the **inner** re-entrant role (`My::Does::Basic`), `%IS_ROLE` makes the early return succeed and `confess` never runs.

## Investigation notes

- Instrumenting `_load_role` showed the second debug line (outer frame) still saw the inner package name.
- Bisection on a forked `Role/Basic.pm`: removing the stash preamble (`my $stash = do { no strict 'refs'; \%{"${role}::"} };` and related `%INC` logic) made jperl match stock perl — implicating **interaction** of that block with **nested compilation**, not `"\%{"${role}::"}"` vs `$role . '::'` alone (a minimal two-level reentrancy test with only interpolation can still pass).
- Copying stash keys in `HashSpecialVariable.getStash` / `GlobalVariable.getGlobalHash` did **not** fix jcpan; treat as unrelated unless a future test proves otherwise.

## Root Cause And Fix

**Nested `eval STRING` compilation** uses a single `ThreadLocal` for `EvalRuntimeContext` in [`RuntimeCode.java`](../../src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java): inner `evalStringHelper` / `evalStringWithInterpreter` **overwrites** the context and the outer `finally` block calls **`remove()`**, dropping the outer eval’s context while outer compilation may still run (e.g. `use` during compile → nested `_load_role` → nested `eval`). That matches the BEGIN-alias cleanup comment in the same file about **recursive** eval corrupting globals.

**Fix:** replace `ThreadLocal<EvalRuntimeContext>` with a **`ThreadLocal<ArrayDeque<EvalRuntimeContext>>`** (LIFO: `addFirst` on entry, `removeFirst` in outer `finally`). Adjust:

- `getEvalRuntimeContext()` → `peekFirst()` or null if empty  
- `saveAndClearEvalRuntimeContext()` → `removeFirst()` (or null if empty)  
- `restoreEvalRuntimeContext(saved)` → `addFirst(saved)`  
- `clearCaches()` → `evalRuntimeContextStack.remove()`  

Apply consistently in both `evalStringHelper` and `evalStringWithInterpreter`.

The passing implementation also tracks the BEGIN-package aliases installed for eval parsing and deactivates them when `saveAndClearEvalRuntimeContext()` is used around `require` / `do` compilation. The stack alone preserves the runtime context, but Role::Basic also needed those temporary aliases hidden while a `use $role` compile re-enters `_load_role`; otherwise the inner call retrieves and mutates the outer eval's `$role` scalar.

## Neutral regression tests

- Prefer a small `.t` under `src/test/resources/unit/` that does **not** bundle Role::Basic: nested `eval` + `do { no strict 'refs'; \%{"${pkg}::"} }` + outer lexical preservation. If no stable minimal repro, rely on jcpan Role::Basic plus existing patterns like `stash_lexical_reentrancy.t` / `sub_reentrant_lexical_register.t` as guards.

## Constraints

- Do not patch tarballs under `~/.perlonjava/cpan` for the real fix.
- Do not edit CPAN `t/*.t` files.
- Do not add Role::Basic as a bundled unit-test dependency.

## PR checklist

1. Implement the `evalRuntimeContext` stack in `RuntimeCode.java` (and any comment updates, e.g. `BytecodeCompiler`, `dev/design/concurrency.md` if desired).  
2. Revert any experimental stash-key-only runtime diffs unless justified.  
3. Run `timeout 600 ./jcpan -t Role::Basic` and `make`.  
4. Branch from `master`, commit with [AI_POLICY.md](../AI_POLICY.md) attribution (`git commit -F file`).  
5. Push and `gh pr create --body-file /tmp/pr_body.md` (never `--body` with inline backticks).

## Progress Tracking

### Current Status: Completed 2026-05-08

### Completed Work

- [x] Implemented eval runtime context stack in [`RuntimeCode.java`](../../src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java)
- [x] Tracked eval BEGIN aliases so they can be temporarily hidden around nested module compilation
- [x] Added neutral regression test [`eval_context_stack_reentrancy.t`](../../src/test/resources/unit/eval_context_stack_reentrancy.t)

### Verification

- `make` -> pass
- `timeout 60 ./jperl src/test/resources/unit/eval_context_stack_reentrancy.t` -> pass
- `timeout 600 ./jcpan -t Role::Basic` -> pass, 16 files / 304 tests

### Next Steps

1. Commit the fix on `fix/role-basic-eval-context-stack`.
2. Open a PR with the verification above.

### Open Questions

- None.
