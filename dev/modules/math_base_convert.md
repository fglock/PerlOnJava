# Math::Base::Convert — CPAN Compatibility Plan

## Module Info

- **CPAN**: Math-Base-Convert-0.13
- **Author**: MIKER
- **Type**: Pure Perl (no XS)
- **Purpose**: Arbitrary base-to-base number conversion

## Current Status

After fixing the MakeMaker root-level `.pm` install issue (PR #500), the module
loads and **15/20 test programs pass**. The 5 remaining failures trace back to
exactly **two jperl bugs** plus one missing bundled module.

## Test Results Summary

| Test file | Result | Root cause |
|-----------|--------|------------|
| t/ascii.t | PASS | — |
| t/backend.t | FAIL (132/133) | Bug 1: `\@{&func}` pattern |
| t/basefunct.t | PASS | — |
| t/basemap.t | PASS | — |
| t/basemethods.t | PASS | — |
| t/benchmarkcalc.t | PASS | — |
| t/benchmarkcnv.t | FAIL (156/157) | Bug 1: `\@{&func}` pattern |
| t/convert.t | PASS | — |
| t/frontend.t | FAIL (138/139) | Bug 1: `\@{&func}` pattern |
| t/isnotp2.t | PASS | — |
| t/longmultiply.t | PASS | — |
| t/overload.t | FAIL (0 subtests) | Issue 3: Missing `diagnostics.pm` |
| t/shiftright.t | PASS | — |
| t/useFROMbaseShortcuts.t | PASS | — |
| t/useFROMbaseto32wide.t | PASS | — |
| t/useTObaseShortcuts.t | PASS | — |
| t/validbase.t | PASS | — |
| t/vet.t | PASS | — |
| t/vetcontext.t | PASS | — |
| t/zstrings.t | FAIL (198/4357) | Bug 2: `caller` hasargs flag |

---

## Bug 1: `\@{&func}` — `parsingTakeReference` flag leaks into block context

**Affects**: t/backend.t, t/benchmarkcnv.t, t/frontend.t (3 test files, ~426 subtests)

### Symptom

The expression `\@{&func}` throws "Not an ARRAY reference" even though
`&func` returns a valid blessed arrayref, and `@{&func}` (without the
backslash) works fine.

### Reproducer

```perl
my $ref = bless ["a", "b", "c"], "Test::Class";
sub mysub { return $ref }

\@{mysub()};    # OK — works in both perl and jperl
@{&mysub};      # OK — works in both (array deref, no backslash)
\@{&mysub};     # BUG — "Not an ARRAY reference" in jperl, works in perl
```

### Root cause: parser flag leak

When the parser encounters `\`, it sets `parser.parsingTakeReference = true`
(in `ParsePrimary.java` line 348) to prevent `&sub` from being auto-called.
This is correct for `\&sub` (which should produce a code reference), but the
flag **leaks** into the inner block of `@{...}` because `parseBracedVariable()`
does not save/restore it before parsing the block contents.

**AST comparison:**

`@{&mysub}` (correct — flag is `false`):
```
OperatorNode: @
  BlockNode:
    BinaryOperatorNode: (              ← &mysub is called with @_
      shareCallerArgs: true
      OperatorNode: &
        IdentifierNode: 'mysub'
      OperatorNode: @
        IdentifierNode: '_'
```

`\@{&mysub}` (broken — flag is `true`):
```
OperatorNode: \
  OperatorNode: @
    BlockNode:
      OperatorNode: &                  ← &mysub treated as CODE ref, not called!
        IdentifierNode: 'mysub'
```

**The critical check in the parser** is at `Variable.java` line 747–749:
```java
if (parser.parsingTakeReference && !peek(parser).text.equals("(")) {
    return node;  // Returns &func as CODE reference, no auto-call
}
```

When `parsingTakeReference` is `true` (leaked from the outer `\`), `&mysub` is
returned as a bare code reference. The `@{}` dereference then tries to use this
code reference as an array reference, which fails.

### Code path walkthrough

1. `\` is parsed → `ParsePrimary.java:348` sets `parser.parsingTakeReference = true`
2. `parseExpression(22)` is called to parse the operand at higher precedence
3. `@` is encountered → dispatches to `Variable.parseVariable("@")`
4. `{` follows → `parseBracedVariable("@", false)` at `Variable.java:133`
5. Inside `parseBracedVariable`, tries simple identifier parse, fails (it's `&func`)
6. Falls through to **block parsing** at `Variable.java:1029`
7. `parseBracedVariable` saves/restores `insideBracedDereference` but **does NOT
   save/restore `parsingTakeReference`**
8. `ParseBlock.parseBlock()` is called with `parsingTakeReference` still `true`
9. Inside the block, `&func` → `parseCoderefVariable()` at `Variable.java:747`:
   - `parser.parsingTakeReference` is `true` ← **leaked from step 1**
   - next token is `}` (not `(`), so condition matches
   - Returns `&func` as bare CODE ref — **no call, no `@_` pass-through**

### Fix

Save/restore `parsingTakeReference` in `parseBracedVariable()` before the block
parsing fallback, following the same pattern as `insideBracedDereference`:

**File**: `src/main/java/org/perlonjava/frontend/parser/Variable.java` (~line 1025)

```java
boolean savedInsideBracedDereference = parser.insideBracedDereference;
boolean savedParsingTakeReference = parser.parsingTakeReference;    // ADD
if (sigil.equals("%")) {
    parser.insideBracedDereference = true;
}
parser.parsingTakeReference = false;                                 // ADD
try {
    BlockNode block = ParseBlock.parseBlock(parser);
    // ... existing code ...
} finally {
    parser.insideBracedDereference = savedInsideBracedDereference;
    parser.parsingTakeReference = savedParsingTakeReference;         // ADD
}
```

This pattern is already used elsewhere:
- `OperatorParser.java:44-47` (`parseDoOperator`)
- `OperatorParser.java:804-807` (`parseDefined`)
- `OperatorParser.java:825-828` (`parseUndef`)
- `PrototypeArgs.java:821-844`

### Module code that triggers this

All three failing tests have the same pattern at line 114:
```perl
my @bases = ( \@{&bin}, \@{&dna}, \@{&oct}, \@{&hex}, \@{&bas32}, \@{&b64}, ... );
```

The `&bin`, `&dna` etc. are imported from `Math::Base::Convert` via `:base` tag.
They return blessed arrayrefs. `\@{&func}` should dereference the arrayref and
create a new plain array ref from it.

---

## Bug 2: `caller(0)[4]` hasargs flag always returns 1

**Affects**: t/zstrings.t (198/4357 subtests)

### Symptom

The `hasargs` field of `caller()` (index 4) always returns `1`, even when a
function is called via `&func` (ampersand, no parens) where `@_` is inherited
rather than explicitly passed.

### Reproducer

```perl
sub inner { print "hasargs: ", (caller(0))[4], "\n" }
sub outer { &inner }     # should inherit @_, hasargs = false
outer("arg");
# jperl:  hasargs: 1      ← WRONG
# perl:   hasargs:         ← correct (empty/false)
```

### Perl 5 semantics

| Call style | @_ behavior | hasargs |
|------------|-------------|---------|
| `func(args)` | Fresh @_ created from args | 1 (true) |
| `&func(args)` | Fresh @_ created from args | 1 (true) |
| `&func` (no parens) | Inherits caller's @_ | empty (false) |

### How this breaks Math::Base::Convert

`Bases.pm` overrides `hex()` and `oct()` to serve dual purpose:

```perl
sub oct {
    unless (ref($_[0]) && ...) {
        if ( defined $_[0] && (caller(0))[4] ) {   # ← checks hasargs
            return CORE::oct $_[0];                  # ← delegate to CORE::oct
        }
    }
    return ocT();                                    # ← return base array
}
```

When called as `&Math::Base::Convert::oct` (via `getref("oct")` in the tests),
`@_` is inherited from the caller (containing the string `"oct"`). The function
checks `(caller(0))[4]` to distinguish:
- `hasargs = true` → called as `oct("string")` → delegate to `CORE::oct`
- `hasargs = false` → called as `&oct` with inherited @_ → return base array

When jperl always returns `hasargs = 1`, the function always takes the CORE path,
so `CORE::oct("oct")` returns `0` instead of the expected base array reference.

The test `zstrings.t` calls `getref("oct")` which uses `&{$sub}` (symbolic ref,
no parens):
```perl
sub getref {
    return $_[0] if ref $_[0];
    my $sub = "Math::Base::Convert::" . $_[0];
    no strict;
    &{$sub};    # ← inherits @_ = ("oct"), but hasargs should be false
}
```

All 198 failures are: any base → `oct` or `hex` target, `"data got: |0|, exp: ||"`.

### Current implementation

**File**: `RuntimeCode.java` lines 1973–1978:
```java
// hasargs is always 1 for any subroutine frame (no distinction)
boolean hasArgs = subName != null && !subName.isEmpty() && 
                  !subName.equals("(eval)") && !subName.endsWith("::(eval)");
res.add(hasArgs ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarUndef);
```

The `shareCallerArgs` annotation IS already tracked at parse time and used
by both backends (JVM and bytecode interpreter) to decide how to pass `@_`:
- JVM: `EmitSubroutine.java` lines 613–643 passes caller's slot 1 directly
- Interpreter: `CALL_SUB_SHARE_ARGS` opcode (401) vs `CALL_SUB` (57)

But the information is **not propagated** to `caller()`.

### How both backends handle `&func` vs `func()` calls

**Parser stage** (`Variable.java` `parseCoderefVariable()`):
- `&func` (no parens): builds `&func(@_)` with annotation `shareCallerArgs=true`
- `func()` or `&func()`: builds normal call, no annotation

**JVM backend** (`EmitSubroutine.java`):
- `shareCallerArgs=true`: calls `RuntimeCode.apply(codeRef, callerArgs, ctx)` (3-arg)
  - Passes caller's `RuntimeArray` (slot 1) directly — no copy
- Normal call: calls `RuntimeCode.apply(codeRef, name, argsArray, ctx)` (4-arg)
  - Creates new `RuntimeArray` from args

**Bytecode interpreter** (`BytecodeInterpreter.java`):
- `CALL_SUB_SHARE_ARGS`: calls `RuntimeCode.apply(codeRef, callArgs, ctx)` (3-arg)
- `CALL_SUB`: calls `RuntimeCode.apply(codeRef, "", callArgs, ctx)` (4-arg)

### Fix approach: thread-local `hasArgsStack`

Add a `ThreadLocal<Deque<Boolean>>` parallel to the existing `argsStack`.

**Existing infrastructure** (`RuntimeCode.java`):
```java
// Already exists — tracks @_ per call frame
private static final ThreadLocal<Deque<RuntimeArray>> argsStack = ...;
public static void pushArgs(RuntimeArray args) { ... }
public static void popArgs() { ... }
```

**Add:**
```java
// NEW — tracks whether each frame created fresh @_
private static final ThreadLocal<Deque<Boolean>> hasArgsStack =
    ThreadLocal.withInitial(ArrayDeque::new);
```

**Push** in both `apply()` overloads:
- 3-arg `apply(scalar, array, ctx)` — shared args: push `false`
- 4-arg `apply(scalar, name, args, ctx)` — fresh args: push `true`

**Pop** in the existing `finally` blocks of both `apply()` methods, alongside
the existing `WarningBitsRegistry.popCallerBits()` etc.

**Read** in `callerWithSub()` at the hasargs section (~line 1976):
```java
// Replace the current heuristic with actual stack lookup
Boolean hasArgs = getHasArgsAt(callerDepth);
res.add(hasArgs != null && hasArgs 
    ? RuntimeScalarCache.scalarTrue 
    : RuntimeScalarCache.scalarUndef);
```

### Key files to modify

| File | Change |
|------|--------|
| `RuntimeCode.java` ~line 150 | Add `hasArgsStack` declaration |
| `RuntimeCode.java` ~line 200 | Add push/pop/getter helpers |
| `RuntimeCode.java` 3-arg `apply()` | Push `false` to `hasArgsStack` |
| `RuntimeCode.java` 4-arg `apply()` | Push `true` to `hasArgsStack` |
| `RuntimeCode.java` both `finally` blocks | Pop `hasArgsStack` |
| `RuntimeCode.java` `callerWithSub()` ~line 1976 | Read from `hasArgsStack` |

### Depth mapping challenge

The `hasArgsStack` depth does not directly correspond to the `caller(N)` depth,
because `caller()` counts Perl-visible frames from the JVM stack trace while
`hasArgsStack` counts `apply()` calls. However, both stacks grow/shrink in lockstep
for normal subroutine calls, so the Nth entry from the top of `hasArgsStack`
corresponds to the Nth Perl subroutine frame.

A simpler approach: since `callerWithSub()` iterates through JVM stack frames
counting Perl-visible frames, it can maintain a counter of how many subroutine
frames it has passed through, and use that as the depth index into `hasArgsStack`.

---

## Issue 3: Missing `diagnostics.pm`

**Affects**: t/overload.t (1 test file, ~23 subtests)

### Symptom

`Can't locate diagnostics.pm in @INC`

### Resolution

Bundle `diagnostics.pm` from `perl5/lib/diagnostics.pm` via `sync.pl`, with a
PerlOnJava-specific patch to also search `@INC` for `Pod/perldiag.pod` (the JAR
bundles it as `lib/Pod/perldiag.pod` with capital P, not in `$Config{privlibexp}`).

**Config entry** (`dev/import-perl5/config.yaml`):
```yaml
- source: perl5/lib/diagnostics.pm
  target: src/main/perl/lib/diagnostics.pm
  protected: true  # has @INC search patch for perldiag.pod
```

**Patch** (in `diagnostics.pm` around line 207):
```perl
# PerlOnJava: also search @INC for pod/perldiag.pod and Pod/perldiag.pod
for my $inc (@INC) {
    push @trypod, "$inc/pod/perldiag.pod", "$inc/Pod/perldiag.pod";
}
```

Dependencies: `Text::Tabs` (already bundled), `Config` (already available),
`perldiag.pod` (already in JAR via `perl5/pod` directory import).

### Status: DONE

diagnostics.pm has been imported, patched, and verified working.

---

## Implementation Status

| Item | Status | Details |
|------|--------|---------|
| PR #500: MakeMaker root-level .pm fix | MERGED | `ExtUtils/MakeMaker.pm` |
| Issue 3: diagnostics.pm | DONE | Imported, patched, protected |
| Bug 1: `\@{&func}` parser fix | DONE | `Variable.java` — save/restore `parsingTakeReference` |
| Bug 2: caller hasargs | IN PROGRESS | `RuntimeCode.java` — `hasArgsStack` added, wiring needed |

## Verification

```bash
# Clean previous build
rm -rf ~/.perlonjava/cpan/build/Math-Base-Convert-*
rm -f ~/.perlonjava/lib/Math/Base/Convert.pm
rm -f ~/.perlonjava/lib/Math/Base/Convert/*.pm

# Rebuild and test
make
./jcpan -t Math::Base::Convert

# Expected after all fixes: 20/20 test programs pass
```
