# Const::Fast — fix `./jcpan -t Const::Fast`

## Status

`./jcpan -t Const::Fast` (LEONT/Const-Fast-0.014) currently fails
`t/10-basics.t` with **8 failures out of 25 tests** (tests 4, 12–15, 18,
20–21). The other test files in the distribution pass.

The module is **pure Perl** — there is no XS compilation issue and no
missing dependency. All failures are caused by gaps in PerlOnJava's
implementation of `Internals::SvREADONLY` and the absence of a
`READONLY_HASH` runtime type for `RuntimeHash`.

## How `Const::Fast` works (relevant slice)

`Const::Fast::const(\[$@%]@)` takes a reference to the variable being
constified and a list of values. It assigns the values, then calls
`_make_readonly($_[0], 1)` to recursively walk the structure. Two
primitives drive everything:

```perl
sub _make_readonly {
    my (undef, $dont_clone) = @_;
    if (my $reftype = reftype $_[0] and not blessed($_[0])
        and not &Internals::SvREADONLY($_[0])) {     # cycle/visited guard
        ...
        &Internals::SvREADONLY($_[0], 1);            # mark this node
        if    ($reftype eq 'SCALAR' || $reftype eq 'REF') {
            _make_readonly(${ $_[0] }, 1);           # descend into referent
        }
        elsif ($reftype eq 'ARRAY') {
            _make_readonly($_) for @{ $_[0] };
        }
        elsif ($reftype eq 'HASH') {
            &Internals::hv_clear_placeholders($_[0]);
            _make_readonly($_) for values %{ $_[0] };
        }
    }
    Internals::SvREADONLY($_[0], 1);
    return;
}

sub const(\[$@%]@) {
    ...
    croak 'Attempt to reassign a readonly variable'
        if &Internals::SvREADONLY($_[0]);            # reassign guard
    ...
}
```

The whole module hinges on `Internals::SvREADONLY` having three
properties:

1. **Setter** marks `$_[0]`'s SV (the argument scalar itself) as
   readonly — *not* the referent.
2. **Getter** returns true after the setter has run on that same
   argument.
3. The combination supports cycle detection: a node already marked
   readonly is skipped on a second visit.

PerlOnJava violates all three for refs and hashes.

---

## Bug 1 — Setter mutates the *referent* instead of the *reference scalar*

Tests affected: **4** ("Modify ref to ref": `const my $ref => \\do{45}; $$ref = 45` should die).

### Where

`src/main/java/org/perlonjava/runtime/perlmodule/Internals.java`,
method `svReadonly`, the REFERENCE branch (lines ~381–393):

```java
} else if (scalar.type == RuntimeScalarType.REFERENCE
        && scalar.value instanceof RuntimeScalar targetScalar) {
    if (targetScalar.type != RuntimeScalarType.READONLY_SCALAR
            && !(targetScalar instanceof RuntimeScalarReadOnly)) {
        // Wrap: save original type+value in an inner scalar,
        // set targetScalar.type = READONLY_SCALAR
        RuntimeScalar inner = new RuntimeScalar();
        inner.type  = targetScalar.type;
        inner.value = targetScalar.value;
        targetScalar.type  = RuntimeScalarType.READONLY_SCALAR;
        targetScalar.value = inner;
    }
}
```

### Why it breaks

Trace `const my $ref => \\do{45}`:

1. `const` calls `_make_readonly(\$ref, 1)`. `$_[0]` is a *temp* RV that
   points at the lexical `$ref`.
2. `_make_readonly` calls `&Internals::SvREADONLY($_[0], 1)`.
   PerlOnJava walks into the REFERENCE branch and stamps
   `READONLY_SCALAR` onto **`$ref` itself** (the `targetScalar`),
   wrapping its current value (`\\do{45}`).
3. The `_make_readonly` body then recurses with `${ $_[0] }`, i.e. with
   `$ref` itself as the new `$_[0]`. The visited-guard check
   `not &Internals::SvREADONLY($_[0])` queries `$ref` — and the query
   branch correctly reports `READONLY_SCALAR` → **true**.
4. Because the guard fires, the recursion exits without marking
   `${ $ref }` (the inner ref `\do{45}`) readonly.
5. `$$ref = 45` now assigns to that still-writable inner ref — no error.

In real Perl, step 2 marks the *temp RV* as `SvREADONLY` (so a second
visit through that very arg slot is detected), and `$ref`'s SV is left
untouched. Step 3's guard then sees `$ref` as still-writable, the
recursion proceeds, and the inner ref is correctly stamped readonly.

### Fix sketch

The setter, when given a `REFERENCE`-typed `scalar`, must mark
**`scalar`** itself readonly while preserving its REFERENCE payload —
not its target. Same wrapping idiom, different victim:

```java
} else if (scalar.type == RuntimeScalarType.REFERENCE) {
    if (scalar.type != RuntimeScalarType.READONLY_SCALAR
            && !(scalar instanceof RuntimeScalarReadOnly)) {
        RuntimeScalar inner = new RuntimeScalar();
        inner.type  = scalar.type;     // REFERENCE
        inner.value = scalar.value;    // referent
        scalar.type  = RuntimeScalarType.READONLY_SCALAR;
        scalar.value = inner;
    }
}
```

Note: the analogous mistake does **not** occur for the
`ARRAYREFERENCE` branch — the existing code there sets
`array.type = READONLY_ARRAY` on the referent, but that is the only
sensible target (there is no scalar slot to mark for an arrayref temp,
and `_make_readonly` for ARRAY *does* want the array marked so the
visited-guard hits on a second visit). So the asymmetry is intentional
for arrays, only the scalar-ref branch needs to change.

### Caveat — do existing PerlOnJava paths depend on the *current* (wrong) behavior?

`grep -n SvREADONLY src/main/perl/lib/` shows three callers besides
`Const::Fast`-style code: `_charnames.pm`, `unicore/Name.pm`,
`constant.pm`. All three call the setter on a plain scalar
(`SvREADONLY($scalar, 1)` / `SvREADONLY(*{$full}{SCALAR}, 1)`), never
on a `\$x`-style reference. So the REFERENCE branch is exercised only
when callers explicitly pass a ref. Switching its semantics to "mark
the ref scalar itself" should not regress those callers.

The query branch (lines ~412–416) already returns "is the *target*
readonly?" when given a REFERENCE — that part actually matches what
`Scalar::Util::readonly` callers tend to want and should stay as-is.

---

## Bug 2 — `READONLY_HASH` does not exist

Tests affected: **12–15** (recursive `%recur => (baz => \%foo)` with
`$foo{bar} = \%foo`) and **20–21** (`const %hash => "another", "hash"`
silently succeeds and overwrites the previously-const hash).

### Where

1. `src/main/java/org/perlonjava/runtime/perlmodule/Internals.java`
   lines 376–379 — the HASHREFERENCE branch of the setter is a
   placeholder:

   ```java
   else if (scalar.type == RuntimeScalarType.HASHREFERENCE
           && scalar.value instanceof RuntimeHash hash) {
       // TODO: implement readonly hash when needed
   }
   ```

2. `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeHash.java`
   has no `READONLY_HASH` constant — only `PLAIN_HASH`,
   `AUTOVIVIFY_HASH`, `TIED_HASH` (lines 18–20). All mutators (`put`,
   `delete`, `setFromList`, `clear` if any) only `switch (type)` on
   those three values.

### Why tests 12–15 fail (recursion)

`_make_readonly` enters with `\%foo` where `$foo{bar} = \%foo`:

1. Visited-guard query `SvREADONLY(\%foo)` falls through the existing
   query code: `\%foo` is `HASHREFERENCE`, so the REFERENCE-branch
   check doesn't match, the fallthrough check sees
   `scalar.type != READONLY_SCALAR` → returns **false**.
2. Setter `SvREADONLY(\%foo, 1)` is a TODO no-op — *nothing* changes.
3. Loop over values, hits `\%foo` again → recurse → step 1 still
   returns false (no state was ever recorded) → infinite recursion →
   `StackOverflowError` surfaces as `lives_ok` failure ("expected
   return but an exception was raised"), and the three follow-up
   `throws_readonly` checks all fail because `%recur` was never
   actually constified.

### Why tests 20–21 fail (mass reassignment)

In `const`, the guard `&Internals::SvREADONLY($_[0])` for
`$_[0] = \%hash` falls through to the same wrong-`false` answer, so
the "Attempt to reassign a readonly" croak is skipped. The function
then falls through to `%{ $_[0] } = @args`, which calls
`RuntimeHash.setFromList(...)` — which has no `READONLY_HASH`
arm — so the readonly hash is silently rebuilt with the new
key/value pairs.

(Note: test 10, `$hash{key1} = "value"`, *does* pass. That's because
`_make_readonly` has already wrapped each *value* RuntimeScalar with
`READONLY_SCALAR`, and `RuntimeScalar.set()` checks that flag. The
slot-level write hits the existing scalar's setter; the whole-hash
`setFromList` does not.)

### Fix sketch

#### Step 2a — add `READONLY_HASH` to `RuntimeHash`

Mirror what `RuntimeArray` already does for `READONLY_ARRAY` (see
`RuntimeArray.java` line 24 + the throw arms at lines 140, 182, 227,
259, 425, 476, 720, 823, 1019, 1040, 1069):

```java
public static final int READONLY_HASH = 3;
```

Add `case READONLY_HASH -> throw new PerlCompilerException(
"Modification of a read-only value attempted");` arms in **every**
mutator switch:

| Method                | Action on `READONLY_HASH`                    |
|-----------------------|----------------------------------------------|
| `put`                 | throw "Modification of a read-only value"    |
| `delete` (both forms) | throw                                        |
| `setFromList`         | throw                                        |
| `setSlice`            | throw                                        |
| `clear` (if exists)   | throw                                        |
| `keys` / `values`     | allow (read-only access is fine)             |
| `iterator`            | allow                                        |
| `exists` / `get`      | allow                                        |

For `get` of a missing key, **do not autovivify** — just return undef
without mutating the underlying map. Without this, code like
`exists $h{baz} || $h{baz}` on a readonly hash would attempt to insert.
This matches the `READONLY_ARRAY` behavior in `RuntimeArray.get`.

#### Step 2b — wire up the setter

In `Internals.svReadonly`, replace the TODO branch:

```java
else if (scalar.type == RuntimeScalarType.HASHREFERENCE
        && scalar.value instanceof RuntimeHash hash) {
    hash.type = RuntimeHash.READONLY_HASH;
}
```

Symmetric handling on the clear path (`flag.getBoolean() == false`):
restore `hash.type = RuntimeHash.PLAIN_HASH` if currently
`READONLY_HASH`. Today the clear branch only handles scalars; mirror
the array clear logic if/when it is added (currently there is no
`READONLY_ARRAY → PLAIN_ARRAY` clear path either, so consistency
is fine).

#### Step 2c — make the query report it

See Bug 3 below.

### Caveat — `hv_clear_placeholders` and restricted-hash semantics

Real Perl's readonly hashes are subtly different from "restricted
hashes" (the `Hash::Util::lock_keys` family). PerlOnJava's
`Internals::hv_clear_placeholders` is already a documented no-op
(`Internals.java` line 97). `Const::Fast`'s POD warns about the
"disallowed key" interaction — we don't need to emulate that, just
flat-out forbid mutation, which is what the tests check.

### Caveat — `Storable::dclone` on circular hashes

`_make_readonly` calls `_dclone($_[0])` when
`SvREFCNT($_[0]) > 1 && !$dont_clone`. The recursive-structure test
constructs a circular hash and passes it through. PerlOnJava's
`Storable::dclone` handles cycles correctly today (Storable has a
seen-table). Even if it didn't, the test case calls `const` with
`$dont_clone = 1` on the inner recursion (note the `1` arg in
`_make_readonly($_) for values %{ $_[0] }` is *not* set, but the
top-level entry from `const` *does* pass `1`). Worth verifying once
Bug 2 is fixed; if dclone-on-cycle blows up, fall back to skipping
clone for `HASH` reftypes whose seen-set already contains them — but
this is unlikely to be needed.

---

## Bug 3 — Query mode ignores `ARRAYREFERENCE`/`HASHREFERENCE`

Tests affected: **18** (wrong error message — `"Modification of a
read-only value"` from inside `Const/Fast.pm` line 57 instead of the
expected `"Attempt to reassign a readonly array at t/10-basics.t line 75"`).
Bug 2 also fundamentally needs this for tests 20–21 to produce the
right message.

### Where

`Internals.svReadonly` query branch (lines ~408–421):

```java
} else if (args.size() == 1) {
    RuntimeBase variable = args.get(0);
    if (variable instanceof RuntimeScalar scalar) {
        if (scalar.type == RuntimeScalarType.REFERENCE
                && scalar.value instanceof RuntimeScalar targetScalar) {
            boolean isRo = targetScalar.type == RuntimeScalarType.READONLY_SCALAR
                    || targetScalar instanceof RuntimeScalarReadOnly;
            return new RuntimeScalar(isRo).getList();
        }
        boolean isRo = scalar instanceof RuntimeScalarReadOnly
                || scalar.type == RuntimeScalarType.READONLY_SCALAR;
        return new RuntimeScalar(isRo).getList();
    }
}
```

The setter happily marks `array.type = READONLY_ARRAY` for an
`ARRAYREFERENCE` argument, but the query above never inspects that
field — it only knows about `READONLY_SCALAR` / `RuntimeScalarReadOnly`.
So `SvREADONLY(\@array)` always returns `false` for a const'd array,
and (after Bug 2 is fixed) `SvREADONLY(\%hash)` would similarly return
`false` for a const'd hash.

In `const`, the reassign guard is therefore skipped, and
`@{ $_[0] } = @args` runs, eventually hitting the *element-level*
readonly check inside `RuntimeArray.set`, which throws with the wrong
message (and from the wrong file/line for the test's regex).

### Fix sketch

Add the two missing cases to the query:

```java
if (scalar.type == RuntimeScalarType.ARRAYREFERENCE
        && scalar.value instanceof RuntimeArray array) {
    return new RuntimeScalar(
        array.type == RuntimeArray.READONLY_ARRAY).getList();
}
if (scalar.type == RuntimeScalarType.HASHREFERENCE
        && scalar.value instanceof RuntimeHash hash) {
    return new RuntimeScalar(
        hash.type == RuntimeHash.READONLY_HASH).getList();
}
```

(Place these before the existing REFERENCE / fallthrough branches.)

---

## Bug-to-test mapping

| Test # | Description                                             | Bug |
|--------|---------------------------------------------------------|-----|
| 4      | Modify ref to ref (`$$ref = 45`)                        | 1   |
| 12     | Recursive structures `lives_ok`                         | 2   |
| 13–15  | Modify recursive struct fields                          | 2   |
| 18     | "Array reassign die" — wrong error message              | 3   |
| 20     | "Hash reassign die" — silent overwrite                  | 2 + 3 |
| 21     | `eq_hash` after reassign — hash got clobbered           | 2   |

Tests 1–3, 5–11, 16–17, 19, 22–25 already pass and must continue to.

---

## Implementation order

1. **Bug 3 first** — adding the two query arms is a pure addition and
   cannot regress anything (no current path produces
   `array.type == READONLY_ARRAY` or `hash.type == READONLY_HASH`
   without going through code we control). Land it standalone.

2. **Bug 2** — add `READONLY_HASH` constant + mutator arms in
   `RuntimeHash`, wire setter in `Internals`. Fixes tests 12–15 and
   20–21. The new query arm from step 1 starts returning useful
   information once this lands.

3. **Bug 1** — flip the REFERENCE-branch setter to mark the ref scalar
   itself. Fixes test 4. Land last because it is the subtlest change
   and most likely to surface unrelated readonly-ref expectations
   elsewhere.

After each step, rerun the verification commands below before moving on.

---

## Verification

```bash
# Module-level test
timeout 120 ./jcpan -t Const::Fast > /tmp/constfast_after.txt 2>&1
grep -E "Result:|Failed|Tests=" /tmp/constfast_after.txt
# Expect: t/10-basics.t .. ok 25/25, Result: PASS

# Full unit test sweep — readonly machinery is touched by constant.pm,
# _charnames.pm, unicore/Name.pm, plus core readonly behavior.
make

# CPAN regression smoke (optional, slow): a handful of modules use
# Const::Fast directly. Spot-check at least:
timeout 600 ./jcpan -t Sub::Quote
timeout 600 ./jcpan -t Sub::Defer
```

If `make` regresses, the most likely culprit is Bug 1 — `constant.pm`'s
generated readonly accessors. Add a focused unit test under
`src/test/resources/unit/` that stresses `\$x` ref-readonly semantics
*before* landing Bug 1 so regressions show up locally rather than
through bundled-module failures.

## Risks / open questions

- **Mass-assignment to `READONLY_HASH` in tied contexts.** PerlOnJava's
  `setFromList` already special-cases `TIED_HASH`. Make sure the new
  `READONLY_HASH` arm is checked *before* `TIED_HASH` in the switch
  (a hash that is both readonly and tied — vanishingly rare — should
  refuse the write).
- **`local %hash` against a readonly hash.** `RuntimeHash` has a
  dynamic-state stack. A `local` save/restore on a `READONLY_HASH`
  must preserve the readonly flag across restore. Audit
  `dynamicStateStack` push/pop sites once Bug 2 lands.
- **`Storable::dclone` interaction with `READONLY_HASH`.** dclone reads
  but does not write; cloning a readonly source should produce a
  *non-readonly* copy. Verify the clone path does not propagate
  `type = READONLY_HASH` (it should always emit `PLAIN_HASH`).
- **Performance.** Adding one extra `case READONLY_HASH` arm per
  mutator switch is free; the JIT optimizes the dead arm to nothing.
  No measurable impact expected.

## Files to modify (summary)

- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeHash.java`
  — add `READONLY_HASH` constant + mutator throw arms.
- `src/main/java/org/perlonjava/runtime/perlmodule/Internals.java`
  — fix REFERENCE setter (Bug 1), implement HASHREFERENCE setter
  (Bug 2), add ARRAYREFERENCE/HASHREFERENCE query arms (Bug 3).

No changes needed in `Const/Fast.pm` itself — it is pure Perl and
correct; PerlOnJava just needs to honor the contract its primitives
already advertise.
