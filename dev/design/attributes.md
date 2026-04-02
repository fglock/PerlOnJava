# Attribute System Implementation Design

## Overview

Implement Perl's `attributes` pragma for PerlOnJava, enabling `attributes::get()`, `attributes->import()`, and the full `MODIFY_*_ATTRIBUTES` / `FETCH_*_ATTRIBUTES` callback chain for CODE, SCALAR, ARRAY, and HASH types.

**Baseline:** 62/216 tests passing (28.7%) across 4 test files.
**Target:** ~140+/216 tests (65%+).

## Perl Semantics (from `perldoc attributes`)

### Core Behavior

When Perl encounters attribute declarations, it translates them into calls to the `attributes` module:

```perl
# Sub attributes (compile-time)
sub foo : method;
# equivalent to:
use attributes __PACKAGE__, \&foo, 'method';

# Variable attributes (my = run-time, our = compile-time)
my ($x, @y, %z) : Bent = 1;
# equivalent to:
use attributes ();
my ($x, @y, %z);
attributes::->import(__PACKAGE__, \$x, 'Bent');
attributes::->import(__PACKAGE__, \@y, 'Bent');
attributes::->import(__PACKAGE__, \%z, 'Bent');
($x, @y, %z) = 1;

# Typed variable (package comes from type, not current package)
package Dog;
my Canine $spot : Watchful;
# equivalent to:
attributes::->import(Canine => \$spot, "Watchful");
```

### Built-in Attributes

| Type | Attribute | Purpose |
|------|-----------|---------|
| CODE | `lvalue` | Marks sub as valid lvalue |
| CODE | `method` | Marks sub as method (suppresses ambiguity warnings) |
| CODE | `prototype(...)` | Sets prototype |
| CODE | `const` | Experimental: calls anon sub immediately, captures return as constant |
| SCALAR/ARRAY/HASH | `shared` | Thread-sharing (no-op in PerlOnJava) |

### `import()` Flow

1. Get `reftype` of the reference (CODE, SCALAR, ARRAY, HASH)
2. Check if `$home_stash` has `MODIFY_<type>_ATTRIBUTES` (via `UNIVERSAL::can`)
3. If handler exists:
   - First apply built-in attributes via `_modify_attrs()` (returns non-built-in attrs)
   - Pass remaining attrs to `MODIFY_<type>_ATTRIBUTES($pkg, $ref, @remaining)`
   - If handler returns empty list AND remaining attrs are all-lowercase: emit "may clash with future reserved word" warning
   - If handler returns non-empty list: croak with "Invalid <TYPE> attribute(s)"
4. If no handler: apply built-in attrs; anything unrecognized is an error

### `get()` Flow

1. Get `reftype` of the reference
2. Determine stash via `_guess_stash()` (for CODE: original package; fallback to `caller`)
3. Get built-in attributes via `_fetch_attrs()`
4. If stash has `FETCH_<type>_ATTRIBUTES`: call it and merge results
5. Return combined list

### Error Messages (must match exactly)

```
Invalid CODE attribute: "plugh"
Invalid CODE attributes: "plugh" : "xyzzy"
Invalid SCALAR attribute: "plugh"
Invalid SCALAR attributes: "switch(10,foo(7,3))" : "expensive"
Unterminated attribute parameter in attribute list
Invalid separator character '+' in attribute list
Invalid separator character ':' in attribute list
SCALAR package attribute may clash with future reserved word: "plugh"
SCALAR package attributes may clash with future reserved words: "plugh" : "plover"
lvalue attribute applied to already-defined subroutine
lvalue attribute removed from already-defined subroutine
Useless use of attribute "const"
```

## Current State

### What Already Works

| Component | Status | Location |
|-----------|--------|----------|
| Sub attribute parsing (`:attr`, `:attr(args)`) | Done | `SubroutineParser.consumeAttributes()` (line 633) |
| Variable attribute parsing | Parsed but **ignored** | `OperatorParser.parseOperatorMyOurState()` (line 413) |
| `:prototype(...)` extraction | Done | `SubroutineParser.consumeAttributes()` (line 650) |
| `RuntimeCode.attributes` storage | Done | `RuntimeCode.java` (line 267) |
| `SubroutineNode.attributes` in AST | Done | `SubroutineNode.java` (line 20) |
| `MODIFY_CODE_ATTRIBUTES` dispatch | Done | `SubroutineParser.callModifyCodeAttributes()` (line 1046) |
| `:=` error detection | Done | `SubroutineParser.consumeAttributes()` (line 638) |
| Empty attr list (`: =`) handling | Done | `SubroutineParser.consumeAttributes()` (line 640) |
| `attributes.pm` module | Done | `src/main/perl/lib/attributes.pm` |
| `Attributes.java` backend | Done | `src/main/java/org/perlonjava/runtime/perlmodule/Attributes.java` |
| `_modify_attrs`, `_fetch_attrs`, `_guess_stash`, `reftype` | Done | `Attributes.java` |
| `:prototype(...)` via `use attributes` | Done | `Attributes.applyCodeAttribute()` |
| Compile-time warning scope for categorized warnings | Done | `Warnings.emitCategoryWarning()` |
| Warning category alias sync (illegalproto <-> syntax::illegalproto) | Done | `WarningFlags.java` hierarchy |

### What Is Missing

| Component | Impact |
|-----------|--------|
| `attributes::get()` returning built-in attrs (lvalue, method) | ~8 tests (attrs.t 35-40) |
| `FETCH_*_ATTRIBUTES` merging in `get()` | ~2 tests (attrs.t 35, 40) |
| Variable attribute dispatch (`MODIFY_SCALAR/ARRAY/HASH_ATTRIBUTES`) | ~12 tests |
| `:const` attribute support | ~2 tests (attrs.t 140, 145) |
| Closure prototype handling | ~4 tests (attrs.t 124-126, uni 30-31) |
| `my sub` attribute parsing after prototype | ~4 tests (attrproto.t 48-51) |
| Error message `"BEGIN failed--compilation aborted"` suffix | ~3 tests (attrs.t 155-157) |
| `Can't declare scalar dereference` error detection | ~2 tests (attrs.t 44-45) |
| `MODIFY_CODE_ATTRIBUTES` returning custom error | ~2 tests (attrs.t 32, uni 16) |
| Variable attribute tie integration | ~2 tests (attrs.t 41-42) |
| `Attribute::Handlers` integration | 4 tests (attrhand.t) — low priority |

## Implementation Strategy

The key insight is that Perl's `attributes.pm` relies on three XS functions (`_modify_attrs`, `_fetch_attrs`, `_guess_stash`) that operate on Perl internals. In PerlOnJava, we implement these as a Java module (`Attributes.java`) that directly accesses `RuntimeCode.attributes` and other runtime structures.

The architecture:

```
attributes.pm (Perl)           Attributes.java (Java)
├── import()                   ├── _modify_attrs(svref, @attrs)
│   └── calls _modify_attrs    │     └── validates built-in attrs
│   └── calls MODIFY_*_ATTRS   │     └── applies lvalue/method/prototype
├── get()                      ├── _fetch_attrs(svref)
│   └── calls _fetch_attrs     │     └── reads RuntimeCode.attributes
│   └── calls FETCH_*_ATTRS    ├── _guess_stash(svref)
├── reftype()                  │     └── returns packageName for CODE refs
│   └── calls Java ref()       └── reftype(svref)
└── require_version()                └── returns underlying ref type
```

Variable attribute dispatch happens in the **emitter/compiler** — when a `my`/`our`/`state` declaration has attributes in its AST annotations, the emitter generates code to call `attributes::->import(PKG, \$var, @attrs)` at the appropriate time (compile-time for `our`, run-time for `my`/`state`).

## Files to Modify

### New Files (already created)

| File | Purpose |
|------|---------|
| `src/main/java/org/perlonjava/perlmodule/Attributes.java` | XS-equivalent functions |
| `src/main/perl/lib/attributes.pm` | Perl `attributes` pragma |

### Modified Files

| File | Change |
|------|--------|
| `SubroutineParser.java` | Prototype warnings, attribute parsing, error message improvements |
| `Warnings.java` | `emitCategoryWarning()`, `emitWarningFromCaller()` |
| `WarningFlags.java` | Warning category alias sync |
| `StatementResolver.java` | (Pending) `my sub` attribute parsing after prototype |
| `EmitVariable.java` or `EmitOperator.java` | (Pending) Variable attribute dispatch |

## Related Documents

- `dev/prompts/test-failures-not-quick-fix.md` — Section 8 (Attribute System)
- `dev/design/defer_blocks.md` — Similar implementation pattern

---

## Progress Tracking

### Current Status: Phase 8 — \K regex fix + variable attribute list dispatch complete

### Completed Phases

- [x] Phase 1: Core attribute infrastructure (2026-04-01 — 2026-04-02)
  - Created `Attributes.java` with `_modify_attrs`, `_fetch_attrs`, `_guess_stash`, `reftype`
  - Created `attributes.pm` (ported from system Perl)
  - Implemented `attributes::get()` and `attributes->import()` flow
  - Implemented `:prototype(...)` via `use attributes` with proper warning emission
  - Fixed compile-time warning scope for categorized warnings (`emitCategoryWarning()`)
  - Synced warning category aliases (illegalproto <-> syntax::illegalproto, etc.)
  - Added `emitWarningFromCaller()` for unconditional warnings with correct location
  - Fixed error message separator in `callModifyCodeAttributes` (`, ` → ` : `)
  - Added prototype/illegalproto validation and warnings to `SubroutineParser.consumeAttributes()`
  - Files: `Attributes.java`, `attributes.pm`, `Warnings.java`, `WarningFlags.java`, `SubroutineParser.java`

- [x] Phase 5 (partial): :const attribute and MODIFY_CODE_ATTRIBUTES dispatch (2026-04-02)
  - Deep-copy bug fix in const folding (`Attributes.java`)
  - InterpretedCode override bypass for constantValue
  - MODIFY_CODE_ATTRIBUTES dispatch for interpreter backend
  - Files: `Attributes.java`, `InterpretedCode.java`, `OpcodeHandlerExtended.java`, `BytecodeCompiler.java`

- [x] Phase 6 (partial): Detect scalar dereference in declarations (2026-04-02)
  - Added `checkForDereference()` in `OperatorParser.java`
  - Throws "Can't declare scalar dereference in 'my'" etc.

- [x] Phase 7 (partial): isDeclared flag + Attribute::Handlers + error format (2026-04-02)
  - Added `isDeclared` flag to `RuntimeCode` for explicitly declared subs
  - Updated `getGlobSlot("CODE")` to return code refs for declared subs
  - Fixed `Attribute::Handlers` `findsym()` — now all 4 attrhand.t tests pass
  - Fixed variable attribute error format with BEGIN failed suffix
  - Files: `RuntimeCode.java`, `RuntimeGlob.java`, `SubroutineParser.java`

- [x] Phase 8: \K regex fix + variable attribute list dispatch (2026-04-02)
  - Fixed `\K` (keep left) regex assertion in `m//` and `s///`
  - `\K` was silently stripped from patterns; now inserts `(?<perlK>)` named capture
  - Substitution preserves text before `\K` position, adjusts match variables
  - Capture group numbering offsets internal perlK group for user captures
  - Restored compile-time/runtime attribute validation (was blocked by \K bug)
  - Fixed list variable attribute dispatch: `my ($x,$y) : attr` now properly
    propagates attribute annotations from parent node to each child in both
    JVM emitter (EmitVariable.java) and interpreter (BytecodeCompiler.java)
  - Files: `RegexPreprocessorHelper.java`, `RegexPreprocessor.java`, `RuntimeRegex.java`,
    `OperatorParser.java`, `Attributes.java`, `EmitVariable.java`, `BytecodeCompiler.java`

### Current Test Results (2026-04-02, PR #423)

| File | Original | After PR #420 | After PR #423 | Total Delta |
|------|----------|---------------|---------------|-------------|
| attrs.t | 49/130 | 152/158* | 158/159** | +109 |
| attrproto.t | 3/52 | 51/52 | 51/52 | +48 |
| attrhand.t | 0/0 | 4/4 | 4/4 | +4 |
| uni/attrs.t | 10/34 | 29/34 | 35/35 | +25 |
| decl-refs.t | — | 346/408 | 346/408 | — |
| **Total** | **62/216** | **236/248** | **248/254** | **+186** |

\* attrs.t grew from 130 to 158 tests because the test no longer crashes partway through.
\*\* Only failure is TODO test 155 (RT #3605: ternary/attribute parsing ambiguity).

### Remaining Failures Analysis (updated 2026-04-02)

#### attrproto.t: 1 remaining (48)

**Root cause: `my sub` prototype not stored on RuntimeCode in interpreter backend**

| Test | Issue |
|------|-------|
| 48 | `eval 'my sub lexsub1(bar) : prototype(baz) {}; prototype \&lexsub1'` returns empty — `\&lexsub` produces REF instead of CODE in interpreter |

**Root cause:** In the interpreter (eval STRING), `\&lexsub` returns a REF reference (to the scalar holding the code) instead of a CODE reference. This is an interpreter parity issue with how lexical subs are stored and referenced — not an attribute-specific bug. Tests 49-52 now pass (warnings are correct).

#### attrs.t: 1 remaining (155 — TODO)

| Test | Issue |
|------|-------|
| 155 | (TODO test) `$a ? my $var : my $othervar` — `:` parsed as attr separator instead of ternary |

**Status:** This is a known Perl 5 edge case (RT #3605). The TODO marker means it's expected to fail.

#### uni/attrs.t: 0 remaining — FULLY PASSING (35/35)

### Next Steps

Most attribute tests now pass. The remaining work is lower-priority:

#### Interpreter parity: `\&lexsub` in eval STRING (1 test: attrproto.t 48)

In the interpreter, `\&lexsub` creates a REF (to the scalar holding the code) instead of a CODE reference. This affects `prototype \&lexsub` and is a general interpreter parity issue, not attribute-specific.

- **Files:** Interpreter's variable reference handling
- **Effort:** Medium — requires changes to how lexical subs are dereferenced in the interpreter

### Estimated Final Results

| Status | Tests Passing | Pass Rate |
|--------|-------------|-----------|
| Current (PR #423) | 248/254 | 97.6% |
| If interpreter \&lexsub fixed | 249/254 | 98.0% |

The only remaining attrs.t failure is TODO test 155 (expected failure).

### Open Questions

1. **Variable attribute storage**: Should variables store their attributes? Currently `RuntimeCode` has an `attributes` field, but `RuntimeScalar`/`RuntimeArray`/`RuntimeHash` do not. Most test cases only need the `MODIFY_*_ATTRIBUTES` callback (side effects like `tie`), not persistent storage. The `FETCH_*_ATTRIBUTES` tests are only for CODE refs. **Decision: Don't add storage to variables yet — not needed for any current test.**

2. **`_modify_attrs` implementation level**: The system Perl implements this as XS that directly manipulates SV flags. In PerlOnJava, we access `RuntimeCode.attributes` from Java. For CODE refs this is straightforward. For variable refs, we only need to validate built-in attrs (`shared`) and return unrecognized ones — no actual flag-setting needed since `shared` is a no-op.

3. **Attribute::Handlers**: The module exists at `src/main/perl/lib/Attribute/Handlers.pm` and the core dependencies (`attributes.pm`, CHECK blocks, MODIFY_CODE_ATTRIBUTES) are now implemented. All core attrhand.t tests pass (4/4). Remaining edge cases are in multi.t (DESTROY, END handler warning) and linerep.t (eval context file/line).

4. **`our` variable attribute timing**: The perldoc says `our` attributes are applied at compile-time. This means the emitter needs to call `attributes::->import()` immediately during parsing (like `callModifyCodeAttributes` does for subs), not defer to runtime. **Decision: Handle in Phase 3.**

### Regressions in Other Tests (vs PR #417 baseline)

Three tests regressed compared to the PR #417 baseline. These are NOT attribute test files,
but they broke due to changes in the attribute-system branch.

#### op/decl-refs.t: 322/408 → 174/408 (-148)

**Root cause**: Two bugs in `callModifyVariableAttributes()` for `state` declared refs.

1. **`state \@h : risible`** — `MODIFY_ARRAY_ATTRIBUTES` handler NOT called.
   The handler exists and is found (`hasHandler=true`), but for `my`/`state` variables
   the code at line 1294 says "handler will be dispatched at runtime by the emitter".
   For declared refs (`state \@h`), the runtime dispatch is not working — the handler
   is silently skipped. (For non-declared-ref forms like `state @h : risible`, it works.)

2. **`state (\@h) : bumpy`** — sigil wrongly detected as `$` (SCALAR) instead of `@` (ARRAY).
   The parenthesized declared-ref form `(\@h)` produces an AST where the OperatorNode's
   operator is `\` (backslash), not `@`. The code at line 1197 (`String sigil = opNode.operator`)
   gets `\`, which doesn't match any sigil case and `continue`s. But somehow the error says
   "Invalid SCALAR attribute" — possibly a fallback that defaults to SCALAR. This causes
   `die $@` in the template, aborting the test.

**Reproduction**:
```bash
./jperl -e '
use feature "declared_refs", "state";
no warnings "experimental::declared_refs";
sub MODIFY_ARRAY_ATTRIBUTES { print "handler: @_\n"; return; }
eval q{ state \@h : risible };   # handler NOT called (silent)
eval q{ state (\@h) : bumpy };   # "Invalid SCALAR attribute: bumpy" error
'
```

**Fix needed**: In `callModifyVariableAttributes()`, handle the declared-ref case where
the operand is a backslash OperatorNode — unwrap it to get the inner sigil. Also ensure
the runtime dispatch path works for `state` declared refs.

#### op/lexsub.t: 105/160 → 0/0 (-105)

**Root cause**: Syntax error at line 370 (`p my @b;`) prevents the entire file from compiling.

On the baseline, the file compiled and ran 105 tests. On our branch, a syntax error at
line 370 causes 0 tests to run. The line is:
```perl
{
  state sub p (\@) { ... }   # line 366: state sub with (\@) prototype
  p(my @a);                   # line 369: works (parenthesized)
  p my @b;                    # line 370: SYNTAX ERROR (unparenthesized)
}
```

The `(\@)` prototype should tell the parser that `p` takes an array by reference,
allowing `p my @b` without parentheses. On the baseline, this prototype was applied
during parsing. On our branch, scope management changes (SubroutineParser.java:
enter scope before parseSignature, exit after block body) may have affected how
`state sub` prototypes are registered and visible for later prototype-aware parsing.

**Investigation needed**: Check whether `state sub p (\@)` registers its prototype
in the symbol table during parsing, and whether the scope changes moved this registration
to a different scope level that's not visible at the call site (line 370).

#### lib/deprecate.t: 4/10 → 0/10 (-4)

**Root cause**: `defined(&foo)` returns true for forward-declared subs (`sub foo;`).

In Perl 5, `sub foo;` (forward declaration) does NOT make `defined(&foo)` true.
In PerlOnJava after Phase 7's `isDeclared` flag changes, it does. This breaks
`File::Copy` which has:
```perl
sub syscopy;                    # line 22: forward declaration
...
unless (defined &syscopy) {     # line 315: should enter this block!
    $Syscopy_is_copy = 1;
    *syscopy = \&copy;          # line 326: sets up syscopy
}
```

Because `defined &syscopy` wrongly returns true, the initialization block is skipped.
When `copy()` later calls `syscopy()`, it dies with "Undefined subroutine &File::Copy::syscopy".

**Reproduction**:
```bash
./jperl -e 'sub foo; print defined(&foo) ? "defined" : "undefined", "\n";'
# Prints "defined" — should print "undefined"
perl  -e 'sub foo; print defined(&foo) ? "defined" : "undefined", "\n";'
# Prints "undefined" — correct
```

**Fix needed**: In `RuntimeGlob.java` or `RuntimeCode.java`, ensure that `defined(&sub)`
returns false for forward declarations (subs that have `isDeclared=true` but no actual body).
The `isDeclared` flag should only affect `getGlobSlot("CODE")` for attribute handler lookup,
not `defined()` semantics.

**Note**: Fixing this restores the baseline 4/10. The remaining 6/10 failures are
pre-existing (unrelated to this branch) — caused by `caller()[7]` (is_require) always
returning undef, which breaks `deprecate.pm`'s require-detection logic.

### Progress Tracking

#### Current Status: Phase 8 completed + strict vars fix + regression investigation (2026-04-02)

#### Test Scores After Phase 8 + strict vars fix

| Test File | Score | Change |
|-----------|-------|--------|
| attrs.t | 158/159 | +6 (test 88 now passes; only TODO test 154 remains) |
| uni/attrs.t | 35/35 | +6 (test 24 now passes; 100% pass rate) |
| attrproto.t | 51/52 | unchanged |
| attrhand.t | 4/4 | unchanged |
| AH/caller.t | 2/2 | unchanged |
| AH/constants.t | 1/1 | unchanged |
| AH/data_convert.t | 8/8 | unchanged |
| AH/linerep.t | 15/18 | unchanged |
| AH/multi.t | 45/51 | unchanged |

**Total: 319/330 (96.7%)**

#### Phase 8 Fixes (2026-04-02)

1. **RuntimeScalarType.java**: Added null check in `blessedId()` for reference-typed scalars with null value
2. **ScalarSpecialVariable.java**: Fixed `${^LAST_SUCCESSFUL_PATTERN}` to return undef when no regex match yet (was REGEX(null))
3. **ReferenceOperators.java**: Added null-safety checks in `ref()` for CODE, REGEX, REFERENCE, ARRAYREFERENCE, HASHREFERENCE, GLOBREFERENCE types
4. **SubroutineParser.java**: Push CallerStack frames in `callModifyCodeAttributes()` with source file/line
5. **OperatorParser.java**: Push CallerStack frames in `callModifyVariableAttributes()` with source file/line
6. **RuntimeCode.java**: Added CallerStack fallback in `callerWithSub()` for frames beyond Java stack trace

#### Parse-time strict vars fix (2026-04-02)

Implemented parse-time `strict 'vars'` checking to fix perl #49472 (attrs.t test 88, uni/attrs.t test 24).
Named subroutine bodies are compiled lazily, so strict vars checking in the bytecode compiler never fired for
undeclared variables inside named sub bodies. Added checking at parse time since parsing is always eager.

1. **Variable.java**: Added `checkStrictVarsAtParseTime()` with comprehensive exemption logic
2. **OperatorParser.java**: Set `parsingDeclaration` flag during `my`/`our`/`state` parsing
3. **Parser.java**: Added `parsingDeclaration` flag
4. **SignatureParser.java**: Register signature parameters in symbol table during parsing
5. **SubroutineParser.java**: Enter scope for signature variables before parsing, exit after block body
6. **StatementParser.java**: Register catch variable in scope, suppress strict check for `catch ($e)`

#### Remaining Failures

| Test | Count | Category | Notes |
|------|-------|----------|-------|
| attrs.t 41-42, uni 17-18 | 4 | Phase 3: `my` var attribute dispatch | Ref points to temp, not lexical |
| attrs.t 124-125, uni 30-31 | 4 | Phase 7: Closure prototype | Not implemented |
| attrs.t 154 | 1 | TODO test (expected failure) | RT #3605 ternary/attribute parsing |
| attrproto.t 48 | 1 | Lexical sub in eval STRING | Pre-existing eval bug |
| linerep.t 16-17 | 2 | eval context file/line | `#line` directive not respected in eval |
| linerep.t 18 | 1 | `my` var ref identity | Same as Phase 3 issue |
| multi.t 45-47,49-50 | 5 | DESTROY not implemented | PerlOnJava limitation |
| multi.t 52 | 1 | END handler warning | Minor edge case |

#### Known Issue: `\K` Regex Bug Affects decl-refs.t

The `\K` (keep left) assertion in `s///` is broken. For example:

```perl
"MODIFY_SCALAR_ATTRIBUTES" =~ s/MODIFY_\KSCALAR/ARRAY/;
# Expected: "MODIFY_ARRAY_ATTRIBUTES"
# Actual:   "ARRAY_ATTRIBUTES"
```

This is a **pre-existing bug on master** (not introduced by the attribute branch).
It affects `decl-refs.t` because the test template uses `\K` substitution to rename
`MODIFY_SCALAR_ATTRIBUTES` → `MODIFY_ARRAY_ATTRIBUTES` / `MODIFY_HASH_ATTRIBUTES`.
The corrupted handler name causes `can()` to fail, which triggers "Invalid attribute"
errors for `@` and `%` variable types in the test iterations.

**Impact:** ~45 tests in decl-refs.t that would otherwise pass if `\K` worked correctly.

**Fix:** Investigate and fix `\K` handling in the regex engine (`org.perlonjava.regex`
or the `s///` substitution logic). Once fixed, decl-refs.t should gain ~45 additional
passing tests without any attribute system changes.

#### Regressions Next Steps (Priority Order)

1. **Fix `defined(&foo)` for forward declarations** (deprecate.t, EASY)
   - In `RuntimeGlob.getGlobSlot("CODE")` or `defined()` check, treat subs with
     `isDeclared=true` but no body as undefined for `defined()` purposes
   - Restores deprecate.t from 0/10 → 4/10 (baseline)
   - May fix other modules that use `sub foo;` forward declarations

2. **Fix `state` declared-ref attribute dispatch** (decl-refs.t, MEDIUM)
   - Unwrap backslash OperatorNode in `callModifyVariableAttributes()` to get inner sigil
   - Ensure runtime dispatch handles declared refs for `my`/`state`
   - Restores decl-refs.t from 174/408 → 322/408 (baseline)

3. **Investigate lexsub.t state sub prototype registration** (lexsub.t, NEEDS INVESTIGATION)
   - Check if scope management changes affected `state sub` prototype visibility
   - The `(\@)` prototype must be visible at the call site for unparenthesized calls
   - Restores lexsub.t from 0/0 → 105/160 (baseline)

4. **Fix `\K` regex assertion in `s///`** (decl-refs.t, SEPARATE ISSUE)
   - Pre-existing bug, not caused by attribute branch
   - Once fixed, decl-refs.t gains ~45 more passing tests
   - See "Known Issue" section above for details

### PR
- https://github.com/fglock/PerlOnJava/pull/420
