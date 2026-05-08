# Plan: `SQL::Statement` on PerlOnJava (`jcpan -t SQL::Statement`)

Goal: `./jcpan -t SQL::Statement` completes with tests passing to the same extent as on Perl 5, without patching the CPAN distribution (fix the runtime / toolchain only).

## Symptoms (current)

1. **Parse / load failure (fixed in-tree):** `SQL::Parser.pm` contains double-quoted strings like `"^$#$predicates^"`. Perl means “last index of the array referenced by `$predicates`” (`$#$predicates`). PerlOnJava previously failed to compile this (`Missing identifier after $#`). **Fix:** string interpolation must treat `$#$ident` / `$#${...}` as `$#` applied to the scalar ref (operand is `$ident` only; JVM codegen already wraps `$#` with `@` once — do not emit an extra inner `@` in the parser).

2. **Runtime failures after load:** Tests die with `Can't call method "full_qualified_column_name" on an undefined value` in `SQL::Statement::Term` / `ColumnValue`, or `prepare` on undef in `TestLib` when using DBD paths. The **direct** `SQL::Statement` path in `t/00error.t` can pass; broader failures come from **`ColumnValue`’s `OWNER` slot becoming undef** while the object is still reachable from `SQL::Statement::Util::Column`.

3. **DBD-related noise:** Tests also iterate “dbd:”-style keys from recommended modules (`/^dbd:/i` matches `DBD::File` because of the first colon). `DBI->connect` may fail when `DBD::CSV` / full DBI stack is missing — that is a **separate** parity track from the `ColumnValue` / `OWNER` bug.

## Root cause (ColumnValue / OWNER)

Investigation shows **`Term::DESTROY` runs on the same `SQL::Statement::ColumnValue` instance** that is still stored in the column list (`refaddr` matches), and `Term::DESTROY` does `undef $self->{OWNER}`, so later `value()` sees undef OWNER.

Trigger pattern (minimal repro on JVM backend):

```perl
sub mk {
    my $owner = bless {}, "O";
    my $term  = TColumn->new($owner, "x");   # weakens OWNER to $owner; has DESTROY
    my $row   = [ qw(a b), $term ];
    my @columns;
    push @columns, $row;
    return @columns;
}
{
    my @got = mk();   # DESTROY can fire before caller finishes using return list
    ...
}
```

So the defect is **not** “weaken is broken in general” or “Util::Column’s hash assignment skips refcount” in isolation — those paths can pass. It **is** tied to **subroutine return of a list built from a lexical array** whose elements are arrayrefs holding tracked blessed refs, combined with **scope-exit cleanup** and **`MortalList` deferred decrements** (`scopeExitCleanupArray` → `deferDecrementRecursive` → `pending`).

Hypothesis for implementers (to confirm in debugger / refcount trace):

- **`return @lexical_array`** materializes a return list that **aliases** the same `RuntimeScalar` wrappers as the soon-to-be-torn-down `@lexical_array`.
- **`SCOPE_EXIT_CLEANUP_ARRAY`** runs for the subroutine body **before** the return value is safely “owned” by the caller (or pending decrements flush at a boundary that is too early).
- **`incrementRefCountForContainerStore`** may **skip** a second increment when `refCountOwned` is already true, so when the **first** container (row array slot) is released at scope exit, the referent’s refcount drops to zero **even though** the return list / wrapper hash still logically holds the same reference from Perl’s perspective.

## Proposed work phases

### Phase A — Lock in regressions (tests only)

- [ ] Add / keep a **unit test** for string interpolation: unbraced `$#$aref` in double quotes (already added in `src/test/resources/unit/string_interpolation.t`).
- [ ] Add a **unit test** for the **return-list / scope-exit** repro (blessed child with `DESTROY` clearing a field, nested in `my @columns` + `return @columns`). Place under `src/test/resources/unit/` (e.g. `destroy_return_list_alias.t`). This must **fail** on the buggy runtime and **pass** after Phase B.

### Phase B — Fix refcount / teardown ordering (runtime)

Pick one coherent strategy (prefer the smallest change that matches Perl 5 semantics):

1. **Return-value protection (preferred direction):** Ensure that for `return EXPR` in list context, any tracked blessed referents reachable from the **returned** `RuntimeList` get an **extra refcount** (or ownership record) **before** `SCOPE_EXIT_CLEANUP_*` walks lexicals that alias the same scalars. After the caller has bound the return value, accounting must match Perl (no leaks).

2. **Multi-container ownership:** Revisit `RuntimeScalar.incrementRefCountForContainerStore` / `refCountOwned` so that **each** distinct container slot that holds a tracked reference increments the referent (or use a proper external ref count), not “first slot wins, second skipped”. Pair with correct **decrement on each** container release. This must not regress DBIC / txn_scope_guard / prior mortal-list fixes (see comments in `RuntimeScalar.java`, `RuntimeArray.java`, `MortalList.java`).

3. **Narrow deferral:** Adjust `MortalList.scopeExitCleanupArray` / `flushAboveMark` / subroutine `popMark` interaction so decrements queued from a **returning** sub’s lexical teardown cannot drop referents to zero until the **caller** has consumed the return list (if that is easier than full multi-container refcount).

**Files likely involved:**

- `src/main/java/org/perlonjava/runtime/runtimetypes/MortalList.java` — `scopeExitCleanupArray`, `deferDecrementRecursive`, `flushAboveMark`, marks.
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java` — `incrementRefCountForContainerStore`, assignment / `setLarge` paths.
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java` — subroutine entry/exit, `MortalList.pushMark` / `popMark`.
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeCompiler.java` — order of `SCOPE_EXIT_CLEANUP_*` vs `RETURN` (verify emitted order for `return @foo`).
- `src/main/java/org/perlonjava/backend/jvm/EmitControlFlow.java` — `return` / local teardown (`cloneScalars` only when `usesLocal`; may need analogous protection for non-local subs).

### Phase C — Verify CPAN module

- [ ] `timeout 600 ./jcpan -t SQL::Statement` — capture full log to a file per `AGENTS.md`.
- [ ] Spot-check: `t/00error.t`, `t/02execute.t` with `SQL::Statement`-only path; then address remaining DBD/DBI gaps if needed (**separate** checklist).

### Phase D — Documentation

- [ ] Update this doc with **date**, **commit / PR**, **before/after** `jcpan` counts, and any **known remaining** failures (DBD::File, etc.).

## Non-goals

- **Do not** modify or delete files under the CPAN extract in `~/.perlonjava/cpan/build/` as a “fix”; changes belong in PerlOnJava or documented patches only if the project later adopts an overlay mechanism.
- **Do not** weaken or remove existing refcount / DESTROY regressions tests; extend coverage instead.

## References

- CPAN: `SQL-Statement` (e.g. 1.414) — `lib/SQL/Parser.pm` (`$#$predicates`), `lib/SQL/Statement/Term.pm` (`weaken($self->{OWNER})`, `Term::DESTROY`).
- In-tree string fix: `StringSegmentParser.java` (`$#$var` interpolation operand must not double-`@` wrap; see `EmitOperatorNode` for `$#`).
