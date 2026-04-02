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

### Current Status: Phase 7 partially complete (isDeclared + Attribute::Handlers + error format)

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

### Current Test Results (2026-04-02)

| File | Before | After | Delta |
|------|--------|-------|-------|
| attrs.t | 49/130 → 111/158* | 152/158 | +41 |
| attrproto.t | 3/52 | 51/52 | +48 |
| attrhand.t | 0/0 | 4/4 | +4 |
| uni/attrs.t | 10/34 | 29/34 | +19 |
| **Total** | **62/216** | **236/248** | **+112** |

\* attrs.t grew from 130 to 158 tests because the test no longer crashes partway through.

### Remaining Failures Analysis

#### attrproto.t: 4 remaining (48-51)

**Root cause: `my sub` parser missing attribute loop after prototype**

| Test | Issue |
|------|-------|
| 48 | `my sub lexsub1(bar) : prototype(baz) {}` — `:prototype(baz)` not parsed |
| 49 | Illegal proto warning not emitted for `(bar)` on lexical sub |
| 50 | Illegal proto warning not emitted for `(baz)` on lexical sub |
| 51 | "Prototype overridden" warning not emitted |

**Fix:** In `StatementResolver.java`, after parsing `(prototype)` for `my sub`, add:
1. Call `emitIllegalProtoWarning()` for the parenthesized prototype
2. A second `while (peek(parser).text.equals(":"))` attribute-parsing loop

**Effort:** Small — straightforward parser fix.

#### attrs.t: 24 remaining

**Group A: `attributes::get` not returning built-in attrs (8 tests: 35-42)**

| Test | Expected | Got | Issue |
|------|----------|-----|-------|
| 35 | `"method Z"` | `"method"` | `FETCH_CODE_ATTRIBUTES` result not merged with built-in attrs |
| 36 | `"lvalue"` | `""` | `_fetch_attrs` not returning `lvalue` for predeclared subs |
| 37 | `"lvalue method"` | `""` | Same — multiple built-in attrs not returned |
| 38 | `"lvalue"` | `""` | `lvalue` on predeclared then defined sub not fetched |
| 39 | `"method"` | `""` | `method` on already-defined sub not fetched |
| 40 | `"method Z"` | `"Z"` | `method` from built-in + `Z` from FETCH not combined |
| 41-42 | `2`, `4` | `1`, `2` | Variable `tie` via `MODIFY_SCALAR_ATTRIBUTES` — `my $x : A0` dispatch missing |

**Root cause:** `_fetch_attrs` in `Attributes.java` doesn't return `lvalue`/`method` from `RuntimeCode.attributes`. Tests 41-42 need variable attribute dispatch from the parser/emitter.

**Fix:**
- Fix `_fetch_attrs` to filter and return built-in CODE attrs (`lvalue`, `method`, `const`)
- For 41-42: implement variable attribute dispatch in emitter (Phase 2)

**Group B: Variable attribute dispatch missing (4 tests: 27-28, 41-42)**

| Test | Issue |
|------|-------|
| 27 | `my A $x : plugh` — `MODIFY_SCALAR_ATTRIBUTES` not called, no "may clash" warning |
| 28 | Same for multiple attrs |
| 41-42 | `my $x : A0` in loop — tie via MODIFY_SCALAR_ATTRIBUTES not happening |

**Fix:** Implement variable attribute dispatch. When the parser sees `my $x : Foo`, generate `attributes::->import(__PACKAGE__, \$x, "Foo")`. This requires emitter changes.

**Group C: Error detection issues (5 tests: 20, 44-45, 87, uni/23)**

| Test | Expected | Got | Issue |
|------|----------|-----|-------|
| 20 | Error with quoted attr names | Error without quotes | Error message formatting: attrs need double-quoting |
| 44 | `Can't declare scalar dereference in "our"` | `Invalid SCALAR attribute: foo` | Parser doesn't detect `our ${""} : foo` as dereference |
| 45 | `Can't declare scalar dereference in "my"` | `Invalid SCALAR attribute: bar` | Same for `my $$foo : bar` |
| 87 | `Global symbol "$nosuchvar" requires` | `Invalid CODE attribute: foo` | Strict error should be emitted instead of attr error |
| 154 | (TODO test) No separator error | Gets separator error | `$a ? my $var : my $othervar` — `:` parsed as attr separator |

**Fix:** Multiple parser improvements needed. Tests 44-45 need dereference detection. Test 87 needs strict checking before attribute validation. Test 154 is a known TODO.

**Group D: `:const` attribute (2 tests: 140, 145)**

| Test | Expected | Got | Issue |
|------|----------|-----|-------|
| 140 | `Useless use of attribute "const"` warning | No warning | `const` not handled in `_modify_attrs` |
| 145 | `32487` (const closure value) | `undef` | `:Const` -> `const` via MODIFY_CODE_ATTRIBUTES not applied |

**Fix:** Implement `:const` in `Attributes.java._modify_attrs()` — call the anon sub immediately and capture return value.

**Group E: `MODIFY_CODE_ATTRIBUTES` returning custom error (2 tests: 32, uni/16)**

| Test | Expected | Got |
|------|----------|-----|
| 32 | `X at ` (die in handler) | `Invalid CODE attribute: foo` |

**Root cause:** When `MODIFY_CODE_ATTRIBUTES` dies, the die message should propagate. Currently the error is being replaced by the default "Invalid CODE attribute" message.

**Group F: Closure prototype handling (3 tests: 124-126)**

| Test | Expected | Got |
|------|----------|-----|
| 124 | `Closure prototype called` error | Empty `$@` |
| 125 | `Closure prototype called` error | `Not a CODE reference` |
| 126 | `undef` | `"referencing closure prototype"` |

**Root cause:** Closure prototypes (stubs with captured lexicals) should die with "Closure prototype called" when invoked. This is a runtime feature, not an attribute-specific issue.

**Group G: Error message suffix (3 tests: 155-157)**

| Test | Expected | Got |
|------|----------|-----|
| 155 | `...at - line 1.\nBEGIN failed--compilation aborted at - line 1.` | `...at - line 1, near ""` |
| 156 | Same pattern for arrays | Same |
| 157 | Same pattern for hashes | Same |

**Root cause:** `fresh_perl_is` tests run `./jperl` as a subprocess. The error message format is `"at - line 1."` + `"BEGIN failed--compilation aborted"` suffix. PerlOnJava produces `"at - line 1, near \"\""` instead.

**Fix:** Two issues: (1) error location format, (2) missing "BEGIN failed" propagation.

#### uni/attrs.t: 11 remaining

These mirror attrs.t failures with Unicode identifiers:
- Tests 8, 11-12, 16-18, 20-21, 23, 30-31 — same root causes as attrs.t groups A-F above

### Next Steps (Priority Order)

#### Phase 2: `attributes::get` built-in attrs (HIGH — 8 tests)

Fix `_fetch_attrs` in `Attributes.java` to return built-in CODE attributes (`lvalue`, `method`, `const`) from `RuntimeCode.attributes`. This is a small Java change.

- **Files:** `Attributes.java`
- **Tests fixed:** attrs.t 35-40, uni/attrs.t equivalent
- **Effort:** Small

#### Phase 3: Variable attribute dispatch (MEDIUM — 6+ tests)

When the parser encounters `my $x : Foo` or `our @arr : Bar`, generate calls to `attributes::->import(__PACKAGE__, \$var, @attrs)`. This requires:

1. In the JVM emitter (`EmitVariable.java` or `EmitOperator.java`): when a variable declaration has `"attributes"` annotation, emit `attributes::->import(PKG, \$var, @attrs)`
2. In the bytecode interpreter: same
3. Timing: compile-time for `our`, run-time for `my`/`state`

- **Files:** `EmitVariable.java`, `CompileAssignment.java` (interpreter)
- **Tests fixed:** attrs.t 27-28, 41-42; uni/attrs.t 11-12, 17-18
- **Effort:** Medium

#### Phase 4: `my sub` attribute parsing (SMALL — 4 tests)

Add attribute parsing after prototype in `StatementResolver.java` `my sub` path:
1. Call `emitIllegalProtoWarning()` for `(proto)` syntax
2. Add second `:` attribute loop after prototype

- **Files:** `StatementResolver.java`
- **Tests fixed:** attrproto.t 48-51
- **Effort:** Small

#### Phase 5: `:const` attribute (SMALL — 2 tests)

Implement `:const` in `Attributes.java._modify_attrs()`:
- When `const` is applied to an already-defined sub, emit "Useless use" warning
- When `const` is applied during sub definition, invoke the sub immediately and replace with constant

- **Files:** `Attributes.java`, possibly `SubroutineParser.java`
- **Tests fixed:** attrs.t 140, 145
- **Effort:** Small-Medium

#### Phase 6: Error message improvements (MEDIUM — 7 tests)

1. Quote attribute names in error messages with `"attr"` format (test 20)
2. Detect `our ${""}` and `my $$foo` as dereferences before attribute processing (tests 44-45)
3. Ensure `MODIFY_CODE_ATTRIBUTES` die propagates correctly (tests 32, uni/16)
4. Fix "BEGIN failed--compilation aborted" error suffix (tests 155-157)
5. Ensure strict errors take priority over attribute errors (test 87)

- **Files:** `Attributes.java`, `SubroutineParser.java`, parser error handling
- **Tests fixed:** attrs.t 20, 32, 44-45, 87, 155-157; uni/attrs.t 8, 16, 20-21, 23
- **Effort:** Medium

#### Phase 7: Closure prototype feature (LOW — 4 tests)

PerlOnJava does not have the "closure prototype" concept that Perl 5 has. In Perl 5, when a named sub is compiled that closes over lexical variables, the initial CV (before cloning) is a "closure prototype" — it has the captured variable slots but they are not yet bound to specific pad instances. This prototype is accessible via `MODIFY_CODE_ATTRIBUTES` (`$_[1]` before the sub is fully instantiated). Calling a closure prototype should die with "Closure prototype called".

**What needs to be implemented:**
1. Detect when a RuntimeCode is a closure prototype (has captured variable slots but the closure hasn't been instantiated/cloned yet)
2. In `RuntimeCode.apply()`, check for the prototype state and die with "Closure prototype called" instead of executing the body
3. The prototype should still be referenceable (test 126: `\&{$proto}` should return a ref to it)

**Test details:**
- Test 124: `eval { $proto->() }` — should die with `/^Closure prototype called/`
- Test 125: `eval { () = &$proto }` — should die with `/^Closure prototype called/`
- Test 126: `\&{$proto}` — should return a reference (referencing closure prototype)

- **Files:** `RuntimeCode.java`, possibly `EmitSubroutine.java`
- **Tests fixed:** attrs.t 124-126; uni/attrs.t 30-31
- **Effort:** Medium — requires implementing a new concept in the runtime

#### Phase 8: Attribute::Handlers (LOW — 4 tests)

The infrastructure (`attributes.pm`, CHECK blocks, MODIFY_CODE_ATTRIBUTES) is now in place. The remaining blockers are likely edge cases in glob manipulation, ref-identity comparison, or `undef &sub` syntax within `Attribute::Handlers.pm` internals.

- **Files:** Possibly runtime fixes
- **Tests fixed:** attrhand.t 1-4
- **Effort:** Unknown — needs investigation

### Estimated Final Results

| Phase | Tests Fixed | Cumulative |
|-------|-----------|------------|
| Current | — | 205/244 (84%) |
| Phase 2 | +8 | 213/244 (87%) |
| Phase 3 | +6 | 219/244 (90%) |
| Phase 4 | +4 | 223/244 (91%) |
| Phase 5 | +2 | 225/244 (92%) |
| Phase 6 | +12 | 237/244 (97%) |
| Phase 7 | +4 | 241/244 (99%) |
| Phase 8 | +4 | 244/244 (100%) |

### Open Questions

1. **Variable attribute storage**: Should variables store their attributes? Currently `RuntimeCode` has an `attributes` field, but `RuntimeScalar`/`RuntimeArray`/`RuntimeHash` do not. Most test cases only need the `MODIFY_*_ATTRIBUTES` callback (side effects like `tie`), not persistent storage. The `FETCH_*_ATTRIBUTES` tests are only for CODE refs. **Decision: Don't add storage to variables yet — not needed for any current test.**

2. **`_modify_attrs` implementation level**: The system Perl implements this as XS that directly manipulates SV flags. In PerlOnJava, we access `RuntimeCode.attributes` from Java. For CODE refs this is straightforward. For variable refs, we only need to validate built-in attrs (`shared`) and return unrecognized ones — no actual flag-setting needed since `shared` is a no-op.

3. **Attribute::Handlers**: The module exists at `src/main/perl/lib/Attribute/Handlers.pm` and the core dependencies (`attributes.pm`, CHECK blocks, MODIFY_CODE_ATTRIBUTES) are now implemented. All core attrhand.t tests pass (4/4). Remaining edge cases are in multi.t (DESTROY, END handler warning) and linerep.t (eval context file/line).

4. **`our` variable attribute timing**: The perldoc says `our` attributes are applied at compile-time. This means the emitter needs to call `attributes::->import()` immediately during parsing (like `callModifyCodeAttributes` does for subs), not defer to runtime. **Decision: Handle in Phase 3.**

### Progress Tracking

#### Current Status: Phase 8 completed (2026-04-02)

#### Test Scores After Phase 8

| Test File | Score | Change |
|-----------|-------|--------|
| attrs.t | 152/158 | unchanged |
| uni/attrs.t | 29/34 | unchanged |
| attrproto.t | 51/52 | unchanged |
| attrhand.t | 4/4 | unchanged |
| AH/caller.t | 2/2 | unchanged |
| AH/constants.t | 1/1 | unchanged |
| AH/data_convert.t | 8/8 | unchanged |
| AH/linerep.t | 15/18 | +2 (filename/linenum for CODE attrs) |
| AH/multi.t | 45/51 | **NEW** (was crash/0) |

**Total: 307/328 (93.6%)**

#### Phase 8 Fixes (2026-04-02)

1. **RuntimeScalarType.java**: Added null check in `blessedId()` for reference-typed scalars with null value
2. **ScalarSpecialVariable.java**: Fixed `${^LAST_SUCCESSFUL_PATTERN}` to return undef when no regex match yet (was REGEX(null))
3. **ReferenceOperators.java**: Added null-safety checks in `ref()` for CODE, REGEX, REFERENCE, ARRAYREFERENCE, HASHREFERENCE, GLOBREFERENCE types
4. **SubroutineParser.java**: Push CallerStack frames in `callModifyCodeAttributes()` with source file/line
5. **OperatorParser.java**: Push CallerStack frames in `callModifyVariableAttributes()` with source file/line
6. **RuntimeCode.java**: Added CallerStack fallback in `callerWithSub()` for frames beyond Java stack trace

#### Remaining Failures

| Test | Count | Category | Notes |
|------|-------|----------|-------|
| attrs.t 41-42, uni 17-18 | 4 | Phase 3: `my` var attribute dispatch | Ref points to temp, not lexical |
| attrs.t 87, uni 23 | 2 | Strict error masked (#49472) | Pre-existing strict checking issue |
| attrs.t 124-125, uni 30-31 | 4 | Phase 7: Closure prototype | Not implemented |
| attrs.t 154 | 1 | TODO test (expected failure) | RT #3605 ternary/attribute parsing |
| attrproto.t 48 | 1 | Lexical sub in eval STRING | Pre-existing eval bug |
| linerep.t 16-17 | 2 | eval context file/line | `#line` directive not respected in eval |
| linerep.t 18 | 1 | `my` var ref identity | Same as Phase 3 issue |
| multi.t 45-47,49-50 | 5 | DESTROY not implemented | PerlOnJava limitation |
| multi.t 52 | 1 | END handler warning | Minor edge case |

### PR
- https://github.com/fglock/PerlOnJava/pull/420
